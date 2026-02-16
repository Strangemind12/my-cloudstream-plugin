package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URI

class LinkkfExtractor {
    // v1.4: 정규식 유연성 강화 (줄바꿈 대응 및 세미콜론 제거) 및 디버깅 로그 강화
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

            // [v1.4 수정] 정규식 개선
            // 1. RegexOption.DOT_MATCHES_ALL: JSON 데이터가 여러 줄에 걸쳐 있어도 매칭
            // 2. 세미콜론(;) 제거: 끝에 세미콜론이 없어도 매칭
            val regex = Regex("""var\s+player_[a-zA-Z0-9_]+\s*=\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(scriptContent)
            
            if (match == null) {
                println("[LinkkfExtractor] 실패: JSON 데이터 정규식 매칭 실패")
                // 디버깅용: 매칭에 실패한 스크립트 내용 출력 (문자열 일부)
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

            if (videoKey.isNullOrEmpty() || actualUrl.isNullOrEmpty()) {
                println("[LinkkfExtractor] 실패: 필수 데이터(url 또는 actual_url) 누락")
                return null
            }

            // URL 생성
            val uri = URI(actualUrl)
            val host = uri.host 
            val idParam = uri.query?.split("&")?.find { it.startsWith("id=") }?.substringAfter("id=")

            if (host != null && idParam != null) {
                val m3u8Url = "https://$host/r2/cache/$idParam-$videoKey.m3u8"
                val subUrl = "$SUBTITLE_BASE_URL/$videoKey.vtt"

                println("[LinkkfExtractor] 최종 URL: $m3u8Url")
                return LinkkfResult(m3u8Url, subUrl)
            } else {
                println("[LinkkfExtractor] 실패: 파라미터 추출 실패 (Host: $host, ID: $idParam)")
            }

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
