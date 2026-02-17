package com.anilife

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver // [필수] 웹뷰 리졸버 사용
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Anilife Provider v6.1
 * - [Performance] 에피소드 처리 루프 내의 디버그 로그 전면 제거 (오버로드 방지)
 * - [Fix] 에피소드 소수점 정렬: Float 변환 -> 오름차순 정렬 -> ID 재할당 (1145.5 위치 보정)
 * - [Fix] 영상 링크: WebViewResolver를 사용하여 리다이렉트 및 m3u8 자동 추출
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

        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val home = parseCommonList(doc)
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            e.printStackTrace()
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun parseCommonList(doc: Document): List<SearchResponse> {
        // 목록 파싱은 개수가 적으므로 로그를 남겨도 되지만, 성능을 위해 최소화
        return doc.select(".listupd > article.bs").mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val rawHref = fixUrl(aTag.attr("href"))
                val title = (element.selectFirst(".tt h2") ?: element.selectFirst(".tt"))?.text()?.trim() ?: "Unknown"

                val imgTag = element.selectFirst("img")
                var poster = imgTag?.attr("src")
                if (poster.isNullOrEmpty()) poster = imgTag?.attr("data-src")
                if (poster.isNullOrEmpty()) poster = imgTag?.attr("data-original")
                poster = poster?.let { fixUrl(it) } ?: ""

                val finalHref = if (poster.isNotEmpty()) {
                    try {
                        val encodedPoster = Base64.encodeToString(poster.toByteArray(), Base64.NO_WRAP)
                        if (rawHref.contains("?")) "$rawHref&poster=$encodedPoster" else "$rawHref?poster=$encodedPoster"
                    } catch (e: Exception) { rawHref }
                } else { rawHref }

                newAnimeSearchResponse(title, finalHref, TvType.Anime) {
                    this.posterUrl = poster
                    this.posterHeaders = commonHeaders
                }
            } catch (e: Exception) { null }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        val doc = app.get(url, headers = commonHeaders).document
        return parseCommonList(doc)
    }

    // 정렬을 위한 임시 데이터 클래스 (가볍게 유지)
    data class TempEpisode(
        val url: String,
        val fullName: String,
        val floatNum: Float
    )

    override suspend fun load(url: String): LoadResponse {
        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            try {
                val posterParam = url.substringAfter("poster=")
                val encodedPoster = if (posterParam.contains("&")) posterParam.substringBefore("&") else posterParam
                tunnelingPoster = String(Base64.decode(encodedPoster, Base64.NO_WRAP))
                url.substringBefore("?poster=")
            } catch (e: Exception) { url }
        } else { url }

        println("$TAG [Load] URL: $cleanUrl") // 중요 로그 하나만 남김
        val doc = app.get(cleanUrl, headers = commonHeaders).document
        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"

        var htmlPoster = doc.selectFirst(".thumb img")?.let { img ->
            img.attr("src").ifEmpty { img.attr("data-src") }
        }?.let { fixUrl(it) }

        if (htmlPoster.isNullOrEmpty() || htmlPoster == mainUrl || htmlPoster == "$mainUrl/") {
            if (!tunnelingPoster.isNullOrEmpty()) htmlPoster = tunnelingPoster
        }

        val description = doc.selectFirst(".synp .entry-content")?.text()?.trim()
        val tags = doc.select(".genxed a, .taged a").map { it.text() }

        // [v6.1 최적화] 대량의 에피소드 파싱 시 로그 절대 금지
        val rawEpisodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            val fullName = if (numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
            // 정렬 키 추출 (실패 시 0 처리)
            val floatNum = numText.toFloatOrNull() ?: 0f
            
            TempEpisode(href, fullName, floatNum)
        }

        // 1. 메모리 상에서 빠르게 정렬 (오름차순: 1화 -> 1145화 -> 1145.5화 -> 1146화)
        // 코틀린의 sort는 매우 빠르므로 1000개 정도는 순식간에 처리됨 (로그만 안 찍으면 됨)
        val sortedEpisodes = rawEpisodes.sortedBy { it.floatNum }

        // 2. 정수 ID 순차 부여 (앱이 ID 순서대로 정렬하도록 유도)
        val finalEpisodes = sortedEpisodes.mapIndexed { index, temp ->
            newEpisode(temp.url) {
                this.name = temp.fullName
                this.episode = index + 1
            }
        }.reversed() // 최신화가 상단에 오도록 반전

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
        // 포스터 파라미터 제거
        val cleanData = if (data.contains("poster=")) data.substringBefore("?poster=") else data
        println("$TAG [LoadLinks] Start: $cleanData")

        try {
            // [v6.1 수정] WebViewResolver를 메인으로 사용
            // 자동으로 리다이렉트(js)를 수행하고 .m3u8 요청을 감지합니다.
            // Anilife 구조: Provider URL -> (JS Redirect) -> Player URL -> .m3u8
            val webViewInterceptor = WebViewResolver(
                Regex("""\.m3u8"""), // .m3u8 요청을 감지
                userAgent = commonHeaders["User-Agent"],
                referer = "https://anilife.live/"
            )
            
            // WebViewResolver를 interceptor로 사용하여 요청
            val response = app.get(cleanData, headers = commonHeaders, interceptor = webViewInterceptor)
            val interceptedUrl = response.url
            
            println("$TAG [WebView] Sniffed URL: $interceptedUrl")

            if (interceptedUrl.contains(".m3u8")) {
                 callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = interceptedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://anilife.live/"
                        this.quality = getQualityFromName("HD")
                    }
                )
                return true
            } else {
                println("$TAG [WebView] Failed to sniff m3u8. URL: $interceptedUrl")
            }
        } catch (e: Exception) {
            println("$TAG [WebView] Error: ${e.message}")
        }

        return false
    }
}
