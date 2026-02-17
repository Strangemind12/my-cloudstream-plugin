package com.anilife

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"

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

        val doc = app.get(url).document
        val home = parseCommonList(doc)
        
        return newHomePageResponse(request.name, home)
    }

    private fun parseCommonList(doc: Document): List<SearchResponse> {
        val items = doc.select(".listupd > article.bs").mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val href = fixUrl(aTag.attr("href"))
                val title = element.selectFirst(".tt")?.text()?.trim() ?: return@mapNotNull null
                val poster = element.selectFirst("img")?.attr("src") ?: ""
                
                // addSub 등 불필요한 장식 로직 제거
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            } catch (e: Exception) {
                null
            }
        }
        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        val doc = app.get(url).document
        return parseCommonList(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst(".thumb img")?.attr("src")
        val description = doc.selectFirst(".synp .entry-content")?.text()?.trim()
        val tags = doc.select(".genxed a, .taged a").map { it.text() }
        
        val episodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val num = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            
            val fullName = if(num.isNotEmpty()) "${num}화 - $epTitle" else epTitle
            val episodeInt = num.toIntOrNull()

            newEpisode(href) {
                this.name = fullName
                this.episode = episodeInt
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
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
        val extractor = AnilifeExtractor()
        return extractor.extract(data, callback)
    }
}
