package com.anilife

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLDecoder

class AnilifeExtractor {
    private val TAG = "[AnilifeExtractor]"

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
            var doc = app.get(currentUrl).document

            // 1. 플레이어 선택 페이지인지 확인 (예: "원피스 1155화 플레이어 선택페이지.txt")
            // moveCloudvideo() 또는 moveJawcloud() 같은 함수가 있는지 확인
            // 혹은 location.href = "..." 패턴 검색
            val scriptContent = doc.select("script").joinToString("\n") { it.data() }
            
            // 플레이어 선택 로직이 있다면 리다이렉트 URL 추출
            val redirectMatch = Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(scriptContent)
            if (redirectMatch != null) {
                val nextUrl = redirectMatch.groupValues[1]
                println("$TAG [Redirect] Found redirect URL: $nextUrl")
                if (nextUrl.contains("http")) {
                    currentUrl = nextUrl
                    doc = app.get(currentUrl).document
                }
            }

            // 2. 최종 재생 페이지에서 _aldata 추출 (예: "원피스 1155화 플레이어 재생페이지.txt")
            // var _aldata = 'ey...'
            val aldataMatch = Regex("""var\s+_aldata\s*=\s*['"]([^"']+)['"]""").find(doc.html())
            
            if (aldataMatch != null) {
                val base64Data = aldataMatch.groupValues[1]
                println("$TAG [Extract] Found _aldata length: ${base64Data.length}")

                // Base64 Decode
                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                val jsonString = String(decodedBytes)
                
                println("$TAG [Extract] Decoded JSON: $jsonString")

                // JSON Parsing
                val data = parseJson<AlData>(jsonString)
                
                // M3U8 URL 추출 (우선순위: 1080 -> 720 -> 480)
                // JSON 안의 URL은 슬래시가 이스케이프 되어 있을 수 있음 (jackson이 처리해주지만 혹시 몰라 replace)
                var m3u8Url = data.vidUrl1080 ?: data.vidUrl720 ?: data.vidUrl480
                
                if (m3u8Url != null && m3u8Url != "none") {
                    m3u8Url = m3u8Url.replace("\\/", "/")
                    if (!m3u8Url.startsWith("http")) {
                        m3u8Url = "https://$m3u8Url"
                    }
                    
                    println("$TAG [Extract] Found M3U8: $m3u8Url")

                    // M3U8 Helper를 사용하여 퀄리티별 링크 생성
                    M3u8Helper.generateM3u8(
                        "Anilife",
                        m3u8Url,
                        "https://anilife.live/" // Referer
                    ).forEach(callback)
                    
                    return true
                } else {
                    println("$TAG [Error] No valid video URL in JSON")
                }
            } else {
                println("$TAG [Error] _aldata not found in page")
            }

        } catch (e: Exception) {
            println("$TAG [Error] Exception: ${e.message}")
            e.printStackTrace()
        }
        return false
    }
}
