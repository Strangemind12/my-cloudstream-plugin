package com.anilife

import android.util.Base64
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v78.0
 * - [Debug] 모든 프로세스 단계별 상세 로깅 추가 (성공/실패/예외 명시)
 * - [Logic] M3U8 파싱 및 키 주소 추출 로직 로깅 강화
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
        
        println("$TAG [MainPage] 요청 시작: $url")
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val home = parseCommonList(doc)
            println("$TAG [MainPage] 파싱 성공: ${home.size}개 항목")
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            println("$TAG [MainPage] 실패: ${e.message}")
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
        println("$TAG [Search] 검색 요청: $query")
        return parseCommonList(app.get("$mainUrl/search?keyword=$query", headers = commonHeaders).document)
    }

    override suspend fun load(url: String): LoadResponse {
        println("$TAG [Load] 상세 페이지 분석: $url")
        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            val posterParam = url.substringAfter("poster=")
            tunnelingPoster = String(Base64.decode(posterParam.substringBefore("&"), Base64.NO_WRAP))
            url.substringBefore("?poster=")
        } else url

        val response = app.get(cleanUrl, headers = commonHeaders)
        val doc = response.document
        val finalUrl = response.url
        val encodedRef = Base64.encodeToString(finalUrl.toByteArray(), Base64.NO_WRAP)

        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        val plot = doc.selectFirst(".synp .entry-content")?.text()?.trim()
        val tags = doc.select(".genxed a").map { it.text() }

        val episodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
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

        println("$TAG [Load] 완료. 에피소드 수: ${episodes.size}")
        return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
            this.posterUrl = doc.selectFirst(".thumb img")?.attr("src") ?: tunnelingPoster
            this.posterHeaders = commonHeaders
            this.plot = plot
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("$TAG [LoadLinks] =================== v78.0 시작 ===================")
        
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
            println("$TAG [Step 1] 웹뷰 로드 시작: $cleanData")
            val webResponse = app.get(
                cleanData, 
                headers = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent), 
                interceptor = WebViewResolver(Regex(".*"))
            )

            val playerUrl = AnilifeProxyExtractor().extractPlayerUrl(webResponse.text, mainUrl) ?: return false
            println("$TAG [Step 2] 플레이어 URL 발견: $playerUrl")

            println("$TAG [Step 3] API 스니핑 (gcdn.app) 시도...")
            val gcdnInterceptor = WebViewResolver(Regex(""".*api\.gcdn\.app.*"""))
            val gcdnResponse = app.get(
                playerUrl,
                headers = mapOf("User-Agent" to pcUserAgent, "Referer" to webResponse.url),
                interceptor = gcdnInterceptor
            )
            val sniffedUrl = gcdnResponse.url
            println("$TAG [Step 3] 스니핑 성공: $sniffedUrl")

            val finalCookies = CookieManager.getInstance().getCookie("https://anilife.live") ?: ""
            println("$TAG [Info] 쿠키 획득 (길이: ${finalCookies.length})")
            
            var xUserSsid: String? = null
            var finalM3u8: String? = null
            var targetKeyUrl: String? = null

            if (sniffedUrl.contains("/m3u8/st/")) {
                println("$TAG [Step 4] API 응답 파싱 및 SSID 추출...")
                val apiResponse = app.get(
                    sniffedUrl,
                    headers = mapOf("User-Agent" to pcUserAgent, "Referer" to "https://anilife.live/", "Cookie" to finalCookies)
                )
                xUserSsid = apiResponse.headers["x-user-ssid"] ?: apiResponse.headers["X-User-Ssid"]
                if (xUserSsid != null) println("$TAG [Step 4] SSID 획득 성공: $xUserSsid") else println("$TAG [Step 4] SSID 없음")

                val match = Regex("""https://api\.gcdn\.app/v1/manifest/[^"']+""").find(apiResponse.text)
                if (match != null) {
                    finalM3u8 = match.value.replace("\\/", "/")
                    println("$TAG [Step 4] Master M3U8 주소: $finalM3u8")
                    
                    // M3U8 미리 읽어서 키 주소 파싱
                    try {
                        val m3u8Content = app.get(finalM3u8!!, headers = mapOf("User-Agent" to pcUserAgent, "Referer" to "https://anilife.live/")).text
                        val keyMatch = Regex("""URI="([^"]+)"""").find(m3u8Content)
                        if (keyMatch != null) {
                            var kUrl = keyMatch.groupValues[1]
                            if (!kUrl.startsWith("http")) {
                                kUrl = if (kUrl.startsWith("/")) "https://api.gcdn.app$kUrl" 
                                       else finalM3u8!!.substringBeforeLast("/") + "/" + kUrl
                            }
                            targetKeyUrl = kUrl
                            println("$TAG [Step 4] ★ 타겟 키 URL 파싱 성공: $targetKeyUrl")
                        } else {
                            println("$TAG [Step 4] 경고: M3U8 내에서 키 URI를 찾지 못함.")
                        }
                    } catch (e: Exception) { println("$TAG [Step 4] M3U8 읽기 실패: ${e.message}") }
                }
            } else {
                finalM3u8 = sniffedUrl
            }

            if (finalM3u8 != null) {
                println("$TAG [Step 5] Extractor 호출 (TargetKey: $targetKeyUrl)")
                return AnilifeProxyExtractor().extractWithProxy(
                    m3u8Url = finalM3u8!!,
                    playerUrl = playerUrl,
                    referer = "https://anilife.live/",
                    ssid = xUserSsid,
                    cookies = finalCookies,
                    directKeyUrl = targetKeyUrl,
                    callback = callback
                )
            } else {
                println("$TAG [Error] 최종 M3U8 주소를 찾지 못함.")
            }

        } catch (e: Exception) {
            println("$TAG [Error] 치명적 오류: ${e.message}")
            e.printStackTrace()
        }
        return false
    }
}
