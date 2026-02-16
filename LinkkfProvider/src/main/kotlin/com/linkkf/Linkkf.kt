package com.linkkf

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.nodes.Element

class Linkkf : MainAPI() {
    override var mainUrl = "https://linkkf.tv"
    override var name = "Linkkf"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime,
        TvType.OVA
    )

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/label/topday" to "오늘의 인기순위",
        "/label/week" to "이번주 인기순위",
        "/label/month" to "이번달 인기순위",
        "/label/view" to "전체 인기순위",
        "/list/2/lang/TV" to "TV 애니메이션",
        "/list/2/lang/Movie" to "극장판",
        "/list/2/lang/OVA" to "OVA"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}/"
        } else {
            val baseUrl = if(request.data.endsWith("/")) request.data else "${request.data}/"
            "$mainUrl${baseUrl}page/$page/"
        }

        val doc = app.get(url, headers = commonHeaders).document
        val list = doc.select(".vod-item").mapNotNull { element ->
            element.toSearchResponse()
        }

        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/view/wd/$query/"
        val doc = app.get(url, headers = commonHeaders).document
        return doc.select(".vod-item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = commonHeaders).document

        val title = doc.selectFirst(".detail-info-title")?.text()?.trim() ?: "Unknown Title"
        val poster = doc.selectFirst(".detail-img img")?.let { img ->
            img.attr("data-original").ifEmpty { img.attr("src") }
        }
        val description = doc.selectFirst(".detail-desc-content")?.text()?.trim()
            ?: doc.selectFirst(".detail-info-desc")?.text()?.trim()
        val tags = doc.select(".detail-info-desc a[href*='/class/']").map { it.text().trim() }
        val year = doc.selectFirst("a[href*='/year/']")?.text()?.trim()?.toIntOrNull()

        val episodes = doc.select(".episode-box .episodelist a").mapNotNull { aTag ->
            val href = aTag.attr("href")
            val name = aTag.text().trim()
            if (href.isNotEmpty()) {
                newEpisode(href) {
                    this.name = name
                    this.episode = name.filter { it.isDigit() }.toIntOrNull()
                }
            } else null
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fullUrl = if (data.startsWith("http")) data else "$mainUrl$data"
        
        // Extractor를 통해 데이터만 추출
        val result = LinkkfExtractor().extract(fullUrl, "$mainUrl/")

        if (result != null) {
            // 자막 처리
            subtitleCallback.invoke(
                SubtitleFile("Korean", result.subtitleUrl)
            )

            // newExtractorLink 사용하여 링크 생성 (Deprecated 에러 해결)
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = result.m3u8Url,
                    referer = "$mainUrl/",
                    quality = getQualityFromName("HD"),
                    isM3u8 = true
                )
            )
            return true
        }
        return false
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a.vod-item-img") ?: return null
        val title = this.selectFirst(".vod-item-title a")?.text()?.trim() ?: return null
        val href = aTag.attr("href")
        val poster = this.selectFirst(".img-wrapper")?.attr("data-original")
            ?.ifEmpty { this.selectFirst("img")?.attr("src") }

        return newTvSeriesSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }
}
