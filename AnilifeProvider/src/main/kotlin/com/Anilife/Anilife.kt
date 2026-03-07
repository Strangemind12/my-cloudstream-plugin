// v3.0 - Removed heavy WebViewResolver, implemented pure API fetching
package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

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
        "/vodtype/categorize/SP/1" to "SP",
        "/vodtype/categorize/Movie/1" to "극장판"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.name.contains("TOP 20")) "$mainUrl${request.data}" 
                  else "$mainUrl${request.data.substringBeforeLast("/")}/$page"
        
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            println("$TAG [getMainPage] url: $url")
            newHomePageResponse(request.name, parseCommonList(doc))
        } catch (e: Exception) {
            println("$TAG [getMainPage] Error: ${e.message}")
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
                
                // 썸네일 누락 시 투명 픽셀 버그 방지
                if (poster.isEmpty()) {
                    poster = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
                }
                
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
        println("$TAG [search] query: $query")
        return parseCommonList(app.get("$mainUrl/search?keyword=$query", headers = commonHeaders).document)
    }

    override suspend fun load(url: String): LoadResponse {
        println("$TAG [load] url: $url")
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
        println("$TAG [LoadLinks] =================== v3.0 (No WebView, Pure API) ===================")
        println("$TAG [LoadLinks] request data: $data")
        
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            try {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            } catch (e: Exception) { }
        }

        val videoIdMatch = Regex("""id/(\d+)""").find(cleanData)
        val videoId = videoIdMatch?.groupValues?.get(1) ?: "unknown_id"

        try {
            // [핵심] 타임아웃을 유발하는 WebViewResolver 완전 제거
            val webResponse = app.get(
                cleanData, 
                headers = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent)
            )

            val playerUrl = AnilifeProxyExtractor().extractPlayerUrl(webResponse.text, mainUrl) ?: return false
            println("$TAG [Step 2] 원본 플레이어 URL: $playerUrl")

            return AnilifeProxyExtractor().extractWithProxy(
                m3u8Url = "", // 어차피 Extractor에서 자체 추출하므로 공백 전달
                playerUrl = playerUrl,
                referer = "https://anilife.live/",
                ssid = null,
                cookies = "",
                targetKeyUrl = null,
                videoId = videoId,
                callback = callback
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("$TAG [LoadLinks] Error: ${e.message}")
        }
        return false
    }
}
