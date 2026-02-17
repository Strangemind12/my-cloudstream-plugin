package com.anilife

import android.util.Base64
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v52.0
 * - [Critical Fix] 스크린샷 분석 결과 반영: 'x-user-ssid' 보안 헤더 추출 및 플레이어 주입 로직 확정
 * - [Restore] v4.1의 제목 중복 해결, 포스터 파싱, 장르/줄거리 추출 로직 완전 복구
 * - [Maintain] v44.0의 API JSON 파싱 로직 유지
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
        println("$TAG [MainPage] 요청: $url")
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            newHomePageResponse(request.name, parseCommonList(doc))
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    // [v4.1 복구] 제목 중복 및 포스터 파싱
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

    override suspend fun search(query: String): List<SearchResponse> = parseCommonList(app.get("$mainUrl/search?keyword=$query", headers = commonHeaders).document)

    override suspend fun load(url: String): LoadResponse {
        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            tunnelingPoster = String(Base64.decode(url.substringAfter("poster=").substringBefore("&"), Base64.NO_WRAP))
            url.substringBefore("?poster=")
        } else url

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
        println("$TAG [LoadLinks] =================== v52.0 시작 ===================")
        
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
            detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
            cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
        }

        try {
            // [1단계] 웹뷰 실행
            val webResponse = app.get(cleanData, headers = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent), interceptor = WebViewResolver(Regex(".*")))

            // [2단계] 플레이어 URL 추출
            val playerUrl = AnilifeExtractor().extractPlayerUrl(webResponse.text, mainUrl) ?: return false

            // [3단계] M3U8 API 스니핑
            val gcdnInterceptor = WebViewResolver(Regex(""".*api\.gcdn\.app.*"""))
            val gcdnResponse = app.get(playerUrl, headers = mapOf("User-Agent" to pcUserAgent, "Referer" to webResponse.url), interceptor = gcdnInterceptor)
            val sniffedUrl = gcdnResponse.url
            println("$TAG [Step 3] 가로챈 URL: $sniffedUrl")

            // [Step 3-B] 쿠키 추출
            val finalCookies = CookieManager.getInstance().getCookie("https://anilife.live") ?: ""

            var finalM3u8: String? = null
            var xUserSsid: String? = null

            // [Step 3-C] API 호출 및 스크린샷 기반 헤더 추출
            if (sniffedUrl.contains("/m3u8/st/")) {
                val apiResponse = app.get(
                    sniffedUrl,
                    headers = mapOf("User-Agent" to pcUserAgent, "Referer" to "https://anilife.live/", "Cookie" to finalCookies)
                )
                
                // [v52.0 핵심] 스크린샷에 있던 x-user-ssid 추출
                xUserSsid = apiResponse.headers["x-user-ssid"] ?: apiResponse.headers["X-User-Ssid"]
                println("$TAG [Step 3-C] 응답 헤더에서 SSID 획득: $xUserSsid")

                val match = Regex("""https://api\.gcdn\.app/v1/manifest/[^"']+""").find(apiResponse.text)
                if (match != null) {
                    finalM3u8 = match.value.replace("\\/", "/")
                }
            } else {
                finalM3u8 = sniffedUrl
            }

            // [4단계] 최종 링크 전달 (보안 토큰 강제 주입)
            if (finalM3u8 != null) {
                callback.invoke(
                    newExtractorLink(source = name, name = name, url = finalM3u8, type = ExtractorLinkType.M3U8) {
                        this.referer = "https://anilife.live/"
                        val hMap = mutableMapOf(
                            "User-Agent" to pcUserAgent,
                            "Origin" to "https://anilife.live",
                            "Cookie" to finalCookies,
                            "Accept" to "*/*"
                        )
                        // 찾아낸 SSID를 플레이어 헤더에 반드시 추가 (enc.bin 404 해결)
                        if (!xUserSsid.isNullOrBlank()) {
                            hMap["x-user-ssid"] = xUserSsid
                            println("$TAG [Step 4] SSID 주입 완료.")
                        }
                        this.headers = hMap
                        this.quality = getQualityFromName("HD")
                    }
                )
                println("$TAG [완료] 프로세스 성공.")
                return true
            }

        } catch (e: Exception) {
            println("$TAG [Error] ${e.message}")
        }
        return false
    }
}
