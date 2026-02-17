package com.anilife

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Anilife Provider v5.1
 * - [Fix] Overload 해결: 불필요한 sort 제거하고 인덱스 기반 역순 할당으로 변경
 * - [Fix] 에피소드 소수점(1145.5) 처리: 화면엔 그대로 표시하되 내부 ID는 정수로 변환하여 정렬 유지
 * - [Fix] Episode 객체 posterHeaders 제거 (빌드 에러 방지)
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

        try {
            val doc = app.get(url, headers = commonHeaders).document
            val home = parseCommonList(doc)
            return newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            e.printStackTrace()
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun parseCommonList(doc: Document): List<SearchResponse> {
        val items = doc.select(".listupd > article.bs").mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val rawHref = fixUrl(aTag.attr("href"))

                val titleElement = element.selectFirst(".tt h2") ?: element.selectFirst(".tt")
                val title = titleElement?.text()?.trim() ?: "Unknown"

                val imgTag = element.selectFirst("img")
                var poster = imgTag?.attr("src")
                if (poster.isNullOrEmpty()) poster = imgTag?.attr("data-src")
                if (poster.isNullOrEmpty()) poster = imgTag?.attr("data-original")
                poster = poster?.let { fixUrl(it) } ?: ""

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
                null
            }
        }
        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        val doc = app.get(url, headers = commonHeaders).document
        return parseCommonList(doc)
    }

    override suspend fun load(url: String): LoadResponse {
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

        println("$TAG [Load] URL: $cleanUrl")
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
        
        // --- [v5.1 수정] 에피소드 파싱 (정렬 부하 제거) ---
        // 사이트가 기본적으로 최신화(내림차순) 정렬이라고 가정하고 파싱
        val rawEpisodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            
            val fullName = if(numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
            
            // 데이터만 추출 (정렬 X)
            Triple(href, fullName, numText)
        }

        val totalEpisodes = rawEpisodes.size
        
        // 최신화가 위에 있다면(내림차순), 인덱스를 역으로 부여하여 정수 ID 생성
        // 예: 0번 인덱스(1146화) -> ID 1100
        // 예: 1번 인덱스(1145.5화) -> ID 1099
        // 이렇게 하면 앱 내부적으로는 정수로 인식되어 "20개씩 끊기(Pagination)"가 정상 작동함
        val finalEpisodes = rawEpisodes.mapIndexed { index, (href, fullName, _) ->
            newEpisode(href) {
                this.name = fullName
                // 고유 ID 부여 (단순 계산이라 매우 빠름)
                this.episode = totalEpisodes - index
            }
        }
        
        // Cloudstream은 보통 addEpisodes에 넣으면 episode 번호에 따라 자동 정렬/그룹화함
        // 만약 역순으로 보이면 .reversed() 추가 필요하나, ID를 잘 부여했으므로 그대로 전달

        println("$TAG [Load] Loaded ${finalEpisodes.size} episodes.")

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
