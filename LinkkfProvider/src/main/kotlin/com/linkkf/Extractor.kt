package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URI

class LinkkfExtractor {
    // v1.3: 상세 로그 추가 및 정규식 개선
    private val SUBTITLE_BASE_URL = "https://k1.sub1.top/s"

    data class LinkkfResult(
        val m3u8Url: String,
        val subtitleUrl: String
    )

    suspend fun extract(url: String, referer: String): LinkkfResult? {
        println("[LinkkfExtractor] Extracting 시작: $url")
        
        try {
            val response = app.get(url, headers = mapOf("Referer" to referer))
            println("[LinkkfExtractor] 응답 코드: ${response.code}")
            
            val doc = response.document
            
            // 스크립트 찾기 (player_ 로 시작하는 변수 검색)
            val scriptTag = doc.select("script").find { 
                it.data().contains("var player_") && it.data().contains("actual_url") 
            }
            
            val scriptContent = scriptTag?.data()

            if (scriptContent == null) {
                println("[LinkkfExtractor] 실패: 플레이어 정보를 담은 스크립트를 찾을 수 없습니다.")
                return null
            }

            println("[LinkkfExtractor] 스크립트 발견 (길이: ${scriptContent.length})")

            // JSON 추출 정규식
            // 예: var player_aaaa = {...}; 형태 매칭
            val regex = Regex("""var\s+player_[a-zA-Z0-9_]+\s*=\s*(\{.*?\});""")
            val match = regex.find(scriptContent)
            
            if (match == null) {
                println("[LinkkfExtractor] 실패: JSON 데이터 정규식 매칭에 실패했습니다.")
                return null
            }

            val jsonString = match.groupValues[1]
            println("[LinkkfExtractor] JSON 문자열 추출 성공")

            // JSON 파싱
            val playerData = try {
                parseJson<PlayerData>(jsonString)
            } catch (e: Exception) {
                println("[LinkkfExtractor] 실패: JSON 파싱 에러 - ${e.message}")
                return null
            }

            val videoKey = playerData.url
            val actualUrl = playerData.actualUrl

            println("[LinkkfExtractor] 파싱 데이터 - Key: $videoKey, ActualUrl: $actualUrl")

            if (videoKey.isNullOrEmpty() || actualUrl.isNullOrEmpty()) {
                println("[LinkkfExtractor] 실패: 필수 데이터(url 또는 actual_url)가 누락되었습니다.")
                return null
            }

            // URL 생성 로직
            val uri = URI(actualUrl)
            val host = uri.host // 예: playv2.sub3.top
            
            // 쿼리 파라미터 파싱 (id=n20 부분 추출)
            val idParam = uri.query?.split("&")?.find { it.startsWith("id=") }?.substringAfter("id=")

            if (host != null && idParam != null) {
                val m3u8Url = "https://$host/r2/cache/$idParam-$videoKey.m3u8"
                val subUrl = "$SUBTITLE_BASE_URL/$videoKey.vtt"

                println("[LinkkfExtractor] 최종 생성 URL: $m3u8Url")
                return LinkkfResult(m3u8Url, subUrl)
            } else {
                println("[LinkkfExtractor] 실패: Host($host) 또는 ID($idParam) 추출 실패")
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
