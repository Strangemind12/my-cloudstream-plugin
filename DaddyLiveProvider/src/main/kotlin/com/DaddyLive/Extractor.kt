/**
 * DaddyLiveExtractor v8.0 (Ultimate Kodi Port)
 * - [Fix] 외부 EasyProxy에 의존하는 v7.0 방식 폐기. Kodi 애드온의 자체 복호화 프록시 엔진으로 롤백 및 완성.
 * - [Fix] 채널 ID 파싱 정규식을 개선하여 watch.php?id=84 및 stream-84.php 모두 완벽히 호환.
 * - [Fix] CDN 원본 플레이리스트 요청을 mono.css로 원복 (v6.2의 404 버그 해결).
 * - [Fix] Cloudflare 봇 차단을 피하기 위해 Kodi와 동일하게 최소한의 헤더(UA, Referer)만 전송.
 * - [성능] 영상 조각(.ts)은 로컬 프록시를 거치지 않고 ExoPlayer가 직접 다운로드하게 하여 버퍼링 제거.
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
                println("[DaddyLiveProxy] 로컬 프록시 서버 가동 완료 (Port: $port)")
                
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
                                "Cache-Control" to "no-cache",
                                "Pragma" to "no-cache"
                            ),
                            timeout = 10L
                        )
                        if (resp.isSuccessful) resp.text else null
                    }
                } catch (e: Exception) { null }
                
                if (responseText == null) { send404(out); return }
                
                var m3u8Body = responseText
                val baseUrl = state.m3u8Url.substringBeforeLast("/") + "/"
                
                // M3U8 내부의 AES 키 요청 주소만 내 로컬 프록시로 변조 (TS는 원본 CDN 유지)
                m3u8Body = m3u8Body.replace(Regex("""URI="([^"]+)"""")) { match ->
                    val uri = match.groupValues[1]
                    val km = Regex("""/key/[^/]+/(\d+)""").find(uri)
                    if (km != null) {
                        """URI="http://127.0.0.1:$port/key/$channelKey/${km.groupValues[1]}""""
                    } else {
                        match.value
                    }
                }

                // 상대 경로로 된 영상 TS 조각들을 절대 경로(CDN)로 변환 (ExoPlayer가 직접 다운받도록 유도)
                val lines = m3u8Body.lines().map { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                        if (trimmed.startsWith("http")) trimmed else baseUrl + trimmed
                    } else {
                        trimmed
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
                // Kodi와 완벽히 동일한 실시간 해시 연산 후 Key 다운로드
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
                        val resp = app.get(keyUrl, headers = headers, timeout = 10L)
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

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        println("[DaddyLiveExt] v8.0 Kodi Addon Direct Port 엔진 가동")

        for ((name, link) in links) {
            // [Fix] watch.php?id=84 와 stream-84.php 모두 추출 가능하도록 정규식 개선
            val idMatch = Regex("""(?:stream-|id=)(\d+)""").find(link)
            val channelId = idMatch?.groupValues?.get(1)
            
            if (channelId != null) {
                val channelKey = "premium$channelId"
                
                try {
                    val credentials = fetchAuthCredentials(channelId)
                    
                    if (credentials != null) {
                        val (authToken, channelSalt) = credentials
                        println("[DaddyLiveExt] [$channelKey] 암호화 토큰 획득 성공!")
                        
                        var serverKey = "zeko"
                        try {
                            val lookupUrl = "https://chevy.vovlacosa.sbs/server_lookup?channel_id=$channelKey"
                            val lookupJson = app.get(lookupUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "https://www.ksohls.ru/")).text
                            serverKey = Regex(""""server_key"\s*:\s*"([^"]+)"""").find(lookupJson)?.groupValues?.get(1) ?: "zeko"
                        } catch (e: Exception) {
                            println("[DaddyLiveExt] 서버 키 조회 실패, 기본값(zeko) 사용")
                        }
                        
                        // [Fix] Kodi 원본과 동일하게 mono.css 요청 (m3u8로 바꾸면 404 발생)
                        val m3u8Url = if (serverKey == "top1/cdn") {
                            "https://chevy.soyspace.cyou/proxy/top1/cdn/$channelKey/mono.css"
                        } else {
                            "https://chevy.soyspace.cyou/proxy/$serverKey/$channelKey/mono.css"
                        }
                        println("[DaddyLiveExt] [$channelKey] 원본 M3U8 주소: $m3u8Url")
                        
                        DaddyLiveProxy.activeChannels[channelKey] = DaddyLiveProxy.ChannelState(authToken, channelSalt, m3u8Url)
                        DaddyLiveProxy.startServer()
                        
                        val localUrl = "http://127.0.0.1:${DaddyLiveProxy.port}/m3u8/$channelKey"
                        
                        // ExoPlayer가 TS 세그먼트를 CDN에서 직접 다운받을 수 있도록 헤더 주입
                        callback(newExtractorLink(name, name, localUrl, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to userAgent,
                                "Origin" to "https://www.ksohls.ru",
                                "Referer" to "https://www.ksohls.ru/"
                            )
                        })
                        break // 메인 스트림 성공 시 즉시 종료
                    } else {
                        println("[DaddyLiveExt] [$channelKey] 토큰 추출 실패. 백업 우회 경로 탐색으로 넘어갑니다.")
                    }
                } catch (e: Exception) {
                    println("[DaddyLiveExt] [$channelKey] 메인 프로세스 예외: ${e.message}")
                }
            }
            
            // 메인 추출 실패 시 백업 AnyPlayer 탐색
            val fallbackStreamUrl = if (channelId != null) getAnyPlayerStreamForId(channelId) else null
            
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
        // 1순위: 메인 플레이어 (Kodi와 완벽히 동일한 최소 헤더만 전송하여 차단 회피)
        val playerUrl1 = "https://www.ksohls.ru/premiumtv/daddyhd.php?id=$channelId"
        try {
            val resp1 = app.get(playerUrl1, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
            if (resp1.isSuccessful) {
                val at = extractCredential(resp1.text, "authToken")
                val cs = extractCredential(resp1.text, "channelSalt")
                if (at != null && cs != null) return Pair(at, cs)
                println("[DaddyLiveExt] 메인 플레이어 내 토큰 없음. 응답길이: ${resp1.text.length}")
            }
        } catch (e: Exception) { 
            println("[DaddyLiveExt] 메인 플레이어 연결 실패: ${e.message}")
        }
        
        // 2순위: 동적 iframe 스캔 (Kodi 백업 로직)
        val paths = listOf("stream", "cast", "watch", "plus", "player")
        val watchUrl = "$mainUrl/watch.php?id=$channelId"
        for (path in paths) {
            try {
                val streamPageUrl = "$mainUrl/$path/stream-$channelId.php"
                val resp2 = app.get(streamPageUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to watchUrl))
                
                val iframes = Regex("""<iframe[^>]+src=["'](https?://[^"']+premiumtv[^"']+)["']""").findAll(resp2.text)
                for (iframeMatch in iframes) {
                    val playerUrl2 = iframeMatch.groupValues[1]
                    val resp3 = app.get(playerUrl2, headers = mapOf("User-Agent" to userAgent, "Referer" to streamPageUrl))
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

    private suspend fun getAnyPlayerStreamForId(channelId: String): String? {
        val watchUrl = "$mainUrl/watch.php?id=$channelId"
        val paths = listOf("stream", "cast", "watch", "plus", "player")
        for (path in paths) {
            try {
                val streamPageUrl = "$mainUrl/$path/stream-$channelId.php"
                val html = app.get(streamPageUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to watchUrl)).text
                
                val iframes = Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(html)
                for (iframeMatch in iframes) {
                    var iframeUrl = iframeMatch.groupValues[1]
                    if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"
                    if (!iframeUrl.startsWith("http")) continue
                    
                    val streamUrl = parseAnyPlayer(iframeUrl, streamPageUrl)
                    if (streamUrl != null) return streamUrl
                }
            } catch (e: Exception) {}
        }
        return null
    }

    private suspend fun parseAnyPlayer(iframeUrl: String, referer: String): String? {
        try {
            val response = app.get(iframeUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to referer)).text
            if (response.contains("ksohls.ru") || iframeUrl.contains("ksohls.ru")) return null
            
            var sdEmbedUrl = if (iframeUrl.contains("superdinamico.com/embed.php")) iframeUrl else null
            if (sdEmbedUrl == null) {
                val sdMatch = Regex("""<iframe[^>]+src=["'](https://[^"']+\.superdinamico\.com/embed\.php[^"']*)["']""").find(response)
                if (sdMatch != null) sdEmbedUrl = sdMatch.groupValues[1]
            }
            if (sdEmbedUrl != null) {
                val r2 = if (sdEmbedUrl == iframeUrl) response else app.get(sdEmbedUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to iframeUrl)).text
                val idMatch = Regex("""get_stream\.php\?id=([a-f0-9]+)""").find(r2)
                if (idMatch != null) return "https://edg.ligapk.com/exemple.php?id=${idMatch.groupValues[1]}"
            }

            var lcEmbedUrl = if (iframeUrl.contains("lovecdn.ru")) iframeUrl else null
            if (lcEmbedUrl == null) {
                val lcMatch = Regex("""<iframe[^>]+src=["'](https://lovecdn\.ru/[^"']+)["']""").find(response)
                if (lcMatch != null) lcEmbedUrl = lcMatch.groupValues[1]
            }
            if (lcEmbedUrl != null) {
                val streamNameMatch = Regex("""[?&]stream=([^&"'>\s]+)""").find(lcEmbedUrl)
                if (streamNameMatch != null) {
                    val ltUrl = "https://lovetier.bz/player/${streamNameMatch.groupValues[1]}"
                    val r2 = app.get(ltUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to lcEmbedUrl)).text
                    val streamUrlMatch = Regex("""streamUrl\s*:\s*["'](https?:[^"']+)["']""").find(r2)
                    if (streamUrlMatch != null) return streamUrlMatch.groupValues[1].replace("\\/", "/")
                }
            }

            val m3u8Match = Regex("""["'](https?://[^\s"'<>]+\.m3u8[^"']*)["']""").find(response)
            if (m3u8Match != null) return m3u8Match.groupValues[1]
            
            val innerIframes = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""").findAll(response)
            val skipHosts = listOf("sstatic", "histats", "adsco", "fidget", "chatango", "facebook.com", "google.com", "ksohls.ru")
            
            for (iframe in innerIframes) {
                val innerUrl = iframe.groupValues[1]
                if (skipHosts.any { innerUrl.contains(it) }) continue
                try {
                    val ri = app.get(innerUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to iframeUrl)).text
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
