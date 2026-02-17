package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v36.0
 * - [Feature] 최종 링크 접속 실패 시, 서버가 반환한 HTML 소스 코드 전체를 로그에 출력 (원인 분석용)
 * - [Fix] 4단계 검증 과정을 WebView로 수행하여 403 Forbidden 페이지의 내용까지 확실하게 캡처
 * - [Fix] v35.0의 헤더(PC UA, Referer) 동기화 로직 유지
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"

    // PC User-Agent (Chrome 144)
    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to pcUserAgent,
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

        println("$TAG [Load] 접속 시도: $cleanUrl")
        
        val response = app.get(cleanUrl, headers = commonHeaders)
        val doc = response.document
        val finalUrl = response.url
        
        println("$TAG [Load] 최종 리다이렉트 URL: $finalUrl")

        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        val encodedRef = Base64.encodeToString(finalUrl.toByteArray(), Base64.NO_WRAP)

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
        println("$TAG [LoadLinks] === 프로세스 시작 (v36.0) ===")
        
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        
        if (cleanData.contains("ref=")) {
            try {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            } catch (e: Exception) {
                println("$TAG [Error] Referer 디코딩 실패")
            }
        }
        
        println("$TAG [1단계] Provider 주소: $cleanData")
        println("$TAG [1단계] 적용할 Referer: $detailReferer")

        try {
            // [1단계] WebView로 Provider 페이지 접속
            println("$TAG [1단계] WebView 접속 시도 (PC UA)...")
            val webResponse = app.get(
                cleanData, 
                headers = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent), 
                interceptor = WebViewResolver(Regex(".*"))
            )
            
            val currentUrl = webResponse.url
            val html = webResponse.text
            
            println("$TAG [1단계] WebView 완료. URL: $currentUrl")
            
            if (currentUrl == mainUrl || currentUrl == "$mainUrl/" || html.contains("메인 홈페이지")) {
                println("$TAG [실패] 메인 페이지로 리다이렉트 되었습니다.")
                return false
            }

            println("$TAG [1단계] 성공! HTML 획득 (길이: ${html.length})")

            // [2단계] 플레이어 URL 파싱
            val playerUrl = AnilifeExtractor().extractPlayerUrl(html, mainUrl)
            
            if (playerUrl != null) {
                println("$TAG [2단계] 추출 성공: $playerUrl")
                println("$TAG [3단계] M3U8 API 스니핑 및 재조립 시도...")

                // [3단계] WebView로 플레이어 페이지 접속
                try {
                    val m3u8Interceptor = WebViewResolver(Regex("""m3u8"""))
                    val m3u8Response = app.get(
                        playerUrl,
                        headers = mapOf(
                            "User-Agent" to pcUserAgent,
                            "Referer" to currentUrl
                        ),
                        interceptor = m3u8Interceptor
                    )
                    var finalM3u8 = m3u8Response.url
                    println("$TAG [3단계] 스니핑된 원본 URL: $finalM3u8")

                    // URL 재조립 (API 주소 -> 최종 영상 주소)
                    if (finalM3u8.contains("/m3u8/st/")) {
                        println("$TAG [3단계] API 주소 감지됨. 재조립 수행...")
                        finalM3u8 = finalM3u8.replace("/m3u8/st/", "/v1/manifest/b/") + "/playlist.m3u8"
                        println("$TAG [3단계] 재조립된 최종 URL: $finalM3u8")
                    }

                    if (finalM3u8.contains("m3u8")) {
                        // [v36.0 추가] 4단계: 최종 링크 사전 검증 (에러 원인 파악용)
                        // WebView를 다시 사용하여 403 페이지 내용까지 긁어옴
                        println("$TAG [4단계] 최종 링크 접속 테스트 및 내용 확인 중...")
                        val checkResponse = app.get(
                            finalM3u8,
                            headers = mapOf(
                                "User-Agent" to pcUserAgent,
                                "Referer" to "https://anilife.live/",
                                "Origin" to "https://anilife.live",
                                "Accept" to "*/*"
                            ),
                            interceptor = WebViewResolver(Regex(".*")) // 모든 내용 로드
                        )
                        
                        val content = checkResponse.text
                        println("$TAG [4단계] 검증 완료. URL: ${checkResponse.url}")
                        
                        if (content.contains("#EXTM3U")) {
                            println("$TAG [4단계] 검증 성공! 유효한 M3U8 파일입니다.")
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = finalM3u8,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://anilife.live/"
                                    this.headers = mapOf(
                                        "User-Agent" to pcUserAgent,
                                        "Origin" to "https://anilife.live"
                                    )
                                    this.quality = getQualityFromName("HD")
                                }
                            )
                            println("$TAG [완료] 링크 반환 성공.")
                            return true
                        } else {
                            println("$TAG [4단계] 검증 실패! 내용은 M3U8이 아닙니다.")
                            println("$TAG [Debug] ================= 서버 응답 내용 (Start) =================")
                            logLargeString(TAG, content)
                            println("$TAG [Debug] ================= 서버 응답 내용 (End) ===================")
                        }
                    } else {
                        println("$TAG [3단계] 실패: 유효한 m3u8 주소를 만들지 못했습니다.")
                    }
                } catch (e: Exception) {
                    println("$TAG [3단계/4단계] 에러: ${e.message}")
                    e.printStackTrace()
                }
            } else {
                println("$TAG [2단계] 실패: HTML에서 플레이어 주소를 찾지 못했습니다.")
            }
        } catch (e: Exception) {
            println("$TAG [Critical Error] ${e.message}")
            e.printStackTrace()
        }
        
        println("$TAG [LoadLinks] 프로세스 종료 (실패)")
        return false
    }
}
