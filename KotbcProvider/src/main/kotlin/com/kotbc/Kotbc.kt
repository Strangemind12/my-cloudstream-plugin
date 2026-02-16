package com.kotbc

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

/**
 * Kotbc Provider v1.4
 * - Build Fix: newEpisode 사용 (Episode 생성자 경고 해결)
 * - TvType.Variety 제거
 * - KotbcExtractor(Class) 인스턴스 호출 방식으로 변경
 */
class Kotbc : MainAPI() {
    override var mainUrl = "https://m135.kotbc2.com"
    override var name = "Kotbc"
    override val hasMainPage = true
    override var lang = "ko"
    
    // Variety 제거됨
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
            val elements = doc.select(".list-row .list-box")
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
            val elements = doc.select(".list-row .list-box")
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

    private fun Element.toSearchResponse(): SearchResponse? {
        try {
            val linkEl = this.selectFirst(".list-img a") ?: this.selectFirst(".list-desc a")
            val href = linkEl?.attr("href") ?: return null
            
            var title = this.selectFirst(".post-title")?.text()?.trim() ?: ""
            if (title.isEmpty()) title = linkEl.text().trim()

            // 제목 뒤 (2026) 같은 연도 제거
            title = title.replace(Regex("\\s*\\(\\d{4}\\)$"), "").trim()

            val imgTag = this.selectFirst(".img-item img")
            val posterUrl = imgTag?.attr("src")

            val type = if (href.contains("movie")) TvType.Movie else TvType.TvSeries

            return if (type == TvType.Movie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            println("[Kotbc] Parse item error: ${e.message}")
            return null
        }
    }

    // ============================================================
    // 상세 페이지
    // ============================================================
    override suspend fun load(url: String): LoadResponse {
        println("[Kotbc] Load Detail: $url")
        val doc = app.get(url).document

        val titleElement = doc.selectFirst(".view-title h1")
        var title = titleElement?.text()?.trim() ?: "Unknown Title"
        title = title.replace(Regex("\\s*\\(\\d{4}\\)$"), "").trim()

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".view-content img")?.attr("src")

        val description = doc.selectFirst("meta[name='description']")?.attr("content")
            ?: doc.selectFirst(".view-content")?.text()?.take(200)

        val tags = doc.select(".view-tag a").map { it.text() }
        val type = if (url.contains("movie")) TvType.Movie else TvType.TvSeries
        
        return if (type == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            // Build Fix: newEpisode 사용
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                newEpisode(url) {
                    this.name = title
                    this.posterUrl = poster
                }
            )) {
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
            
            // form.tt2 요소 찾기
            val form = doc.selectFirst("form.tt2")
            if (form == null) {
                println("[Kotbc] form.tt2 not found!")
                return false
            }

            val action = form.attr("action")
            val vParam = form.selectFirst("input[name=v]")?.attr("value")

            println("[Kotbc] Found form action: $action, v: $vParam")

            if (action.isNotEmpty() && !vParam.isNullOrEmpty()) {
                val targetUrl = "$action?v=$vParam"
                println("[Kotbc] Generated target URL: $targetUrl")
                
                // [변경] ExtractorApi를 상속받은 클래스 인스턴스 생성 및 호출
                // MoviekingProvider와 유사한 구조
                val extractor = KotbcExtractor()
                extractor.getUrl(targetUrl, mainUrl, subtitleCallback, callback)
            } else {
                println("[Kotbc] Invalid form data")
            }

        } catch (e: Exception) {
            println("[Kotbc] Error in loadLinks: ${e.message}")
            e.printStackTrace()
        }

        return true
    }
}
