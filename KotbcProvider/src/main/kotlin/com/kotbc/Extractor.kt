package com.kotbc

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile

/**
 * KotbcExtractor v2.4
 * - Fix: nnmo0oi1.com 전용 정규식(/m3/...) 복구 및 강화
 * - Feat: 정규식 실패 시 loadExtractor(내장 추출기) 백업 실행
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
            // <iframe src="https://nnmo0oi1.com/video/..." ...>
            val iframeRegex = Regex("""src=["'](https://nnmo0oi1\.com/video/[^"']+)["']""")
            val iframeMatch = iframeRegex.find(html)
            
            var targetUrl = url
            
            if (iframeMatch != null) {
                targetUrl = iframeMatch.groupValues[1]
                println("[KotbcExtractor] Found video iframe: $targetUrl")
            } else if (!url.contains("nnmo0oi1.com")) {
                 println("[KotbcExtractor] No nnmo0oi1 iframe found, scanning current page content.")
            }

            // 3. 비디오 페이지(nnmo0oi1) 내용 가져오기
            val videoHtml = if (targetUrl != url) {
                app.get(targetUrl, headers = mapOf("Referer" to url)).text
            } else {
                html
            }

            // 4. M3U8 링크 추출 시도
            if (!extractM3u8(videoHtml, targetUrl, callback)) {
                // 5. 실패 시 내장 추출기(loadExtractor) 시도 (JwPlayer 등 자동 탐지)
                println("[KotbcExtractor] Regex failed, trying loadExtractor fallback for $targetUrl")
                loadExtractor(targetUrl, subtitleCallback = subtitleCallback, callback = callback)
            }

        } catch (e: Exception) {
            println("[KotbcExtractor] Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun extractM3u8(html: String, refererUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false

        // Pattern A: nnmo0oi1 전용 패턴 (/m3/...) - .m3u8로 끝나지 않을 수 있음
        // 예: https://nnmo0oi1.com/m3/YlBJMFpt...
        val specificRegex = Regex("""(https://nnmo0oi1\.com/m3/[a-zA-Z0-9%\-_=]+)""")
        
        // Pattern B: 일반적인 .m3u8 패턴
        val genericRegex = Regex("""(https?://[^"']+\.m3u8)""")

        val matchesA = specificRegex.findAll(html)
        val matchesB = genericRegex.findAll(html)
        
        // 두 패턴 합치기
        val allMatches = matchesA + matchesB

        for (match in allMatches) {
            val m3u8Url = match.value
            println("[KotbcExtractor] Found Link: $m3u8Url")
            
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

        // Pattern C: JSON 내의 "file": "..." 패턴 (이스케이프 문자 고려 안함, 단순 매칭)
        if (!found) {
            val fileRegex = Regex("""["']file["']\s*:\s*["']([^"']+)["']""")
            val fileMatch = fileRegex.find(html)
            if (fileMatch != null) {
                val link = fileMatch.groupValues[1]
                if (link.contains("m3u8") || link.contains("/m3/")) {
                    println("[KotbcExtractor] Found JSON file link: $link")
                    callback(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = link,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://nnmo0oi1.com"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                }
            }
        }

        if (!found) {
            println("[KotbcExtractor] No M3U8 found via Regex in content")
        }
        
        return found
    }
}
