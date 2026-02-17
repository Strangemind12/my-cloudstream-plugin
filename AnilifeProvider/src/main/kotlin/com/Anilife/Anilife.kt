package com.anilife

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Anilife Provider v2.1
 * - 포스터 파싱 로직 개선 (src 우선, fallback 처리)
 * - 제목 중복 출력 수정 (.tt vs h2)
 * - 헤더(User-Agent) 추가
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"

    // 차단 방지용 공통 헤더
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

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
        val url = if (request.name.contains("TOP 20")) {
            "$mainUrl${request.data}"
        } else {
            val basePath = request.data.substringBeforeLast("/")
            "$mainUrl$basePath/$page"
        }

        println("$TAG [MainPage] Request: $url")
        
        try {
            val doc = app.get(url, headers = commonHeaders).document
            val home = parseCommonList(doc)
            return newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            println("$TAG [MainPage] Error: ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun parseCommonList(doc: Document): List<SearchResponse> {
        val items = doc.select(".listupd > article.bs").mapNotNull { element ->
            try {
                // 1. 링크 파싱
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val href = fixUrl(aTag.attr("href"))

                // 2. 제목 파싱 (중복 방지)
                // 구조: <div class="tt"> 텍스트 <h2 itemprop="headline"> 제목 </h2> </div>
                // h2가 있으면 h2의 텍스트만 가져오고, 없으면 .tt 전체 텍스트 사용
                val titleElement = element.selectFirst(".tt h2") ?: element.selectFirst(".tt")
                val title = titleElement?.text()?.trim() ?: "Unknown"

                // 3. 포스터 파싱 (v2.1 개선)
                // 구조: <div class="limit"> ... <img src="..."> </div>
                val imgTag = element.selectFirst("img")
                var poster = imgTag?.attr("src")
                
                // src가 비어있을 경우를 대비해 다른 속성 확인
                if (poster.isNullOrEmpty()) {
                    poster = imgTag?.attr("data-src")
                }
                if (poster.isNullOrEmpty()) {
                    poster = imgTag?.attr("data-original")
                }
                
                poster = poster?.let { fixUrl(it) } ?: ""

                // 디버깅 로그 (필요시 주석 해제)
                // println("$TAG [Item] Title: $title | Poster: $poster")

                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            } catch (e: Exception) {
                println("$TAG [ListItem] Parse Error: ${e.message}")
                null
            }
        }
        println("$TAG [List] Parsed ${items.size} items.")
        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        println("$TAG [Search] Query: $query -> $url")
        val doc = app.get(url, headers = commonHeaders).document
        return parseCommonList(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        println("$TAG [Load] Details URL: $url")
        val doc = app.get(url, headers = commonHeaders).document

        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        
        // 상세 페이지 포스터 파싱
        val poster = doc.selectFirst(".thumb img")?.let { img ->
            img.attr("src").ifEmpty { img.attr("data-src") }
        }?.let { fixUrl(it) }

        val description = doc.selectFirst(".synp .entry-content")?.text()?.trim()
        val tags = doc.select(".genxed a, .taged a").map { it.text() }
        
        println("$TAG [Load] Title: $title")

        // 에피소드 파싱
        val episodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val num = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            
            val fullName = if(num.isNotEmpty()) "${num}화 - $epTitle" else epTitle
            val episodeInt = num.toIntOrNull()

            newEpisode(href) {
                this.name = fullName
                this.episode = episodeInt
            }
        }.reversed()

        println("$TAG [Load] Episodes found: ${episodes.size}")

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("$TAG [LoadLinks] Start: $data")
        val extractor = AnilifeExtractor()
        return extractor.extract(data, callback)
    }
}
