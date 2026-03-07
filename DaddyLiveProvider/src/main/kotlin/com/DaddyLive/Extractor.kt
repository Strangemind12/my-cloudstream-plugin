/**
 * DaddyLiveExtractor v6.2 (Cloudflare Bypass & Full Headers Sync)
 * - [핵심 원인] 이전 버전에서 메인 토큰 추출이 막혔던 이유는, app.get 요청 시 브라우저 표준 헤더(Sec-Fetch 등)가 누락되어 Cloudflare의 봇 방어(403)에 차단되었기 때문임.
 * - [Fix] Kodi와 완벽히 동일한 위장 헤더(getStandardHeaders)를 모든 HTTP 통신에 주입하여 403 캡챠 페이지 차단을 원천 봉쇄.
 * - [Fix] 서버에서 요구하는 M3U8 원본 플레이리스트의 파일명을 mono.css -> mono.m3u8 로 정정.
 * - [Fix] 백업 경로(superdinamico, lovecdn)의 다중 iframe 스캔 정밀도를 향상하여 실패 시 2차 방어망 보강.
 */
package com.DaddyLive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

object DaddyLiveProxy {
    var port = 0
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    
    data class ChannelState(val authToken: String, val channelSalt: String, val m3u8Url: String)
    val activeChannels = mutableMapOf<String, ChannelState>()
    val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun startServer() {
        if (isRunning && serverSocket?.isClosed == false) return
        isRunning = true
        thread {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                println("[DaddyLiveProxy] 로컬 프록시 서버 가동 (Port: $port)")
                
                while (isRunning) {
                    try {
                        val client = serverSocket!!.accept()
                        thread { handleClient(client) }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            
            val path = parts[1].substringBefore("?")
            val out = client.getOutputStream()

            val segments = path.trim('/').split("/")
            if (segments.size >= 2 && segments[0] == "m3u8") {
                val channelKey = segments[1]
                val state = activeChannels[channelKey]
                if (state == null) { send404(out); return }
                
                val responseText = try {
                    runBlocking {
                        val resp = app.get(
                            state.m3u8Url, 
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to "https://www.ksohls.ru/",
                                "Authorization" to "Bearer ${state.authToken}",
                                "X-Channel-Key" to channelKey,
                                "X-User-Agent" to userAgent,
                                "Cache-Control" to "no-cache"
                            )
                        )
                        if (resp.isSuccessful) resp.text else null
                    }
                } catch (e: Exception) { null }
                
                if (responseText == null) { send404(out); return }
                
                var m3u8Body = responseText
                val baseUrl = state.m3u8Url.substringBeforeLast("/") + "/"
                
                m3u8Body = m3u8Body.replace(Regex("""URI="([^"]+)"""")) { match ->
                    val uri = match.groupValues[1]
                    val km = Regex("""/key/[^/]+/(\d+)""").find(uri)
                    if (km != null) {
                        """URI="http://127.0.0.1:$port/key/$channelKey/${km.groupValues[1]}""""
                    } else {
                        match.value
                    }
                }

                val lines = m3u8Body.lines().map { line ->
                    if (line.isNotBlank() && !line.startsWith("#")) {
                        if (line.startsWith("http")) line else baseUrl + line
                    } else {
                        line
                    }
                }
                m3u8Body = lines.joinToString("\n")

                val bytes = m3u8Body.toByteArray()
                out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
                out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                out.write("Connection: close\r\n\r\n".toByteArray())
                out.write(bytes)
                out.flush()

            } else if (segments.size >= 3 && segments[0] == "key") {
                val channelKey = segments[1]
                val keyId = segments[2]
                val state = activeChannels[channelKey]
                if (state == null) { send404(out); return }
                
                val ts = System.currentTimeMillis() / 1000
                val fp = computeFingerprint(userAgent)
                val nonce = computePowNonce(channelKey, state.channelSalt, keyId, ts)
                val authSig = computeAuthSig(channelKey, state.channelSalt, keyId, ts, fp)

                val keyUrl = "https://chevy.vovlacosa.sbs/key/$channelKey/$keyId"
                val headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "https://www.ksohls.ru/",
                    "Authorization" to "Bearer ${state.authToken}",
                    "X-Key-Timestamp" to ts.toString(),
                    "X-Key-Nonce" to nonce.toString(),
                    "X-Key-Path" to authSig,
                    "X-Fingerprint" to fp
                )
                
                val responseBytes = try {
                    runBlocking { 
                        val resp = app.get(keyUrl, headers = headers)
                        if (resp.isSuccessful) resp.okhttpResponse.body?.bytes() else null
                    }
                } catch (e: Exception) { null }
                
                if (responseBytes == null) { send404(out); return }
                
                out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                out.write("Content-Type: application/octet-stream\r\n".toByteArray())
                out.write("Content-Length: ${responseBytes.size}\r\n".toByteArray())
                out.write("Connection: close\r\n\r\n".toByteArray())
                out.write(responseBytes)
                out.flush()
            } else {
                send404(out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            client.close()
        }
    }

    private fun send404(out: OutputStream) {
        out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun computeFingerprint(ua: String): String = sha256(ua + "1920x1080UTCen").take(16)

    private fun computePowNonce(channelKey: String, channelSalt: String, keyId: String, ts: Long): Long {
        val hmacBase = hmacSha256(channelSalt, channelKey)
        for (nonce in 0L..100000L) {
            val combined = hmacBase + channelKey + keyId + ts.toString() + nonce.toString()
            val h = md5(combined)
            if (h.take(4).toInt(16) < 0x1000) return nonce
        }
        return 99999L
    }

    private fun computeAuthSig(channelKey: String, channelSalt: String, keyId: String, ts: Long, fp: String): String {
        return hmacSha256(channelSalt, "$channelKey|$keyId|$ts|$fp").take(16)
    }
}

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlstreams.top"
    override val name = "DaddyLive"
    override val requiresReferer = false
    
    private val userAgent = DaddyLiveProxy.userAgent

    // Cloudflare 차단 방지를 위한 브라우저 표준 헤더 세트 (Kodi 완벽 동기화)
    private fun getStandardHeaders(referer: String = "$mainUrl/"): Map<String, String> {
        return mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to referer,
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1"
        )
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        println("[DaddyLiveExt] v6.2 Cloudflare Bypass & Proxy 엔진 가동")

        for ((name, link) in links) {
            val idMatch = Regex("""stream-(\d+)""").find(link)
            val channelId = idMatch?.groupValues?.get(1)
            
            if (channelId != null) {
                val channelKey = "premium$channelId"
                
                try {
                    val credentials = fetchAuthCredentials(channelId)
                    
                    if (credentials != null) {
                        val (authToken, channelSalt) = credentials
                        println("[DaddyLiveExt] [$channelKey] 암호화 토큰 탈취 성공!")
                        
                        val lookupUrl = "https://chevy.vovlacosa.sbs/server_lookup?channel_id=$channelKey"
                        val lookupJson = app.get(lookupUrl, headers = getStandardHeaders("https://www.ksohls.ru/")).text
                        val serverKey = Regex(""""server_key"\s*:\s*"([^"]+)"""").find(lookupJson)?.groupValues?.get(1) ?: "zeko"
                        
                        // v6.2 Fix: mono.css -> mono.m3u8 확장자 변경 (Kodi 최신 사양 적용)
                        val m3u8Url = if (serverKey == "top1/cdn") {
                            "https://chevy.soyspace.cyou/proxy/top1/cdn/$channelKey/mono.m3u8"
                        } else {
                            "https://chevy.soyspace.cyou/proxy/$serverKey/$channelKey/mono.m3u8"
                        }
                        println("[DaddyLiveExt] [$channelKey] 원본 M3U8 할당됨: $m3u8Url")
                        
                        DaddyLiveProxy.activeChannels[channelKey] = DaddyLiveProxy.ChannelState(authToken, channelSalt, m3u8Url)
                        DaddyLiveProxy.startServer()
                        
                        val localUrl = "http://127.0.0.1:${DaddyLiveProxy.port}/m3u8/$channelKey"
                        
                        callback(newExtractorLink(name, name, localUrl, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to userAgent,
                                "Origin" to "https://www.ksohls.ru",
                                "Referer" to "https://www.ksohls.ru/"
                            )
                        })
                        break
                    } else {
                        println("[DaddyLiveExt] [$channelKey] 토큰 추출 실패. 백업 우회 경로 탐색으로 넘어갑니다.")
                    }
                } catch (e: Exception) {
                    println("[DaddyLiveExt] [$channelKey] 메인 프로세스 에러: ${e.message}")
                }
            }
            
            // 백업 우회망 탐색 (메인 추출 실패 시)
            val fallbackStreamUrl = if (channelId != null) extractFallbackLinks(channelId) else getAnyPlayerStream(link, mainUrl)
            
            if (fallbackStreamUrl != null) {
                println("[DaddyLiveExt] 백업 우회 성공: $fallbackStreamUrl")
                val origin = if (fallbackStreamUrl.contains("sanwalyaarpya.com")) "https://stellarthread.com" else "https://lefttoplay.xyz/"
                
                callback(newExtractorLink(name, name, fallbackStreamUrl, type = ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Origin" to origin,
                        "Referer" to origin
                    )
                })
                break
            }
        }
    }

    private suspend fun fetchAuthCredentials(channelId: String): Pair<String, String>? {
        // 1순위: 메인 플레이어 (Cloudflare 차단 우회를 위한 표준 헤더 사용)
        val playerUrl1 = "https://www.ksohls.ru/premiumtv/daddyhd.php?id=$channelId"
        try {
            val resp1 = app.get(playerUrl1, headers = getStandardHeaders())
            if (resp1.isSuccessful) {
                val at = extractCredential(resp1.text, "authToken")
                val cs = extractCredential(resp1.text, "channelSalt")
                if (at != null && cs != null) return Pair(at, cs)
            }
        } catch (e: Exception) { }
        
        // 2순위: 동적 iframe 스캔
        val paths = listOf("stream", "cast", "watch", "plus", "player")
        for (path in paths) {
            try {
                val streamPageUrl = "$mainUrl/$path/stream-$channelId.php"
                val html2 = app.get(streamPageUrl, headers = getStandardHeaders()).text
                
                val iframes = Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(html2)
                for (iframeMatch in iframes) {
                    var playerUrl2 = iframeMatch.groupValues[1]
                    if (playerUrl2.startsWith("//")) playerUrl2 = "https:$playerUrl2"
                    if (!playerUrl2.startsWith("http")) continue
                    
                    val resp3 = app.get(playerUrl2, headers = getStandardHeaders(streamPageUrl))
                    if (resp3.isSuccessful) {
                        val at = extractCredential(resp3.text, "authToken")
                        val cs = extractCredential(resp3.text, "channelSalt")
                        if (at != null && cs != null) return Pair(at, cs)
                    }
                }
            } catch (e: Exception) { }
        }
        return null
    }

    private fun extractCredential(page: String, field: String): String? {
        val m1 = Regex(field + """\s*:\s*['"]([^'"]+)['"]""").find(page)
        if (m1 != null) return m1.groupValues[1]
        
        val m2 = Regex(field + """\s*:\s*_dec_\w+\((_init_\w+),\s*(\d+)\)""").find(page)
        if (m2 != null) {
            val initName = m2.groupValues[1]
            val key = m2.groupValues[2].toInt()
            val arrM = Regex(initName + """\s*=\s*\[([^\]]+)\]""").find(page)
            if (arrM != null) {
                val arr = arrM.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
                return arr.map { (it xor key).toChar() }.joinToString("")
            }
        }
        return null
    }

    private suspend fun extractFallbackLinks(channelId: String): String? {
        val paths = listOf("stream", "cast", "watch", "plus", "player")
        for (path in paths) {
            try {
                val streamPageUrl = "$mainUrl/$path/stream-$channelId.php"
                val html = app.get(streamPageUrl, headers = getStandardHeaders()).text
                
                val iframes = Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(html)
                for (iframeMatch in iframes) {
                    var iframeUrl = iframeMatch.groupValues[1]
                    if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"
                    if (!iframeUrl.startsWith("http")) continue
                    
                    val streamUrl = getAnyPlayerStream(iframeUrl, streamPageUrl)
                    if (streamUrl != null) return streamUrl
                }
            } catch (e: Exception) {}
        }
        return null
    }

    private suspend fun getAnyPlayerStream(iframeUrl: String, referer: String): String? {
        try {
            val response = app.get(iframeUrl, headers = getStandardHeaders(referer)).text
            
            // ksohls는 프록시 전용이므로 백업에서는 스킵 처리
            if (response.contains("ksohls.ru") || iframeUrl.contains("ksohls.ru")) return null
            
            // 1. superdinamico 우회
            var sdEmbedUrl = if (iframeUrl.contains("superdinamico.com/embed.php")) iframeUrl else null
            if (sdEmbedUrl == null) {
                val sdMatch = Regex("""<iframe[^>]+src=["'](https://[^"']+\.superdinamico\.com/embed\.php[^"']*)["']""").find(response)
                if (sdMatch != null) sdEmbedUrl = sdMatch.groupValues[1]
            }
            if (sdEmbedUrl != null) {
                val r2 = if (sdEmbedUrl == iframeUrl) response else app.get(sdEmbedUrl, headers = getStandardHeaders(iframeUrl)).text
                val idMatch = Regex("""get_stream\.php\?id=([a-f0-9]+)""").find(r2)
                if (idMatch != null) return "https://edg.ligapk.com/exemple.php?id=${idMatch.groupValues[1]}"
            }

            // 2. lovecdn 우회
            var lcEmbedUrl = if (iframeUrl.contains("lovecdn.ru")) iframeUrl else null
            if (lcEmbedUrl == null) {
                val lcMatch = Regex("""<iframe[^>]+src=["'](https://lovecdn\.ru/[^"']+)["']""").find(response)
                if (lcMatch != null) lcEmbedUrl = lcMatch.groupValues[1]
            }
            if (lcEmbedUrl != null) {
                val streamNameMatch = Regex("""[?&]stream=([^&"'>\s]+)""").find(lcEmbedUrl)
                if (streamNameMatch != null) {
                    val ltUrl = "https://lovetier.bz/player/${streamNameMatch.groupValues[1]}"
                    val r2 = app.get(ltUrl, headers = getStandardHeaders(lcEmbedUrl)).text
                    val streamUrlMatch = Regex("""streamUrl\s*:\s*["'](https?:[^"']+)["']""").find(r2)
                    if (streamUrlMatch != null) return streamUrlMatch.groupValues[1].replace("\\/", "/")
                }
            }

            // 3. 다이렉트 m3u8
            val m3u8Match = Regex("""["'](https?://[^\s"'<>]+\.m3u8[^"']*)["']""").find(response)
            if (m3u8Match != null) return m3u8Match.groupValues[1]
            
            // 4. 알 수 없는 내부 iframe 재귀 파싱
            val innerIframes = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""").findAll(response)
            val skipHosts = listOf("sstatic", "histats", "adsco", "fidget", "chatango", "facebook.com", "google.com", "ksohls.ru")
            
            for (iframe in innerIframes) {
                val innerUrl = iframe.groupValues[1]
                if (skipHosts.any { innerUrl.contains(it) }) continue
                try {
                    val ri = app.get(innerUrl, headers = getStandardHeaders(iframeUrl)).text
                    val mi = Regex("""["'](https?://[^\s"'<>]+\.m3u8[^"']*)["']""").find(ri)
                    if (mi != null) return mi.groupValues[1]
                    
                    val mi2 = Regex("""streamUrl\s*:\s*["'](https?:[^"']+)["']""").find(ri)
                    if (mi2 != null) return mi2.groupValues[1].replace("\\/", "/")
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        return null
    }
}
