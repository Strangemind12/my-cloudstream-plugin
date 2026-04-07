package com.linkkf

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import kotlinx.coroutines.CancellationException

class Linkkf : MainAPI() {
    override var mainUrl = "https://linkkf.tv"
    override var name = "Linkkf"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime, TvType.OVA)

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
        val url = if (page == 1) "$mainUrl${request.data}/" else "${mainUrl}${if(request.data.endsWith("/")) request.data else "${request.data}/"}page/$page/"
        try {
            val doc = app.get(url, headers = commonHeaders).document
            val list = doc.select(".vod-item").mapNotNull { it.toSearchResponse() }
            return newHomePageResponse(HomePageList(request.name, list, isHorizontalImages = true), hasNext = if (request.data.startsWith("/label/")) false else list.isNotEmpty())
        } catch (e: Exception) {
            if (e is CancellationException) throw e; return newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try { 
            app.get("$mainUrl/view/wd/$query/", headers = commonHeaders).document.select(".vod-item").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) { 
            if (e is CancellationException) throw e; emptyList() 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val doc = app.get(url, headers = commonHeaders).document
            val title = doc.selectFirst(".detail-info-title")?.text()?.trim() ?: "Unknown Title"
            val poster = doc.selectFirst(".detail-img img")?.let { it.attr("data-original").ifEmpty { it.attr("src") } }
            val description = doc.selectFirst(".detail-desc-content")?.text()?.trim()?.ifEmpty { "다시보기" } ?: "다시보기"
            val tags = doc.select(".detail-info-desc a[href*='/class/']").map { it.text().trim() }
            val year = doc.selectFirst("a[href*='/year/']")?.text()?.trim()?.toIntOrNull()

            val subEpisodes = mutableListOf<Episode>()
            val dubEpisodes = mutableListOf<Episode>()

            // [고유 개선] 탭 구조 파괴를 대비한 광역 에피소드 스캔 로직 (범용성)
            val allEpisodeLinks = doc.select("a[href*='/episode/'], a[href*='/video/']").filter { it.text().trim().isNotEmpty() }
            
            allEpisodeLinks.forEach { aTag ->
                val href = aTag.attr("href")
                val name = aTag.text().trim()
                val isDub = doc.selectFirst("a[href='$href']")?.parents()?.text()?.lowercase()?.contains("dub") == true || name.contains("더빙")
                
                val ep = newEpisode(href) { this.name = name; this.episode = name.filter { it.isDigit() }.toIntOrNull() }
                if (isDub) dubEpisodes.add(ep) else subEpisodes.add(ep)
            }
            
            val finalSubs = subEpisodes.distinctBy { it.data }.reversed()
            val finalDubs = dubEpisodes.distinctBy { it.data }.reversed()

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster; this.plot = description; this.tags = tags; this.year = year
                if (finalSubs.isNotEmpty()) addEpisodes(DubStatus.Subbed, finalSubs)
                if (finalDubs.isNotEmpty()) addEpisodes(DubStatus.Dubbed, finalDubs)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e; throw e
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val fullUrl = if (data.startsWith("http")) data else "$mainUrl$data"
        try {
            val result = LinkkfExtractor().extract(fullUrl, "$mainUrl/") ?: return false
            if (result.subtitleUrl.isNotEmpty()) subtitleCallback.invoke(newSubtitleFile("Korean", result.subtitleUrl))

            var finalM3u8Url = result.m3u8Url
            val targetReferer = if (result.needsWebView) result.m3u8Url else "$mainUrl/"

            // [고유 개선] 선제적 HTTP 헤더(Location) 체크로 무조건 WebView를 띄우는 시간낭비 제거
            if (result.needsWebView) {
                try {
                    val preCheck = app.get(result.m3u8Url, headers = commonHeaders, allowRedirects = false)
                    val location = preCheck.headers["Location"] ?: preCheck.headers["location"]
                    if (!location.isNullOrEmpty() && location.contains(".m3u8")) {
                        finalM3u8Url = location // 웹뷰 없이 다이렉트 우회 성공
                    } else {
                        val response = app.get(result.m3u8Url, headers = commonHeaders, interceptor = WebViewResolver(Regex("""\.m3u8""")))
                        finalM3u8Url = response.url
                    }
                } catch (e: Exception) {
                    val response = app.get(result.m3u8Url, headers = commonHeaders, interceptor = WebViewResolver(Regex("""\.m3u8""")))
                    finalM3u8Url = response.url
                }
            }

            callback.invoke(newExtractorLink(name, name, finalM3u8Url, ExtractorLinkType.M3U8) { this.referer = targetReferer; this.quality = getQualityFromName("HD") })
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e; return false
        }
    }

    // [수정] 실수로 누락했던 파싱 확장 함수 복구
    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a.vod-item-img") ?: return null
        val title = this.selectFirst(".vod-item-title a")?.text()?.trim() ?: return null
        val href = aTag.attr("href")
        val poster = this.selectFirst(".img-wrapper")?.attr("data-original")?.ifEmpty { this.selectFirst("img")?.attr("src") }
        
        return newTvSeriesSearchResponse(title, href, TvType.Anime) { 
            this.posterUrl = poster 
        }
    }
}
