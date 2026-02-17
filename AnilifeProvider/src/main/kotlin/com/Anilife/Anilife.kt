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
 * Anilife Provider v12.0
 * - [Debug] 모든 주요 프로세스에 상세 println 로그 추가 (사용자 요청)
 * - [Fix] 영상 링크 추출: MainAPI 내에서 HTML 파싱 -> WebViewResolver 실행 (가장 확실한 방법)
 * - [Fix] 에피소드 정렬: v11.0 방식 유지 (단순 파싱, 렉 없음)
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
            println("$TAG [MainPage] Success. Found ${home.size} items.")
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
                println("$TAG [List] Parse Error: ${e.message}")
                null 
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        println("$TAG [Search] Query: $query -> $url")
        val doc = app.get(url, headers = commonHeaders).document
        return parseCommonList(doc)
    }

    override suspend fun load(url: String): LoadResponse {
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

        // v11.0 방식: 단순 파싱 (렉 없음)
        val episodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            val fullName = if (numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
            val epNum = numText.toIntOrNull()

            newEpisode(href) {
                this.name = fullName
                this.episode = epNum
            }
        }.reversed()

        println("$TAG [Load] Loaded ${episodes.size} episodes")

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
        // 1. URL 정리
        val cleanData = if (data.contains("poster=")) data.substringBefore("?poster=") else data
        println("$TAG [LoadLinks] Start: $cleanData")

        try {
            // 2. Provider 페이지 로드
            println("$TAG [LoadLinks] Fetching Provider Page...")
            val response = app.get(cleanData, headers = commonHeaders)
            val html = response.text
            
            // 3. 실제 플레이어 주소 파싱 (HTML 전체 검색)
            // 패턴: https://anilife.live/h/live?p=...&player=...
            val regex = Regex("""https://anilife\.live/h/live\?p=[^"']+(?:&player=[^"']+)*""")
            val match = regex.find(html)
            val playerUrl = match?.value

            if (playerUrl != null) {
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
                } else {
                    println("$TAG [WebView] Failed. URL does not contain .m3u8")
                }
            } else {
                println("$TAG [LoadLinks] Failed to find player URL in HTML.")
                // 디버깅용: HTML 일부 출력 (너무 길면 자름)
                // println("$TAG [Debug] HTML Snippet: ${html.take(500)}")
            }
        } catch (e: Exception) {
            println("$TAG [LoadLinks] Critical Error: ${e.message}")
            e.printStackTrace()
        }

        println("$TAG [LoadLinks] Returned False.")
        return false
    }
}
