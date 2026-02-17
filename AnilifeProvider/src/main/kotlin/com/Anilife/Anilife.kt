package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v29.0
 * - [Feature] M3U8 링크 추출 후, 플레이어에게 넘기기 전에 내용을 직접 다운로드하여 '#EXTM3U' 헤더 유무 검증
 * - [Debug] 검증 실패 시(차단됨) 서버가 반환한 실제 내용(HTML 등)을 로그에 출력하여 원인 규명
 * - [Fix] v28.0의 로직(완전 웹뷰, 모바일 UA, 리다이렉트 추적) 계승
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"

    // 모바일 User-Agent
    private val mobileUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to mobileUserAgent,
        "Referer" to "$mainUrl/"
    )

    private fun logLargeString(tag: String, msg: String) {
        if (msg.length > 4000) {
            println("$tag [Chunk] ${msg.substring(0, 4000)}")
            logLargeString(tag, msg.substring(4000))
        } else {
            println("$tag [End] $msg")
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
        
        // 리다이렉트된 최종 URL 획득
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
        println("$TAG [LoadLinks] === 프로세스 시작 (v29.0) ===")
        
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
            println("$TAG [1단계] WebView 접속 시도...")
            
            val webResponse = app.get(
                cleanData, 
                headers = mapOf(
                    "Referer" to detailReferer,
                    "User-Agent" to mobileUserAgent
                ), 
                interceptor = WebViewResolver(Regex(".*"))
            )
            
            val currentUrl = webResponse.url
            val html = webResponse.text
            
            println("$TAG [1단계] WebView 완료. 현재 URL: $currentUrl")
            
            if (currentUrl == mainUrl || currentUrl == "$mainUrl/" || html.contains("메인 홈페이지")) {
                println("$TAG [실패] 메인 페이지로 리다이렉트 되었습니다.")
                return false
            }

            println("$TAG [1단계] 성공! HTML 획득 (길이: ${html.length})")

            // [2단계] 플레이어 URL 파싱
            val playerUrl = AnilifeExtractor().extractPlayerUrl(html, mainUrl)
            
            if (playerUrl != null) {
                println("$TAG [2단계] 추출 성공: $playerUrl")
                println("$TAG [3단계] M3U8 스니핑 시도...")

                // [3단계] WebView로 플레이어 페이지 접속 -> M3U8 낚아채기
                try {
                    val m3u8Interceptor = WebViewResolver(Regex("""m3u8"""))
                    val m3u8Response = app.get(
                        playerUrl,
                        headers = mapOf(
                            "User-Agent" to mobileUserAgent,
                            "Referer" to currentUrl
                        ),
                        interceptor = m3u8Interceptor
                    )
                    val finalM3u8 = m3u8Response.url
                    println("$TAG [3단계] 스니핑 URL: $finalM3u8")

                    if (finalM3u8.contains("m3u8")) {
                        // [v29.0 추가] 4단계: M3U8 헤더 검증
                        println("$TAG [4단계] M3U8 내용 검증 시도...")
                        
                        val checkResponse = app.get(
                            finalM3u8,
                            headers = mapOf(
                                "User-Agent" to mobileUserAgent,
                                "Referer" to playerUrl // 플레이어 URL을 Referer로 사용
                            )
                        )
                        
                        val content = checkResponse.text.trim()
                        
                        if (content.startsWith("#EXTM3U")) {
                            println("$TAG [4단계] 검증 성공! 유효한 M3U8 파일입니다.")
                            
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = finalM3u8,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = playerUrl
                                    this.headers = mapOf("User-Agent" to mobileUserAgent)
                                    this.quality = getQualityFromName("HD")
                                }
                            )
                            println("$TAG [완료] 링크 반환 성공.")
                            return true
                        } else {
                            println("$TAG [4단계] 검증 실패: 내용이 #EXTM3U로 시작하지 않습니다.")
                            println("$TAG [Debug] 응답 내용(앞부분): ${content.take(500)}")
                            // 필요시 전체 내용 출력
                            // logLargeString(TAG, content)
                        }
                    } else {
                        println("$TAG [3단계] 실패: 스니핑된 주소에 m3u8이 없습니다.")
                    }
                } catch (e: Exception) {
                    println("$TAG [3단계/4단계] 에러: ${e.message}")
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
