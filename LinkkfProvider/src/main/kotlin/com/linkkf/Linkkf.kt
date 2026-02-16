package com.linkkf

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
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

    // 메인 페이지 카테고리 설정
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
        // 페이징 처리: 1페이지는 기본 URL, 2페이지부터는 /page/N/ 형태
        // 라벨(/label/) 페이지와 리스트(/list/) 페이지 모두 동일한 페이징 규칙을 따르는지 확인 필요하지만,
        // 일반적인 그누보드/CMS 구조를 따름.
        val url = if (page == 1) {
            "$mainUrl${request.data}/"
        } else {
             // 끝에 슬래시가 있는지 확인하고 처리
            val baseUrl = if(request.data.endsWith("/")) request.data else "${request.data}/"
            "$mainUrl${baseUrl}page/$page/"
        }

        println("[Linkkf] Main Page Request: $url")
        val doc = app.get(url, headers = commonHeaders).document
        
        val list = doc.select(".vod-item").mapNotNull { element ->
            element.toSearchResponse()
        }

        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/view/wd/$query/"
        println("[Linkkf] Search Request: $url")
        
        val doc = app.get(url, headers = commonHeaders).document
        return doc.select(".vod-item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        println("[Linkkf] Load Request: $url")
        val doc = app.get(url, headers = commonHeaders).document

        // 상세 정보 파싱
        val title = doc.selectFirst(".detail-info-title")?.text()?.trim() ?: "Unknown Title"
        
        val poster = doc.selectFirst(".detail-img img")?.let { img ->
            img.attr("data-original").ifEmpty { img.attr("src") }
        }

        // 줄거리 파싱 (여러 위치 시도)
        val description = doc.selectFirst(".detail-desc-content")?.text()?.trim()
            ?: doc.selectFirst(".detail-info-desc")?.text()?.trim()

        // 태그(장르) 파싱
        val tags = doc.select(".detail-info-desc a[href*='/class/']").map { it.text().trim() }
        val year = doc.selectFirst("a[href*='/year/']")?.text()?.trim()?.toIntOrNull()

        // 에피소드 파싱
        val episodes = doc.select(".episode-box .episodelist a").mapNotNull { aTag ->
            val href = aTag.attr("href")
            val name = aTag.text().trim()
            if (href.isNotEmpty()) {
                newEpisode(href) {
                    this.name = name
                    // 숫자만 추출하여 에피소드 번호 지정 시도
                    this.episode = name.filter { it.isDigit() }.toIntOrNull()
                }
            } else null
        }
        // 최신화가 보통 맨 뒤에 오므로 역순 정렬이 필요할 수 있음. 
        // 사이트 구조상 1화가 먼저 나오면 그대로, 최신화가 먼저 나오면 .reversed() 필요.
        // 제공된 파일(상세페이지)에는 7화, 6화... 순으로 되어 있으므로 역순 정렬 필요.
        val sortedEpisodes = episodes.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
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
        // Extractor.kt로 분리된 로직 호출
        // data는 에피소드 페이지 URL (예: /watch/403791/a1/k7/)
        val fullUrl = if (data.startsWith("http")) data else "$mainUrl$data"
        
        return LinkkfExtractor().extract(
            url = fullUrl,
            referer = "$mainUrl/",
            subtitleCallback = subtitleCallback,
            callback = callback
        )
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
