package com.linkkf

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Linkkf : MainAPI() {
    // v1.4: 줄거리 파싱 로직 수정 및 가로 포스터 유지
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

        println("[Linkkf] getMainPage 요청 시작: $url (Page: $page)")
        
        try {
            val doc = app.get(url, headers = commonHeaders).document
            val list = doc.select(".vod-item").mapNotNull { element ->
                element.toSearchResponse()
            }
            
            println("[Linkkf] getMainPage 파싱 완료: ${list.size}개 항목 발견")
            
            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = list,
                    isHorizontalImages = true // 가로 포스터 활성화
                ),
                hasNext = list.isNotEmpty()
            )

        } catch (e: Exception) {
            println("[Linkkf] getMainPage 에러 발생: ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/view/wd/$query/"
        println("[Linkkf] search 요청: $url")
        
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val list = doc.select(".vod-item").mapNotNull { it.toSearchResponse() }
            println("[Linkkf] search 결과: ${list.size}개 항목 발견")
            list
        } catch (e: Exception) {
            println("[Linkkf] search 에러: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        println("[Linkkf] load 요청: $url")
        
        try {
            val doc = app.get(url, headers = commonHeaders).document

            val title = doc.selectFirst(".detail-info-title")?.text()?.trim() ?: "Unknown Title"
            
            // 포스터
            val poster = doc.selectFirst(".detail-img img")?.let { img ->
                val original = img.attr("data-original")
                val src = img.attr("src")
                if (original.isNotEmpty()) original else src
            }
            println("[Linkkf] 상세 정보: 제목='$title'")

            // [v1.4 수정] 줄거리 파싱 로직 변경
            // 1순위: .detail-desc-content (실제 줄거리 영역)
            // 2순위: meta 태그
            // 차단: .detail-info-desc (메타데이터 영역이므로 사용하지 않음)
            val description = doc.selectFirst(".detail-desc-content")?.text()?.trim()
                ?: doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
            
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

            println("[Linkkf] 에피소드 파싱: ${episodes.size}개 발견")

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } catch (e: Exception) {
            println("[Linkkf] load 에러: ${e.message}")
            e.printStackTrace()
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
        
        // Extractor 호출
        val result = LinkkfExtractor().extract(fullUrl, "$mainUrl/")

        if (result != null) {
            println("[Linkkf] Extractor 성공. M3U8: ${result.m3u8Url}")
            
            subtitleCallback.invoke(
                SubtitleFile("Korean", result.subtitleUrl)
            )

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = result.m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName("HD")
                }
            )
            return true
        } else {
            println("[Linkkf] Extractor 실패: 결과를 반환받지 못했습니다.")
            return false
        }
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
