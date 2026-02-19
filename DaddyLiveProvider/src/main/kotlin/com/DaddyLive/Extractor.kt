/**
 * DaddyLiveExtractor v1.5
 * - [Design] WebView Interceptor 강화: .m3u8 외 .mpd, manifest, WebRTC 징후(wss) 등 감시 패턴 확장
 * - [Fix] 패키지명 com.DaddyLive 유지 및 하드코딩된 죽은 도메인(topembed.pw) 회피 로직 강화
 * - [Debug] 탐지된 모든 네트워크 요청 후보군을 상세 로그로 출력
 */
package com.DaddyLive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URL

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.link"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url)
        println("[DaddyLiveExt] 추출 시작 (v1.5): ${links?.size ?: 1}개 링크 처리 중")
        
        links?.forEach { (name, link) ->
            extractWithWebView(link, name, callback)
        } ?: extractWithWebView(url, this.name, callback)
    }

    private suspend fun extractWithWebView(
        url: String, 
        sourceName: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.contains("dlhd") && !url.contains("thedaddy")) return
        println("[DaddyLiveExt] ($sourceName) 웹뷰 정밀 분석 중: $url")

        // Cloudstream의 webViewLinkInspector를 사용하여 모든 네트워크 트래픽 감시
        val interceptedUrl = webViewLinkInspector(
            url = url,
            source = sourceName,
            filter = { requestUrl ->
                val lowerUrl = requestUrl.lowercase()
                
                // 1. HLS/DASH/MSS 매니페스트 패턴 감시
                val isManifest = lowerUrl.contains(".m3u8") || 
                                 lowerUrl.contains(".mpd") || 
                                 lowerUrl.contains("manifest") || 
                                 lowerUrl.contains("playlist") ||
                                 lowerUrl.contains(".m4s")

                // 2. WebRTC 및 라이브 스트리밍 서버 서버 징후 감시 (wss, 시그널링)
                val isStreamServer = lowerUrl.contains("mizhls") || 
                                     lowerUrl.contains("newkso") || 
                                     lowerUrl.contains("wss://") || 
                                     lowerUrl.contains("topembed")

                // 3. 확정된 가짜 도메인(topembed.pw)은 필터링에서 제외
                val isFake = lowerUrl.contains("topembed.pw")

                // 디버깅용 로그: 의심되는 모든 주소를 로그캣에 기록
                if ((isManifest || isStreamServer) && !isFake) {
                    println("[DaddyLiveExt] ★유효 후보 발견: $requestUrl")
                    true // 이 주소를 가로챔
                } else {
                    if (isStreamServer && isFake) {
                        // 죽은 도메인이 포착되면 로그로만 남김
                        // println("[DaddyLiveExt] 무시된 가짜 주소: $requestUrl")
                    }
                    false
                }
            }
        )

        if (interceptedUrl != null) {
            println("[DaddyLiveExt] 최종 추출 주소: $interceptedUrl")
            
            // 발견된 주소가 WebRTC(wss)일 경우 현재 플레이어에서 지원되지 않을 수 있음을 알림
            val isWebRTC = interceptedUrl.startsWith("wss")
            
            callback(
                newExtractorLink(
                    sourceName,
                    sourceName,
                    interceptedUrl,
                    type = if (interceptedUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8,
                ) {
                    val uri = URL(if (isWebRTC) url else interceptedUrl)
                    this.referer = "${uri.protocol}://${uri.host}/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Origin" to "${uri.protocol}://${uri.host}"
                    )
                }
            )
        } else {
            println("[DaddyLiveExt] ($sourceName) 분석 실패: 유효한 미디어 스트림을 찾지 못함")
        }
    }
}
