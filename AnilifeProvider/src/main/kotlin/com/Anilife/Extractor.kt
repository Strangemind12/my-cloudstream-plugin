package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.app

/**
 * Extractor v17.0
 * - [Fix] 빌드 에러 원인인 WebViewResolver 호출 코드를 Anilife.kt로 이동
 * - 오직 HTML 파싱을 통한 URL 추출 역할만 수행
 */
class AnilifeExtractor {
    private val TAG = "[AnilifeExtractor]"
    private val mainUrl = "https://anilife.live"

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "https://anilife.live/"
    )

    suspend fun getRawPlayerUrl(data: String): String? {
        println("$TAG [Parser] HTML 분석 시작...")
        try {
            // 1. 파라미터 및 Referer 분석
            var cleanData = data.substringBefore("?poster=")
            var detailReferer = "$mainUrl/"

            if (cleanData.contains("ref=")) {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            }

            // 2. Provider 페이지 정적 로드
            val response = app.get(cleanData, headers = mapOf("Referer" to detailReferer))
            val html = response.text
            println("$TAG [Parser] HTML 로드 완료 (길이: ${html.length})")

            // 3. 자바스크립트 내 플레이어 URL 추출
            val regex = Regex("""["']([^"']*\/?h\/live\?p=[^"']+)["']""")
            val match = regex.find(html)
            var playerUrl = match?.groupValues?.get(1)

            if (playerUrl != null) {
                if (!playerUrl.startsWith("http")) {
                    playerUrl = if (playerUrl.startsWith("/")) "$mainUrl$playerUrl" else "$mainUrl/$playerUrl"
                }
                return playerUrl.replace("\\/", "/")
            }
        } catch (e: Exception) {
            println("$TAG [Parser] 예외 발생: ${e.message}")
        }
        return null
    }
}
