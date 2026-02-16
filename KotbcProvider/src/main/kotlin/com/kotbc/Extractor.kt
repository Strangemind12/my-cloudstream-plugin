package com.kotbc

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * KotbcExtractor v2.8
 * - Fix: securedLink 우선 추출 (MD5/Expires 파라미터 포함된 진짜 링크)
 * - Fix: Hash 추출 패턴 강화 (모든 32자리 Hex 검색)
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

            // 4. API를 통한 영상 추출
            // originalReferer는 r값 생성을 위해 필요 (glamov 주소)
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
        originalReferer: String, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("[KotbcExtractor] Attempting API extraction...")
            
            // 1. Hash 추출 (32자리 hex)
            // HTML 내에 있는 모든 32자리 소문자 Hex 값을 찾아서 시도해봄 (가장 확실한 방법)
            val allHexPattern = Regex("""['"]([a-f0-9]{32})['"]""")
            val matches = allHexPattern.findAll(html).map { it.groupValues[1] }.toSet()
            
            if (matches.isEmpty()) {
                println("[KotbcExtractor] No hash found in HTML")
                return false
            }

            // r 값 설정 (mov.glamov.com)
            val rValue = if (originalReferer.contains("glamov")) originalReferer else "https://mov.glamov.com/"

            // 2. 각 Hash 후보에 대해 API 요청 시도
            for (hash in matches) {
                println("[KotbcExtractor] Trying hash: $hash")
                
                val apiUrl = "https://nnmo0oi1.com/player/index.php?data=$hash&do=getVideo"
                
                val headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Origin" to "https://nnmo0oi1.com",
                    "Referer" to videoPageUrl,
                    "Accept" to "*/*"
                )
                
                val params = mapOf(
                    "hash" to hash,
                    "r" to rValue
                )

                val apiResponse = app.post(apiUrl, headers = headers, data = params).text
                
                // 성공적인 응답인지 확인 (securedLink가 있어야 함)
                if (apiResponse.contains("securedLink")) {
                    println("[KotbcExtractor] API Success with hash: $hash")
                    println("[KotbcExtractor] API Response: $apiResponse")
                    
                    // 3. JSON 파싱 및 링크 추출
                    // securedLink 우선 추출
                    val securedLinkRegex = Regex("""["']securedLink["']\s*:\s*["']([^"']+)["']""")
                    var videoUrl = securedLinkRegex.find(apiResponse)?.groupValues?.get(1)?.replace("\\/", "/")

                    if (videoUrl != null) {
                        println("[KotbcExtractor] Found Secured Video URL: $videoUrl")
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
                }
            }

        } catch (e: Exception) {
            println("[KotbcExtractor] API extraction failed: ${e.message}")
        }
        return false
    }
}
