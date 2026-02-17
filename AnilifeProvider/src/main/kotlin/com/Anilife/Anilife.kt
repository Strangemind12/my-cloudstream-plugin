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
 * Anilife Provider v7.1
 * - [Fix] WebViewResolver 'referer' 파라미터 에러 수정 (headers 맵으로 전달)
 * - [Fix] 에피소드 정렬 최적화 (Float 정렬 + 로그 제거)
 * - [Fix] 링크 추출 로직 유지 (HTML 파싱 -> WebView 실행)
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

        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val home = parseCommonList(doc)
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
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
            } catch (e: Exception) { null }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        val doc = app.get(url, headers = commonHeaders).document
        return parseCommonList(doc)
    }

    data class TempEpisode(
        val url: String,
        val fullName: String,
        val floatNum: Float
    )

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

        println("$TAG [Load] URL: $cleanUrl")
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

        // [v7.0] 렉 방지를 위해 로그 제거 후 Float 정렬 수행
        val rawEpisodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            val fullName = if (numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
            val floatNum = numText.toFloatOrNull() ?: 0f
            TempEpisode(href, fullName, floatNum)
        }

        val sortedEpisodes = rawEpisodes.sortedBy { it.floatNum }

        val finalEpisodes = sortedEpisodes.mapIndexed { index, temp ->
            newEpisode(temp.url) {
                this.name = temp.fullName
                this.episode = index + 1
            }
        }.reversed()

        println("$TAG [Load] Loaded ${finalEpisodes.size} episodes.")

        return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
            this.posterUrl = htmlPoster
            this.posterHeaders = commonHeaders
            this.plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, finalEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. URL 정리 (포스터 파라미터 제거)
        val cleanData = if (data.contains("poster=")) data.substringBefore("?poster=") else data
        println("$TAG [LoadLinks] Start: $cleanData")

        try {
            // 2. Provider 페이지 로드 (HTML 텍스트 가져오기)
            val response = app.get(cleanData, headers = commonHeaders)
            val html = response.text

            // 3. 실제 플레이어 주소 파싱
            // 자바스크립트 내의 "https://anilife.live/h/live?p=...&player=..." 패턴 추출
            val regex = Regex("""https://anilife\.live/h/live\?p=[^"']+(?:&player=[^"']+)*""")
            val match = regex.find(html)
            val playerUrl = match?.value

            if (playerUrl != null) {
                println("$TAG [LoadLinks] Found Player URL: $playerUrl")
                
                // 4. 추출한 플레이어 주소로 WebViewResolver 실행
                // [수정] referer 파라미터 제거 -> headers 맵에 포함
                val webViewInterceptor = WebViewResolver(
                    Regex("""\.m3u8"""),
                    userAgent = commonHeaders["User-Agent"],
                    headers = mapOf("Referer" to "https://anilife.live/") // 수정된 부분
                )
                
                val webViewResponse = app.get(playerUrl, headers = commonHeaders, interceptor = webViewInterceptor)
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
                    return true
                }
            } else {
                println("$TAG [LoadLinks] Failed to find player URL in HTML.")
            }
        } catch (e: Exception) {
            println("$TAG [LoadLinks] Error: ${e.message}")
            e.printStackTrace()
        }

        return false
    }
}
