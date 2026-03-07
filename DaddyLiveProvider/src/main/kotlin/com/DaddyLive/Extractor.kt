/**
 * DaddyLiveExtractor v12.0 (Zero-Base / Pure HTTP Extractor)
 * - [핵심] 사용자 요청에 따라 WebView, 로컬 프록시, 해싱 등 복잡한 코드를 100% 전면 폐기.
 * - [로직] 파이썬 스크립트의 legacy get_stream_link 로직(iframe 탐색 -> server_lookup API 호출 -> iosplayer 조합)을 그대로 직역함.
 * - [헤더] 파이썬 스크립트 구조에 맞춰 Referer와 Origin을 ilovetoplay.xyz 로 고정.
 */
package com.DaddyLive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DaddyLiveExtractor : ExtractorApi() {
    // 파이썬 코드에 명시된 최신 베이스 URL
    override val mainUrl = "https://dlstreams.top"
    override val name = "DaddyLive"
    override val requiresReferer = false

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        println("[DaddyLiveExt] v12.0 Zero-Base 초경량 추출 엔진 가동")

        for ((name, link) in links) {
            // 1. 링크에서 채널 ID만 순수하게 추출 (예: id=1508)
            val idMatch = Regex("""(?:stream-|id=)(\d+)""").find(link)
            val channelId = idMatch?.groupValues?.get(1) ?: continue
            
            println("[DaddyLiveExt] 타겟 채널 ID: $channelId")
            
            try {
                // 2. 임베드 페이지 직접 접근
                val embedUrl = "$mainUrl/embed/stream-$channelId.php"
                val embedHtml = app.get(embedUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")).text
                
                // 3. 내부 iframe(실제 플레이어) 도메인 찾기
                val iframeMatch = Regex("""<iframe[^>]+id=["']thatframe["'][^>]+src=["'](https?://[^"']+)["']""").find(embedHtml)
                    ?: Regex("""<iframe[^>]+src=["'](https?://[^"']+premiumtv[^"']+)["']""").find(embedHtml)
                    
                val iframeUrl = iframeMatch?.groupValues?.get(1)
                
                if (iframeUrl != null) {
                    println("[DaddyLiveExt] iframe 도메인 발견: $iframeUrl")
                    
                    val domainMatch = Regex("""(https?://[^/]+)""").find(iframeUrl)
                    val parentDomain = domainMatch?.groupValues?.get(1) ?: "https://newembedplay.xyz"
                    
                    // 4. Server Lookup API 직접 호출하여 서버 키(server_key) 파싱
                    val serverKeyUrl = "$parentDomain/server_lookup.php?channel_id=premium$channelId"
                    val keyHeaders = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to iframeUrl,
                        "Origin" to parentDomain,
                        "Sec-Fetch-Site" to "same-origin"
                    )
                    
                    val serverKeyJson = app.get(serverKeyUrl, headers = keyHeaders).text
                    val serverKey = Regex(""""server_key"\s*:\s*"([^"]+)"""").find(serverKeyJson)?.groupValues?.get(1) ?: "zeko"
                    
                    println("[DaddyLiveExt] Server Key 획득: $serverKey")
                    
                    // 5. 파이썬 로직대로 iosplayer.ru M3U8 주소 수동 조립
                    val streamUrl = "https://${serverKey}new.iosplayer.ru/$serverKey/premium$channelId/mono.m3u8"
                    println("[DaddyLiveExt] 최종 M3U8 직링크: $streamUrl")
                    
                    // 6. ExoPlayer 재생 필수 헤더 주입
                    callback(newExtractorLink(name, name, streamUrl, type = ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "https://ilovetoplay.xyz/" 
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to "https://ilovetoplay.xyz/",
                            "Origin" to "https://ilovetoplay.xyz"
                        )
                    })
                    break 
                } else {
                    println("[DaddyLiveExt] iframe을 찾을 수 없습니다. (Cloudflare 차단 가능성)")
                }
            } catch (e: Exception) {
                println("[DaddyLiveExt] 에러 발생: ${e.message}")
            }
        }
    }
}
