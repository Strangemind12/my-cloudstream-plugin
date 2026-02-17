package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

/**
 * Extractor v16.0
 * - [Bypass] @file:OptIn을 통해 TestingApi 빌드 체크 해제
 * - [Fix] WebViewResolver 생성자 에러 수정 및 URL 직접 추출 로직 강화
 * - [Debug] 모든 프로세스 println 로그 출력
 */
@file:OptIn(com.lagradost.cloudstream3.utils.TestingApi::class)
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
        println("$TAG [Extract] Step 1 시작 - 파라미터 분석")
        try {
            var cleanData = data.substringBefore("?poster=")
            var detailReferer = "$mainUrl/"

            if (cleanData.contains("ref=")) {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            }

            println("$TAG [Step 1] Provider URL: $cleanData | Referer: $detailReferer")

            // [Step 2] 봇 차단 우회를 위해 WebView로 Provider 페이지 먼저 접속
            // 이 단계에서 JS 함수 moveCloudvideo 등을 실행하여 실제 플레이어 URL을 얻어야 함
            println("$TAG [Step 2] 웹뷰 가동: 플레이어 주소 스니핑 시작...")
            
            // initJs 에러를 피하기 위해 생성자 확인 후 동적 처리
            val playerInterceptor = WebViewResolver(Regex("""/h/live\?p=""")) 
            // initJs가 생성자에 없다면 호출 후 JS 실행하는 다른 방식을 쓰거나, 
            // 일단 페이지를 로드하여 리다이렉트를 유도함.
            
            val playerResponse = app.get(
                cleanData,
                headers = mapOf("Referer" to detailReferer),
                interceptor = playerInterceptor
            )
            
            val playerUrl = playerResponse.url
            println("$TAG [Step 2] 최종 추출된 플레이어 URL: $playerUrl")

            if (!playerUrl.contains("/h/live")) {
                println("$TAG [Error] 플레이어 주소(h/live)를 찾지 못함. 웹뷰 차단 가능성 있음.")
                return false
            }

            // [Step 3] 실제 플레이어 페이지에서 M3U8 스니핑
            println("$TAG [Step 3] 웹뷰 가동: 최종 M3U8 추출 중...")
            val m3u8Interceptor = WebViewResolver(Regex("""\.m3u8"""))
            
            val m3u8Response = app.get(
                playerUrl,
                headers = commonHeaders,
                interceptor = m3u8Interceptor
            )
            
            val finalM3u8 = m3u8Response.url
            println("$TAG [Step 3] 스니핑 결과: $finalM3u8")

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
                println("$TAG [Extract] 링크 추출 성공 및 전달 완료")
                return true
            }

        } catch (e: Exception) {
            println("$TAG [Critical Error] 예외 발생: ${e.message}")
            e.printStackTrace()
        }
        println("$TAG [Extract] 프로세스 종료 (실패)")
        return false
    }
}
