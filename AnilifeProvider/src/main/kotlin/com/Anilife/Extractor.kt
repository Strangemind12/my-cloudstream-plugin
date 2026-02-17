package com.anilife

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.getQualityFromName

/**
 * Extractor v8.0
 * - [Fix] WebViewResolver 생성자 에러 수정: headers, referer 파라미터 제거
 * - [Fix] 헤더는 app.get()에 전달하여 적용
 */
class AnilifeExtractor {
    private val TAG = "[AnilifeExtractor]"

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "https://anilife.live/"
    )

    suspend fun extract(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // 1. Provider 페이지 로드 (HTML 텍스트 가져오기)
            val response = app.get(url, headers = commonHeaders)
            val html = response.text

            // 2. 실제 플레이어 주소 파싱
            // 자바스크립트 내의 "https://anilife.live/h/live?p=...&player=..." 패턴 추출
            val regex = Regex("""https://anilife\.live/h/live\?p=[^"']+(?:&player=[^"']+)*""")
            val match = regex.find(html)
            val playerUrl = match?.value

            if (playerUrl != null) {
                println("$TAG [LoadLinks] Found Player URL: $playerUrl")
                
                // 3. WebViewResolver 실행
                // [수정] 생성자에는 Regex만 전달 (headers, referer 파라미터 없음)
                val webViewInterceptor = WebViewResolver(
                    Regex("""\.m3u8""")
                )
                
                // [수정] 헤더는 app.get에 직접 전달해야 함
                val webViewResponse = app.get(
                    playerUrl, 
                    headers = commonHeaders, 
                    interceptor = webViewInterceptor
                )
                
                val sniffedUrl = webViewResponse.url
                println("$TAG [WebView] Sniffed URL: $sniffedUrl")

                if (sniffedUrl.contains(".m3u8")) {
                     callback.invoke(
                        ExtractorLink(
                            source = "Anilife",
                            name = "Anilife",
                            url = sniffedUrl,
                            referer = "https://anilife.live/",
                            quality = getQualityFromName("HD"),
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    return true
                }
            } else {
                println("$TAG [LoadLinks] Failed to find player URL in HTML.")
            }
        } catch (e: Exception) {
            println("$TAG [LoadLinks] Error: ${e.message}")
            e.printStackTrace()
        }
        return false
    }
}
