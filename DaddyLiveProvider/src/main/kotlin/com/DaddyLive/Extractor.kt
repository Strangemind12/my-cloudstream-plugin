/**
 * DaddyLiveExtractor v5.0 (Pure HTTP Bypass Engine - Kodi Port)
 * - [핵심 원인] v4.9에서 플레이어 직링크(ksohls.ru)로 강제 접속 시도 시, 해당 도메인의 강력한 SSL/Cloudflare 봇 차단(net_error -100)으로 인해 연결이 거부됨.
 * - [해결] Kodi 애드온의 AnyPlayer 우회 방식을 Kotlin으로 완벽히 포팅함.
 * - [성능] 버그가 많은 WebView를 완전히 제거하고 순수 HTTP(app.get) 파싱으로 전환하여 추출 속도가 1~2초 이내로 획기적 향상.
 * - [우회] ksohls.ru (AES-128 암호화)를 즉시 스킵하고, 대체 플레이어(superdinamico, lovecdn 등)의 직링(.m3u8)을 스크래핑함.
 */
package com.DaddyLive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlstreams.top"
    override val name = "DaddyLive"
    override val requiresReferer = false
    
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        println("[DaddyLiveExt] v5.0 순수 HTTP 우회 엔진 가동 (WebView 제거 / Kodi 로직 포팅)")

        for ((name, link) in links) {
            println("[DaddyLiveExt] 탐색 중인 링크: $name -> $link")
            
            val streamUrl = getAnyPlayerStream(link)
            if (streamUrl != null) {
                println("[DaddyLiveExt] ★최종 추출된 영상 URL: $streamUrl")
                
                // sanwalyaarpya.com 등 특수 도메인은 고정된 Origin이 필요함 (Kodi 로직 참고)
                val origin = if (streamUrl.contains("sanwalyaarpya.com")) "https://stellarthread.com" else "https://lefttoplay.xyz/"
                
                callback(newExtractorLink(name, name, streamUrl, type = ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Origin" to origin,
                        "Referer" to origin
                    )
                })
                break // 성공적인 m3u8을 찾으면 즉시 루프 종료
            }
        }
    }

    private suspend fun getAnyPlayerStream(watchUrl: String): String? {
        try {
            val response = app.get(watchUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)).text
            
            // ksohls.ru는 Cloudstream(ExoPlayer)에서 처리 불가한 복잡한 AES-128 PoW가 걸려있음. 
            // Kodi처럼 해당 플레이어가 감지되면 즉시 포기하고 다음 링크(cast, watch 등)로 스킵함.
            if (response.contains("ksohls.ru")) {
                println("[DaddyLiveExt] ksohls.ru 감지됨 (AES 암호화 우회 불가) -> 대체 링크 탐색으로 스킵")
                return null
            }
            
            // 1. superdinamico (ligapk) 토큰 없는 직링 우회
            val sdMatch = Regex("""<iframe[^>]+src=["'](https://[^"']+\.superdinamico\.com/embed\.php[^"']*)["']""").find(response)
            if (sdMatch != null) {
                val embedUrl = sdMatch.groupValues[1]
                val r2 = app.get(embedUrl, headers = mapOf("Referer" to watchUrl, "User-Agent" to userAgent)).text
                val idMatch = Regex("""get_stream\.php\?id=([a-f0-9]{32})""").find(r2)
                if (idMatch != null) {
                    val finalUrl = "https://edg.ligapk.com/exemple.php?id=${idMatch.groupValues[1]}"
                    println("[DaddyLiveExt] superdinamico/ligapk 우회 성공: $finalUrl")
                    return finalUrl
                }
            }
            
            // 2. lovecdn (lovetier) 우회
            val lcMatch = Regex("""<iframe[^>]+src=["'](https://lovecdn\.ru/[^"']+)["']""").find(response)
            if (lcMatch != null) {
                val embedUrl = lcMatch.groupValues[1]
                val streamNameMatch = Regex("""[?&]stream=([^&"'>\s]+)""").find(embedUrl)
                if (streamNameMatch != null) {
                    val ltUrl = "https://lovetier.bz/player/${streamNameMatch.groupValues[1]}"
                    val r2 = app.get(ltUrl, headers = mapOf("Referer" to embedUrl, "User-Agent" to userAgent)).text
                    val streamUrlMatch = Regex("""streamUrl\s*:\s*["'](https?:[^"']+)["']""").find(r2)
                    if (streamUrlMatch != null) {
                        val finalUrl = streamUrlMatch.groupValues[1].replace("\\/", "/")
                        println("[DaddyLiveExt] lovecdn/lovetier 우회 성공: $finalUrl")
                        return finalUrl
                    }
                }
            }
            
            // 3. 다이렉트 m3u8 (플레이어 페이지 소스 내 정규식 탐색)
            val m3u8Match = Regex("""["'](https?://[^\s"'<>]+\.m3u8[^"']*)["']""").find(response)
            if (m3u8Match != null) {
                println("[DaddyLiveExt] 직링 m3u8 발견: ${m3u8Match.groupValues[1]}")
                return m3u8Match.groupValues[1]
            }
            
            // 4. 기타 알 수 없는 서브 iframe 내부 탐색
            val iframes = Regex("""<iframe[^>]+src=["'](https?://[^"']{10,200})["']""").findAll(response)
            val skipHosts = listOf("sstatic", "histats", "adsco", "fidget", "chatango", "facebook.com", "google.com", "ksohls.ru")
            
            for (iframe in iframes) {
                val iframeUrl = iframe.groupValues[1]
                if (skipHosts.any { iframeUrl.contains(it) }) continue
                
                println("[DaddyLiveExt] 서브 iframe 파싱 중: $iframeUrl")
                try {
                    val ri = app.get(iframeUrl, headers = mapOf("Referer" to watchUrl, "User-Agent" to userAgent)).text
                    
                    val mi = Regex("""["'](https?://[^\s"'<>]+\.m3u8[^"']*)["']""").find(ri)
                    if (mi != null) {
                        println("[DaddyLiveExt] 서브 iframe에서 m3u8 직링 발견: ${mi.groupValues[1]}")
                        return mi.groupValues[1]
                    }
                    
                    val mi2 = Regex("""streamUrl\s*:\s*["'](https?:[^"']+)["']""").find(ri)
                    if (mi2 != null) {
                        val finalUrl = mi2.groupValues[1].replace("\\/", "/")
                        println("[DaddyLiveExt] 서브 iframe에서 streamUrl 발견: $finalUrl")
                        return finalUrl
                    }
                } catch (e: Exception) {
                    println("[DaddyLiveExt] 서브 iframe 내부 파싱 에러: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            println("[DaddyLiveExt] 페이지 HTTP 요청 에러: ${e.message}")
        }
        return null
    }
}
