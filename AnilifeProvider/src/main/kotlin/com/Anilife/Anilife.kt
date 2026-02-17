package com.anilife

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Anilife Provider v1.0
 * - 메인, 검색, 상세, 에피소드 로드 구현
 * - Base64 _aldata 디코딩 방식 적용
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
        // TOP 20 페이지는 페이징이 없을 수 있으나, 일반 리스트는 페이지 번호가 필요할 수 있음
        // 예: /vodtype/categorize/TV/1 -> page 인자를 받아서 처리
        // 제공된 URL 패턴이 /vodtype/categorize/TV/1 형식이므로 page 변수를 경로에 적용
        val url = if (request.name.contains("TOP 20")) {
            "$mainUrl${request.data}"
        } else {
            // /vodtype/categorize/TV/1 에서 마지막 숫자를 페이지로 교체
            // 원본 data가 "/vodtype/categorize/TV/1" 이라고 가정
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

                // 디버깅 로그
                // println("$TAG [List] Found: $title ($href)")

                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                    addSub(epText)
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
            val fullName = if(num.isNotEmpty()) "$num화 - $epTitle" else epTitle

            Episode(href, fullName)
        }.reversed() // 최신화가 위에 있는 경우 역순 정렬 (필요시)

        println("$TAG [Load] Found ${episodes.size} episodes for $title")

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addEpisodes(TvType.Anime, episodes)
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
