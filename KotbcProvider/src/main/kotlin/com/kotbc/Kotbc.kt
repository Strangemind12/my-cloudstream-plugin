package com.kotbc

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * Kotbc Provider v3.1
 * - Update: 에피소드 정규식에 '부' 추가 (화/회/부)
 * - Update: 상세 정보 태그 파싱 로직 개선 (제목 제외, 개요->장르 변환 및 텍스트 정제, 라벨링 추가)
 */
class Kotbc : MainAPI() {
    override var mainUrl = "https://m135.kotbc2.com"
    override var name = "KOTBC"
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
        "mid" to "해외드라마"
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
        
        // [수정] 태그 파싱 로직 개선
        val tags = doc.select(".view-info p").mapNotNull { p ->
            val labelEl = p.selectFirst("span.block:first-child")
            val valueEl = p.selectFirst("span.block:last-child")
            
            if (labelEl != null && valueEl != null) {
                var label = labelEl.text().trim()
                var value = valueEl.text().trim()

                // 1. "제목" 태그는 제외
                if (label == "제목") return@mapNotNull null

                // 2. "개요" -> "장르"로 변경 및 내용 정제
                if (label == "개요") {
                    label = "장르"
                    // 예: "드라마/코미디 한국영화 (2025)" -> "드라마/코미디"
                    // "한국영화 (2025)", "해외영화 (2004)" 등의 패턴 제거
                    value = value.replace(Regex("\\s*(한국|해외)?영화\\s*\\(\\d{4}\\).*"), "").trim()
                }

                // 내용이 있을 경우에만 "라벨: 내용" 형식으로 반환
                if (value.isNotEmpty()) {
                    "$label: $value"
                } else {
                    null
                }
            } else {
                null
            }
        }

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
                
                // [수정] 에피소드 정규식에 '부' 추가
                val epNum = Regex("(\\d+)[화회부]").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                
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
