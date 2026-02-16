package com.kotbc

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile

/**
 * KotbcExtractor v1.4
 * - Structure Change: ExtractorApi 상속 (MainAPI 상속 대체 가능)
 * - Deprecation Fix: newExtractorLink 사용
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
        println("[KotbcExtractor] Fetching URL: $url")
        try {
            // 중간 페이지(glamov) 요청
            // referer는 Kotbc 메인 페이지에서 넘어왔으므로 인자로 받은 것을 사용하거나 mainUrl 사용
            val response = app.get(url, headers = mapOf("Referer" to "https://m135.kotbc2.com"))
            val html = response.text
            
            // https://nnmo0oi1.com/m3/... 구조의 링크 추출
            val regex = Regex("""(https://nnmo0oi1\.com/m3/[a-zA-Z0-9%\-_=]+)""")
            
            val match = regex.find(html)
            if (match != null) {
                val m3u8Url = match.value
                println("[KotbcExtractor] Found M3U8 URL: $m3u8Url")
                
                // [변경] 생성자 대신 newExtractorLink 사용
                callback(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = m3u8Url,
                        referer = "https://nnmo0oi1.com",
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            } else {
                println("[KotbcExtractor] M3U8 URL pattern not found in $url")
            }

        } catch (e: Exception) {
            println("[KotbcExtractor] Error: ${e.message}")
            e.printStackTrace()
        }
    }
}
