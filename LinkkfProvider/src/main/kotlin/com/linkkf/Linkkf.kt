package com.linkkf

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element

// v1.2: 인기순위(/label/) 카탈로그에 대해 hasNext를 false로 설정하여 페이지네이션 방지, 불필요한 분기문 제거
class Linkkf : MainAPI() {
    override var mainUrl = "https://linkkf.tv"
    override var name = "Linkkf"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(
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
        println("[Linkkf v1.2] getMainPage 호출됨 - 카탈로그: ${request.name}, 데이터: ${request.data}, 페이지: $page")
        
        val url = if (page == 1) {
            "$mainUrl${request.data}/"
        } else {
            val baseUrl = if(request.data.endsWith("/")) request.data else "${request.data}/"
            "$mainUrl${baseUrl}page/$page/"
        }
        println("[Linkkf v1.2] 요청 URL: $url")
        
        try {
            val doc = app.get(url, headers = commonHeaders).document
            val list = doc.select(".vod-item").mapNotNull { element ->
                element.toSearchResponse()
            }
            println("[Linkkf v1.2] 파싱된 아이템 개수: ${list.size}")
            
            // v1.2 수정: /label/ 경로의 카탈로그일 경우 hasNext를 단순하게 false로 설정
            val isLabelCatalog = request.data.startsWith("/label/")
            val hasNextPage = if (isLabelCatalog) false else list.isNotEmpty()
            println("[Linkkf v1.2] 다음 페이지 존재 여부(hasNext): $hasNextPage")
            
            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = list,
                    isHorizontalImages = true
                ),
                hasNext = hasNextPage
            )
        } catch (e: Exception) {
            println("[Linkkf v1.2] getMainPage 에러 발생: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/view/wd/$query/"
        println("[Linkkf v1.2] search 호출됨 - 검색어: $query, URL: $url")
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val results = doc.select(".vod-item").mapNotNull { it.toSearchResponse() }
            println("[Linkkf v1.2] 검색 완료, 결과 개수: ${results.size}")
            results
        } catch (e: Exception) {
            println("[Linkkf v1.2] search 에러 발생: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        println("[Linkkf v1.2] load 호출됨 - URL: $url")
        try {
            val doc = app.get(url, headers = commonHeaders).document

            val title = doc.selectFirst(".detail-info-title")?.text()?.trim() ?: "Unknown Title"
            println("[Linkkf v1.2] 상세 페이지 제목 파싱: $title")
            
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
            val yearStr = doc.selectFirst("a[href*='/year/']")?.text()?.trim()
            val year: Int? = yearStr?.toIntOrNull()

            val subEpisodes = mutableListOf<Episode>()
            val dubEpisodes = mutableListOf<Episode>()

            val tabs = doc.select(".playlist-tab-box .tab-item")
            println("[Linkkf v1.2] 파싱된 탭 개수: ${tabs.size}")

            fun parseEpisodes(selector: String): List<Episode> {
                return doc.select(selector).mapNotNull { aTag ->
                    val href = aTag.attr("href")
                    val name = aTag.text().trim()
                    if (href.isNotEmpty()) {
                        newEpisode(href) {
                            this.name = name
                            this.episode = name.filter { it.isDigit() }.toIntOrNull()
                        }
                    } else null
                }.reversed()
            }

            if (tabs.isNotEmpty()) {
                tabs.forEach { tab ->
                    val tabText = tab.text().lowercase()
                    val targetId = tab.attr("data-target")

                    if (targetId.isNotEmpty()) {
                        val episodes = parseEpisodes("$targetId .episodelist a")
                        
                        if (tabText.contains("dub") || tabText.contains("더빙")) {
                            dubEpisodes.addAll(episodes)
                        } else {
                            subEpisodes.addAll(episodes)
                        }
                    }
                }
            } else {
                subEpisodes.addAll(parseEpisodes(".episode-box .episodelist a"))
            }
            
            println("[Linkkf v1.2] 자막 에피소드 개수: ${subEpisodes.size}, 더빙 에피소드 개수: ${dubEpisodes.size}")

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                
                if (subEpisodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, subEpisodes)
                }
                if (dubEpisodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Dubbed, dubEpisodes)
                }
            }
        } catch (e: Exception) {
            println("[Linkkf v1.2] load 에러 발생: ${e.message}")
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
        println("[Linkkf v1.2] loadLinks 호출됨 - URL: $fullUrl")
        
        val result = LinkkfExtractor().extract(fullUrl, "$mainUrl/")
        if (result == null) {
            println("[Linkkf v1.2] 영상 링크 추출 실패")
            return false
        }

        if (result.subtitleUrl.isNotEmpty()) {
            println("[Linkkf v1.2] 자막 URL 발견: ${result.subtitleUrl}")
            subtitleCallback.invoke(newSubtitleFile("Korean", result.subtitleUrl))
        }

        val playerPageUrl = result.m3u8Url
        val targetReferer = if (result.needsWebView) playerPageUrl else "$mainUrl/"

        var finalM3u8Url = result.m3u8Url

        if (result.needsWebView) {
            println("[Linkkf v1.2] WebViewResolver를 통한 m3u8 추출 시도")
            try {
                val response = app.get(
                    result.m3u8Url, 
                    headers = commonHeaders, 
                    interceptor = WebViewResolver(Regex("""\.m3u8"""))
                )
                finalM3u8Url = response.url
                println("[Linkkf v1.2] WebViewResolver 성공: $finalM3u8Url")
            } catch (e: Exception) {
                println("[Linkkf v1.2] WebViewResolver 에러 발생: ${e.message}")
                return false
            }
        } else {
            println("[Linkkf v1.2] 직접 m3u8 추출됨: $finalM3u8Url")
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = finalM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = targetReferer
                this.quality = getQualityFromName("HD")
            }
        )
        println("[Linkkf v1.2] loadLinks 처리 완료")
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
