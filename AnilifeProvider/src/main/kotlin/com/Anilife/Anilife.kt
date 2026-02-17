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

        println("$TAG [MainPage] Request: $url")
        
        try {
            val doc = app.get(url).document
            val home = parseCommonList(doc)
            return newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            println("$TAG [MainPage] Error: ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun parseCommonList(doc: Document): List<SearchResponse> {
        // .listupd > article.bs 구조
        val items = doc.select(".listupd > article.bs").mapNotNull { element ->
            try {
                // 1. 링크 파싱
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val href = fixUrl(aTag.attr("href"))

                // 2. 제목 중복 수정 (.tt 안에 텍스트와 h2가 같이 있음)
                // 우선 h2 태그의 텍스트를 가져오고, 없으면 .tt의 text를 가져옴
                val titleElement = element.selectFirst(".tt h2") ?: element.selectFirst(".tt")
                val title = titleElement?.text()?.trim() ?: "Unknown"

                // 3. 포스터 파싱 (selectFirst("img")가 가끔 빗나갈 수 있으므로 구체화)
                val imgTag = element.selectFirst(".limit img") ?: element.selectFirst("img")
                val poster = imgTag?.attr("src")?.let { fixUrl(it) } ?: ""

                // 디버깅: 포스터나 제목이 이상하면 로그 출력
                // println("$TAG [ListItem] Title: $title | Poster: $poster | Link: $href")

                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            } catch (e: Exception) {
                println("$TAG [ListItem] Parse Error: ${e.message}")
                null
            }
        }
        println("$TAG [List] Parsed ${items.size} items.")
        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        println("$TAG [Search] Query: $query -> $url")
        val doc = app.get(url).document
        return parseCommonList(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        println("$TAG [Load] Details URL: $url")
        val doc = app.get(url).document

        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst(".thumb img")?.attr("src")?.let { fixUrl(it) }
        val description = doc.selectFirst(".synp .entry-content")?.text()?.trim()
        val tags = doc.select(".genxed a, .taged a").map { it.text() }
        
        println("$TAG [Load] Title: $title")

        // 에피소드 파싱
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

        println("$TAG [Load] Episodes found: ${episodes.size}")

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
        println("$TAG [LoadLinks] Start: $data")
        val extractor = AnilifeExtractor()
        return extractor.extract(data, callback)
    }
}
