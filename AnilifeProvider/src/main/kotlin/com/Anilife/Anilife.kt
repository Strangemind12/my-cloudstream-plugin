package com.anilife

import android.util.Base64
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v56.0
 * - [Critical Fix] TVWiki 방식의 키 후킹(Key Hooking) 및 로컬 프록시 서버 구현 통합
 * - [Restore] v4.1 제목/포스터/장르/줄거리 파싱 로직 완전 복구
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

    // 메인 페이지 및 파싱 로직 (v4.1 복구)
    override val mainPage = mainPageOf(
        "/top20" to "실시간 TOP 20",
        "/vodtype/categorize/TV/1" to "TV 애니메이션",
        "/vodtype/categorize/Movie/1" to "극장판"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.name.contains("TOP 20")) "$mainUrl${request.data}" else "$mainUrl${request.data.substringBeforeLast("/")}/$page"
        val doc = app.get(url, headers = commonHeaders).document
        return newHomePageResponse(request.name, parseCommonList(doc))
    }

    private fun parseCommonList(doc: Document): List<SearchResponse> {
        return doc.select(".listupd > article.bs").mapNotNull { element ->
            val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
            val title = (element.selectFirst(".tt h2") ?: element.selectFirst(".tt"))?.text()?.trim() ?: "Unknown"
            val poster = fixUrl(element.selectFirst("img")?.let { it.attr("src").ifEmpty { it.attr("data-src") } } ?: "")
            val rawHref = fixUrl(aTag.attr("href"))
            val finalHref = if (poster.isNotEmpty()) {
                val encoded = Base64.encodeToString(poster.toByteArray(), Base64.NO_WRAP)
                if (rawHref.contains("?")) "$rawHref&poster=$encoded" else "$rawHref?poster=$encoded"
            } else rawHref
            newAnimeSearchResponse(title, finalHref, TvType.Anime) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> = parseCommonList(app.get("$mainUrl/search?keyword=$query", headers = commonHeaders).document)

    override suspend fun load(url: String): LoadResponse {
        var tunnelingPoster: String? = null
        if (url.contains("poster=")) tunnelingPoster = String(Base64.decode(url.substringAfter("poster=").substringBefore("&"), Base64.NO_WRAP))
        val cleanUrl = url.substringBefore("?poster=")
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
        println("$TAG [LoadLinks] =================== v56.0 시작 ===================")
        
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            detailReferer = String(Base64.decode(cleanData.substringAfter("ref=").substringBefore("&"), Base64.NO_WRAP))
            cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
        }

        try {
            // 1. 웹뷰로 플레이어 주소 획득
            val webResponse = app.get(cleanData, headers = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent), interceptor = WebViewResolver(Regex(".*")))
            val playerUrl = AnilifeExtractor().extractPlayerUrl(webResponse.text, mainUrl) ?: return false

            // 2. M3U8 API 스니핑
            val gcdnInterceptor = WebViewResolver(Regex(""".*api\.gcdn\.app.*"""))
            val gcdnResponse = app.get(playerUrl, headers = mapOf("User-Agent" to pcUserAgent, "Referer" to webResponse.url), interceptor = gcdnInterceptor)
            val sniffedUrl = gcdnResponse.url

            // 3. 쿠키 및 SSID 추출
            val finalCookies = CookieManager.getInstance().getCookie("https://anilife.live") ?: ""
            var xUserSsid: String? = null
            var finalM3u8: String? = null

            if (sniffedUrl.contains("/m3u8/st/")) {
                val apiResponse = app.get(sniffedUrl, headers = mapOf("User-Agent" to pcUserAgent, "Referer" to "https://anilife.live/", "Cookie" to finalCookies))
                xUserSsid = apiResponse.headers["x-user-ssid"] ?: apiResponse.headers["X-User-Ssid"]
                val match = Regex("""https://api\.gcdn\.app/v1/manifest/[^"']+""").find(apiResponse.text)
                if (match != null) finalM3u8 = match.value.replace("\\/", "/")
            }

            // 4. 로컬 프록시 및 키 후킹 엔진 가동 (TVWiki 방식)
            if (finalM3u8 != null) {
                return AnilifeProxyExtractor().extractWithProxy(finalM3u8, playerUrl, xUserSsid, finalCookies, callback)
            }

        } catch (e: Exception) { println("$TAG [Error] ${e.message}") }
        return false
    }
}
