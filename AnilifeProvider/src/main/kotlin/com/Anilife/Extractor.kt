package com.anilife

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

/**
 * Extractor v4.0
 * - [Fix] "플레이어 선택 페이지" 리다이렉트 Regex 완화
 * - function moveX() { location.href = ... } 패턴 전체 검색 후 유효 URL 선택
 */
class AnilifeExtractor {
    private val TAG = "[AnilifeExtractor]"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "https://anilife.live/"
    )

    data class AlData(
        @JsonProperty("vid_url_1080") val vidUrl1080: String?,
        @JsonProperty("vid_url_720") val vidUrl720: String?,
        @JsonProperty("vid_url_480") val vidUrl480: String?
    )

    suspend fun extract(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            var currentUrl = url
            println("$TAG [Extract] Step 1: Visiting $currentUrl")
            
            // 1. 페이지 로드
            var doc = app.get(currentUrl, headers = headers).document

            // 2. 플레이어 선택 페이지인지 확인 (자바스크립트 리다이렉트 찾기)
            val scriptContent = doc.select("script").joinToString("\n") { it.data() }
            
            // Regex: location.href = "URL" (따옴표 종류 상관없이, URL 내용 관대하게)
            val redirectMatches = Regex("""location\.href\s*=\s*["']([^"']+)["']""").findAll(scriptContent)
            
            // 발견된 모든 URL 중 플레이어 URL(h/live)이나 유효한 링크 탐색
            var targetUrl: String? = null
            for (match in redirectMatches) {
                val foundUrl = match.groupValues[1]
                println("$TAG [Extract] Found potential redirect: $foundUrl")
                
                if (foundUrl.contains("h/live") || foundUrl.contains("player=")) {
                    targetUrl = foundUrl
                    break
                }
            }

            if (targetUrl != null) {
                println("$TAG [Extract] Following redirect to: $targetUrl")
                currentUrl = targetUrl
                doc = app.get(currentUrl, headers = headers).document
            } else {
                println("$TAG [Extract] No specific player redirect found. Checking current page for _aldata.")
            }

            // 3. 최종 재생 페이지에서 _aldata 추출
            val aldataMatch = Regex("""var\s+_aldata\s*=\s*['"]([^"']+)['"]""").find(doc.html())
            
            if (aldataMatch != null) {
                val base64Data = aldataMatch.groupValues[1]
                println("$TAG [Extract] Found _aldata (Length: ${base64Data.length})")

                try {
                    // Base64 Decode
                    val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val jsonString = String(decodedBytes)
                    // println("$TAG [Extract] Decoded JSON: $jsonString")

                    // JSON Parsing
                    val data = parseJson<AlData>(jsonString)
                    
                    var m3u8Url = data.vidUrl1080 ?: data.vidUrl720 ?: data.vidUrl480
                    
                    if (m3u8Url != null && m3u8Url != "none") {
                        m3u8Url = m3u8Url.replace("\\/", "/")
                        if (!m3u8Url.startsWith("http")) {
                            m3u8Url = "https://$m3u8Url"
                        }
                        
                        println("$TAG [Extract] Final M3U8 URL: $m3u8Url")

                        M3u8Helper.generateM3u8(
                            "Anilife",
                            m3u8Url,
                            "https://anilife.live/"
                        ).forEach(callback)
                        return true
                    } else {
                        println("$TAG [Error] No valid video URL found in JSON.")
                    }
                } catch (e: Exception) {
                    println("$TAG [Error] Failed to decode/parse _aldata: ${e.message}")
                }
            } else {
                println("$TAG [Error] '_aldata' variable not found.")
                // 디버깅: HTML 일부 출력 (너무 길면 자름)
                // val snippet = doc.html().take(500)
                // println("$TAG [Debug] Page Snippet: $snippet")
            }

        } catch (e: Exception) {
            println("$TAG [Error] Critical Exception: ${e.message}")
            e.printStackTrace()
        }
        return false
    }
}
