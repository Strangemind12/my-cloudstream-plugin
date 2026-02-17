package com.anilife

import android.util.Base64
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v53.0
 * - [Critical Fix] 2004(404) 에러 해결을 위해 Referer 제거 (스크린샷의 no-referrer 정책 반영)
 * - [Fix] x-user-ssid 헤더를 대소문자 모두 대응하여 주입
 * - [Fix] 쿠키 추출 대상을 gcdn.app 도메인으로 더욱 정밀하게 타겟팅
 * - [Maintain] v49.0의 제목/포스터/장르/줄거리 파싱 로직 유지
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

    private fun logFullContent(tag: String, prefix: String, msg: String) {
        val maxLogSize = 4000
        if (msg.length > maxLogSize) {
            println("$tag $prefix [Part-Start] ${msg.substring(0, maxLogSize)}")
            logFullContent(tag, prefix, msg.substring(maxLogSize))
        } else {
            println("$tag $prefix [Part-End] $msg")
        }
    }

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
                val imgTag = element.selectFirst("img")
                var poster = imgTag?.attr("src") ?: imgTag?.attr("data-src") ?: ""
                poster = fixUrl(poster)
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

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        val doc = app.get(url, headers = commonHeaders).document
        return parseCommonList(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        var tunnelingPoster: String? = null
        if (url.contains("poster=")) {
            tunnelingPoster = String(Base64.decode(url.substringAfter("poster=").substringBefore("&"), Base64.NO_WRAP))
        }
        val cleanUrl = url.substringBefore("?poster=")
        val response = app.get(cleanUrl, headers = commonHeaders)
        val doc = response.document
        val encodedRef = Base64.encodeToString(response.url.toByteArray(), Base64.NO_WRAP)
        val episodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val rawHref = fixUrl(element.attr("href"))
            val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            newEpisode(if (rawHref.contains("?")) "$rawHref&ref=$encodedRef" else "$rawHref?ref=$encodedRef") {
                this.name = if (numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
                this.episode = numText.toIntOrNull()
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
        println("$TAG [LoadLinks] =================== v53.0 시작 ===================")
        
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
            detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
            cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
        }

        try {
            // [1단계] Provider 페이지 로드
            val webResponse = app.get(cleanData, headers = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent), interceptor = WebViewResolver(Regex(".*")))

            // [2단계] 플레이어 URL 추출
            val playerUrl = AnilifeExtractor().extractPlayerUrl(webResponse.text, mainUrl) ?: return false

            // [3단계] M3U8 API 스니핑
            val gcdnInterceptor = WebViewResolver(Regex(""".*api\.gcdn\.app.*"""))
            val gcdnResponse = app.get(playerUrl, headers = mapOf("User-Agent" to pcUserAgent, "Referer" to webResponse.url), interceptor = gcdnInterceptor)
            val sniffedUrl = gcdnResponse.url
            println("$TAG [Step 3] 가로챈 URL: $sniffedUrl")

            // [Step 3-B] 쿠키 추출 범위 확장
            val cookieManager = CookieManager.getInstance()
            val siteCookies = cookieManager.getCookie("https://anilife.live") ?: ""
            val gcdnCookies = cookieManager.getCookie("https://api.gcdn.app") ?: ""
            val finalCookies = "$siteCookies; $gcdnCookies".trim(';', ' ')

            var finalM3u8: String? = null
            var xUserSsid: String? = null

            // [Step 3-C] API 호출 및 SSID 추출
            if (sniffedUrl.contains("/m3u8/st/")) {
                val apiResponse = app.get(
                    sniffedUrl,
                    headers = mapOf("User-Agent" to pcUserAgent, "Referer" to "https://anilife.live/", "Cookie" to finalCookies)
                )
                // 대소문자 모두 체크
                xUserSsid = apiResponse.headers["x-user-ssid"] ?: apiResponse.headers["X-User-Ssid"]
                println("$TAG [Step 3-C] SSID 발견: $xUserSsid")

                val match = Regex("""https://api\.gcdn\.app/v1/manifest/[^"']+""").find(apiResponse.text)
                if (match != null) {
                    finalM3u8 = match.value.replace("\\/", "/")
                }
            } else {
                finalM3u8 = sniffedUrl
            }

            // [4단계] 최종 링크 전달 (no-referrer 전략 적용)
            if (finalM3u8 != null) {
                callback.invoke(
                    newExtractorLink(source = name, name = name, url = finalM3u8, type = ExtractorLinkType.M3U8) {
                        // [v53.0 핵심 수정] Referer를 제거하여 서버의 no-referrer 정책에 맞춤
                        this.referer = "" 
                        
                        val hMap = mutableMapOf(
                            "User-Agent" to pcUserAgent,
                            "Origin" to "https://anilife.live",
                            "Cookie" to finalCookies,
                            "Accept" to "*/*",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Site" to "cross-site",
                            "Sec-Fetch-Dest" to "empty"
                        )
                        if (!xUserSsid.isNullOrBlank()) {
                            hMap["x-user-ssid"] = xUserSsid
                            hMap["X-User-Ssid"] = xUserSsid // 중복 방어
                        }
                        this.headers = hMap
                        this.quality = getQualityFromName("HD")
                    }
                )
                println("$TAG [Step 4] SSID 주입 및 Referer 제거 완료.")
                return true
            }

        } catch (e: Exception) {
            println("$TAG [Error] ${e.message}")
        }
        return false
    }
}
