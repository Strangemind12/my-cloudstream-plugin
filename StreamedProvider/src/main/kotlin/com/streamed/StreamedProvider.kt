// 버전 정보: v4.0
package com.streamed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.Jsoup
import java.net.URI

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.pk"
    override var name = "Streamed"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Live Sports"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("[Streamed v4.0] 디버깅 - getMainPage 호출")
        
        // 사용자 지침 준수: CloudflareKiller를 적용하여 안티봇 우회
        val document = app.get(request.data, interceptor = CloudflareKiller()).document
        val homeList = arrayListOf<SearchResponse>()
        
        document.select("a[href^=/watch/]").forEach { element ->
            val href = element.attr("href")
            val parts = href.trimEnd('/').split("/")
            if (parts.isNotEmpty() && parts.last().toIntOrNull() == null) {
                val title = element.text().trim().ifEmpty { parts.last() }
                homeList.add(newLiveSearchResponse(title, fixUrl(href)) { this.posterUrl = null })
            }
        }
        return newHomePageResponse(request.name, homeList.distinctBy { it.url })
    }

    override suspend fun load(url: String): LoadResponse {
        println("[Streamed v4.0] 디버깅 - load 호출: $url")
        
        val document = app.get(url, interceptor = CloudflareKiller()).document
        val title = document.select("title").text().replace("Stream Links - Streamed", "").trim()
        val sourceLinks = arrayListOf<String>()
        val baseUrl = url.trimEnd('/')
        
        // 동적 파싱을 통한 Admin 링크 추출 시도
        document.select("a[href^=/watch/]").forEach {
            val href = it.attr("href")
            if (href.matches(Regex(""".*/\d+$"""))) {
                sourceLinks.add(fixUrl(href))
            }
        }
        
        if (sourceLinks.isEmpty()) {
            println("[Streamed v4.0] 디버깅 - 정적 파싱 실패, 기본 하위 경로 강제 주입")
            sourceLinks.add("$baseUrl/admin/1")
            sourceLinks.add("$baseUrl/admin/2")
            sourceLinks.add("$baseUrl/delta/1")
            sourceLinks.add("$baseUrl/echo/1")
        }
        
        return newLiveStreamLoadResponse(title, url, dataUrl = sourceLinks.toJson())
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        println("[Streamed v4.0] 디버깅 - loadLinks 시작")
        val links = AppUtils.tryParseJson<List<String>>(data) ?: listOf(data)
        var isSuccess = false
        
        for (sourceUrl in links) {
            try {
                println("[Streamed v4.0] 디버깅 - 채널 탐색: $sourceUrl")
                
                // 1. 메인 페이지 Cloudflare 우회 (네이티브)
                val response = app.get(sourceUrl, interceptor = CloudflareKiller())
                val html = response.text
                var iframeUrl: String? = null
                
                // SvelteKit의 커스텀 난독화 스크립트 탐색
                val jsMatch = Regex("""window\['[^']+'\]\s*=\s*'([^']+)'""").find(html)
                
                if (jsMatch != null) {
                    println("[Streamed v4.0] 디버깅 - SvelteKit 난독화 페이로드 발견. 순수 Kotlin으로 역난독화를 시작합니다.")
                    // 역엔지니어링된 로직으로 무거운 WebView 없이 0.01초만에 Iframe 주소 복원
                    val decodedString = decodeCustomBase64(jsMatch.groupValues[1])
                    
                    val embedMatch = Regex("""(https?://[^\s"'\\]+(?:embed|player)[^\s"'\\]+)""").find(decodedString)
                    if (embedMatch != null) {
                        iframeUrl = embedMatch.groupValues[1]
                        println("[Streamed v4.0] 디버깅 - 난독화 해제 및 Iframe 주소 추출 완료: $iframeUrl")
                    }
                }

                // 난독화가 안 된 정적 iframe 폴백
                if (iframeUrl == null) {
                    val doc = Jsoup.parse(html)
                    val staticIframe = doc.select("iframe").firstOrNull()?.attr("src")?.ifEmpty { doc.select("iframe").attr("data-src") }
                    if (!staticIframe.isNullOrBlank()) {
                        iframeUrl = fixUrl(staticIframe)
                    }
                }

                if (iframeUrl != null) {
                    println("[Streamed v4.0] 디버깅 - 외부 플레이어 Iframe 내부 탐색 (CloudflareKiller 재적용): $iframeUrl")
                    
                    // 2. 외부 플레이어 도메인(embedme 등)도 Cloudflare로 막혀있으므로 다시 한번 우회
                    val iframeResponse = app.get(iframeUrl, headers = mapOf("Referer" to mainUrl), interceptor = CloudflareKiller())
                    val iframeHtml = iframeResponse.text
                    
                    var m3u8Url = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""").find(iframeHtml)?.groupValues?.get(1)
                    
                    // atob() 방식의 2중 난독화 탐색
                    if (m3u8Url == null) {
                        val atobMatch = Regex("""atob\(['"]([^"']+)['"]\)""").find(iframeHtml)
                        if (atobMatch != null) {
                            try {
                                val decodedAtob = String(android.util.Base64.decode(atobMatch.groupValues[1], android.util.Base64.DEFAULT))
                                m3u8Url = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""").find(decodedAtob)?.groupValues?.get(1)
                                println("[Streamed v4.0] 디버깅 - atob 난독화 해독 성공")
                            } catch (e: Exception) {}
                        }
                    }

                    if (m3u8Url != null) {
                        m3u8Url = m3u8Url.replace("&amp;", "&")
                        val refererDomain = "https://${URI(iframeUrl).host}/"
                        println("[Streamed v4.0] 디버깅 - 최종 m3u8 링크 추출 완료: $m3u8Url")
                        
                        callback(
                            newExtractorLink(
                                name = this.name,
                                source = this.name,
                                url = m3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = refererDomain
                            }
                        )
                        isSuccess = true
                        break // 링크를 찾으면 다른 Admin 서버는 건너뜀
                    } else {
                        println("[Streamed v4.0] 디버깅 - 해당 채널에서 m3u8을 찾지 못했습니다. 다음 채널로 넘어갑니다.")
                    }
                }
            } catch (e: Throwable) {
                // CloudflareKiller나 네트워크 문제로 스레드가 죽지 않도록 Throwable 방어벽 적용
                println("[Streamed v4.0] 디버깅 - 크롤링 에러 발생: ${e.message}")
            }
        }
        
        return isSuccess
    }

    // SvelteKit의 대/소문자 스왑 Base64 커스텀 알고리즘을 표준 Base64로 치환하여 디코딩하는 함수
    private fun decodeCustomBase64(encoded: String): String {
        val standardBase64 = encoded.map { char ->
            when {
                char.isUpperCase() -> char.lowercaseChar()
                char.isLowerCase() -> char.uppercaseChar()
                else -> char
            }
        }.joinToString("")
        
        return try {
            val bytes = android.util.Base64.decode(standardBase64, android.util.Base64.DEFAULT)
            String(bytes, Charsets.UTF_8).replace("\\/", "/") // JSON 내 이스케이프 문자 정리
        } catch (e: Exception) {
            ""
        }
    }
}
