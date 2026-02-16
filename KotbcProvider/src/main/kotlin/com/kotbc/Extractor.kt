package com.kotbc

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile

/**
 * KotbcExtractor v1.5
 * - Fix: newExtractorLink 문법 수정 (Builder 패턴 사용)
 * - ExtractorApi 상속 유지
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
            val response = app.get(url, headers = mapOf("Referer" to "https://m135.kotbc2.com"))
            val html = response.text
            
            // https://nnmo0oi1.com/m3/... 구조의 링크 추출
            val regex = Regex("""(https://nnmo0oi1\.com/m3/[a-zA-Z0-9%\-_=]+)""")
            
            val match = regex.find(html)
            if (match != null) {
                val m3u8Url = match.value
                println("[KotbcExtractor] Found M3U8 URL: $m3u8Url")
                
                // [수정] newExtractorLink 파라미터 및 빌더 패턴 적용
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://nnmo0oi1.com"
                        this.quality = Qualities.Unknown.value
                    }
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
