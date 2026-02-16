package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URI

class LinkkfExtractor {
    // 자막 도메인
    private val SUBTITLE_BASE_URL = "https://k1.sub1.top/s"

    // 결과를 반환할 데이터 클래스
    data class LinkkfResult(
        val m3u8Url: String,
        val subtitleUrl: String
    )

    suspend fun extract(url: String, referer: String): LinkkfResult? {
        println("[LinkkfExtractor] Extracting: $url")
        
        try {
            val response = app.get(url, headers = mapOf("Referer" to referer))
            val doc = response.document
            
            // 스크립트 찾기
            val scriptContent = doc.select("script").find { it.data().contains("var player_aaaa=") }?.data()

            if (scriptContent == null) {
                println("[LinkkfExtractor] Player script not found")
                return null
            }

            // JSON 파싱
            val jsonString = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\});""").find(scriptContent)?.groupValues?.get(1)
                ?: return null

            val playerData = parseJson<PlayerData>(jsonString)

            val videoKey = playerData.url ?: return null
            val actualUrl = playerData.actualUrl ?: return null

            // URL 생성 로직
            val uri = URI(actualUrl)
            val host = uri.host
            val idParam = uri.query.split("&").find { it.startsWith("id=") }?.substringAfter("id=")

            if (host != null && idParam != null) {
                val m3u8Url = "https://$host/r2/cache/$idParam-$videoKey.m3u8"
                val subUrl = "$SUBTITLE_BASE_URL/$videoKey.vtt"

                println("[LinkkfExtractor] Found: $m3u8Url")
                return LinkkfResult(m3u8Url, subUrl)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            println("[LinkkfExtractor] Error: ${e.message}")
        }

        return null
    }

    private data class PlayerData(
        @JsonProperty("url") val url: String?,
        @JsonProperty("actual_url") val actualUrl: String?
    )
}
