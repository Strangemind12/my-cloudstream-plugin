package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import java.net.URI

class LinkkfExtractor {
    // 자막 도메인 상수
    private val SUBTITLE_BASE_URL = "https://k1.sub1.top/s"

    suspend fun extract(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[LinkkfExtractor] Extracting: $url")
        
        try {
            // 에피소드 페이지 로드
            val response = app.get(url, headers = mapOf("Referer" to referer))
            val doc = response.document
            val scriptContent = doc.select("script").find { it.data().contains("var player_aaaa=") }?.data()

            if (scriptContent == null) {
                println("[LinkkfExtractor] Player script not found")
                return false
            }

            // JSON 데이터 추출
            val jsonString = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\});""").find(scriptContent)?.groupValues?.get(1)
                ?: return false

            val playerData = parseJson<PlayerData>(jsonString)

            // 데이터 유효성 검사
            val videoKey = playerData.url ?: return false
            val actualUrl = playerData.actualUrl ?: return false

            // M3U8 URL 생성 로직
            val uri = URI(actualUrl)
            val host = uri.host
            val idParam = uri.query.split("&").find { it.startsWith("id=") }?.substringAfter("id=")

            if (host != null && idParam != null) {
                val m3u8Url = "https://$host/r2/cache/$idParam-$videoKey.m3u8"
                val subUrl = "$SUBTITLE_BASE_URL/$videoKey.vtt"

                println("[LinkkfExtractor] Generated M3U8: $m3u8Url")

                // 자막 제공
                subtitleCallback.invoke(
                    SubtitleFile(
                        "Korean",
                        subUrl
                    )
                )

                // 영상 링크 제공 (수정됨: 호환성 높은 생성자 사용)
                callback.invoke(
                    ExtractorLink(
                        source = "Linkkf",
                        name = "Linkkf",
                        url = m3u8Url,
                        referer = referer,
                        quality = getQualityFromName("HD"),
                        isM3u8 = true
                    )
                )
                return true
            }

        } catch (e: Exception) {
            e.printStackTrace()
            println("[LinkkfExtractor] Error: ${e.message}")
        }

        return false
    }

    private data class PlayerData(
        @JsonProperty("url") val url: String?,
        @JsonProperty("actual_url") val actualUrl: String?
    )
}
