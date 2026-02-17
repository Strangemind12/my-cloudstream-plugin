package com.anilife

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

/**
 * Extractor v5.0
 * - [Fix] 리다이렉트 URL 탐색 로직 강화
 * - location.href 특정 패턴 대신, HTML 전체에서 'https://anilife.live/h/live' 링크 검색
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

            // 2. 플레이어 선택 페이지인지 확인 (HTML 전체 검색)
            val rawHtml = doc.html()
            
            // [v5.0 수정] location.href 문법에 의존하지 않고, 실제 이동해야 할 URL 패턴을 직접 찾음
            // 패턴: https://anilife.live/h/live... 로 시작하는 URL
            val urlRegex = Regex("""https:\\/\\/anilife\.live\\/h\\/live[^"']+""")
            val match = urlRegex.find(rawHtml)
            
            var targetUrl: String? = null
            
            if (match != null) {
                targetUrl = match.value
                // 이스케이프된 슬래시(\/) 제거
                targetUrl = targetUrl.replace("\\/", "/")
                println("$TAG [Extract] Found Redirect URL (Regex): $targetUrl")
            } else {
                 // 혹시 모르니 기존 방식(script)으로도 한 번 더 체크
                 val scriptMatch = Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(rawHtml)
                 if (scriptMatch != null) {
                     targetUrl = scriptMatch.groupValues[1]
                     println("$TAG [Extract] Found Redirect URL (Script): $targetUrl")
                 }
            }

            if (!targetUrl.isNullOrEmpty()) {
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
                    val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val jsonString = String(decodedBytes)

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
            }

        } catch (e: Exception) {
            println("$TAG [Error] Critical Exception: ${e.message}")
            e.printStackTrace()
        }
        return false
    }
}
