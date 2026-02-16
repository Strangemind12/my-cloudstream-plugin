package com.kotbc

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile

/**
 * KotbcExtractor v2.3
 * - Update: import utils.* 적용
 * - Logic: mov.glamov -> iframe(nnmo0oi1) -> m3u8 regex
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
                app.get(targetUrl, headers = mapOf("Referer" to url)).text
            } else {
                html
            }

            // 4. M3U8 링크 추출
            extractM3u8(videoHtml, targetUrl, callback)

        } catch (e: Exception) {
            println("[KotbcExtractor] Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun extractM3u8(html: String, refererUrl: String, callback: (ExtractorLink) -> Unit) {
        // .m3u8 링크 찾기
        val m3u8Regex = Regex("""(https?://[^"']+\.m3u8)""")
        val matches = m3u8Regex.findAll(html)
        
        for (match in matches) {
            val m3u8Url = match.value
            println("[KotbcExtractor] Found M3U8: $m3u8Url")
            
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
            return
        }
        println("[KotbcExtractor] No M3U8 found in content")
    }
}
