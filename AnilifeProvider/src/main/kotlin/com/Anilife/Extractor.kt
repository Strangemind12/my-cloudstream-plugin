package com.anilife

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

/**
 * Extractor v5.1
 * - [Fix] 영상 링크 찾기 실패 해결
 * - 특정 변수명(location.href) 대신, URL 패턴(p=...&player=...) 자체를 검색하여 신뢰도 향상
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
            
            // [v5.1 수정] URL 패턴을 더 유연하게 검색
            // 예: h/live?p=...&player=... 형태를 찾음 (따옴표나 공백 무시)
            // p 파라미터값과 player 파라미터값을 캡처
            val regex = Regex("""h\/live\?p=([a-zA-Z0-9\-]+)&player=([a-zA-Z0-9]+)""")
            val match = regex.find(rawHtml)
            
            var targetUrl: String? = null
            
            if (match != null) {
                // 전체 매칭된 문자열 사용 (예: h/live?p=...&player=...)
                val relativeUrl = match.value
                targetUrl = "https://anilife.live/$relativeUrl"
                println("$TAG [Extract] Found Redirect URL (Pattern): $targetUrl")
            } else {
                // 백업: location.href 검색
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
                println("$TAG [Extract] No redirect found. Checking for _aldata directly.")
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
