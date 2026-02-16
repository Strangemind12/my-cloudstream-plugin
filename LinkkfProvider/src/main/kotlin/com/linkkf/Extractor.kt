package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URI

class LinkkfExtractor {
    // v1.10: 구형 주소 처리를 위한 needsWebView 플래그 추가
    private val SUBTITLE_BASE_URL = "https://k1.sub1.top/s"

    data class LinkkfResult(
        val m3u8Url: String,
        val subtitleUrl: String,
        val needsWebView: Boolean = false // 기본값 false
    )

    suspend fun extract(url: String, referer: String): LinkkfResult? {
        println("[LinkkfExtractor] Extracting 시작: $url")
        
        try {
            val response = app.get(url, headers = mapOf("Referer" to referer))
            val doc = response.document
            
            val scriptTag = doc.select("script").find { 
                it.data().contains("var player_") && it.data().contains("actual_url") 
            }
            
            val scriptContent = scriptTag?.data() ?: run {
                println("[LinkkfExtractor] 실패: 플레이어 스크립트 미발견")
                return null
            }

            // JSON 추출 (v1.8 로직 유지 - 안전한 추출)
            val startIndex = scriptContent.indexOf("{")
            val endIndex = scriptContent.lastIndexOf("}")

            if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) {
                println("[LinkkfExtractor] 실패: JSON 괄호 쌍 오류")
                return null
            }

            val jsonString = scriptContent.substring(startIndex, endIndex + 1)
            
            val playerData = try {
                parseJson<PlayerData>(jsonString)
            } catch (e: Exception) {
                println("[LinkkfExtractor] 실패: JSON 파싱 에러")
                return null
            }

            val videoKey = playerData.url
            val actualUrl = playerData.actualUrl

            if (actualUrl.isNullOrEmpty()) {
                println("[LinkkfExtractor] 실패: actual_url 누락")
                return null
            }

            val subUrl = if (videoKey != null) "$SUBTITLE_BASE_URL/$videoKey.vtt" else ""

            // --- [v1.10] URL 분석 및 분기 ---
            
            // Case 1: 완제품 M3U8 (직접 사용 가능)
            if (actualUrl.contains(".m3u8")) {
                println("[LinkkfExtractor] 완제품 URL: $actualUrl")
                return LinkkfResult(actualUrl, subUrl, needsWebView = false)
            }

            // 파라미터 분석
            val uri = URI(actualUrl)
            val host = uri.host ?: return null
            val idParam = uri.query?.split("&")?.find { it.startsWith("id=") }?.substringAfter("id=")
            
            // Case 2: 신형 (ID가 'n'으로 시작) -> 직접 조립 가능 (Cache 방식)
            if (idParam != null && idParam.startsWith("n") && !videoKey.isNullOrEmpty()) {
                val m3u8Url = "https://$host/r2/cache/$idParam-$videoKey.m3u8"
                println("[LinkkfExtractor] 신형(Cache) URL 조립: $m3u8Url")
                return LinkkfResult(m3u8Url, subUrl, needsWebView = false)
            }

            // Case 3: 구형 (ID가 'n'이 아님, 예: pp4) -> WebView로 스니핑 필요
            // 이때 m3u8Url 필드에는 actual_url(플레이어 페이지 주소)을 담아 보냅니다.
            println("[LinkkfExtractor] 구형 플레이어 감지. WebView 스니핑 요청. Page: $actualUrl")
            return LinkkfResult(actualUrl, subUrl, needsWebView = true)

        } catch (e: Exception) {
            println("[LinkkfExtractor] 에러: ${e.message}")
            e.printStackTrace()
        }

        return null
    }

    private data class PlayerData(
        @JsonProperty("url") val url: String?,
        @JsonProperty("actual_url") val actualUrl: String?
    )
}
