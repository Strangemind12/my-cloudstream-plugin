package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class ExampleProvider : MainAPI() { 
    override var mainUrl = "https://lovable.app" 
    override var name = "Muviex"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true

    // This finds movies on your home page
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val home = document.select("div.movie-card, div.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // This makes the search bar work
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.movie-card, div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    // Helper to turn your site's HTML into CloudStream data
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2, h3, .title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // This gets the video link when a user clicks a movie
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("iframe").forEach { 
            val src = it.attr("src")
            loadExtractor(src, data, subtitleCallback, callback)
        }
        return true
    }
}
