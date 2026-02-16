package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URI

class LinkkfExtractor {
    // v1.9: ID 패턴('n' vs 그외)에 따른 URL 생성 분기 처리 추가
    private val SUBTITLE_BASE_URL = "https://k1.sub1.top/s"

    data class LinkkfResult(
        val m3u8Url: String,
        val subtitleUrl: String
    )

    suspend fun extract(url: String, referer: String): LinkkfResult? {
        println("[LinkkfExtractor] Extracting 시작: $url")
        
        try {
            val response = app.get(url, headers = mapOf("Referer" to referer))
            val doc = response.document
            
            val scriptTag = doc.select("script").find { 
                it.data().contains("var player_") && it.data().contains("actual_url") 
            }
            
            val scriptContent = scriptTag?.data()

            if (scriptContent == null) {
                println("[LinkkfExtractor] 실패: 플레이어 스크립트 미발견")
                return null
            }

            // JSON 추출 (v1.8 로직 유지)
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

            // --- [v1.9 핵심] URL 생성 로직 세분화 ---
            
            // Case 1: 완제품
            if (actualUrl.contains(".m3u8")) {
                val subUrl = if (videoKey != null) "$SUBTITLE_BASE_URL/$videoKey.vtt" else ""
                println("[LinkkfExtractor] 완제품 URL: $actualUrl")
                return LinkkfResult(actualUrl, subUrl)
            }

            val uri = URI(actualUrl)
            val host = uri.host ?: return null
            val idParam = uri.query?.split("&")?.find { it.startsWith("id=") }?.substringAfter("id=")
            
            // Case 2: 신형 (ID가 'n'으로 시작하는 경우, 예: n20) -> Cache 방식
            if (idParam != null && idParam.startsWith("n") && !videoKey.isNullOrEmpty()) {
                val m3u8Url = "https://$host/r2/cache/$idParam-$videoKey.m3u8"
                val subUrl = "$SUBTITLE_BASE_URL/$videoKey.vtt"
                println("[LinkkfExtractor] 신형(Cache) URL 생성: $m3u8Url")
                return LinkkfResult(m3u8Url, subUrl)
            }

            // Case 3: 구형 (ID가 없거나, 'pp4' 등 다른 문자인 경우) -> Index 방식
            // 예: https://play.sub3.top/48343m1/index.m3u8
            // (Cloudstream이 302 리다이렉트를 자동으로 따라가서 pp4.ssku1x.top으로 연결됩니다)
            if (!videoKey.isNullOrEmpty()) {
                val m3u8Url = "https://$host/$videoKey/index.m3u8"
                val subUrl = "$SUBTITLE_BASE_URL/$videoKey.vtt"
                println("[LinkkfExtractor] 구형(Index) URL 생성: $m3u8Url")
                return LinkkfResult(m3u8Url, subUrl)
            }

            println("[LinkkfExtractor] 실패: URL 생성 불가")

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
