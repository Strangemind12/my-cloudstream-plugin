package com.linkkf

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element

class Linkkf : MainAPI() {
    // v1.11: 403 Forbidden 해결을 위한 Referer 전략 수정
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

        println("[Linkkf] getMainPage 요청: $url")
        
        try {
            val doc = app.get(url, headers = commonHeaders).document
            val list = doc.select(".vod-item").mapNotNull { element ->
                element.toSearchResponse()
            }
            
            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = list,
                    isHorizontalImages = true
                ),
                hasNext = list.isNotEmpty()
            )

        } catch (e: Exception) {
            e.printStackTrace()
            return newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/view/wd/$query/"
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            doc.select(".vod-item").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val doc = app.get(url, headers = commonHeaders).document

            val title = doc.selectFirst(".detail-info-title")?.text()?.trim() ?: "Unknown Title"
            
            val poster = doc.selectFirst(".detail-img img")?.let { img ->
                val original = img.attr("data-original")
                val src = img.attr("src")
                if (original.isNotEmpty()) original else src
            }

            var description = doc.selectFirst(".detail-desc-content")?.text()?.trim()
            if (description.isNullOrEmpty()) {
                description = "다시보기"
            }
            
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
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fullUrl = if (data.startsWith("http")) data else "$mainUrl$data"
        println("[Linkkf] loadLinks 진입: $fullUrl")
        
        val result = LinkkfExtractor().extract(fullUrl, "$mainUrl/") ?: return false

        // 자막 처리
        if (result.subtitleUrl.isNotEmpty()) {
            subtitleCallback.invoke(SubtitleFile("Korean", result.subtitleUrl))
        }

        // [v1.11 수정] Referer 결정 로직
        // needsWebView가 true인 경우(구형 플레이어), m3u8Url 변수에는 '플레이어 페이지 주소'가 들어있음.
        // 이를 Referer로 사용해야 403 에러를 피할 수 있습니다.
        val playerPageUrl = result.m3u8Url
        val targetReferer = if (result.needsWebView) playerPageUrl else "$mainUrl/"

        var finalM3u8Url = result.m3u8Url

        if (result.needsWebView) {
            println("[Linkkf] 구형 플레이어. WebViewResolver 사용. (Referer 예정: $targetReferer)")
            try {
                val response = app.get(
                    result.m3u8Url, 
                    headers = commonHeaders, 
                    interceptor = WebViewResolver(Regex("""\.m3u8"""))
                )
                finalM3u8Url = response.url
                println("[Linkkf] WebView 스니핑 성공: $finalM3u8Url")
            } catch (e: Exception) {
                println("[Linkkf] WebViewResolver 에러: ${e.message}")
                return false
            }
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = finalM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                // 수정된 Referer 적용
                this.referer = targetReferer
                this.quality = getQualityFromName("HD")
            }
        )
        return true
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
