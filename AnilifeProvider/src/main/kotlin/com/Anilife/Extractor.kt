@file:OptIn(com.lagradost.cloudstream3.utils.TestingApi::class)
package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

/**
 * Extractor v18.0
 * - [Bypass] @file:OptIn 위치 수정을 통해 빌드 체크 해제
 * - [Fix] 봇 차단 우회를 위해 모든 로직을 WebView로 수행
 * - [Fix] initJs 파라미터 에러 해결을 위해 동적 파싱 후 웹뷰 재실행 방식 사용
 * - [Debug] 전 과정 상세 println 로그 포함
 */
class AnilifeExtractor {
    private val TAG = "[AnilifeExtractor]"
    private val mainUrl = "https://anilife.live"

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "https://anilife.live/"
    )

    suspend fun extract(
        data: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("$TAG [Extract] 1단계: 파라미터 분석 시작")
        try {
            var cleanData = data.substringBefore("?poster=")
            var detailReferer = "$mainUrl/"

            if (cleanData.contains("ref=")) {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            }

            println("$TAG [Extract] Provider URL: $cleanData")
            println("$TAG [Extract] Referer URL: $detailReferer")

            // [Step 1] 웹뷰로 Provider 페이지 로드하여 봇 차단 우회 및 플레이어 주소 획득
            println("$TAG [Extract] 2단계: 웹뷰로 Provider 페이지 로드 중...")
            
            // Regex(".*")를 사용해 페이지가 로드되자마자 HTML을 가져옵니다.
            val providerResponse = app.get(
                cleanData,
                headers = mapOf("Referer" to detailReferer),
                interceptor = WebViewResolver(Regex(".*"))
            )
            val html = providerResponse.text
            println("$TAG [Extract] HTML 획득 완료 (길이: ${html.length})")

            // 자바스크립트 내의 플레이어 주소 추출
            val regex = Regex("""["']([^"']*\/?h\/live\?p=[^"']+)["']""")
            val match = regex.find(html)
            var playerUrl = match?.groupValues?.get(1)

            if (playerUrl == null) {
                println("$TAG [Error] HTML에서 플레이어 주소를 찾지 못했습니다. 로그캣의 HTML Dump를 확인하세요.")
                return false
            }

            // URL 완성
            if (!playerUrl.startsWith("http")) {
                playerUrl = if (playerUrl.startsWith("/")) "$mainUrl$playerUrl" else "$mainUrl/$playerUrl"
            }
            playerUrl = playerUrl.replace("\\/", "/")
            println("$TAG [Extract] 3단계: 추출된 플레이어 주소 접속: $playerUrl")

            // [Step 2] 웹뷰로 플레이어 페이지 접속하여 최종 M3U8 낚아채기
            println("$TAG [Extract] 4단계: 웹뷰로 최종 M3U8 스니핑 시작...")
            val m3u8Interceptor = WebViewResolver(Regex("""\.m3u8"""))
            
            val m3u8Response = app.get(
                playerUrl,
                headers = commonHeaders,
                interceptor = m3u8Interceptor
            )
            
            val finalM3u8 = m3u8Response.url
            println("$TAG [Extract] 최종 스니핑 성공: $finalM3u8")

            if (finalM3u8.contains(".m3u8")) {
                callback.invoke(
                    ExtractorLink(
                        source = "Anilife",
                        name = "Anilife",
                        url = finalM3u8,
                        referer = "$mainUrl/",
                        quality = getQualityFromName("HD"),
                        type = ExtractorLinkType.M3U8
                    )
                )
                println("$TAG [Extract] 모든 과정 성공 완료")
                return true
            }

        } catch (e: Exception) {
            println("$TAG [Critical Error] 예외 발생: ${e.message}")
            e.printStackTrace()
        }
        println("$TAG [Extract] 링크 추출 실패")
        return false
    }
}
