package com.kotbc

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * Kotbc Provider v2.3
 * - Update: import utils.* 적용
 * - Fix: 포스터 이미지 상대 경로(../data/...) 처리
 * - Fix: 시리즈 에피소드 파싱 (.serial-list)
 * - Fix: form.tt2 로직 강화
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

    // ============================================================
    // 메인 페이지
    // ============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/bbs/board.php?bo_table=${request.data}&page=$page"
        println("[Kotbc] Main Page Request: $url")

        return try {
            val doc = app.get(url).document
            // 소스 분석 결과: .list-body .list-row .list-box
            val elements = doc.select(".list-body .list-row .list-box")
            
            println("[Kotbc] Found elements: ${elements.size}")

            val list = elements.mapNotNull { element ->
                element.toSearchResponse()
            }

            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            println("[Kotbc] Error getting main page: ${e.message}")
            e.printStackTrace()
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    // ============================================================
    // 검색
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/bbs/search.php?sfl=wr_subject&stx=$query"
        println("[Kotbc] Search Request: $url")

        return try {
            val doc = app.get(url).document
            val elements = doc.select(".list-body .list-row .list-box")
            println("[Kotbc] Search results found: ${elements.size}")

            elements.mapNotNull { element ->
                element.toSearchResponse()
            }
        } catch (e: Exception) {
            println("[Kotbc] Search error: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    // ============================================================
    // 아이템 파싱 (공통)
    // ============================================================
    private fun Element.toSearchResponse(): SearchResponse? {
        try {
            // 링크 (.list-front .list-img a 또는 .list-text .list-desc a)
            val linkEl = this.selectFirst(".list-front a") ?: this.selectFirst("a")
            val href = linkEl?.attr("href") ?: return null
            val fullUrl = fixUrl(href)

            // 제목 (.post-title)
            val titleEl = this.selectFirst(".post-title")
            var title = titleEl?.text()?.trim() ?: linkEl.text().trim()
            
            // 제목 뒤 연도 및 괄호 제거
            title = title.replace(Regex("\\s*\\(\\d{4}\\)$"), "").trim()

            // 포스터 (.img-item img)
            val imgTag = this.selectFirst(".img-item img") ?: this.selectFirst("img")
            val rawSrc = imgTag?.attr("src")
            val posterUrl = rawSrc?.let { resolvePosterUrl(it) }

            // 타입 결정
            val type = if (href.contains("bo_table=movie")) TvType.Movie else TvType.TvSeries

            return if (type == TvType.Movie) {
                newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            } else {
                newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            println("[Kotbc] Parse item error: ${e.message}")
            return null
        }
    }

    // ============================================================
    // 상세 페이지 로드
    // ============================================================
    override suspend fun load(url: String): LoadResponse {
        println("[Kotbc] Load Detail: $url")
        val doc = app.get(url).document

        // 제목
        val titleEl = doc.selectFirst(".view-title h1")
        var title = titleEl?.text()?.trim() ?: "Unknown Title"
        title = title.replace(Regex("\\s*\\(\\d{4}\\)$"), "").trim()

        // 포스터
        val posterEl = doc.selectFirst(".view-info .image img")
        val poster = posterEl?.attr("src")?.let { resolvePosterUrl(it) }

        // 줄거리
        val description = doc.selectFirst(".view-cont")?.text()?.trim()

        // 태그
        val tags = doc.select(".view-info p span.block:last-child").map { it.text() }
        
        val type = if (url.contains("bo_table=movie")) TvType.Movie else TvType.TvSeries
        
        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            // 시리즈 에피소드 파싱 (.serial-list)
            val episodes = mutableListOf<Episode>()
            val episodeItems = doc.select(".serial-list .list-body .list-item")
            
            println("[Kotbc] Found ${episodeItems.size} episodes")

            if (episodeItems.isNotEmpty()) {
                episodeItems.forEach { item ->
                    val linkEl = item.selectFirst("a.item-subject")
                    val epHref = linkEl?.attr("href")
                    val epName = linkEl?.text()?.trim() ?: "Episode"
                    // 번호 추출
                    val epNum = Regex("(\\d+)[화회]").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    
                    if (!epHref.isNullOrEmpty()) {
                        val fullEpUrl = fixUrl(epHref)
                        episodes.add(
                            newEpisode(fullEpUrl) {
                                this.name = epName
                                this.episode = epNum
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            } else {
                episodes.add(newEpisode(url) {
                    this.name = title
                    this.posterUrl = poster
                })
            }

            // 에피소드 번호순 정렬
            val sortedEpisodes = episodes.sortedBy { it.episode ?: 0 }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

    // ============================================================
    // 링크 추출
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[Kotbc] loadLinks data: $data")

        try {
            val doc = app.get(data).document
            
            // 1. form.tt2 찾기
            val form = doc.selectFirst("form.tt2") ?: doc.selectFirst("form.tt")
            
            if (form != null) {
                val action = form.attr("action")
                val vParam = form.selectFirst("input[name=v]")?.attr("value")

                println("[Kotbc] Found form action: $action, v: $vParam")

                if (action.isNotEmpty() && !vParam.isNullOrEmpty()) {
                    val targetUrl = "$action?v=$vParam"
                    val extractor = KotbcExtractor()
                    extractor.getUrl(targetUrl, mainUrl, subtitleCallback, callback)
                    return true
                }
            }
            
            // 2. 백업: iframe 검색
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.contains("nnmo0oi1") || src.contains("glamov")) {
                     val extractor = KotbcExtractor()
                     extractor.getUrl(fixUrl(src), mainUrl, subtitleCallback, callback)
                }
            }

        } catch (e: Exception) {
            println("[Kotbc] Error in loadLinks: ${e.message}")
            e.printStackTrace()
        }

        return true
    }

    // URL 보정 함수
    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        
        val baseUrl = "$mainUrl/bbs/"
        
        if (url.startsWith("./")) {
            return baseUrl + url.substring(2)
        }
        if (url.startsWith("/")) {
            return mainUrl + url
        }
        return baseUrl + url
    }

    // 포스터 URL 보정 함수
    private fun resolvePosterUrl(url: String): String {
        if (url.startsWith("http")) return url
        
        if (url.startsWith("../")) {
            return mainUrl + "/" + url.substring(3)
        }
        if (url.startsWith("/")) {
            return mainUrl + url
        }
        return "$mainUrl/bbs/$url"
    }
}
