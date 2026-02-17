package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v24.0
 * - [Critical Fix] Provider 페이지 접속 시 메인으로 리다이렉트되는 문제 해결 (HTTP 요청 + 강력한 Referer 적용)
 * - [Fix] 업로드된 소스 파일 분석 결과를 바탕으로 정확한 파싱 흐름 구현
 * - [Debug] 리다이렉트 여부, 파싱 결과, 최종 URL 등 모든 단계를 추적하는 로그 추가
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"

    // 모바일 User-Agent 고정 (서버 봇 차단 방지)
    private val mobileUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to mobileUserAgent,
        "Referer" to "$mainUrl/"
    )

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
        
        // [중요] 상세 페이지 URL을 암호화하여 에피소드 링크의 ref 파라미터로 전달
        val encodedRef = Base64.encodeToString(cleanUrl.toByteArray(), Base64.NO_WRAP)

        // v4.1 로직 (단순 파싱)
        val episodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val rawHref = fixUrl(element.attr("href"))
            val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            val fullName = if (numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
            
            // ref 파라미터 추가
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
        println("$TAG [LoadLinks] ================= 프로세스 시작 =================")
        
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        
        // ref 파라미터(상세 페이지 주소) 추출 - 리다이렉트 방지용 핵심 키
        if (cleanData.contains("ref=")) {
            try {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            } catch (e: Exception) {
                println("$TAG [Error] Referer 디코딩 실패")
            }
        }
        
        println("$TAG [1단계] Provider 페이지 주소: $cleanData")
        println("$TAG [1단계] 사용할 Referer 헤더: $detailReferer")

        try {
            // [1단계] Provider 페이지 소스 가져오기
            // HTTP 요청을 사용하여 헤더를 확실하게 적용 (WebView는 헤더 적용이 불안정할 수 있음)
            println("$TAG [1단계] HTTP 요청 시도 (리다이렉트 방지)...")
            
            val response = app.get(
                cleanData, 
                headers = mapOf(
                    "User-Agent" to mobileUserAgent, 
                    "Referer" to detailReferer
                )
            )
            
            println("$TAG [1단계] 응답 URL: ${response.url}")
            println("$TAG [1단계] 응답 코드: ${response.code}")

            // 리다이렉트 여부 확인
            if (response.url == mainUrl || response.url == "$mainUrl/" || response.text.contains("메인 홈페이지")) {
                println("$TAG [실패] 메인 페이지로 리다이렉트 되었습니다. (Referer 차단됨)")
                // 실패 시 WebView로 재시도 (최후의 수단)
                println("$TAG [1단계-재시도] WebView로 다시 시도합니다...")
                val webResponse = app.get(
                    cleanData, 
                    headers = mapOf("Referer" to detailReferer), 
                    interceptor = WebViewResolver(Regex(".*"))
                )
                if (webResponse.url.contains(cleanData)) {
                    println("$TAG [1단계-재시도] WebView 성공! Provider 페이지 유지됨.")
                    processHtml(webResponse.text, callback)
                    return true
                } else {
                    println("$TAG [1단계-재시도] WebView도 실패 (URL: ${webResponse.url})")
                    return false
                }
            } else {
                println("$TAG [1단계] 성공! Provider 페이지 소스 획득.")
                return processHtml(response.text, callback)
            }

        } catch (e: Exception) {
            println("$TAG [Critical Error] ${e.message}")
            e.printStackTrace()
        }
        
        println("$TAG [LoadLinks] 프로세스 종료 (실패)")
        return false
    }

    // HTML 파싱 및 2단계 진행 함수
    private suspend fun processHtml(html: String, callback: (ExtractorLink) -> Unit): Boolean {
        // [2단계] 플레이어 URL 파싱
        println("$TAG [2단계] 자바스크립트 내 플레이어 주소 추출 시도...")
        val playerUrl = AnilifeExtractor().extractPlayerUrl(html, mainUrl)
        
        if (playerUrl != null) {
            println("$TAG [2단계] 추출 성공: $playerUrl")
            println("$TAG [3단계] 해당 주소로 이동하여 영상(M3U8) 스니핑 시도...")

            // [3단계] WebView로 플레이어 페이지 접속 -> M3U8 낚아채기
            try {
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
                    println("$TAG [완료] 최종 링크 반환 성공.")
                    return true
                } else {
                    println("$TAG [3단계] 실패: 스니핑된 주소가 m3u8이 아닙니다.")
                }
            } catch (e: Exception) {
                println("$TAG [3단계] WebView 에러: ${e.message}")
            }
        } else {
            println("$TAG [2단계] 실패: HTML에서 플레이어 주소를 찾지 못했습니다.")
            // HTML 일부 출력하여 원인 분석 지원
            // logLargeString(TAG, html)
        }
        return false
    }
}
