/**
 * DaddyLiveExtractor v7.0 (README Header Bypass)
 * - [핵심 변경] 사용자가 제공한 README.md의 "우회 헤더(adffdafdsafds.sbs)" 완벽 적용
 * - [최적화] 잦은 크래시와 타임아웃을 유발하던 로컬 프록시(Proxy) 및 AES 복호화 로직 전면 제거
 * - [원인 규명] 이전 403/타임아웃 에러는 복잡한 암호화 때문이 아니라, 최신 서버가 요구하는 정확한 Referer/Origin 헤더가 누락되었기 때문임.
 */
package com.DaddyLive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlstreams.top"
    override val name = "DaddyLive"
    override val requiresReferer = false
    
    // README.md 에서 지시한 필수 우회 헤더값 (이 값이 없으면 무조건 403 에러 발생)
    private val targetReferer = "https://adffdafdsafds.sbs/"
    private val targetOrigin = "https://adffdafdsafds.sbs"
    private val targetUa = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        println("[DaddyLiveExt] v7.0 README 기반 헤더 우회 엔진 가동")

        for ((name, link) in links) {
            println("[DaddyLiveExt] 탐색 중인 링크: $link")
            try {
                // 1. 메인 스트리밍 페이지 (예: stream-1508.php) 요청
                val html = app.get(link, headers = mapOf("User-Agent" to targetUa, "Referer" to "$mainUrl/")).text
                
                // 2. 소스 내부의 모든 iframe 추출
                val iframeRegex = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""")
                val iframes = iframeRegex.findAll(html).map { it.groupValues[1] }.toList()
                
                var foundM3u8: String? = null
                
                // 3. iframe 내부를 순회하며 순수 .m3u8 직링크 스크래핑
                for (iframe in iframes) {
                    println("[DaddyLiveExt] iframe 스캔 중: $iframe")
                    try {
                        val iframeHtml = app.get(iframe, headers = mapOf("User-Agent" to targetUa, "Referer" to link)).text
                        
                        // 정규식: .m3u8 로 끝나는 혹은 포함하는 URL 추출
                        val m3u8Match = Regex("""["'](https?://[^\s"'<>]+\.m3u8[^"']*)["']""").find(iframeHtml)
                        if (m3u8Match != null) {
                            foundM3u8 = m3u8Match.groupValues[1]
                            println("[DaddyLiveExt] 직링크 m3u8 발견: $foundM3u8")
                            break
                        }
                        
                        // 정규식: JSON이나 Javascript 객체 형태의 streamUrl 파싱
                        val streamUrlMatch = Regex("""streamUrl\s*:\s*["'](https?:[^"']+)["']""").find(iframeHtml)
                        if (streamUrlMatch != null) {
                            foundM3u8 = streamUrlMatch.groupValues[1].replace("\\/", "/")
                            println("[DaddyLiveExt] 스크립트 내 streamUrl 발견: $foundM3u8")
                            break
                        }
                    } catch (e: Exception) {
                        println("[DaddyLiveExt] iframe 내부 파싱 에러: ${e.message}")
                    }
                }

                // 4. 추출한 M3U8 주소에 README 필수 헤더를 달아서 Cloudstream 플레이어로 전송
                if (foundM3u8 != null) {
                    println("[DaddyLiveExt] ★최종 추출 성공. 필수 헤더 주입 후 플레이어로 넘깁니다.")
                    callback(newExtractorLink(name, name, foundM3u8, type = ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        
                        // ExoPlayer가 영상 조각(TS)과 키를 다운받을 때 사용할 헤더를 강제 주입
                        this.headers = mapOf(
                            "User-Agent" to targetUa,
                            "Referer" to targetReferer,
                            "Origin" to targetOrigin
                        )
                    })
                    break // 성공했으므로 다른 링크 탐색 중단
                } else {
                    println("[DaddyLiveExt] 이 링크에서 M3U8을 찾을 수 없습니다. 다음 백업 링크를 탐색합니다.")
                }
            } catch (e: Exception) {
                println("[DaddyLiveExt] 메인 페이지 요청/파싱 에러: ${e.message}")
            }
        }
    }
}
