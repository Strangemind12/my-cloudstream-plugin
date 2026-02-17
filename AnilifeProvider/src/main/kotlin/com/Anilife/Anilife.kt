package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v43.0
 * - [Log] 중간 API(st) 주소 감지 시, 요청 헤더 및 응답 본문 전체(Full Body) 상세 로깅 추가
 * - [Debug] 로그캣 글자수 제한 우회를 위해 분할 출력 로직 강화 (logFullContent)
 * - [Fix] 60초 타임아웃 방지를 위한 넓은 범위의 웹뷰 스니핑 정규식 유지
 * - [Fix] PC User-Agent 및 성공한 브라우저 헤더 동기화
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

    // 긴 메시지를 로그캣에서 잘리지 않게 전체 출력하는 함수
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
        "/vodtype/categorize/TV/1" to "TV 애니메이션"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}"
        println("$TAG [MainPage] 요청 URL: $url")
        val doc = app.get(url, headers = mapOf("User-Agent" to pcUserAgent)).document
        val home = doc.select(".listupd > article.bs").mapNotNull { element ->
            val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
            newAnimeSearchResponse(element.selectFirst(".tt")?.text() ?: "", aTag.attr("href"), TvType.Anime)
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun load(url: String): LoadResponse {
        println("$TAG [Load] 상세 페이지 접속: $url")
        val response = app.get(url, headers = mapOf("User-Agent" to pcUserAgent))
        val finalUrl = response.url
        val encodedRef = Base64.encodeToString(finalUrl.toByteArray(), Base64.NO_WRAP)
        
        val episodes = response.document.select(".eplister > ul > li > a").map {
            newEpisode(it.attr("href") + "&ref=$encodedRef") {
                this.name = it.selectFirst(".epl-title")?.text()
            }
        }.reversed()

        return newAnimeLoadResponse("Anime", url, TvType.Anime) {
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("$TAG [LoadLinks] =================== v43.0 시작 ===================")
        
        var cleanData = data
        var detailReferer = "$mainUrl/"
        if (data.contains("ref=")) {
            val refEncoded = data.substringAfter("ref=").substringBefore("&")
            detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
            cleanData = data.substringBefore("&ref=").substringBefore("?ref=")
        }
        
        println("$TAG [Step 1] 웹뷰 로딩 시작: $cleanData")
        println("$TAG [Step 1] 헤더 설정 - UA: PC Chrome, Referer: $detailReferer")

        try {
            // [1단계] Provider 페이지 로드
            val webResponse = app.get(
                cleanData,
                headers = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent),
                interceptor = WebViewResolver(Regex(".*"))
            )
            println("$TAG [Step 1] 웹뷰 로드 완료. 최종 URL: ${webResponse.url}")

            // [2단계] 플레이어 URL 추출
            val playerUrl = AnilifeExtractor().extractPlayerUrl(webResponse.text, mainUrl)
            if (playerUrl == null) {
                println("$TAG [Step 2] 실패: HTML에서 플레이어(h/live) 주소를 찾지 못함.")
                logFullContent(TAG, "[HTML-Dump]", webResponse.text)
                return false
            }
            println("$TAG [Step 2] 플레이어 주소 추출 성공: $playerUrl")

            // [3단계] M3U8 API 스니핑
            println("$TAG [Step 3] 웹뷰 스니핑 시작 (Target: api.gcdn.app)...")
            
            // 모든 gcdn 관련 요청을 잡아내어 타임아웃 방지
            val gcdnInterceptor = WebViewResolver(Regex(""".*api\.gcdn\.app.*"""))
            val gcdnResponse = app.get(
                playerUrl,
                headers = mapOf("User-Agent" to pcUserAgent, "Referer" to webResponse.url),
                interceptor = gcdnInterceptor
            )
            val sniffedUrl = gcdnResponse.url
            println("$TAG [Step 3] [WEBVIEW-CATCH] 가로챈 URL: $sniffedUrl")

            var finalM3u8: String? = null

            // [Step 3-B] 중간 API(st) 주소 처리 및 상세 로깅
            if (sniffedUrl.contains("/m3u8/st/")) {
                println("$TAG [Step 3-B] 중간 API(st) 주소가 확인되었습니다.")
                println("$TAG [Step 3-B] [API-REQUEST-START] 직접 호출을 시작합니다...")
                
                val apiHeaders = mapOf(
                    "User-Agent" to pcUserAgent,
                    "Referer" to "https://anilife.live/",
                    "Origin" to "https://anilife.live",
                    "Accept" to "*/*"
                )
                println("$TAG [Step 3-B] [API-REQUEST-HEADERS] $apiHeaders")

                val apiResponse = app.get(sniffedUrl, headers = apiHeaders)
                
                println("$TAG [Step 3-B] [API-RESPONSE-STATUS] Code: ${apiResponse.code}")
                println("$TAG [Step 3-B] [API-RESPONSE-BODY] 전체 응답 데이터를 출력합니다:")
                logFullContent(TAG, "[API-Body]", apiResponse.text)

                // 응답 본문에서 manifest 주소 정밀 추출
                println("$TAG [Step 3-B] 응답 본문에서 진짜 영상 주소(manifest) 검색 중...")
                val manifestMatch = Regex("""https://api\.gcdn\.app/v1/manifest/[^"']+""").find(apiResponse.text)
                if (manifestMatch != null) {
                    finalM3u8 = manifestMatch.value.replace("\\/", "/")
                    println("$TAG [Step 3-B] [SUCCESS] 진짜 주소 발견: $finalM3u8")
                } else {
                    println("$TAG [Step 3-B] [FAILED] 응답 본문 내에 manifest 주소 패턴이 존재하지 않습니다.")
                }
            } else if (sniffedUrl.contains("manifest") || sniffedUrl.contains(".m3u8")) {
                finalM3u8 = sniffedUrl
                println("$TAG [Step 3-C] 웹뷰가 이미 최종 주소를 찾았습니다: $finalM3u8")
            }

            // [4단계] 최종 링크 전달
            if (finalM3u8 != null) {
                println("$TAG [Step 4] 플레이어(ExoPlayer)에 링크 및 헤더를 전달합니다.")
                println("$TAG [Step 4] 전달 URL: $finalM3u8")
                println("$TAG [Step 4] 전달 Referer: https://anilife.live/")
                
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
                println("$TAG [LoadLinks] 모든 프로세스 완료.")
                return true
            }

        } catch (e: Exception) {
            println("$TAG [Critical Error] 예외 발생: ${e.message}")
            e.printStackTrace()
        }
        
        println("$TAG [LoadLinks] 링크를 찾지 못하고 종료되었습니다.")
        return false
    }
}
