package com.anilife

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class AnilifeExtractor {
    private val TAG = "[AnilifeExtractor]"

    // 헤더 추가 (User-Agent 등)
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
            
            var doc = app.get(currentUrl, headers = headers).document

            // 1. 플레이어 선택 페이지 처리 (자바스크립트 리다이렉트 감지)
            val scriptContent = doc.select("script").joinToString("\n") { it.data() }
            
            // "location.href"를 포함하는 URL 찾기 (https://anilife.live/h/live...)
            val redirectMatch = Regex("""location\.href\s*=\s*["'](https:\/\/anilife\.live\/h\/live[^"']+)["']""").find(scriptContent)
            
            if (redirectMatch != null) {
                val nextUrl = redirectMatch.groupValues[1]
                println("$TAG [Extract] Found Redirect URL: $nextUrl")
                
                // 리다이렉트 URL로 이동
                currentUrl = nextUrl
                doc = app.get(currentUrl, headers = headers).document
            } else {
                println("$TAG [Extract] No redirect found. Assuming current page is player page.")
            }

            // 2. 최종 재생 페이지에서 _aldata 추출
            val aldataMatch = Regex("""var\s+_aldata\s*=\s*['"]([^"']+)['"]""").find(doc.html())
            
            if (aldataMatch != null) {
                val base64Data = aldataMatch.groupValues[1]
                println("$TAG [Extract] Found _aldata (Length: ${base64Data.length})")

                try {
                    // Base64 Decode
                    val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val jsonString = String(decodedBytes)
                    println("$TAG [Extract] Decoded JSON: $jsonString")

                    // JSON Parsing
                    val data = parseJson<AlData>(jsonString)
                    
                    // URL 선택 (1080 -> 720 -> 480)
                    var m3u8Url = data.vidUrl1080 ?: data.vidUrl720 ?: data.vidUrl480
                    
                    if (m3u8Url != null && m3u8Url != "none") {
                        m3u8Url = m3u8Url.replace("\\/", "/")
                        
                        if (!m3u8Url.startsWith("http")) {
                            m3u8Url = "https://$m3u8Url"
                        }
                        
                        println("$TAG [Extract] Final M3U8 URL: $m3u8Url")

                        // M3U8 Helper로 트랙 생성
                        M3u8Helper.generateM3u8(
                            "Anilife",
                            m3u8Url,
                            "https://anilife.live/" // Referer 중요
                        ).forEach(callback)
                        
                        return true
                    } else {
                        println("$TAG [Error] No valid video URL found in JSON.")
                    }
                } catch (e: Exception) {
                    println("$TAG [Error] Failed to decode/parse _aldata: ${e.message}")
                }
            } else {
                println("$TAG [Error] '_aldata' variable not found in page HTML.")
            }

        } catch (e: Exception) {
            println("$TAG [Error] Critical Exception: ${e.message}")
            e.printStackTrace()
        }
        return false
    }
}
