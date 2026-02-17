package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v21.0
 * - [Debug] HTML 소스 코드 전체 분할 출력 (로그 잘림 방지)
 * - [Debug] 접속 URL, Referer, 매칭된 정규식 등 모든 정보 상세 출력
 * - [Fix] 에피소드 로직 v4.1 유지
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

    // 긴 로그가 잘리지 않도록 나누어 출력하는 함수
    private fun logLargeString(tag: String, msg: String) {
        if (msg.length > 4000) {
            println("$tag [HTML-Chunk-Start] ${msg.substring(0, 4000)}")
            logLargeString(tag, msg.substring(4000))
        } else {
            println("$tag [HTML-Chunk-End] $msg")
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

        println("$TAG [Load] cleanUrl: $cleanUrl")
        val doc = app.get(cleanUrl, headers = commonHeaders).document
        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        val encodedRef = Base64.encodeToString(cleanUrl.toByteArray(), Base64.NO_WRAP)

        // v4.1 방식 유지
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
        println("$TAG [LoadLinks] =================== 프로세스 시작 ===================")
        
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
            detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
            cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
        }
        
        println("$TAG [Step 1] 접속할 URL: $cleanData")
        println("$TAG [Step 1] 사용할 Referer: $detailReferer")

        try {
            // [Step 1] 웹뷰로 Provider 페이지 로드
            println("$TAG [Step 1] 웹뷰 실행 (Provider 페이지 로드)...")
            val providerResponse = app.get(
                cleanData,
                headers = mapOf("Referer" to detailReferer),
                interceptor = WebViewResolver(Regex(".*"))
            )
            
            val html = providerResponse.text
            println("$TAG [Step 1] 웹뷰 로드 완료. 상태코드: ${providerResponse.code}, URL: ${providerResponse.url}")
            println("$TAG [Step 1] HTML 길이: ${html.length}")
            
            // HTML 전체 로그 출력 (디버깅용)
            println("$TAG [Debug] HTML 소스 코드 출력 시작 (2000자씩 분할)")
            logLargeString(TAG, html)
            println("$TAG [Debug] HTML 소스 코드 출력 종료")

            // [Step 2] Extractor로 플레이어 URL 파싱
            println("$TAG [Step 2] HTML 파싱 시작...")
            val playerUrl = AnilifeExtractor().extractPlayerUrl(html, mainUrl)
            
            if (playerUrl != null) {
                println("$TAG [Step 2] 플레이어 URL 발견됨: $playerUrl")

                // [Step 3] 웹뷰로 M3U8 스니핑
                println("$TAG [Step 3] 웹뷰 실행 (M3U8 스니핑): $playerUrl")
                val m3u8Interceptor = WebViewResolver(Regex("""\.m3u8"""))
                val m3u8Response = app.get(
                    playerUrl,
                    headers = commonHeaders,
                    interceptor = m3u8Interceptor
                )
                val finalM3u8 = m3u8Response.url
                println("$TAG [Step 3] 스니핑된 URL: $finalM3u8")

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
                    println("$TAG [LoadLinks] 성공: 링크 반환됨")
                    return true
                } else {
                    println("$TAG [Step 3] 실패: URL에 .m3u8이 포함되지 않음")
                }
            } else {
                println("$TAG [Error] 플레이어 URL 추출 실패. (위의 HTML 로그를 확인하세요)")
                // 혹시 'moveCloudvideo' 같은 키워드가 있는데도 못 찾았는지 확인
                if (html.contains("moveCloudvideo")) {
                     println("$TAG [Debug] 'moveCloudvideo' 키워드는 발견되었으나 정규식 매칭에 실패했습니다.")
                } else {
                     println("$TAG [Debug] 'moveCloudvideo' 키워드조차 발견되지 않았습니다. 엉뚱한 페이지일 가능성이 높습니다.")
                }
            }
        } catch (e: Exception) {
            println("$TAG [Critical Error] ${e.message}")
            e.printStackTrace()
        }
        println("$TAG [LoadLinks] 프로세스 종료 (실패)")
        return false
    }
}
