package com.anilife

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Anilife Provider v1.1
 * - v1.0: 초기 구현
 * - v1.1: 빌드 에러 수정
 * - SearchResponse addEpisode 타입 불일치 수정
 * - String Template 변수명 모호성 수정 (${num}화)
 * - Deprecated Episode 생성자를 newEpisode로 변경
 * - addEpisodes DubStatus 타입 불일치 수정
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    // 상세 디버그 로그용 태그
    private val TAG = "[Anilife]"

    override val mainPage = mainPageOf(
        "/top20" to "실시간 TOP 20",
        "/vodtype/categorize/TV/1" to "TV 애니메이션",
        "/vodtype/categorize/OVA/1" to "OVA",
        "/vodtype/categorize/ONA/1" to "ONA",
        "/vodtype/categorize/Web/1" to "Web",
        "/vodtype/categorize/SP/1" to "SP",
        "/vodtype/categorize/Movie/1" to "극장판"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // 제공된 URL 패턴이 /vodtype/categorize/TV/1 형식이므로 page 변수를 경로에 적용
        val url = if (request.name.contains("TOP 20")) {
            "$mainUrl${request.data}"
        } else {
            // /vodtype/categorize/TV/1 에서 마지막 숫자를 페이지로 교체
            // 원본 data가 "/vodtype/categorize/TV/1" 이라고 가정할 때, 마지막 / 뒤의 숫자를 page로 변경
            val basePath = request.data.substringBeforeLast("/")
            "$mainUrl$basePath/$page"
        }

        println("$TAG [MainPage] Requesting: $url")
        val doc = app.get(url).document
        val home = parseCommonList(doc)
        
        return newHomePageResponse(request.name, home)
    }

    private fun parseCommonList(doc: Document): List<SearchResponse> {
        // .listupd > article.bs 구조 파싱
        val items = doc.select(".listupd > article.bs").mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val href = fixUrl(aTag.attr("href"))
                val title = element.selectFirst(".tt")?.text()?.trim() ?: return@mapNotNull null
                val poster = element.selectFirst("img")?.attr("src") ?: ""
                val epText = element.selectFirst(".bt .epx")?.text() ?: ""

                // [v1.1 수정] String인 epText를 Int로 변환하여 addEpisode에 전달
                val episodeNum = epText.filter { it.isDigit() }.toIntOrNull()

                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                    // 에피소드 번호가 있으면 추가
                    if (episodeNum != null) {
                        addEpisode(episodeNum)
                    }
                }
            } catch (e: Exception) {
                println("$TAG [List] Error parsing item: ${e.message}")
                null
            }
        }
        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        println("$TAG [Search] Searching for: $query -> $url")
        val doc = app.get(url).document
        return parseCommonList(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        println("$TAG [Load] Loading details: $url")
        val doc = app.get(url).document

        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst(".thumb img")?.attr("src")
        val description = doc.selectFirst(".synp .entry-content")?.text()?.trim()
        
        // 태그 파싱
        val tags = doc.select(".genxed a, .taged a").map { it.text() }
        
        // 에피소드 파싱
        // .eplister > ul > li > a
        val episodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val num = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            
            // [v1.1 수정] "$num화" -> "${num}화" (변수명 모호성 해결)
            val fullName = if(num.isNotEmpty()) "${num}화 - $epTitle" else epTitle
            val episodeInt = num.toIntOrNull()

            // [v1.1 수정] Episode(...) 생성자 대신 newEpisode 사용
            newEpisode(href) {
                this.name = fullName
                this.episode = episodeInt
            }
        }.reversed() // 최신화가 위에 있는 경우 역순 정렬 (필요시)

        println("$TAG [Load] Found ${episodes.size} episodes for $title")

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            // [v1.1 수정] TvType.Anime -> DubStatus.Subbed
            // Anilife는 기본적으로 자막이 많으므로 Subbed로 설정 (필요시 로직 추가 가능)
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("$TAG [LoadLinks] Fetching links for: $data")
        
        // Extractor 호출
        val extractor = AnilifeExtractor()
        return extractor.extract(data, callback)
    }
}
