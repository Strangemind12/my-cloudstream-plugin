package com.kotbc

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities

/**
 * KotbcExtractor v1.1
 * - mov.glamov.com 등의 중간 페이지에서 nnmo0oi1.com M3U8 링크 추출
 */
object KotbcExtractor {
    
    suspend fun fetch(
        url: String, 
        referer: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        println("[KotbcExtractor] Fetching URL: $url")
        try {
            // 중간 페이지(glamov) 요청
            val response = app.get(url, headers = mapOf("Referer" to referer))
            val html = response.text
            
            // 요청사항: https://nnmo0oi1.com/m3/... 구조의 링크 추출
            // 정규식 설명: https://nnmo0oi1.com/m3/ 뒤에 알파벳, 숫자, %, -, _, = 등이 오는 패턴
            val regex = Regex("""(https://nnmo0oi1\.com/m3/[a-zA-Z0-9%\-_=]+)""")
            
            val match = regex.find(html)
            if (match != null) {
                val m3u8Url = match.value
                println("[KotbcExtractor] Found M3U8 URL: $m3u8Url")
                
                callback(
                    ExtractorLink(
                        source = "Kotbc",
                        name = "Kotbc",
                        url = m3u8Url,
                        referer = "https://nnmo0oi1.com", // 스트리밍 시 필요한 리퍼러
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            } else {
                println("[KotbcExtractor] M3U8 URL pattern not found in $url")
                // 디버깅용: html 일부 출력
                // println("[KotbcExtractor] HTML dump: ${html.take(500)}")
            }

        } catch (e: Exception) {
            println("[KotbcExtractor] Error: ${e.message}")
            e.printStackTrace()
        }
    }
}
