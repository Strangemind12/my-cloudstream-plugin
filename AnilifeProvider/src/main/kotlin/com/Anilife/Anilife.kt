package com.anilife

import android.util.Base64
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v60.0
 * - [Critical Fix] 메모리 후킹 실패(0개) 해결을 위해 '네트워크 인터셉트 후킹' 방식으로 전환
 * - [Logic] WebViewClient.shouldInterceptRequest를 사용하여 enc.bin 요청을 직접 가로채고 키 데이터 확보
 * - [Integrated] v58.0의 메타데이터(메인/상세) 파싱 로직 완전 유지
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"
    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to pcUserAgent,
        "Referer" to "$mainUrl/"
    )

    // 메인 페이지 및 파싱 (v4.1 복구 유지)
    override val mainPage = mainPageOf(
        "/top20" to "실시간 TOP 20",
        "/vodtype/categorize/TV/1" to "TV 애니메이션",
        "/vodtype/categorize/OVA/1" to "OVA",
        "/vodtype/categorize/ONA/1" to "ONA",
        "/vodtype/categorize/Web/1" to "Web",
        "/vodtype/categorize/SP/1" to "SP",
        "/vodtype/categorize/Movie/1" to "극장판"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.name.contains("TOP 20")) "$mainUrl${request.data}" 
                  else "$mainUrl${request.data.substringBeforeLast("/")}/$page"
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            newHomePageResponse(request.name, parseCommonList(doc))
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun parseCommonList(doc: Document): List<SearchResponse> {
        return doc.select(".listupd > article.bs").mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val title = (element.selectFirst(".tt h2") ?: element.selectFirst(".tt"))?.text()?.trim() ?: "Unknown"
                val poster = fixUrl(element.selectFirst("img")?.let { it.attr("src").ifEmpty { it.attr("data-src") } } ?: "")
                val rawHref = fixUrl(aTag.attr("href"))
                val finalHref = if (poster.isNotEmpty()) {
                    val encoded = Base64.encodeToString(poster.toByteArray(), Base64.NO_WRAP)
                    if (rawHref.contains("?")) "$rawHref&poster=$encoded" else "$rawHref?poster=$encoded"
                } else rawHref
                newAnimeSearchResponse(title, finalHref, TvType.Anime) {
                    this.posterUrl = poster
                    this.posterHeaders = commonHeaders
                }
            } catch (e: Exception) { null }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> = parseCommonList(app.get("$mainUrl/search?keyword=$query", headers = commonHeaders).document)

    override suspend fun load(url: String): LoadResponse {
        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            val posterParam = url.substringAfter("poster=")
            tunnelingPoster = String(Base64.decode(posterParam.substringBefore("&"), Base64.NO_WRAP))
            url.substringBefore("?poster=")
        } else url

        val response = app.get(cleanUrl, headers = commonHeaders)
        val doc = response.document
        val encodedRef = Base64.encodeToString(response.url.toByteArray(), Base64.NO_WRAP)
        val episodes = doc.select(".eplister > ul > li > a").mapNotNull { 
            val epHref = fixUrl(it.attr("href"))
            newEpisode(if (epHref.contains("?")) "$epHref&ref=$encodedRef" else "$epHref?ref=$encodedRef") {
                this.name = it.selectFirst(".epl-title")?.text()?.trim()
                this.episode = it.selectFirst(".epl-num")?.text()?.trim()?.toIntOrNull()
            }
        }.reversed()

        return newAnimeLoadResponse(doc.selectFirst(".entry-title")?.text()?.trim() ?: "Anime", cleanUrl, TvType.Anime) {
            this.posterUrl = doc.selectFirst(".thumb img")?.attr("src") ?: tunnelingPoster
            this.posterHeaders = commonHeaders
            this.plot = doc.selectFirst(".synp .entry-content")?.text()?.trim()
            this.tags = doc.select(".genxed a").map { it.text() }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("$TAG [LoadLinks] =================== v60.0 시작 (Network Intercept) ===================")
        
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            try {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            } catch (e: Exception) { }
        }

        try {
            // [1단계] 플레이어 페이지 로드
            val webResponse = app.get(cleanData, headers = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent), interceptor = WebViewResolver(Regex(".*")))
            val playerUrl = AnilifeProxyExtractor().extractPlayerUrl(webResponse.text, mainUrl) ?: return false
            println("$TAG [Step 2] 플레이어 주소: $playerUrl")

            // [3단계] M3U8 API 스니핑
            val gcdnInterceptor = WebViewResolver(Regex(""".*api\.gcdn\.app.*"""))
            val gcdnResponse = app.get(playerUrl, headers = mapOf("User-Agent" to pcUserAgent, "Referer" to webResponse.url), interceptor = gcdnInterceptor)
            val sniffedUrl = gcdnResponse.url
            println("$TAG [Step 3] API 주소: $sniffedUrl")

            // [4단계] 쿠키 및 SSID 추출 (스크린샷 기반)
            val finalCookies = CookieManager.getInstance().getCookie("https://anilife.live") ?: ""
            var xUserSsid: String? = null
            var finalM3u8: String? = null

            if (sniffedUrl.contains("/m3u8/st/")) {
                val apiResponse = app.get(sniffedUrl, headers = mapOf("User-Agent" to pcUserAgent, "Referer" to "https://anilife.live/", "Cookie" to finalCookies))
                xUserSsid = apiResponse.headers["x-user-ssid"] ?: apiResponse.headers["X-User-Ssid"]
                val match = Regex("""https://api\.gcdn\.app/v1/manifest/[^"']+""").find(apiResponse.text)
                if (match != null) finalM3u8 = match.value.replace("\\/", "/")
            } else {
                finalM3u8 = sniffedUrl
            }

            // [5단계] 네트워크 인터셉트 방식의 후킹 엔진 가동
            if (finalM3u8 != null) {
                println("$TAG [Step 5] 네트워크 인터셉트 엔진 가동: $finalM3u8")
                return AnilifeProxyExtractor().extractWithProxy(
                    m3u8Url = finalM3u8,
                    playerUrl = playerUrl,
                    referer = "https://anilife.live/",
                    ssid = xUserSsid,
                    cookies = finalCookies,
                    callback = callback
                )
            }

        } catch (e: Exception) {
            println("$TAG [Error] ${e.message}")
        }
        return false
    }
}
