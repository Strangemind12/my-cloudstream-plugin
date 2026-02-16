package com.kotbc

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile

/**
 * KotbcExtractor v2.5
 * - Logic: mov.glamov -> iframe(nnmo0oi1) -> POST API (player/index.php) -> JSON -> M3U8
 * - Fix: 정적 정규식 추출 실패 시 API(AJAX) 호출 방식으로 전환
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
            } else if (!url.contains("nnmo0oi1.com")) {
                 println("[KotbcExtractor] No nnmo0oi1 iframe found, scanning current page content.")
            }

            // 3. 비디오 페이지(nnmo0oi1) 접속
            // iframe 주소라면 해당 페이지 소스를 다시 가져옴
            val videoHtml = if (targetUrl != url) {
                app.get(targetUrl, headers = mapOf("Referer" to url)).text
            } else {
                html
            }

            // 4. API를 통한 영상 추출 시도 (사용자 분석 기반)
            if (fetchVideoApi(videoHtml, targetUrl, callback)) {
                return
            }

            // 5. 실패 시 기존 M3U8 정규식 탐색 (백업)
            if (!extractM3u8Regex(videoHtml, targetUrl, callback)) {
                // 6. 최후의 수단: 내장 추출기
                println("[KotbcExtractor] All methods failed, trying loadExtractor fallback")
                loadExtractor(targetUrl, subtitleCallback = subtitleCallback, callback = callback)
            }

        } catch (e: Exception) {
            println("[KotbcExtractor] Error: ${e.message}")
            e.printStackTrace()
        }
    }

    // [v2.5 추가] API 호출을 통한 추출 함수
    private suspend fun fetchVideoApi(html: String, refererUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            println("[KotbcExtractor] Attempting API extraction...")
            
            // 1. Hash/Data 값 추출
            // 소스 코드 내에서 32자리 16진수 문자열(MD5 형태)을 찾습니다. 보통 이것이 hash 값입니다.
            val hashRegex = Regex("""['"]([a-f0-9]{32})['"]""")
            val hashMatch = hashRegex.find(html) ?: return false
            
            val hash = hashMatch.groupValues[1]
            println("[KotbcExtractor] Found hash: $hash")

            // 2. API POST 요청
            val apiUrl = "https://nnmo0oi1.com/player/index.php"
            val headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Referer" to refererUrl
            )
            
            // 사용자 발견: index.php?data=...&do=getVideo
            // 보통 POST body에 hash={hash}&do=getVideo 형태로 전송합니다.
            val params = mapOf(
                "hash" to hash,
                "do" to "getVideo"
            )

            val apiResponse = app.post(apiUrl, headers = headers, data = params).text
            println("[KotbcExtractor] API Response: $apiResponse")

            // 3. JSON 응답에서 비디오 링크 추출
            // 응답 예: {"videoSource":"https://...", "securedLink":"..."}
            // 정규식으로 URL 패턴을 찾습니다. (json 파서 없이 처리)
            val urlRegex = Regex("""(https?:\\?/\\?/[^"']+\.(m3u8|mp4|txt)[^"']*)""")
            val match = urlRegex.find(apiResponse)
            
            if (match != null) {
                // 이스케이프 문자(\/) 제거
                var videoUrl = match.groupValues[1].replace("\\/", "/")
                
                // .txt 확장자인 경우 m3u8로 간주하거나 그대로 사용 (User Report: master.txt)
                println("[KotbcExtractor] Found API Video URL: $videoUrl")

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

    private suspend fun extractM3u8Regex(html: String, refererUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        // 기존 정규식 로직 (백업용)
        val m3u8Regex = Regex("""(https?://[^"']+\.m3u8)""")
        val matches = m3u8Regex.findAll(html)
        var found = false
        
        for (match in matches) {
            val m3u8Url = match.value
            println("[KotbcExtractor] Found Regex M3U8: $m3u8Url")
            callback(
                newExtractorLink(
                    name = name,
                    source = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://nnmo0oi1.com" 
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        
        // nnmo0oi1 전용 패턴 추가 탐색
        val specialRegex = Regex("""(https://nnmo0oi1\.com/m3/[a-zA-Z0-9%\-_=]+)""")
        val specialMatches = specialRegex.findAll(html)
        for (match in specialMatches) {
             val m3u8Url = match.value
             println("[KotbcExtractor] Found Special M3U8: $m3u8Url")
             callback(
                newExtractorLink(
                    name = name,
                    source = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://nnmo0oi1.com"
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        return found
    }
}
