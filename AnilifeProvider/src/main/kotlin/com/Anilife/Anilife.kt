package com.anilife

import android.util.Base64
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v58.0
 * - [Fix] 빌드 에러 해결: AnilifeExtractor -> AnilifeProxyExtractor 클래스명 참조 수정
 * - [Restore] v4.1 메인페이지 카테고리(7개), 포스터 터널링, 플롯, 장르 파싱 로직 완전 복구
 * - [Integrated] v57.0의 키 후킹 및 로컬 프록시 재생 엔진 탑재
 * - [Constraint] 코드 생략 없이 전체 코드 제공 원칙 준수
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"
    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to pcUserAgent,
        "Referer" to "$mainUrl/"
    )

    private fun logFullContent(tag: String, prefix: String, msg: String) {
        val maxLogSize = 4000
        if (msg.length > maxLogSize) {
            println("$tag $prefix [Part] ${msg.substring(0, maxLogSize)}")
            logFullContent(tag, prefix, msg.substring(maxLogSize))
        } else {
            println("$tag $prefix [End] $msg")
        }
    }

    // [v4.1 복구] 메인 페이지 카테고리 전체 목록
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
        
        println("$TAG [MainPage] 요청 실행: $url")
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val home = parseCommonList(doc)
            println("$TAG [MainPage] 결과 획득: ${home.size}건")
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            println("$TAG [MainPage] 에러 발생: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    // [v4.1 복구] 제목 중복 방지 및 포스터 Base64 터널링 로직
    private fun parseCommonList(doc: Document): List<SearchResponse> {
        return doc.select(".listupd > article.bs").mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val rawHref = fixUrl(aTag.attr("href"))
                
                // 제목 중복 방지: .tt 내부의 h2를 우선적으로 선택
                val title = (element.selectFirst(".tt h2") ?: element.selectFirst(".tt"))?.text()?.trim() ?: "Unknown"
                
                // 포스터 파싱 (src 및 data-src 대응)
                val imgTag = element.selectFirst("img")
                var poster = imgTag?.attr("src") ?: imgTag?.attr("data-src") ?: ""
                poster = fixUrl(poster)

                // 상세페이지에서의 이미지 누락 방지를 위한 Base64 인코딩 주입
                val finalHref = if (poster.isNotEmpty()) {
                    val encoded = Base64.encodeToString(poster.toByteArray(), Base64.NO_WRAP)
                    if (rawHref.contains("?")) "$rawHref&poster=$encoded" else "$rawHref?poster=$encoded"
                } else rawHref

                newAnimeSearchResponse(title, finalHref, TvType.Anime) {
                    this.posterUrl = poster
                    this.posterHeaders = commonHeaders
                }
            } catch (e: Exception) { 
                println("$TAG [Parser] 항목 파싱 오류: ${e.message}")
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
        println("$TAG [Load] 상세 정보 분석 시작: $url")
        
        // 터널링된 포스터 복원
        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            val posterParam = url.substringAfter("poster=")
            tunnelingPoster = String(Base64.decode(posterParam.substringBefore("&"), Base64.NO_WRAP))
            url.substringBefore("?poster=")
        } else url

        val response = app.get(cleanUrl, headers = commonHeaders)
        val doc = response.document
        val finalUrl = response.url
        
        println("$TAG [Load] 리다이렉트 확인됨: $finalUrl")

        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
        val encodedRef = Base64.encodeToString(finalUrl.toByteArray(), Base64.NO_WRAP)

        // [v4.1 복구] 줄거리(Plot) 및 장르(Tags) 추출
        val plot = doc.selectFirst(".synp .entry-content")?.text()?.trim()
        val tags = doc.select(".genxed a").map { it.text() }
        println("$TAG [Load] 메타데이터 수집 성공 (장르: ${tags.joinToString(",")})")

        val episodes = doc.select(".eplister > ul > li > a").mapNotNull { element ->
            val rawHref = fixUrl(element.attr("href"))
            val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
            val fullName = if (numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
            
            // 재생 Referer 검증용 ref 파라미터 추가
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
        println("$TAG [LoadLinks] =================== v58.0 프로세스 시작 ===================")
        
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            try {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            } catch (e: Exception) { println("$TAG [Error] Referer 디코딩 실패") }
        }

        try {
            // [1단계] 플레이어 페이지 로드 (웹뷰 필수)
            println("$TAG [Step 1] 웹뷰 가동: $cleanData")
            val webResponse = app.get(
                cleanData, 
                headers = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent), 
                interceptor = WebViewResolver(Regex(".*"))
            )

            // [2단계] 플레이어 주소 추출 (Extractor 호출부 이름 수정하여 빌드에러 해결)
            println("$TAG [Step 2] 플레이어 주소(h/live) 파싱 중...")
            val playerUrl = AnilifeProxyExtractor().extractPlayerUrl(webResponse.text, mainUrl) ?: return false
            println("$TAG [Step 2] 주소 획득: $playerUrl")

            // [3단계] M3U8 API 스니핑
            println("$TAG [Step 3] API 주소 낚아채기 시작...")
            val gcdnInterceptor = WebViewResolver(Regex(""".*api\.gcdn\.app.*"""))
            val gcdnResponse = app.get(
                playerUrl,
                headers = mapOf("User-Agent" to pcUserAgent, "Referer" to webResponse.url),
                interceptor = gcdnInterceptor
            )
            val sniffedUrl = gcdnResponse.url
            println("$TAG [Step 3] 캡처된 URL: $sniffedUrl")

            // [4단계] 쿠키 및 보안 SSID 추출 (스크린샷 기반 no-referrer 대응)
            val finalCookies = CookieManager.getInstance().getCookie("https://anilife.live") ?: ""
            var xUserSsid: String? = null
            var finalM3u8: String? = null

            if (sniffedUrl.contains("/m3u8/st/")) {
                println("$TAG [Step 4] API 응답에서 SSID 및 진짜 주소 확보 중...")
                val apiResponse = app.get(
                    sniffedUrl,
                    headers = mapOf("User-Agent" to pcUserAgent, "Referer" to "https://anilife.live/", "Cookie" to finalCookies)
                )
                // 대소문자 SSID 모두 체크
                xUserSsid = apiResponse.headers["x-user-ssid"] ?: apiResponse.headers["X-User-Ssid"]
                val match = Regex("""https://api\.gcdn\.app/v1/manifest/[^"']+""").find(apiResponse.text)
                if (match != null) finalM3u8 = match.value.replace("\\/", "/")
            } else {
                finalM3u8 = sniffedUrl
            }

            // [5단계] 로컬 프록시 및 키 후킹 엔진 가동 (TVWiki 기술 이식)
            if (finalM3u8 != null) {
                println("$TAG [Step 5] 키 후킹 엔진 호출 중...")
                return AnilifeProxyExtractor().extractWithProxy(
                    m3u8Url = finalM3u8,
                    playerUrl = playerUrl,
                    ssid = xUserSsid,
                    cookies = finalCookies,
                    callback = callback
                )
            }

        } catch (e: Exception) {
            println("$TAG [Error] 프로세스 중단: ${e.message}")
            e.printStackTrace()
        }
        
        println("$TAG [LoadLinks] 최종 실패.")
        return false
    }
}
