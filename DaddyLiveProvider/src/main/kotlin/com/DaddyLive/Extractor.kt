/**
 * DaddyLiveExtractor v13.0 (Zero-Base Iframe Crawler)
 * - [핵심] 기존 프록시, WebView, AES 해독 로직 전면 폐기 (사용자 요청).
 * - [분석] iosplayer.ru 도메인 사망(UnknownHost) 확인. 파이썬 스크립트가 의존하던 외부 서버(MFP) 없이 앱 단독으로 동작하도록 재설계.
 * - [로직] 암호화된 메인 플레이어(premiumtv)를 무시하고, 암호화가 없는 순수 백업 iframe(superdinamico, lovecdn 등)을 재귀적으로 파도타기 하여 m3u8을 직출함.
 */
package com.DaddyLive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.dad"
    override val name = "DaddyLive"
    override val requiresReferer = false

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        println("[DaddyLiveExt] v13.0 Zero-Base Iframe Crawler 가동")

        for ((name, link) in links) {
            val idMatch = Regex("""(?:stream-|id=)(\d+)""").find(link)
            val channelId = idMatch?.groupValues?.get(1) ?: continue
            
            println("[DaddyLiveExt] 타겟 채널 ID: $channelId")
            
            // 시도할 여러 미러 주소들 (Provider가 넘겨준 원본 링크 우선 탐색)
            val endpoints = listOf(
                link,
                "https://dlhd.dad/embed/stream-$channelId.php",
                "https://dlstreams.top/cast/stream-$channelId.php"
            )

            var foundStream: String? = null

            // 각 미러 주소의 내부 iframe을 재귀적으로 탐색
            for (endpoint in endpoints) {
                println("[DaddyLiveExt] 미러 탐색 시작: $endpoint")
                foundStream = crawlForM3u8(endpoint, "$mainUrl/", 0)
                if (foundStream != null) break
            }

            if (foundStream != null) {
                println("[DaddyLiveExt] 최종 M3U8 직링크 추출 성공: $foundStream")
                
                // M3u8 must contains TS files (403 에러) 방지를 위한 동적 Referer 강제 세팅
                val targetReferer = if (foundStream.contains("lovecdn") || foundStream.contains("lovetier")) {
                    "https://lovecdn.ru/"
                } else if (foundStream.contains("ligapk") || foundStream.contains("superdinamico")) {
                    "https://superdinamico.com/"
                } else {
                    "https://ilovetoplay.xyz/" // 파이썬 스크립트의 기본 헤더
                }

                callback(newExtractorLink(name, name, foundStream, type = ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.referer = targetReferer
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to targetReferer,
                        "Origin" to targetReferer.trimEnd('/')
                    )
                })
                break // 성공적으로 찾았으면 루프 종료
            } else {
                println("[DaddyLiveExt] 암호화되지 않은 직링크를 찾지 못했습니다. (모든 백업 플레이어 차단됨)")
            }
        }
    }

    // iframe을 추적하여 순수 m3u8을 찾아내는 경량 재귀 함수
    private suspend fun crawlForM3u8(url: String, referer: String, depth: Int): String? {
        if (depth > 2) return null // 무한 루프 방지를 위한 최대 깊이 제한
        
        try {
            val html = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to referer)).text
            
            // 1. 순수 m3u8 직링크가 있는지 정규식으로 직접 검사
            val m3u8Match = Regex("""["'](https?://[^\s"'<>]+\.m3u8[^"']*)["']""").find(html)
            if (m3u8Match != null) return m3u8Match.groupValues[1]

            // 2. superdinamico/ligapk 우회 플레이어 API 패턴 검출
            val sdMatch = Regex("""get_stream\.php\?id=([a-f0-9]+)""").find(html)
            if (sdMatch != null) return "https://edg.ligapk.com/exemple.php?id=${sdMatch.groupValues[1]}"

            // 3. 페이지 내의 다른 서브 iframe들을 파도타기
            val iframes = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""").findAll(html)
            for (iframe in iframes) {
                val src = iframe.groupValues[1]
                
                // Cloudflare 캡챠나 AES 암호화가 걸려있어 앱 단독으로 뚫을 수 없는 메인 서버는 즉시 스킵
                if (src.contains("premiumtv") || src.contains("newembedplay") || src.contains("server_lookup")) {
                    println("[DaddyLiveExt] 암호화된 메인 플레이어 감지되어 스킵: $src")
                    continue
                }
                
                println("[DaddyLiveExt] 서브 iframe 깊이 탐색 [Depth $depth]: $src")
                val result = crawlForM3u8(src, url, depth + 1)
                if (result != null) return result
            }
        } catch (e: Exception) {
            println("[DaddyLiveExt] 크롤링 에러 ($url): ${e.message}")
        }
        return null
    }
}
