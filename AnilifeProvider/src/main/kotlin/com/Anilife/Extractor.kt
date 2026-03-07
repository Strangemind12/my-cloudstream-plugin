// v2.0 - Github aldata extraction logic applied. WebView and ProxyWebServer completely removed.
package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AnilifeProxyExtractor : ExtractorApi() {
    override val name = "AnilifeExtractor"
    override val mainUrl = "https://api.gcdn.app"
    override val requiresReferer = false

    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) { }

    fun extractPlayerUrl(html: String, domain: String): String? {
        val patterns = listOf(
            Regex("""location\.href\s*=\s*["']([^"']+)["']"""),
            Regex("""["']([^"']*h\/live\?p=[^"']+)["']""")
        )
        for (regex in patterns) {
            regex.find(html)?.let {
                var url = it.groupValues[1]
                if (url.contains("h/live") && url.contains("p=")) {
                    if (!url.startsWith("http")) url = if (url.startsWith("/")) "$domain$url" else "$domain/$url"
                    return url.replace("\\/", "/")
                }
            }
        }
        return null
    }

    suspend fun extractWithProxy(
        m3u8Url: String,
        playerUrl: String,
        referer: String,
        ssid: String?,
        cookies: String,
        targetKeyUrl: String? = null,
        videoId: String = "unknown_id",
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[Anilife][Extractor] Github 브랜치 로직 적용 - WebView/Proxy 완전 제거")
        
        val headers = mutableMapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://anilife.live/",
            "Origin" to "https://anilife.live",
            "Cookie" to cookies,
            "Accept" to "*/*"
        )
        if (!ssid.isNullOrBlank()) {
            headers["x-user-ssid"] = ssid
            headers["X-User-Ssid"] = ssid
        }

        try {
            // 1. 플레이어 페이지 HTML 요청
            val playerHtml = app.get(playerUrl, headers = headers).text
            
            // 2. _aldata Base64 추출 (Github Python/Go 로직 이식)
            val aldataRegex = Regex("""_aldata\s*=\s*['"]([A-Za-z0-9+/=]+)['"]""")
            val match = aldataRegex.find(playerHtml)
            
            var finalM3u8 = m3u8Url

            if (match != null) {
                val b64 = match.groupValues[1]
                val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                println("[Anilife][Extractor] _aldata 디코딩 성공: $decoded")
                
                // 3. 디코딩된 JSON 텍스트에서 vid_url_1080 (또는 720) 추출
                val vid1080Regex = Regex(""""vid_url_1080"\s*:\s*"([^"]+)"""")
                val vid720Regex = Regex(""""vid_url_720"\s*:\s*"([^"]+)"""")
                
                var apiUrl = vid1080Regex.find(decoded)?.groupValues?.get(1)
                if (apiUrl.isNullOrBlank() || apiUrl == "none") {
                    apiUrl = vid720Regex.find(decoded)?.groupValues?.get(1)
                }
                
                if (!apiUrl.isNullOrBlank() && apiUrl != "none") {
                    val fullApiUrl = if (apiUrl.startsWith("http")) apiUrl else "https://$apiUrl"
                    println("[Anilife][Extractor] API URL 확보: $fullApiUrl")
                    
                    // 4. API 호출하여 실제 찐 M3U8 주소가 담긴 JSON 배열 획득
                    val apiRes = app.get(fullApiUrl, headers = headers).text
                    println("[Anilife][Extractor] API 응답: $apiRes")
                    
                    val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
                    val extractedUrl = urlRegex.find(apiRes)?.groupValues?.get(1)
                    
                    if (!extractedUrl.isNullOrBlank()) {
                        finalM3u8 = extractedUrl.replace("\\/", "/")
                        println("[Anilife][Extractor] 찐 M3U8 URL 추출 완료: $finalM3u8")
                    }
                }
            } else {
                println("[Anilife][Extractor] _aldata 추출 실패, 폴백 M3U8 사용")
            }

            // 5. 무거운 로컬 프록시 서버 없이 다이렉트로 ExtractorLink 반환
            // ExoPlayer에 이 주소와 헤더만 넘겨주면, 암호화 키 요청 등은 알아서 네이티브로 처리합니다.
            callback(newExtractorLink(name, name, finalM3u8, ExtractorLinkType.M3U8) {
                this.referer = "https://anilife.live/"
                this.headers = headers
            })
            
            return true

        } catch (e: Exception) {
            e.printStackTrace()
            println("[Anilife][Extractor] 로직 에러: ${e.message}")
            return false
        }
    }
}
