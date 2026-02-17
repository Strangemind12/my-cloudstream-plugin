package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v45.0
 * - [Critical Fix] v4.1의 제목 중복 방지 및 포스터 파싱 로직 재적용
 * - [Maintain] v44.0의 웹뷰 기반 영상 주소(manifest) 추출 로직 보존
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"

    // PC User-Agent (브라우저 성공 로그 기반)
    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to pcUserAgent,
        "Referer" to "$mainUrl/"
    )

    private fun logFullContent(tag: String, prefix: String, msg: String) {
        val maxLogSize = 4000
        if (msg.length > maxLogSize) {
            println("$tag $prefix [Part-Start] ${msg.substring(0, maxLogSize)}")
            logFullContent(tag, prefix, msg.substring(maxLogSize))
        } else {
            println("$tag $prefix [Part-End] $msg")
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
        
        println("$TAG [MainPage] 요청: $url")
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val home = parseCommonList(doc)
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            println("$TAG [MainPage] 에러: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    // [v4.1 로직 복구] 목록 파싱 (제목 중복 및 포스터 문제 해결)
    private fun parseCommonList(doc: Document): List<SearchResponse> {
        return doc.select(".listupd > article.bs").mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val rawHref = fixUrl(aTag.attr("href"))
                
                // 제목 중복 방지: .tt 내부의 h2를 먼저 찾고 없으면 .tt 전체 텍스트 사용
                val title = (element.selectFirst(".tt h2") ?: element.selectFirst(".tt"))?.text()?.trim() ?: "Unknown"
                
                // 포스터 파싱 강화: src와 data-src 모두 체크
                val imgTag = element.selectFirst("img")
                var poster = imgTag?.attr("src") ?: imgTag?.attr("data-src") ?: ""
                poster = fixUrl(poster)

                // 포스터 터널링: 상세페이지에서 이미지가 안나올 경우를 대비해 URL에 포스터 주소 포함
                val finalHref = if (poster.isNotEmpty()) {
                    val encoded = Base64.encodeToString(poster.toByteArray(), Base64.NO_WRAP)
                    if (rawHref.contains("?")) "$rawHref&poster=$encoded" else "$rawHref?poster=$encoded"
                } else rawHref

                newAnimeSearchResponse(title, finalHref, TvType.Anime) {
                    this.posterUrl = poster
                    this.posterHeaders = commonHeaders
                }
            } catch (e: Exception) { 
                println("$TAG [Parser] 항목 파싱 에러: ${e.message}")
                null 
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        println("$TAG [Search] 쿼리: $url")
        val doc = app.get(url, headers = commonHeaders).document
        return parseCommonList(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        // 포스터 터널링 복구
        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            val posterParam = url.substringAfter("poster=")
            tunnelingPoster = String(Base64.decode(posterParam.substringBefore("&"), Base64.NO_WRAP))
            url.substringBefore("?poster=")
        } else url

        println("$TAG [Load] 상세 페이지 접속: $cleanUrl")
        val response = app.get(cleanUrl, headers = commonHeaders)
        val doc = response.document
        val finalUrl = response.url
        
        println("$TAG [Load] 리다이렉트된 최종 URL: $finalUrl")

        // 제목 및 메타데이터 파싱
        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        val encodedRef = Base64.encodeToString(finalUrl.toByteArray(), Base64.NO_WRAP)

        // 에피소드 파싱 (v4.1 로직 유지: 에피소드 번호 및 정렬)
        val episodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val rawHref = fixUrl(element.attr("href"))
            val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            val fullName = if (numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
            
            // Referer 추적용 ref 파라미터 추가
            val finalHref = if (rawHref.contains("?")) "$rawHref&ref=$encodedRef" else "$rawHref?ref=$encodedRef"

            newEpisode(finalHref) {
                this.name = fullName
                this.episode = numText.toIntOrNull()
            }
        }.reversed() // 최신화가 아래로 가도록 정렬

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
        println("$TAG [LoadLinks] =================== v45.0 시작 ===================")
        
        // 데이터 전처리
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            try {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            } catch (e: Exception) { println("$TAG [Error] Referer 디코딩 실패") }
        }
        
        println("$TAG [Step 1] Provider 접속: $cleanData")

        try {
            // [1단계] Provider 페이지 로드
            val webResponse = app.get(
                cleanData,
                headers = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent),
                interceptor = WebViewResolver(Regex(".*"))
            )

            // [2단계] 플레이어 URL 추출 (Extractor 호출)
            val playerUrl = AnilifeExtractor().extractPlayerUrl(webResponse.text, mainUrl)
            if (playerUrl == null) {
                println("$TAG [Step 2] 실패: 플레이어 주소 추출 불가.")
                return false
            }
            println("$TAG [Step 2] 추출 성공: $playerUrl")

            // [3단계] M3U8 API 스니핑 (v44.0 검증 로직)
            println("$TAG [Step 3] 웹뷰 스니핑 시작 (Target: api.gcdn.app)...")
            val gcdnInterceptor = WebViewResolver(Regex(""".*api\.gcdn\.app.*"""))
            val gcdnResponse = app.get(
                playerUrl,
                headers = mapOf("User-Agent" to pcUserAgent, "Referer" to webResponse.url),
                interceptor = gcdnInterceptor
            )
            val sniffedUrl = gcdnResponse.url
            println("$TAG [Step 3] [CATCH] 가로챈 URL: $sniffedUrl")

            var finalM3u8: String? = null

            // [Step 3-B] API(st) 응답 본문에서 진짜 주소 추출 (v44.0 핵심 로직)
            if (sniffedUrl.contains("/m3u8/st/")) {
                println("$TAG [Step 3-B] API 응답 본문 파싱 시작...")
                val apiResponse = app.get(
                    sniffedUrl,
                    headers = mapOf(
                        "User-Agent" to pcUserAgent,
                        "Referer" to "https://anilife.live/",
                        "Origin" to "https://anilife.live",
                        "Accept" to "*/*"
                    )
                )
                
                println("$TAG [Step 3-B] 응답 코드: ${apiResponse.code}")
                logFullContent(TAG, "[API-Body]", apiResponse.text)

                // 정규식으로 manifest 주소 추출
                val manifestRegex = Regex("""https://api\.gcdn\.app/v1/manifest/[^"']+""")
                val match = manifestRegex.find(apiResponse.text)
                
                if (match != null) {
                    finalM3u8 = match.value.replace("\\/", "/")
                    println("$TAG [Step 3-B] [SUCCESS] 진짜 주소: $finalM3u8")
                } else {
                    println("$TAG [Step 3-B] [FAILED] 본문 내 패턴 없음.")
                }
            } else if (sniffedUrl.contains("manifest") || sniffedUrl.contains(".m3u8")) {
                finalM3u8 = sniffedUrl
            }

            // [4단계] 최종 링크 반환
            if (finalM3u8 != null) {
                println("$TAG [Step 4] 플레이어 전달: $finalM3u8")
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
            }

        } catch (e: Exception) {
            println("$TAG [Critical Error] ${e.message}")
            e.printStackTrace()
        }
        
        println("$TAG [LoadLinks] 실패.")
        return false
    }
}
