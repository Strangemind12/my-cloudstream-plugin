package com.anilife

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Anilife Provider v3.0
 * - 포스터 터널링 구현 (목록 -> 상세 페이지로 포스터 URL 전달)
 * - 상세 페이지 포스터 파싱 로직 강화
 * - Base64 인코딩/디코딩 적용
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"

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
                // 1. 기본 정보 파싱
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val rawHref = fixUrl(aTag.attr("href"))

                val titleElement = element.selectFirst(".tt h2") ?: element.selectFirst(".tt")
                val title = titleElement?.text()?.trim() ?: "Unknown"

                val imgTag = element.selectFirst("img")
                var poster = imgTag?.attr("src")
                if (poster.isNullOrEmpty()) poster = imgTag?.attr("data-src")
                if (poster.isNullOrEmpty()) poster = imgTag?.attr("data-original")
                poster = poster?.let { fixUrl(it) } ?: ""

                // 2. 포스터 터널링 (URL에 포스터 정보 숨기기)
                // 상세 페이지에서 포스터를 못 불러올 경우를 대비해, 여기서 찾은 포스터 URL을 Base64로 인코딩해서 넘김
                val finalHref = if (poster.isNotEmpty()) {
                    try {
                        val encodedPoster = Base64.encodeToString(poster.toByteArray(), Base64.NO_WRAP)
                        if (rawHref.contains("?")) "$rawHref&poster=$encodedPoster" 
                        else "$rawHref?poster=$encodedPoster"
                    } catch (e: Exception) {
                        rawHref
                    }
                } else {
                    rawHref
                }

                newAnimeSearchResponse(title, finalHref, TvType.Anime) {
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
        println("$TAG [Load] Raw URL: $url")
        
        // 1. 터널링된 포스터 URL 추출 (Base64 디코딩)
        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            try {
                val posterParam = url.substringAfter("poster=")
                // 파라미터가 더 있다면(&) 제거
                val encodedPoster = if (posterParam.contains("&")) posterParam.substringBefore("&") else posterParam
                
                tunnelingPoster = String(Base64.decode(encodedPoster, Base64.NO_WRAP))
                println("$TAG [Load] Found tunneling poster: $tunnelingPoster")
                
                // 실제 요청할 URL은 파라미터 제거
                url.substringBefore("?poster=")
            } catch (e: Exception) {
                println("$TAG [Load] Tunneling decode failed: ${e.message}")
                url
            }
        } else {
            url
        }

        println("$TAG [Load] Requesting Details: $cleanUrl")
        val doc = app.get(cleanUrl, headers = commonHeaders).document

        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        
        // 2. HTML에서 포스터 파싱 시도 (제공된 소스 기준 .thumb img)
        var htmlPoster = doc.selectFirst(".thumb img")?.let { img ->
            img.attr("src").ifEmpty { img.attr("data-src") }
        }?.let { fixUrl(it) }

        // 3. 포스터 유효성 검사 및 터널링 데이터 사용
        // HTML 포스터가 없거나, 메인 URL과 같거나(잘못된 로드), 아이콘/로고 등인 경우
        if (htmlPoster.isNullOrEmpty() || htmlPoster == mainUrl || htmlPoster == "$mainUrl/") {
            println("$TAG [Load] HTML poster invalid ($htmlPoster). Using tunneling poster.")
            if (!tunnelingPoster.isNullOrEmpty()) {
                htmlPoster = tunnelingPoster
            }
        }

        val description = doc.selectFirst(".synp .entry-content")?.text()?.trim()
        val tags = doc.select(".genxed a, .taged a").map { it.text() }
        
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

        return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
            this.posterUrl = htmlPoster
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
        // loadLinks로 넘어오는 data에도 ?poster=... 가 붙어있을 수 있으므로 제거
        val cleanData = if (data.contains("poster=")) data.substringBefore("?poster=") else data
        
        println("$TAG [LoadLinks] Start: $cleanData")
        val extractor = AnilifeExtractor()
        return extractor.extract(cleanData, callback)
    }
}
