// v2.1 - Clean Headers (Removed Cookie & ssid) to match Github logic & prevent CDN 404 in ExoPlayer
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
        println("[Anilife][Extractor] v2.1 - Clean Headers & Direct API Parsing 시작")
        
        // [핵심 수정] Github 로직(mod_anilife.py / anilife.go)과 100% 동일하게 맞춥니다.
        // 기존에는 ExtractorLink에 Cookie와 x-user-ssid를 포함시켰으나, 
        // ExoPlayer가 세그먼트/키를 요청할 때 CDN에서 비정상 헤더로 간주하여 404를 뱉는 원인이 되므로 완벽히 제거합니다.
        val cleanHeaders = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://anilife.live/",
            "Origin" to "https://anilife.live",
            "Accept" to "*/*"
        )

        try {
            var finalM3u8 = m3u8Url

            // 1. Anilife.kt에서 넘어온 URL이 API 주소 형태라면 직접 JSON 파싱하여 M3U8 도출
            if (finalM3u8.contains("/m3u8/st/")) {
                println("[Anilife][Extractor] API URL 감지됨, 직접 파싱 시도: $finalM3u8")
                val apiRes = app.get(finalM3u8, headers = cleanHeaders).text
                val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
                val extractedUrl = urlRegex.find(apiRes)?.groupValues?.get(1)
                if (!extractedUrl.isNullOrBlank()) {
                    finalM3u8 = extractedUrl.replace("\\/", "/")
                    println("[Anilife][Extractor] API에서 찐 M3U8 추출 완료: $finalM3u8")
                }
            }

            // 2. 혹시라도 파싱이 안 되었을 때를 대비한 _aldata 백업 추출
            if (!finalM3u8.contains("master.m3u8") && !finalM3u8.contains("manifest")) {
                println("[Anilife][Extractor] 백업: _aldata 추출 시도")
                try {
                    val playerHtml = app.get(playerUrl, headers = cleanHeaders).text
                    val aldataRegex = Regex("""_aldata\s*=\s*['"]([^'"]+)['"]""")
                    val match = aldataRegex.find(playerHtml)
                    
                    if (match != null) {
                        val b64 = match.groupValues[1]
                        val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                        val vid1080Regex = Regex(""""vid_url_1080"\s*:\s*"([^"]+)"""")
                        val vid720Regex = Regex(""""vid_url_720"\s*:\s*"([^"]+)"""")
                        
                        var apiUrl = vid1080Regex.find(decoded)?.groupValues?.get(1)
                        if (apiUrl.isNullOrBlank() || apiUrl == "none") {
                            apiUrl = vid720Regex.find(decoded)?.groupValues?.get(1)
                        }
                        
                        if (!apiUrl.isNullOrBlank() && apiUrl != "none") {
                            val fullApiUrl = if (apiUrl.startsWith("http")) apiUrl else "https://$apiUrl"
                            val apiRes = app.get(fullApiUrl, headers = cleanHeaders).text
                            val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
                            val extractedUrl = urlRegex.find(apiRes)?.groupValues?.get(1)
                            if (!extractedUrl.isNullOrBlank()) {
                                finalM3u8 = extractedUrl.replace("\\/", "/")
                                println("[Anilife][Extractor] _aldata에서 찐 M3U8 추출 완료: $finalM3u8")
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("[Anilife][Extractor] _aldata 백업 추출 실패 (CF 차단 등): ${e.message}")
                }
            }

            println("[Anilife][Extractor] 최종 M3U8 전달: $finalM3u8")
            
            // 3. 군더더기 없는 깔끔한 헤더와 함께 ExoPlayer로 전달 (404 에러 방지)
            callback(newExtractorLink(name, name, finalM3u8, ExtractorLinkType.M3U8) {
                this.referer = "https://anilife.live/"
                this.headers = cleanHeaders
            })
            
            return true

        } catch (e: Exception) {
            e.printStackTrace()
            println("[Anilife][Extractor] 에러 발생: ${e.message}")
            return false
        }
    }
}
