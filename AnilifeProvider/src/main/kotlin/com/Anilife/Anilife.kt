package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document

/**
 * Anilife Provider v44.0
 * - [Critical Fix] 중간 API(/m3u8/st/)의 JSON 응답에서 진짜 영상 주소(v1/manifest/)를 추출하는 로직 확정.
 * - [Feature] JSON 내 이스케이프 문자(\/) 제거 및 정밀 정규식 파싱 적용.
 * - [Log] API 응답 본문 및 추출된 최종 URL 상세 로깅.
 * - [Fix] PC User-Agent 및 성공 헤더 설정 유지.
 */
class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"

    // PC User-Agent (사용자 성공 로그 기반)
    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

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
        println("$TAG [LoadLinks] =================== v44.0 시작 ===================")
        
        var cleanData = data
        var detailReferer = "$mainUrl/"
        if (data.contains("ref=")) {
            val refEncoded = data.substringAfter("ref=").substringBefore("&")
            detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
            cleanData = data.substringBefore("&ref=").substringBefore("?ref=")
        }
        
        println("$TAG [Step 1] 웹뷰 실행: Provider 페이지 접속...")

        try {
            // [1단계] Provider 페이지 로드
            val webResponse = app.get(
                cleanData,
                headers = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent),
                interceptor = WebViewResolver(Regex(".*"))
            )

            // [2단계] 플레이어 URL 추출
            val playerUrl = AnilifeExtractor().extractPlayerUrl(webResponse.text, mainUrl)
            if (playerUrl == null) {
                println("$TAG [Step 2] 실패: 플레이어 주소를 찾지 못함.")
                return false
            }
            println("$TAG [Step 2] 추출 성공: $playerUrl")

            // [3단계] M3U8 API 스니핑
            println("$TAG [Step 3] 웹뷰 스니핑 시작 (Target: api.gcdn.app)...")
            
            val gcdnInterceptor = WebViewResolver(Regex(""".*api\.gcdn\.app.*"""))
            val gcdnResponse = app.get(
                playerUrl,
                headers = mapOf("User-Agent" to pcUserAgent, "Referer" to webResponse.url),
                interceptor = gcdnInterceptor
            )
            val sniffedUrl = gcdnResponse.url
            println("$TAG [Step 3] [WEBVIEW-CATCH] 가로챈 URL: $sniffedUrl")

            var finalM3u8: String? = null

            // [Step 3-B] 가로챈 URL이 중간 API(st)인 경우 처리
            if (sniffedUrl.contains("/m3u8/st/")) {
                println("$TAG [Step 3-B] 중간 API 응답 본문에서 진짜 주소를 추출합니다.")
                
                val apiResponse = app.get(
                    sniffedUrl,
                    headers = mapOf(
                        "User-Agent" to pcUserAgent,
                        "Referer" to "https://anilife.live/",
                        "Origin" to "https://anilife.live",
                        "Accept" to "*/*"
                    )
                )
                
                println("$TAG [Step 3-B] API 응답 코드: ${apiResponse.code}")
                println("$TAG [Step 3-B] API 응답 전문 출력:")
                logFullContent(TAG, "[API-Body]", apiResponse.text)

                // [v44.0 핵심] 응답 본문(JSON)에서 진짜 주소 추출
                // 정규식 설명: https://api.gcdn.app/v1/manifest/ 로 시작해서 따옴표(")가 나올 때까지 모든 문자 추출
                val manifestRegex = Regex("""https://api\.gcdn\.app/v1/manifest/[^"']+""")
                val match = manifestRegex.find(apiResponse.text)
                
                if (match != null) {
                    // 이스케이프 제거 (\/ -> /)
                    finalM3u8 = match.value.replace("\\/", "/")
                    println("$TAG [Step 3-B] [SUCCESS] 진짜 주소 파싱 성공: $finalM3u8")
                } else {
                    println("$TAG [Step 3-B] [FAILED] 응답 본문에서 manifest 패턴을 찾지 못했습니다.")
                }
            } else if (sniffedUrl.contains("manifest") || sniffedUrl.contains(".m3u8")) {
                finalM3u8 = sniffedUrl
                println("$TAG [Step 3-C] 이미 최종 주소입니다: $finalM3u8")
            }

            // [4단계] 최종 링크 반환
            if (finalM3u8 != null) {
                println("$TAG [Step 4] 최종 링크를 플레이어에 전달합니다: $finalM3u8")
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
                println("$TAG [완료] 프로세스 성공.")
                return true
            }

        } catch (e: Exception) {
            println("$TAG [Error] 예외 발생: ${e.message}")
            e.printStackTrace()
        }
        
        println("$TAG [LoadLinks] 실패.")
        return false
    }
}
