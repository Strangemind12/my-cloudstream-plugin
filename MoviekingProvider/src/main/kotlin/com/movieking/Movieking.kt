package com.movieking

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException

class MovieKing : MainAPI() {
    override var mainUrl = "https://mvking.net"
    override var name = "MovieKing"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)
    private val commonHeaders = mapOf("Referer" to "$mainUrl/")
    private val titleRegex = Regex("""\s*\(\d{4}\).*""")
    private val tagCleanRegex = Regex("""\s*(한국|해외)?영화\s*\(?\d{4}\)?.*""")

    override val mainPage = mainPageOf("/video/영화" to "영화", "/video/드라마" to "드라마", "/video/TV예능" to "예능", "/video/애니" to "애니", "/video/시사다큐" to "시사/다큐")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "$mainUrl${request.data}${separator}page=$page"
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val list = doc.select(".video-card").mapNotNull { it.toSearchResponse(request.name) }
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            if (e is CancellationException) throw e //[공통 개선] CancellationException 방어
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResponse(categoryName: String? = null): SearchResponse? {
        val linkTag = this.selectFirst(".video-card-image a") ?: return null
        val titleTag = this.selectFirst(".video-title a") ?: return null
        
        val href = fixUrl(linkTag.attr("href"))
        val title = titleTag.text().trim().replace(titleRegex, "").trim()

        val imgTag = this.selectFirst("img")
        val rawPoster = imgTag?.attr("src") ?: imgTag?.attr("data-src")
        val fixedPoster = fixUrl(rawPoster ?: "")

        var finalHref = href
        if (fixedPoster.isNotEmpty()) {
            try { finalHref = "$href&poster_url=${URLEncoder.encode(fixedPoster, "UTF-8")}" } catch (e: Exception) {}
        }

        val isMovie = categoryName == "영화" || href.contains("movie") || href.contains("영화")
        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        return if (type == TvType.Movie) newMovieSearchResponse(title, finalHref, TvType.Movie) { this.posterUrl = fixedPoster }
               else newTvSeriesSearchResponse(title, finalHref, TvType.TvSeries) { this.posterUrl = fixedPoster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?k=$query"
        return try {
            app.get(searchUrl, headers = commonHeaders).document.select(".video-card").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e; emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var passedPoster: String? = null
        var realUrl = url

        try {
            val match = Regex("""[&?]poster_url=([^&]+)""").find(url)
            if (match != null) { passedPoster = URLDecoder.decode(match.groupValues[1], "UTF-8"); realUrl = url.replace(match.value, "") }
        } catch (e: Exception) {}

        try {
            val doc = app.get(realUrl, headers = commonHeaders).document
            val title = (doc.selectFirst("h3.title")?.text()?.trim() ?: doc.selectFirst("h1.text-secondary")?.text()?.trim() ?: "Unknown").replace(titleRegex, "").trim()
            val poster = passedPoster ?: doc.selectFirst(".single-video-left img")?.attr("src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            val infoContent = doc.selectFirst(".single-video-info-content")

            fun getInfoText(keyword: String) = infoContent?.select("p:contains($keyword)")?.text()?.replace(keyword, "")?.replace(":", "")?.trim()

            val genre = getInfoText("장르")?.replace(tagCleanRegex, "")?.trim()
            val country = getInfoText("나라")
            val releaseDate = getInfoText("개봉")
            val director = getInfoText("감독")
            val cast = getInfoText("출연")
            val intro = infoContent?.selectFirst("h6:contains(소개)")?.nextElementSibling()?.text()?.trim()

            val tagsList = mutableListOf<String>()
            if (!genre.isNullOrBlank()) tagsList.add("장르: $genre")
            if (!country.isNullOrBlank()) tagsList.add("국가: $country")
            if (!releaseDate.isNullOrBlank()) tagsList.add("공개일: $releaseDate")
            if (!director.isNullOrBlank()) tagsList.add("감독(방송사): $director")
            if (!cast.isNullOrBlank()) tagsList.add("출연: $cast")

            val year = releaseDate?.replace(Regex("[^0-9-]"), "")?.take(4)?.toIntOrNull()
            val episodeList = doc.select(".video-slider-right-list .eps_a").map { newEpisode(fixUrl(it.attr("href"))) { this.name = it.text().trim() } }.reversed()

            return if (episodeList.isEmpty() || episodeList.size == 1) {
                newMovieLoadResponse(title, realUrl, TvType.Movie, realUrl) { this.posterUrl = fixUrl(poster ?: ""); this.plot = intro; this.tags = tagsList; this.year = year }
            } else {
                newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, episodeList) { this.posterUrl = fixUrl(poster ?: ""); this.plot = intro; this.tags = tagsList; this.year = year }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e; throw e
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val doc = app.get(data, headers = commonHeaders).document
            val src = doc.selectFirst("iframe#view_iframe")?.attr("src")
            if (src != null) { loadExtractor(fixUrl(src), data, subtitleCallback, callback); return true }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
        return false
    }
}
