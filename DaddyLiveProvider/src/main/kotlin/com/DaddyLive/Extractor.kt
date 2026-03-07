/**
 * DaddyLiveExtractor v10.0 (Zero-Base Python Script Port)
 * - [핵심] 기존의 무겁고 쓸데없는 로컬 프록시, WebView, AES 복호화 로직을 100% 전면 폐기.
 * - [적용] 사용자가 제공한 파이썬 스크립트의 로직(server_lookup API 직통 호출 및 iosplayer CDN 조립)을 완벽히 이식.
 * - [헤더] 파이썬 스크립트에 명시된 ilovetoplay.xyz Referer 및 Origin 헤더 적용.
 */
package com.DaddyLive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DaddyLiveExtractor : ExtractorApi() {
    // 파이썬 스크립트 기준 메인 URL 적용
    override val mainUrl = "https://daddylive.mp"
    override val name = "DaddyLive"
    override val requiresReferer = false

    // 파이썬 스크립트에 명시된 User-Agent
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        println("[DaddyLiveExt] v10.0 Zero-Base 파이썬 로직 이식 엔진 가동")

        for ((name, link) in links) {
            // URL에서 stream-1508 등 채널 ID 추출
            val idMatch = Regex("""(?:stream-|id=)(\d+)""").find(link)
            val channelId = idMatch?.groupValues?.get(1)
            
            if (channelId == null) {
                println("[DaddyLiveExt] 채널 ID를 찾을 수 없습니다: $link")
                continue
            }
            
            println("[DaddyLiveExt] 타겟 채널 ID: $channelId")
            
            try {
                // 1. 임베드 페이지 요청 (파이썬: f"https://daddylive.mp/embed/stream-{dlhd_id}.php")
                val embedUrl = "$mainUrl/embed/stream-$channelId.php"
                val embedHtml = app.get(embedUrl, headers = mapOf("User-Agent" to userAgent)).text
                
                // 2. iframe src 추출 (파이썬: iframe = soup.find('iframe', id='thatframe'))
                val iframeMatch = Regex("""<iframe[^>]+id=["']thatframe["'][^>]+src=["'](https?://[^"']+)["']""").find(embedHtml)
                    ?: Regex("""<iframe[^>]+src=["'](https?://[^"']+premiumtv[^"']+)["']""").find(embedHtml)
                    
                if (iframeMatch != null) {
                    val iframeUrl = iframeMatch.groupValues[1]
                    println("[DaddyLiveExt] iframe URL 발견: $iframeUrl")
                    
                    // iframe 도메인 추출 (파이썬: parent_site_domain = real_link.split('/premiumtv')[0])
                    val domainMatch = Regex("""(https?://[^/]+)""").find(iframeUrl)
                    val parentDomain = domainMatch?.groupValues?.get(1) ?: "https://newembedplay.xyz"
                    
                    // 3. Server Lookup API 호출 (파이썬: server_key_link)
                    val serverKeyUrl = "$parentDomain/server_lookup.php?channel_id=premium$channelId"
                    
                    // 파이썬 코드와 동일한 헤더 구성
                    val keyHeaders = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to iframeUrl,
                        "Origin" to parentDomain,
                        "Sec-Fetch-Site" to "same-origin"
                    )
                    
                    println("[DaddyLiveExt] Server Lookup API 요청: $serverKeyUrl")
                    val serverKeyJson = app.get(serverKeyUrl, headers = keyHeaders).text
                    
                    // JSON에서 server_key 추출
                    val serverKeyMatch = Regex(""""server_key"\s*:\s*"([^"]+)"""").find(serverKeyJson)
                    val serverKey = serverKeyMatch?.groupValues?.get(1)
                    
                    if (serverKey != null) {
                        println("[DaddyLiveExt] Server Key 추출 성공: $serverKey")
                        
                        // 4. M3U8 주소 직접 조립 (파이썬: f"https://{server_key}new.iosplayer.ru/{server_key}/premium{dlhd_id}/mono.m3u8")
                        val streamUrl = "https://${serverKey}new.iosplayer.ru/$serverKey/premium$channelId/mono.m3u8"
                        println("[DaddyLiveExt] 최종 추출된 직링크: $streamUrl")
                        
                        // 5. ExoPlayer에 전달할 재생 헤더 구성 (파이썬 M3U8 생성 로직 참고)
                        callback(newExtractorLink(name, name, streamUrl, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.Unknown.value
                            this.referer = "https://ilovetoplay.xyz/" // M3u8 must contains TS files 에러 방지용
                            this.headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to "https://ilovetoplay.xyz/",
                                "Origin" to "https://ilovetoplay.xyz"
                            )
                        })
                        
                        break // 성공했으므로 다른 링크 탐색 중단
                    } else {
                        println("[DaddyLiveExt] Server Key 추출 실패. 응답 내용: $serverKeyJson")
                    }
                } else {
                    println("[DaddyLiveExt] 임베드 페이지에서 'thatframe' iframe을 찾을 수 없습니다.")
                }
            } catch (e: Exception) {
                println("[DaddyLiveExt] 파이썬 로직 수행 중 에러 발생: ${e.message}")
            }
        }
    }
}
