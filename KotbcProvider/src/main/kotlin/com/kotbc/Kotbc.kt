package com.kotbc

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * Kotbc Provider v3.0
 * - Refactor: TVWiki/TVMON 방식의 WebView 기반 Extractor 사용
 */
class Kotbc : MainAPI() {
    override var mainUrl = "https://m135.kotbc2.com"
    override var name = "Kotbc"
    override val hasMainPage = true
    override var lang = "ko"
    
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "movie" to "영화",
        "drama" to "드라마",
        "enter" to "예능/시사",
        "mid" to "미드"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/bbs/board.php?bo_table=${request.data}&page=$page"
        return try {
            val doc = app.get(url).document
            val elements = doc.select(".list-body .list-row .list-box")
            val list = elements.mapNotNull { element -> element.toSearchResponse() }
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/bbs/search.php?sfl=wr_subject&stx=$query"
        return try {
            val doc = app.get(url).document
            val elements = doc.select(".list-body .list-row .list-box")
            elements.mapNotNull { element -> element.toSearchResponse() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        try {
            val linkEl = this.selectFirst(".list-front a") ?: this.selectFirst("a")
            val href = linkEl?.attr("href") ?: return null
            val fullUrl = fixUrl(href)
            val titleEl = this.selectFirst(".post-title")
            var title = titleEl?.text()?.trim() ?: linkEl.text().trim()
            title = title.replace(Regex("\\s*\\(\\d{4}\\)$"), "").trim()
            val imgTag = this.selectFirst(".img-item img") ?: this.selectFirst("img")
            val posterUrl = imgTag?.attr("src")?.let { resolvePosterUrl(it) }
            val type = if (href.contains("bo_table=movie")) TvType.Movie else TvType.TvSeries

            return if (type == TvType.Movie) {
                newMovieSearchResponse(title, fullUrl, TvType.Movie) { this.posterUrl = posterUrl }
            } else {
                newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) { this.posterUrl = posterUrl }
            }
        } catch (e: Exception) { return null }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst(".view-title h1")?.text()?.trim()?.replace(Regex("\\s*\\(\\d{4}\\)$"), "") ?: "Unknown"
        val poster = doc.selectFirst(".view-info .image img")?.attr("src")?.let { resolvePosterUrl(it) }
        val description = doc.selectFirst(".view-cont")?.text()?.trim()
        val tags = doc.select(".view-info p span.block:last-child").map { it.text() }
        val type = if (url.contains("bo_table=movie")) TvType.Movie else TvType.TvSeries
        
        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            val episodes = mutableListOf<Episode>()
            doc.select(".serial-list .list-body .list-item").forEach { item ->
                val linkEl = item.selectFirst("a.item-subject")
                val epHref = linkEl?.attr("href")
                val epName = linkEl?.text()?.trim() ?: "Episode"
                val epNum = Regex("(\\d+)[화회]").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                if (!epHref.isNullOrEmpty()) {
                    episodes.add(newEpisode(fixUrl(epHref)) {
                        this.name = epName
                        this.episode = epNum
                        this.posterUrl = poster
                    })
                }
            }
            if (episodes.isEmpty()) episodes.add(newEpisode(url) { this.name = title; this.posterUrl = poster })
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedBy { it.episode ?: 0 }) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // [수정] tt2 폼 데이터를 이용해 Extractor 호출
        try {
            val doc = app.get(data).document
            val form = doc.selectFirst("form.tt2") ?: doc.selectFirst("form.tt")
            if (form != null) {
                val action = form.attr("action")
                val vParam = form.selectFirst("input[name=v]")?.attr("value")
                if (action.isNotEmpty() && !vParam.isNullOrEmpty()) {
                    val targetUrl = "$action?v=$vParam"
                    val extractor = KotbcExtractor()
                    extractor.getUrl(targetUrl, mainUrl, subtitleCallback, callback)
                    return true
                }
            }
            // 백업: iframe 직접 검색
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.contains("nnmo0oi1") || src.contains("glamov")) {
                     val extractor = KotbcExtractor()
                     extractor.getUrl(fixUrl(src), mainUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return true
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        val baseUrl = "$mainUrl/bbs/"
        if (url.startsWith("./")) return baseUrl + url.substring(2)
        if (url.startsWith("/")) return mainUrl + url
        return baseUrl + url
    }

    private fun resolvePosterUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("../")) return mainUrl + "/" + url.substring(3)
        if (url.startsWith("/")) return mainUrl + url
        return "$mainUrl/bbs/$url"
    }
}
