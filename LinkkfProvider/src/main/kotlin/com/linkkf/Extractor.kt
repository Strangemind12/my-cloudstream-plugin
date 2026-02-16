package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URI

class LinkkfExtractor {
    // v1.6: 구형 URL(index.m3u8) 및 신형 URL(cache) 모두 지원하도록 개선
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
            
            // 스크립트 찾기
            val scriptTag = doc.select("script").find { 
                it.data().contains("var player_") && it.data().contains("actual_url") 
            }
            
            val scriptContent = scriptTag?.data()

            if (scriptContent == null) {
                println("[LinkkfExtractor] 실패: 플레이어 스크립트 미발견")
                return null
            }

            println("[LinkkfExtractor] 스크립트 발견 (길이: ${scriptContent.length})")

            // JSON 추출 (세미콜론까지 읽도록 하여 중첩 객체 문제 해결)
            val regex = Regex("""var\s+player_[a-zA-Z0-9_]+\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(scriptContent)
            
            if (match == null) {
                println("[LinkkfExtractor] 실패: JSON 데이터 정규식 매칭 실패")
                println("[LinkkfExtractor] Script Dump: ${scriptContent.take(500)} ...")
                return null
            }

            val jsonString = match.groupValues[1]
            println("[LinkkfExtractor] JSON 문자열 추출 성공")

            val playerData = try {
                parseJson<PlayerData>(jsonString)
            } catch (e: Exception) {
                println("[LinkkfExtractor] 실패: JSON 파싱 에러 - ${e.message}")
                return null
            }

            val videoKey = playerData.url
            val actualUrl = playerData.actualUrl

            if (actualUrl.isNullOrEmpty()) {
                println("[LinkkfExtractor] 실패: actual_url 누락")
                return null
            }

            // --- [v1.6 핵심] URL 생성 로직 다변화 ---
            
            // Case 1: actual_url 자체가 이미 .m3u8 주소인 경우 (가장 우선)
            if (actualUrl.contains(".m3u8")) {
                println("[LinkkfExtractor] 완제품 M3U8 발견: $actualUrl")
                val subUrl = if (videoKey != null) "$SUBTITLE_BASE_URL/$videoKey.vtt" else ""
                return LinkkfResult(actualUrl, subUrl)
            }

            // 파라미터 및 호스트 분석
            val uri = URI(actualUrl)
            val host = uri.host ?: return null
            val idParam = uri.query?.split("&")?.find { it.startsWith("id=") }?.substringAfter("id=")
            
            // Case 2: 'id' 파라미터가 있는 신형 구조 (예: /r2/cache/n20-key.m3u8)
            if (idParam != null && !videoKey.isNullOrEmpty()) {
                val m3u8Url = "https://$host/r2/cache/$idParam-$videoKey.m3u8"
                val subUrl = "$SUBTITLE_BASE_URL/$videoKey.vtt"
                println("[LinkkfExtractor] 신형 URL 생성: $m3u8Url")
                return LinkkfResult(m3u8Url, subUrl)
            }

            // Case 3: 'id' 파라미터가 없는 구형 구조 (예: /key/index.m3u8)
            if (!videoKey.isNullOrEmpty()) {
                val m3u8Url = "https://$host/$videoKey/index.m3u8"
                val subUrl = "$SUBTITLE_BASE_URL/$videoKey.vtt"
                println("[LinkkfExtractor] 구형 URL 생성: $m3u8Url")
                return LinkkfResult(m3u8Url, subUrl)
            }

            println("[LinkkfExtractor] 실패: URL 생성 조건을 만족하지 못함 (Key: $videoKey, ID: $idParam)")

        } catch (e: Exception) {
            println("[LinkkfExtractor] 치명적 에러: ${e.message}")
            e.printStackTrace()
        }

        return null
    }

    private data class PlayerData(
        @JsonProperty("url") val url: String?,
        @JsonProperty("actual_url") val actualUrl: String?
    )
}
