package com.anilife

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Anilife Provider v14.0
 * - [Fix] Referer 차단 우회: 상세 페이지 URL을 에피소드 링크에 'ref' 파라미터로 전달 -> 요청 시 헤더에 적용
 * - [Fix] 서버가 Provider 페이지 요청 시 'Detail Page' Referer가 없으면 메인으로 리다이렉트시키는 문제 해결
 * - [Keep] 에피소드 정렬 로직 동결 (v11.0 방식)
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
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
        val url = if (request.name.contains("TOP 20")) {
            "$mainUrl${request.data}"
        } else {
            val basePath = request.data.substringBeforeLast("/")
            "$mainUrl$basePath/$page"
        }

        println("$TAG [MainPage] Requesting: $url")
        
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val home = parseCommonList(doc)
            println("$TAG [MainPage] Found ${home.size} items.")
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            println("$TAG [MainPage] Error: ${e.message}")
            e.printStackTrace()
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
                var poster = imgTag?.attr("src")
                if (poster.isNullOrEmpty()) poster = imgTag?.attr("data-src")
                if (poster.isNullOrEmpty()) poster = imgTag?.attr("data-original")
                poster = poster?.let { fixUrl(it) } ?: ""

                // 포스터 터널링 (목록 -> 상세)
                val finalHref = if (poster.isNotEmpty()) {
                    try {
                        val encodedPoster = Base64.encodeToString(poster.toByteArray(), Base64.NO_WRAP)
                        if (rawHref.contains("?")) "$rawHref&poster=$encodedPoster" else "$rawHref?poster=$encodedPoster"
                    } catch (e: Exception) { rawHref }
                } else { rawHref }

                newAnimeSearchResponse(title, finalHref, TvType.Anime) {
                    this.posterUrl = poster
                    this.posterHeaders = commonHeaders
                }
            } catch (e: Exception) { 
                null 
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        println("$TAG [Search] Query: $url")
        val doc = app.get(url, headers = commonHeaders).document
        return parseCommonList(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        // URL에서 포스터 등 파라미터 분리
        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            try {
                val posterParam = url.substringAfter("poster=")
                val encodedPoster = if (posterParam.contains("&")) posterParam.substringBefore("&") else posterParam
                tunnelingPoster = String(Base64.decode(encodedPoster, Base64.NO_WRAP))
                url.substringBefore("?poster=")
            } catch (e: Exception) { url }
        } else { url }

        println("$TAG [Load] cleanUrl: $cleanUrl")
        
        val doc = app.get(cleanUrl, headers = commonHeaders).document
        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"

        var htmlPoster = doc.selectFirst(".thumb img")?.let { img ->
            img.attr("src").ifEmpty { img.attr("data-src") }
        }?.let { fixUrl(it) }

        if (htmlPoster.isNullOrEmpty() || htmlPoster == mainUrl || htmlPoster == "$mainUrl/") {
            if (!tunnelingPoster.isNullOrEmpty()) htmlPoster = tunnelingPoster
        }

        val description = doc.selectFirst(".synp .entry-content")?.text()?.trim()
        val tags = doc.select(".genxed a, .taged a").map { it.text() }

        // [v14.0 핵심] 현재 상세 페이지 URL을 Base64로 인코딩하여 Referer로 사용
        // 이 값이 없으면 Provider 페이지 접속 시 메인으로 튕김
        val encodedReferer = try {
            Base64.encodeToString(cleanUrl.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) { "" }

        // v11.0 방식 (렉 없는 단순 파싱)
        val episodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val rawHref = fixUrl(element.attr("href"))
            val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            val fullName = if (numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
            val epNum = numText.toIntOrNull()

            // href에 referer 파라미터 추가
            val finalHref = if (rawHref.contains("?")) "$rawHref&ref=$encodedReferer" else "$rawHref?ref=$encodedReferer"

            newEpisode(finalHref) {
                this.name = fullName
                this.episode = epNum
            }
        }.reversed()

        return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
            this.posterUrl = htmlPoster
            this.posterHeaders = commonHeaders
            this.plot = description
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
        println("$TAG [LoadLinks] Start Data: $data")

        // 1. 파라미터 파싱 (poster, ref)
        var cleanData = data
        var refererUrl = "$mainUrl/" // 기본 Referer

        // poster 파라미터 제거
        if (cleanData.contains("poster=")) {
            cleanData = cleanData.substringBefore("?poster=")
        }
        
        // [v14.0 핵심] ref 파라미터 추출 및 적용
        if (cleanData.contains("ref=")) {
            try {
                val refParam = cleanData.substringAfter("ref=")
                val encodedRef = if (refParam.contains("&")) refParam.substringBefore("&") else refParam
                val decodedRef = String(Base64.decode(encodedRef, Base64.NO_WRAP))
                
                if (decodedRef.startsWith("http")) {
                    refererUrl = decodedRef
                    println("$TAG [LoadLinks] Extracted Referer: $refererUrl")
                }
                
                // URL에서 ref 파라미터 제거
                cleanData = if (cleanData.contains("?ref=")) cleanData.substringBefore("?ref=") 
                            else cleanData.substringBefore("&ref=")
            } catch (e: Exception) {
                println("$TAG [LoadLinks] Referer decode failed: ${e.message}")
            }
        }

        println("$TAG [LoadLinks] Target URL: $cleanData")

        try {
            // 2. Provider 페이지 로드 (올바른 Referer 사용)
            val requestHeaders = commonHeaders.toMutableMap()
            requestHeaders["Referer"] = refererUrl // 상세 페이지 주소를 Referer로 설정

            println("$TAG [LoadLinks] Fetching Provider Page with Referer: $refererUrl")
            val response = app.get(cleanData, headers = requestHeaders)
            val html = response.text
            
            // HTML 검증 (메인 페이지로 튕겼는지 확인)
            if (html.contains("메인 홈페이지") || html.length > 35000) { // 메인 페이지는 보통 용량이 큼
                 // println("$TAG [Debug] Warning: HTML might be Main Page. Dump: ${html.take(300)}")
            }

            // 3. 실제 플레이어 주소 파싱
            val regex = Regex("""["']([^"']*\/?h\/live\?p=[^"']+)["']""")
            val match = regex.find(html)
            var playerUrl = match?.groupValues?.get(1)

            if (playerUrl != null) {
                if (!playerUrl.startsWith("http")) {
                    playerUrl = if (playerUrl.startsWith("/")) "$mainUrl$playerUrl" else "$mainUrl/$playerUrl"
                }
                playerUrl = playerUrl.replace("\\/", "/")

                println("$TAG [LoadLinks] Found Player URL: $playerUrl")
                println("$TAG [LoadLinks] Starting WebViewResolver...")

                // 4. WebViewResolver 실행
                val webViewInterceptor = WebViewResolver(
                    Regex("""\.m3u8""")
                )
                
                val webViewResponse = app.get(
                    playerUrl,
                    headers = commonHeaders,
                    interceptor = webViewInterceptor
                )
                
                val sniffedUrl = webViewResponse.url
                println("$TAG [WebView] Sniffed URL: $sniffedUrl")

                if (sniffedUrl.contains(".m3u8")) {
                     callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = sniffedUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://anilife.live/"
                            this.quality = getQualityFromName("HD")
                        }
                    )
                    println("$TAG [LoadLinks] Success! Link returned.")
                    return true
                }
            } else {
                println("$TAG [LoadLinks] Failed to find player URL in HTML.")
                // 디버깅: 실패 시 HTML 앞부분 확인
                println("$TAG [Debug] HTML Dump (Start): ${html.take(500)}")
            }
        } catch (e: Exception) {
            println("$TAG [LoadLinks] Critical Error: ${e.message}")
            e.printStackTrace()
        }

        println("$TAG [LoadLinks] Returned False.")
        return false
    }
}
