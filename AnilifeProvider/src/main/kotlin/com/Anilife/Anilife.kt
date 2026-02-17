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
 * Anilife Provider v13.0
 * - [Fix] 정규식 대폭 완화: 도메인 유무 관계없이 '/h/live?p=' 패턴 검색
 * - [Debug] 실패 시 HTML 원본 로그 출력 (원인 분석용)
 * - [Fix] 에피소드 정렬: v11.0 방식(단순 파싱) 유지
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

        // v11.0 방식 유지 (렉 없음)
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
            
            println("$TAG [LoadLinks] HTML Fetched (Length: ${html.length})")

            // 3. 실제 플레이어 주소 파싱 (정규식 대폭 완화)
            // 도메인이 있든 없든, 따옴표 안에 /h/live?p=... 패턴이 있으면 잡습니다.
            // 예: "https://anilife.live/h/live?p=..." 또는 "/h/live?p=..."
            val regex = Regex("""["']([^"']*\/?h\/live\?p=[^"']+)["']""")
            val match = regex.find(html)
            var playerUrl = match?.groupValues?.get(1)

            if (playerUrl != null) {
                // 상대 경로일 경우 도메인 추가
                if (!playerUrl.startsWith("http")) {
                    playerUrl = if (playerUrl.startsWith("/")) "$mainUrl$playerUrl" else "$mainUrl/$playerUrl"
                }
                // 이스케이프 문자 제거 (혹시 자바스크립트 내부에 \/ 로 되어있을 경우)
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
                } else {
                    println("$TAG [WebView] Failed. URL does not contain .m3u8")
                }
            } else {
                println("$TAG [LoadLinks] Failed to find player URL in HTML.")
                // [중요] 디버깅을 위해 HTML 앞부분 1000자를 출력합니다. 로그캣 확인 필수.
                println("$TAG [Debug] HTML Dump (Start): ${html.take(1000)}")
            }
        } catch (e: Exception) {
            println("$TAG [LoadLinks] Critical Error: ${e.message}")
            e.printStackTrace()
        }

        println("$TAG [LoadLinks] Returned False.")
        return false
    }
}
