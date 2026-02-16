package com.kotbc

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * KotbcExtractor v2.6
 * - Fix: API 요청 URL 파라미터(data, do)와 Body 파라미터(hash, r) 분리 적용
 * - Fix: Origin 헤더 추가 및 securedLink 추출 우선순위 변경
 */
class KotbcExtractor : ExtractorApi() {
    override val name = "Kotbc"
    override val mainUrl = "https://mov.glamov.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[KotbcExtractor] Fetching: $url")
        try {
            // 1. mov.glamov 페이지 요청
            val response = app.get(url, headers = mapOf("Referer" to "https://m135.kotbc2.com"))
            val html = response.text

            // 2. 내부 iframe 찾기 (nnmo0oi1.com/video/...)
            val iframeRegex = Regex("""src=["'](https://nnmo0oi1\.com/video/[^"']+)["']""")
            val iframeMatch = iframeRegex.find(html)
            
            var targetUrl = url
            
            if (iframeMatch != null) {
                targetUrl = iframeMatch.groupValues[1]
                println("[KotbcExtractor] Found video iframe: $targetUrl")
            }

            // 3. 비디오 페이지(nnmo0oi1) 내용 가져오기
            val videoHtml = if (targetUrl != url) {
                // iframe 요청 시 referer는 mov.glamov.com (즉, 현재 url)
                app.get(targetUrl, headers = mapOf("Referer" to url)).text
            } else {
                html
            }

            // 4. API를 통한 영상 추출 (Payload r값 설정을 위해 원래 URL 전달)
            if (fetchVideoApi(videoHtml, targetUrl, url, callback)) {
                return
            }

            // 5. 실패 시 내장 추출기 백업
            println("[KotbcExtractor] API failed, trying loadExtractor fallback")
            loadExtractor(targetUrl, subtitleCallback = subtitleCallback, callback = callback)

        } catch (e: Exception) {
            println("[KotbcExtractor] Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun fetchVideoApi(
        html: String, 
        videoPageUrl: String, 
        originalReferer: String, // mov.glamov.com 주소 (r값 용도)
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("[KotbcExtractor] Attempting API extraction...")
            
            // 1. Hash 추출 (32자리 hex)
            val hashRegex = Regex("""['"]([a-f0-9]{32})['"]""")
            val hashMatch = hashRegex.find(html) ?: return false
            val hash = hashMatch.groupValues[1]
            println("[KotbcExtractor] Found hash: $hash")

            // 2. API 요청 구성
            // URL: /player/index.php?data={hash}&do=getVideo
            val apiUrl = "https://nnmo0oi1.com/player/index.php?data=$hash&do=getVideo"
            
            val headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to "https://nnmo0oi1.com",
                "Referer" to videoPageUrl,
                "Accept" to "*/*"
            )
            
            // Body Payload: hash={hash}&r={mov.glamov.com}
            // 주의: r 값은 비디오 페이지를 호출한 부모 페이지(glamov) 주소여야 함
            val rValue = if (originalReferer.contains("glamov")) originalReferer else "https://mov.glamov.com/"
            val params = mapOf(
                "hash" to hash,
                "r" to rValue
            )

            // 3. POST 전송
            val apiResponse = app.post(apiUrl, headers = headers, data = params).text
            println("[KotbcExtractor] API Response: $apiResponse")

            // 4. JSON 파싱 및 링크 추출
            // 응답 예: {"securedLink": "https://...m3u8?...", "videoSource": "..."}
            // 정규식으로 securedLink 우선 추출
            
            // securedLink 찾기 (.m3u8 포함)
            val securedLinkRegex = Regex("""["']securedLink["']\s*:\s*["']([^"']+)["']""")
            var videoUrl = securedLinkRegex.find(apiResponse)?.groupValues?.get(1)?.replace("\\/", "/")

            // 없으면 videoSource 찾기
            if (videoUrl == null) {
                val sourceRegex = Regex("""["']videoSource["']\s*:\s*["']([^"']+)["']""")
                videoUrl = sourceRegex.find(apiResponse)?.groupValues?.get(1)?.replace("\\/", "/")
            }

            if (videoUrl != null) {
                println("[KotbcExtractor] Found Video URL: $videoUrl")
                callback(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://nnmo0oi1.com"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

        } catch (e: Exception) {
            println("[KotbcExtractor] API extraction failed: ${e.message}")
        }
        return false
    }
}
