package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v19.0
 * - [Fix] 빌드 에러 해결: WebViewResolver 사용을 Anilife.kt(MainAPI)로 통합하여 제한 우회
 * - [Fix] 파일 분리: Anilife.kt / Extractor.kt
 * - [Fix] 모든 과정 웹뷰화: Provider 로드부터 M3U8 스니핑까지 웹뷰로 진행 (봇 차단 해결)
 * - [Debug] 모든 프로세스 상세 println 로그 추가
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
        val url = if (request.name.contains("TOP 20")) "$mainUrl${request.data}" 
                  else "$mainUrl${request.data.substringBeforeLast("/")}/$page"
        println("$TAG [MainPage] Request: $url")
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val home = parseCommonList(doc)
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            println("$TAG [MainPage] Error: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun parseCommonList(doc: Document): List<SearchResponse> {
        return doc.select(".listupd > article.bs").mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val rawHref = fixUrl(aTag.attr("href"))
                val title = (element.selectFirst(".tt h2") ?: element.selectFirst(".tt"))?.text()?.trim() ?: "Unknown"
                val imgTag = element.selectFirst("img")
                var poster = imgTag?.attr("src") ?: imgTag?.attr("data-src") ?: ""
                poster = fixUrl(poster)

                val finalHref = if (poster.isNotEmpty()) {
                    val encoded = Base64.encodeToString(poster.toByteArray(), Base64.NO_WRAP)
                    if (rawHref.contains("?")) "$rawHref&poster=$encoded" else "$rawHref?poster=$encoded"
                } else rawHref

                newAnimeSearchResponse(title, finalHref, TvType.Anime) {
                    this.posterUrl = poster
                    this.posterHeaders = commonHeaders
                }
            } catch (e: Exception) { null }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        println("$TAG [Search] Query: $url")
        val doc = app.get(url, headers = commonHeaders).document
        return parseCommonList(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            val posterParam = url.substringAfter("poster=")
            tunnelingPoster = String(Base64.decode(posterParam.substringBefore("&"), Base64.NO_WRAP))
            url.substringBefore("?poster=")
        } else url

        println("$TAG [Load] cleanUrl: $cleanUrl")
        val doc = app.get(cleanUrl, headers = commonHeaders).document
        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        val encodedRef = Base64.encodeToString(cleanUrl.toByteArray(), Base64.NO_WRAP)

        // v4.1 로직 유지 (렉 없음)
        val episodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val rawHref = fixUrl(element.attr("href"))
            val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            val fullName = if (numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
            
            val finalHref = if (rawHref.contains("?")) "$rawHref&ref=$encodedRef" else "$rawHref?ref=$encodedRef"

            newEpisode(finalHref) {
                this.name = fullName
                this.episode = numText.toIntOrNull()
            }
        }.reversed()

        return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
            this.posterUrl = doc.selectFirst(".thumb img")?.attr("src") ?: tunnelingPoster
            this.posterHeaders = commonHeaders
            this.plot = doc.selectFirst(".synp .entry-content")?.text()?.trim()
            this.tags = doc.select(".genxed a").map { it.text() }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("$TAG [LoadLinks] 프로세스 시작")
        
        // 1. 파라미터 분석 (Referer 추출)
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
            detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
            cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
        }
        println("$TAG [LoadLinks] Target: $cleanData | Referer: $detailReferer")

        try {
            // [Step 1] 웹뷰로 Provider 페이지 로드하여 봇 차단 우회 및 HTML 획득
            println("$TAG [Step 1] 웹뷰를 사용하여 Provider 페이지를 로드합니다 (봇 차단 해제)")
            // Regex(".*")를 사용해 페이지가 로드되자마자 HTML을 가져옵니다.
            val providerResponse = app.get(
                cleanData,
                headers = mapOf("Referer" to detailReferer),
                interceptor = WebViewResolver(Regex(".*"))
            )
            val html = providerResponse.text
            println("$TAG [Step 1] HTML 획득 성공 (길이: ${html.length})")

            // [Step 2] 추출기(Extractor)를 사용하여 플레이어 주소 파싱
            val playerUrl = AnilifeExtractor().extractPlayerUrl(html, mainUrl)
            
            if (playerUrl != null) {
                println("$TAG [Step 2] 플레이어 주소 발견: $playerUrl")

                // [Step 3] 웹뷰로 최종 M3U8 스니핑
                println("$TAG [Step 3] 웹뷰를 사용하여 최종 M3U8을 스니핑합니다...")
                val m3u8Interceptor = WebViewResolver(Regex("""\.m3u8"""))
                val m3u8Response = app.get(
                    playerUrl,
                    headers = commonHeaders,
                    interceptor = m3u8Interceptor
                )
                val finalM3u8 = m3u8Response.url
                println("$TAG [Step 3] 스니핑 성공: $finalM3u8")

                if (finalM3u8.contains(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = finalM3u8,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://anilife.live/"
                            this.quality = getQualityFromName("HD")
                        }
                    )
                    println("$TAG [LoadLinks] 모든 프로세스 완료 (성공)")
                    return true
                }
            } else {
                println("$TAG [Error] HTML에서 플레이어 주소를 추출하지 못했습니다.")
            }
        } catch (e: Exception) {
            println("$TAG [Critical Error] ${e.message}")
            e.printStackTrace()
        }
        println("$TAG [LoadLinks] 실패")
        return false
    }
}
