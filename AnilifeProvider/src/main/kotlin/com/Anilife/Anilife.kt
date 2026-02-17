package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v23.0
 * - [Fix] User-Agent 강제 변경 제거 -> load() 단계의 세션/쿠키와 일치시켜 봇 차단/리다이렉트 해결
 * - [Log] 사용자가 요청한 '접속 시도', 'URL 추출', '재생 페이지 접속' 로그 명시적 추가
 * - [Fix] 에피소드 정렬 로직 v4.1 유지
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"

    // [중요] load()와 동일한 헤더를 사용하여 쿠키/세션 불일치 방지
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // 긴 로그 분할 출력 함수
    private fun logLargeString(tag: String, msg: String) {
        if (msg.length > 4000) {
            println("$tag [HTML-Chunk] ${msg.substring(0, 4000)}")
            logLargeString(tag, msg.substring(4000))
        } else {
            println("$tag [HTML-End] $msg")
        }
    }

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

        println("$TAG [Load] 접속: $cleanUrl")
        val doc = app.get(cleanUrl, headers = commonHeaders).document
        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        val encodedRef = Base64.encodeToString(cleanUrl.toByteArray(), Base64.NO_WRAP)

        // v4.1 로직 (단순 파싱)
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
        println("$TAG [LoadLinks] === 프로세스 시작 ===")
        
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
            detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
            cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
        }
        
        println("$TAG [1단계] Provider 페이지 접속 시도: $cleanData")
        println("$TAG [1단계] Referer 설정: $detailReferer")

        try {
            // [1단계] Provider 페이지 로드
            var html: String = ""
            var success = false
            
            // A. HTTP 요청 우선 시도 (헤더/세션 유지를 위해 권장)
            println("$TAG [1단계-A] HTTP(app.get) 접속 시도...")
            try {
                val response = app.get(cleanData, headers = mapOf("Referer" to detailReferer))
                
                // 리다이렉트 체크
                if (response.url.contains("anilife.live/ani/provider") && !response.text.contains("메인 홈페이지")) {
                    html = response.text
                    success = true
                    println("$TAG [1단계-A] HTTP 접속 성공. HTML 획득.")
                } else {
                    println("$TAG [1단계-A] 실패 (리다이렉트됨). URL: ${response.url}")
                }
            } catch (e: Exception) {
                println("$TAG [1단계-A] 에러: ${e.message}")
            }

            // B. 실패 시 WebView 시도
            if (!success) {
                println("$TAG [1단계-B] WebView 접속 시도...")
                val providerResponse = app.get(
                    cleanData,
                    headers = mapOf("Referer" to detailReferer),
                    interceptor = WebViewResolver(Regex(".*"))
                )
                html = providerResponse.text
                println("$TAG [1단계-B] WebView 완료. 최종 URL: ${providerResponse.url}")
            }

            // [2단계] 플레이어 URL 파싱
            println("$TAG [2단계] 재생 페이지 URL 추출 시도...")
            // 전체 HTML 로그 (디버깅용)
            // println("$TAG [Debug] HTML 소스 시작:")
            // logLargeString(TAG, html)
            
            val playerUrl = AnilifeExtractor().extractPlayerUrl(html, mainUrl)
            
            if (playerUrl != null) {
                println("$TAG [2단계] 재생 페이지 URL 추출 성공: $playerUrl")

                // [3단계] WebView로 M3U8 스니핑
                println("$TAG [3단계] 재생 페이지 접속 및 M3U8 스니핑 시도...")
                val m3u8Interceptor = WebViewResolver(Regex("""\.m3u8"""))
                val m3u8Response = app.get(
                    playerUrl,
                    headers = commonHeaders,
                    interceptor = m3u8Interceptor
                )
                val finalM3u8 = m3u8Response.url
                println("$TAG [3단계] 결과 URL: $finalM3u8")

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
                    println("$TAG [완료] 링크 반환 성공.")
                    return true
                }
            } else {
                println("$TAG [2단계] 실패: HTML에서 플레이어 URL을 찾지 못함.")
                logLargeString(TAG, html) // 실패 시 HTML 전체 출력
            }
        } catch (e: Exception) {
            println("$TAG [Critical Error] ${e.message}")
            e.printStackTrace()
        }
        println("$TAG [LoadLinks] 실패")
        return false
    }
}
