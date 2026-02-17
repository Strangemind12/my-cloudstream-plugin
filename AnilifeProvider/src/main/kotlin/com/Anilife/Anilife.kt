package com.anilife

import android.util.Base64
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v47.0
 * - [Critical Fix] #EXT-X-KEY (enc.bin) 파일 접근 시 발생하는 404/403 에러 해결
 * - [Solution] gcdn.app 도메인의 모든 세션 쿠키를 통합 추출하여 플레이어에게 주입
 * - [Maintain] v45.0 제목/포스터 로직 및 v44.0 API 파싱 로직 유지
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"

    // PC User-Agent (성공 로그 기반)
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
            val home = parseCommonList(doc)
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            println("$TAG [MainPage] 에러: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun parseCommonList(doc: Document): List<SearchResponse> {
        return doc.select(".listupd > article.bs").mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val rawHref = fixUrl(aTag.attr("href"))
                val title = (element.selectFirst(".tt h2") ?: element.selectFirst(".tt"))?.text()?.trim() ?: "Unknown"
                val imgTag = element.selectFirst("img")
                var poster = imgTag?.attr("src") ?: imgTag?.attr("data-src") ?: ""
                poster = fixUrl(poster)

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
        val cleanUrl = if (url.contains("poster=")) {
            val posterParam = url.substringAfter("poster=")
            tunnelingPoster = String(Base64.decode(posterParam.substringBefore("&"), Base64.NO_WRAP))
            url.substringBefore("?poster=")
        } else url

        println("$TAG [Load] 접속: $cleanUrl")
        val response = app.get(cleanUrl, headers = commonHeaders)
        val finalUrl = response.url
        val title = response.document.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        val encodedRef = Base64.encodeToString(finalUrl.toByteArray(), Base64.NO_WRAP)

        val episodes = response.document.select(".eplister > ul > li > a").mapNotNull { element ->
            val rawHref = fixUrl(element.attr("href"))
            val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            val fullName = if (numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
            val finalHref = if (rawHref.contains("?")) "$rawHref&ref=$encodedRef" else "$rawHref?ref=$encodedRef"

            newEpisode(finalHref) {
                this.name = fullName
                this.episode = numText.toIntOrNull()
            }
        }.reversed()

        return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
            this.posterUrl = response.document.selectFirst(".thumb img")?.attr("src") ?: tunnelingPoster
            this.posterHeaders = commonHeaders
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("$TAG [LoadLinks] =================== v47.0 시작 ===================")
        
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            try {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            } catch (e: Exception) { println("$TAG [Error] Referer 디코딩 실패") }
        }

        try {
            // [1단계] Provider 페이지 로드
            println("$TAG [Step 1] Provider 접속 시도...")
            val webResponse = app.get(
                cleanData,
                headers = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent),
                interceptor = WebViewResolver(Regex(".*"))
            )

            // [2단계] 플레이어 URL 파싱
            val playerUrl = AnilifeExtractor().extractPlayerUrl(webResponse.text, mainUrl)
            if (playerUrl == null) return false
            println("$TAG [Step 2] 추출 성공: $playerUrl")

            // [3단계] M3U8 API 스니핑
            println("$TAG [Step 3] API 스니핑 시작 (Target: api.gcdn.app)...")
            val gcdnInterceptor = WebViewResolver(Regex(""".*api\.gcdn\.app.*"""))
            val gcdnResponse = app.get(
                playerUrl,
                headers = mapOf("User-Agent" to pcUserAgent, "Referer" to webResponse.url),
                interceptor = gcdnInterceptor
            )
            val sniffedUrl = gcdnResponse.url
            println("$TAG [Step 3] 가로챈 URL: $sniffedUrl")

            // [Step 3-B] 쿠키 추출 (핵심: gcdn.app 전체 도메인 대상)
            println("$TAG [Step 3-B] gcdn.app 도메인 통합 쿠키 추출 중...")
            val cookieManager = CookieManager.getInstance()
            // 하위 도메인(api, edge-02 등)에서 공통으로 사용될 수 있도록 메인 도메인 기준으로 쿠키 획득 시도
            val apiCookies = cookieManager.getCookie("https://api.gcdn.app") ?: ""
            val edgeCookies = cookieManager.getCookie("https://edge-02.gcdn.app") ?: ""
            val finalCookies = if (apiCookies.contains(edgeCookies)) apiCookies else "$apiCookies; $edgeCookies".trim(';', ' ')
            
            println("$TAG [Step 3-B] 최종 통합 쿠키: $finalCookies")

            var finalM3u8: String? = null

            // [Step 3-C] 진짜 주소 추출
            if (sniffedUrl.contains("/m3u8/st/")) {
                println("$TAG [Step 3-C] API 응답에서 진짜 주소 추출 중...")
                val apiResponse = app.get(
                    sniffedUrl,
                    headers = mapOf(
                        "User-Agent" to pcUserAgent,
                        "Referer" to "https://anilife.live/",
                        "Origin" to "https://anilife.live",
                        "Cookie" to finalCookies,
                        "Accept" to "*/*"
                    )
                )
                
                val manifestMatch = Regex("""https://api\.gcdn\.app/v1/manifest/[^"']+""").find(apiResponse.text)
                if (manifestMatch != null) {
                    finalM3u8 = manifestMatch.value.replace("\\/", "/")
                    println("$TAG [Step 3-C] 진짜 주소 발견: $finalM3u8")
                } else {
                    println("$TAG [Step 3-C] 실패: 응답 본문에 주소 없음.")
                    logFullContent(TAG, "[API-Body]", apiResponse.text)
                }
            } else {
                finalM3u8 = sniffedUrl
            }

            // [4단계] 최종 링크 전달
            if (finalM3u8 != null) {
                println("$TAG [Step 4] 플레이어에게 링크 및 통합 헤더 전달...")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = finalM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://anilife.live/"
                        this.headers = mapOf(
                            "User-Agent" to pcUserAgent,
                            "Origin" to "https://anilife.live",
                            "Cookie" to finalCookies, // [핵심] enc.bin 접근을 위한 쿠키 주입
                            "Accept" to "*/*",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Site" to "cross-site",
                            "Sec-Fetch-Dest" to "empty"
                        )
                        this.quality = getQualityFromName("HD")
                    }
                )
                println("$TAG [완료] 프로세스 종료.")
                return true
            }

        } catch (e: Exception) {
            println("$TAG [Critical Error] ${e.message}")
        }
        return false
    }
}
