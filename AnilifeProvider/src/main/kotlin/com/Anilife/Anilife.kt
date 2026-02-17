package com.anilife

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Anilife Provider v5.0
 * - [Fix] 에피소드 소수점 정렬 구현 (실수형 파싱 -> 정렬 -> 정수형 재인덱싱)
 * - [Fix] Episode 객체 'posterHeaders' 제거 (빌드 에러 방지)
 * - [Fix] 1100개 이상의 에피소드를 한 번에 정렬하여 로드 (20개씩 끊김 없음)
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"

    // 이미지/페이지 요청 공통 헤더
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

                // 2. 포스터 터널링
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
                    this.posterHeaders = commonHeaders
                }
            } catch (e: Exception) {
                println("$TAG [ListItem] Parse Error: ${e.message}")
                null
            }
        }
        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        println("$TAG [Search] Query: $query -> $url")
        val doc = app.get(url, headers = commonHeaders).document
        return parseCommonList(doc)
    }

    // 에피소드 정렬을 위한 임시 데이터 클래스
    data class TempEpisode(
        val url: String,
        val fullName: String,
        val floatNum: Float // 정렬 기준 (1145.5 등)
    )

    override suspend fun load(url: String): LoadResponse {
        println("$TAG [Load] Raw URL: $url")
        
        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            try {
                val posterParam = url.substringAfter("poster=")
                val encodedPoster = if (posterParam.contains("&")) posterParam.substringBefore("&") else posterParam
                tunnelingPoster = String(Base64.decode(encodedPoster, Base64.NO_WRAP))
                url.substringBefore("?poster=")
            } catch (e: Exception) {
                url
            }
        } else {
            url
        }

        println("$TAG [Load] Requesting Details: $cleanUrl")
        val doc = app.get(cleanUrl, headers = commonHeaders).document

        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        
        var htmlPoster = doc.selectFirst(".thumb img")?.let { img ->
            img.attr("src").ifEmpty { img.attr("data-src") }
        }?.let { fixUrl(it) }

        if (htmlPoster.isNullOrEmpty() || htmlPoster == mainUrl || htmlPoster == "$mainUrl/") {
            if (!tunnelingPoster.isNullOrEmpty()) {
                htmlPoster = tunnelingPoster
            }
        }

        val description = doc.selectFirst(".synp .entry-content")?.text()?.trim()
        val tags = doc.select(".genxed a, .taged a").map { it.text() }
        
        // --- [v5.0 수정] 에피소드 파싱 및 정렬 로직 ---
        val tempEpisodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            
            // "1145.5화 - 제목" 형식으로 표시
            val fullName = if(numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
            
            // 소수점 포함 파싱 (1145.5 -> 1145.5f)
            // 숫자가 없으면 0f 처리
            val floatNum = numText.toFloatOrNull() ?: 0f

            TempEpisode(href, fullName, floatNum)
        }

        // 1. 실수 기준 오름차순 정렬 (1145.0 -> 1145.5 -> 1146.0)
        val sortedTempEpisodes = tempEpisodes.sortedBy { it.floatNum }

        // 2. 정렬된 순서대로 정수 인덱스 재할당 (Re-indexing)
        // Cloudstream은 정수(Int) 에피소드 번호만 지원하므로,
        // 실제 번호와 무관하게 정렬된 순서대로 1부터 번호를 부여하여 강제 정렬시킵니다.
        val startEpisodeIndex = sortedTempEpisodes.firstOrNull()?.floatNum?.toInt() ?: 1
        
        val finalEpisodes = sortedTempEpisodes.mapIndexed { index, temp ->
            newEpisode(temp.url) {
                this.name = temp.fullName
                // [핵심] 실제 번호(1145.5)와 상관없이 정렬된 순서대로 고유 정수 부여
                // 예: 1145 -> ep:1145, 1145.5 -> ep:1146, 1146 -> ep:1147
                this.episode = startEpisodeIndex + index
            }
        }.reversed() // 최신화가 위로 오도록 역순 정렬

        println("$TAG [Load] Processed ${finalEpisodes.size} episodes with decimal sorting.")

        return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
            this.posterUrl = htmlPoster
            this.posterHeaders = commonHeaders 
            this.plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, finalEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = if (data.contains("poster=")) data.substringBefore("?poster=") else data
        println("$TAG [LoadLinks] Start: $cleanData")
        val extractor = AnilifeExtractor()
        return extractor.extract(cleanData, callback)
    }
}
