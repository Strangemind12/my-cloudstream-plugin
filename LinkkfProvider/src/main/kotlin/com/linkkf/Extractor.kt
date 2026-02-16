package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URI

class LinkkfExtractor {
    // v1.13: 모든 플레이어(신형/구형)를 WebViewResolver 스니핑 방식으로 통합
    private val SUBTITLE_BASE_URL = "https://k1.sub1.top/s"

    data class LinkkfResult(
        val m3u8Url: String,
        val subtitleUrl: String,
        val needsWebView: Boolean = false
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

            // JSON 추출 (안전한 방식 유지)
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

            // --- [v1.13] 통합 로직 ---
            
            // Case 1: 완제품 M3U8 (직접 사용)
            if (actualUrl.contains(".m3u8")) {
                println("[LinkkfExtractor] 완제품 URL 발견: $actualUrl")
                return LinkkfResult(actualUrl, subUrl, needsWebView = false)
            }

            // Case 2 (Unified): 그 외 모든 경우(.php 등)는 WebViewResolver로 스니핑
            // 신형(Cache)이든 구형(Index)이든 상관없이 실제 로딩되는 주소를 잡습니다.
            println("[LinkkfExtractor] WebView 스니핑 요청 (Unified). Page: $actualUrl")
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
