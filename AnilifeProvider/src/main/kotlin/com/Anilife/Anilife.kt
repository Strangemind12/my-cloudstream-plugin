package com.anilife

import android.util.Base64
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v49.0
 * - [Restore] v4.1의 제목 중복 방지, 포스터 파싱, 장르 및 줄거리 추출 로직 완전 복구
 * - [Integrated] v48.0의 메인 사이트 쿠키 동기화 및 API JSON 파싱 로직 통합
 * - [Debug] 전 과정 상세 디버깅 로그(println) 포함
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"

    // PC User-Agent (Chrome 144 - 브라우저 개발자 도구 기준)
    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to pcUserAgent,
        "Referer" to "$mainUrl/"
    )

    // 로그캣 글자수 제한 대응용 분할 출력 함수
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
        
        println("$TAG [MainPage] 요청 시작: $url")
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val home = parseCommonList(doc)
            println("$TAG [MainPage] 파싱 완료: ${home.size}개 항목")
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            println("$TAG [MainPage] 에러 발생: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    // [v4.1 복구] 제목 중복 방지 및 포스터 파싱 로직
    private fun parseCommonList(doc: Document): List<SearchResponse> {
        return doc.select(".listupd > article.bs").mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val rawHref = fixUrl(aTag.attr("href"))
                
                // 제목 중복 방지: .tt 내부의 h2를 우선순위로 선택
                val title = (element.selectFirst(".tt h2") ?: element.selectFirst(".tt"))?.text()?.trim() ?: "Unknown"
                
                // 포스터 파싱 최적화
                val imgTag = element.selectFirst("img")
                var poster = imgTag?.attr("src") ?: imgTag?.attr("data-src") ?: ""
                poster = fixUrl(poster)

                // 상세페이지 포스터 미출력 대비 터널링 (Base64)
                val finalHref = if (poster.isNotEmpty()) {
                    val encoded = Base64.encodeToString(poster.toByteArray(), Base64.NO_WRAP)
                    if (rawHref.contains("?")) "$rawHref&poster=$encoded" else "$rawHref?poster=$encoded"
                } else rawHref

                newAnimeSearchResponse(title, finalHref, TvType.Anime) {
                    this.posterUrl = poster
                    this.posterHeaders = commonHeaders
                }
            } catch (e: Exception) { 
                println("$TAG [Parser] 항목 스킵됨: ${e.message}")
                null 
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        println("$TAG [Search] 쿼리 전송: $url")
        val doc = app.get(url, headers = commonHeaders).document
        return parseCommonList(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        println("$TAG [Load] 상세 페이지 분석 시작: $url")
        
        // 터널링된 포스터 복구
        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            val posterParam = url.substringAfter("poster=")
            tunnelingPoster = String(Base64.decode(posterParam.substringBefore("&"), Base64.NO_WRAP))
            url.substringBefore("?poster=")
        } else url

        val response = app.get(cleanUrl, headers = commonHeaders)
        val doc = response.document
        val finalUrl = response.url // 리다이렉트된 최종 상세 페이지 주소
        
        println("$TAG [Load] 최종 리다이렉트 주소: $finalUrl")

        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        val encodedRef = Base64.encodeToString(finalUrl.toByteArray(), Base64.NO_WRAP)

        // [v4.1 복구] 줄거리(Plot) 및 태그(Genres) 파싱
        val plot = doc.selectFirst(".synp .entry-content")?.text()?.trim()
        val tags = doc.select(".genxed a").map { it.text() }
        println("$TAG [Load] 메타데이터 파싱 성공: 장르 ${tags.size}개")

        val episodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val rawHref = fixUrl(element.attr("href"))
            val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            val fullName = if (numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
            
            // 재생 시 Referer 검증 통과를 위한 ref 파라미터 추가
            val finalHref = if (rawHref.contains("?")) "$rawHref&ref=$encodedRef" else "$rawHref?ref=$encodedRef"

            newEpisode(finalHref) {
                this.name = fullName
                this.episode = numText.toIntOrNull()
            }
        }.reversed()

        return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
            this.posterUrl = doc.selectFirst(".thumb img")?.attr("src") ?: tunnelingPoster
            this.posterHeaders = commonHeaders
            this.plot = plot
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("$TAG [LoadLinks] =================== v49.0 시작 ===================")
        
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            try {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            } catch (e: Exception) { println("$TAG [Error] Referer 디코딩 실패") }
        }
        
        println("$TAG [Step 1] Provider 접속 및 웹뷰 대기: $cleanData")

        try {
            // [1단계] WebView 실행
            val webResponse = app.get(
                cleanData,
                headers = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent),
                interceptor = WebViewResolver(Regex(".*"))
            )

            // [2단계] 플레이어 URL 추출
            val playerUrl = AnilifeExtractor().extractPlayerUrl(webResponse.text, mainUrl)
            if (playerUrl == null) {
                println("$TAG [Step 2] 실패: HTML에서 플레이어 주소(h/live)를 찾지 못함.")
                return false
            }
            println("$TAG [Step 2] 플레이어 주소 발견: $playerUrl")

            // [3단계] M3U8 API 스니핑
            println("$TAG [Step 3] 웹뷰 스니핑 시작 (Target: api.gcdn.app)...")
            val gcdnInterceptor = WebViewResolver(Regex(""".*api\.gcdn\.app.*"""))
            val gcdnResponse = app.get(
                playerUrl,
                headers = mapOf("User-Agent" to pcUserAgent, "Referer" to webResponse.url),
                interceptor = gcdnInterceptor
            )
            val sniffedUrl = gcdnResponse.url
            println("$TAG [Step 3] 가로챈 API 주소: $sniffedUrl")

            // [Step 3-B] 쿠키 추출 (v48.0 성공 로직 - 메인 사이트 중심)
            println("$TAG [Step 3-B] 인증 쿠키 추출 중 (anilife.live)...")
            val cookieManager = CookieManager.getInstance()
            val siteCookies = cookieManager.getCookie("https://anilife.live") ?: ""
            val gcdnCookies = cookieManager.getCookie("https://api.gcdn.app") ?: ""
            val finalCookies = "$siteCookies; $gcdnCookies".trim(';', ' ')
            println("$TAG [Step 3-B] 최종 통합 쿠키 획득 완료.")

            var finalM3u8: String? = null

            // [Step 3-C] API 응답 본문 파싱 (v44.0 핵심 로직)
            if (sniffedUrl.contains("/m3u8/st/")) {
                println("$TAG [Step 3-C] API JSON 응답에서 진짜 주소를 추출합니다...")
                val apiResponse = app.get(
                    sniffedUrl,
                    headers = mapOf(
                        "User-Agent" to pcUserAgent,
                        "Referer" to "https://anilife.live/",
                        "Origin" to "https://anilife.live",
                        "Cookie" to finalCookies,
                        "Accept" to "*/*"
                    )
                )
                
                // JSON 본문에서 v1/manifest 주소를 정밀 파싱
                val manifestRegex = Regex("""https://api\.gcdn\.app/v1/manifest/[^"']+""")
                val match = manifestRegex.find(apiResponse.text)
                
                if (match != null) {
                    finalM3u8 = match.value.replace("\\/", "/")
                    println("$TAG [Step 3-C] [SUCCESS] 진짜 주소 파싱 성공: $finalM3u8")
                } else {
                    println("$TAG [Step 3-C] [FAILED] 응답 본문에 주소 패턴이 없습니다.")
                    logFullContent(TAG, "[API-Error]", apiResponse.text)
                }
            } else {
                finalM3u8 = sniffedUrl
            }

            // [4단계] 최종 링크 전달 및 브라우저 헤더 동기화
            if (finalM3u8 != null) {
                println("$TAG [Step 4] 플레이어에 최종 정보 전달 중...")
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
                            "Origin" to "https://anilife.live",
                            "Cookie" to finalCookies, // [핵심] enc.bin 접근 권한 부여
                            "Accept" to "*/*",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Site" to "cross-site",
                            "Sec-Fetch-Dest" to "empty"
                        )
                        this.quality = getQualityFromName("HD")
                    }
                )
                println("$TAG [완료] 프로세스 성공 종료.")
                return true
            }

        } catch (e: Exception) {
            println("$TAG [Critical Error] 예외 발생: ${e.message}")
            e.printStackTrace()
        }
        
        println("$TAG [LoadLinks] 실패.")
        return false
    }
}
