package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

/**
 * Extractor v15.0
 * - [Fix] WebViewResolver 생성자 에러 수정 (Regex만 전달)
 * - [Fix] 봇 차단 우회를 위해 Provider 페이지부터 웹뷰로 로드 (2단계 스니핑)
 * - [Debug] 모든 단계 상세 println 로그 추가
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
        try {
            // 1. 파라미터 분리 (cleanData: 주소, referer: 상세페이지)
            var cleanData = data.substringBefore("?poster=")
            var detailReferer = "$mainUrl/"

            if (cleanData.contains("ref=")) {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            }

            println("$TAG [Step 1] Provider 주소: $cleanData")
            println("$TAG [Step 1] 사용될 Referer: $detailReferer")

            // 2. [뚫기] Provider 페이지를 웹뷰로 로드하여 실제 플레이어 주소 낚아채기
            // 사이트가 버튼을 눌러야 이동하므로, initJs를 사용하여 버튼 클릭(함수 실행)을 강제함
            println("$TAG [Step 2] 웹뷰 가동: Provider -> Player URL 추출 중...")
            val playerInterceptor = WebViewResolver(
                Regex("""/h/live\?p="""), // 플레이어 URL 패턴
                initJs = "if(typeof moveCloudvideo === 'function') moveCloudvideo(); else if(typeof moveJawcloud === 'function') moveJawcloud();"
            )

            val playerResponse = app.get(
                cleanData, 
                headers = mapOf("Referer" to detailReferer), 
                interceptor = playerInterceptor
            )
            val playerUrl = playerResponse.url
            println("$TAG [Step 2] 추출된 플레이어 URL: $playerUrl")

            if (!playerUrl.contains("/h/live")) {
                println("$TAG [Error] 플레이어 주소 추출 실패. HTML이 차단되었을 가능성 있음.")
                return false
            }

            // 3. [뚫기] 플레이어 주소에서 최종 m3u8 낚아채기
            println("$TAG [Step 3] 웹뷰 가동: Player -> M3U8 추출 중...")
            val m3u8Interceptor = WebViewResolver(Regex("""\.m3u8"""))
            
            val m3u8Response = app.get(
                playerUrl,
                headers = commonHeaders,
                interceptor = m3u8Interceptor
            )
            val finalM3u8 = m3u8Response.url
            println("$TAG [Step 3] 최종 스니핑 성공: $finalM3u8")

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
                println("$TAG [LoadLinks] 모든 프로세스 성공 완료")
                return true
            }

        } catch (e: Exception) {
            println("$TAG [Critical Error] 추출 중 예외 발생: ${e.message}")
            e.printStackTrace()
        }
        return false
    }
}
