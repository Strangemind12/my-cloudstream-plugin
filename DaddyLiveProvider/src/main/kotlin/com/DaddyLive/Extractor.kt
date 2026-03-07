/**
 * DaddyLiveExtractor v8.1 (iPhone UA & Dynamic Referer Fix)
 * - [Fix] README.md에 명시된 최신 정책(iPhone User-Agent) 전면 적용하여 Cloudflare(응답길이 85KB) 차단 회피.
 * - [Fix] 백업 플레이어(lovecdn 등) 추출 시 ExtractorLink에 this.referer 속성이 누락되어 발생하던 'M3u8 must contains TS files' 및 ExoPlayer 403 에러 해결.
 * - [Fix] ksohls.ru 고정 주소 대신 동적 iframe 스캔을 통해 현재 라이브 중인 메인 플레이어 도메인(adffdafdsafds.sbs 등)을 자동 감지하여 토큰을 탈취함.
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
    
    data class ChannelState(val authToken: String, val channelSalt: String, val m3u8Url: String, val playerDomain: String)
    val activeChannels = mutableMapOf<String, ChannelState>()
    
    // README.md에 명시된 필수 우회 헤더 (Cloudflare 봇 차단 회피용 모바일 UA)
    val userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

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
                                "Referer" to "${state.playerDomain}/",
                                "Origin" to state.playerDomain,
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
                
                // M3U8 내부의 실시간 AES 키 요청 주소만 내 로컬 프록시로 가로채기
                m3u8Body = m3u8Body.replace(Regex("""URI="([^"]+)"""")) { match ->
                    val uri = match.groupValues[1]
                    val km = Regex("""/key/[^/]+/(\d+)""").find(uri)
                    if (km != null) {
                        """URI="http://127.0.0.1:$port/key/$channelKey/${km.groupValues[1]}""""
                    } else {
                        match.value
                    }
                }

                // 상대 경로로 된 영상 TS 조각들을 절대 경로(원본 CDN)로 변환
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
                    "Referer" to "${state.playerDomain}/",
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
        println("[DaddyLiveExt] v8.1 iPhone UA & Dynamic Referer 엔진 가동")

        for ((name, link) in links) {
            val idMatch = Regex("""(?:stream-|id=)(\d+)""").find(link)
            val channelId = idMatch?.groupValues?.get(1)
            
            if (channelId != null) {
                val channelKey = "premium$channelId"
                
                try {
                    val credentialsData = fetchAuthCredentials(channelId)
                    
                    if (credentialsData != null) {
                        val (authToken, channelSalt, playerDomain) = credentialsData
                        println("[DaddyLiveExt] [$channelKey] 암호화 토큰 획득 성공! (동적 감지 도메인: $playerDomain)")
                        
                        var serverKey = "zeko"
                        try {
                            val lookupUrl = "https://chevy.vovlacosa.sbs/server_lookup?channel_id=$channelKey"
                            val lookupJson = app.get(lookupUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$playerDomain/")).text
                            serverKey = Regex(""""server_key"\s*:\s*"([^"]+)"""").find(lookupJson)?.groupValues?.get(1) ?: "zeko"
                        } catch (e: Exception) {
                            println("[DaddyLiveExt] 서버 키 조회 실패, 기본값(zeko) 사용")
                        }
                        
                        val m3u8Url = if (serverKey == "top1/cdn") {
                            "https://chevy.soyspace.cyou/proxy/top1/cdn/$channelKey/mono.css"
                        } else {
                            "https://chevy.soyspace.cyou/proxy/$serverKey/$channelKey/mono.css"
                        }
                        
                        DaddyLiveProxy.activeChannels[channelKey] = DaddyLiveProxy.ChannelState(authToken, channelSalt, m3u8Url, playerDomain)
                        DaddyLiveProxy.startServer()
                        
                        val localUrl = "http://127.0.0.1:${DaddyLiveProxy.port}/m3u8/$channelKey"
                        
                        callback(newExtractorLink(name, name, localUrl, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.Unknown.value
                            // [Fix] ExoPlayer가 영상 조각(.ts) 다운로드 시 사용할 동적 Referer 명시
                            this.referer = "$playerDomain/" 
                            this.headers = mapOf(
                                "User-Agent" to userAgent,
                                "Origin" to playerDomain,
                                "Referer" to "$playerDomain/"
                            )
                        })
                        break
                    } else {
                        println("[DaddyLiveExt] [$channelKey] 메인 토큰 추출 실패. 백업 우회 경로 탐색으로 넘어갑니다.")
                    }
                } catch (e: Exception) {
                    println("[DaddyLiveExt] [$channelKey] 메인 프로세스 예외: ${e.message}")
                }
            }
            
            // 메인 추출 실패 시 백업 AnyPlayer 탐색
            val fallbackStreamUrl = if (channelId != null) getAnyPlayerStreamForId(channelId) else null
            
            if (fallbackStreamUrl != null) {
                println("[DaddyLiveExt] 백업 우회 성공: $fallbackStreamUrl")
                
                // [Fix] 백업 CDN 맞춤형 동적 Referer 생성 (M3u8 must contain TS files 에러 방지 핵심)
                val targetReferer = when {
                    fallbackStreamUrl.contains("lovecdn") -> "https://lovecdn.ru/"
                    fallbackStreamUrl.contains("lovetier") -> "https://lovetier.bz/"
                    fallbackStreamUrl.contains("superdinamico") -> "https://superdinamico.com/"
                    fallbackStreamUrl.contains("ligapk") -> "https://edg.ligapk.com/"
                    fallbackStreamUrl.contains("sanwalyaarpya") -> "https://stellarthread.com/"
                    else -> "https://adffdafdsafds.sbs/"
                }
                
                callback(newExtractorLink(name, name, fallbackStreamUrl, type = ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    // [Fix] Cloudstream의 safeApiCall과 ExoPlayer가 사용할 referer를 속성에 강제 주입
                    this.referer = targetReferer 
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Origin" to targetReferer.trimEnd('/'),
                        "Referer" to targetReferer
                    )
                })
                break
            }
        }
    }

    private suspend fun fetchAuthCredentials(channelId: String): Triple<String, String, String>? {
        val paths = listOf("stream", "cast", "watch", "plus", "player")
        val watchUrl = "$mainUrl/watch.php?id=$channelId"
        
        var playerDomain = "https://adffdafdsafds.sbs"
        var playerUrl = "$playerDomain/premiumtv/daddyhd.php?id=$channelId"

        // 1. iframe을 파싱하여 실시간으로 변경되는 메인 플레이어 도메인(ksohls.ru -> adffdafdsafds.sbs 등) 찾기
        for (path in paths) {
            try {
                val streamPageUrl = "$mainUrl/$path/stream-$channelId.php"
                val resp = app.get(streamPageUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to watchUrl))
                val iframeMatch = Regex("""<iframe[^>]+src=["'](https?://[^"']+premiumtv[^"']+)["']""").find(resp.text)
                if (iframeMatch != null) {
                    playerUrl = iframeMatch.groupValues[1]
                    val domainMatch = Regex("""(https?://[^/]+)""").find(playerUrl)
                    if (domainMatch != null) {
                        playerDomain = domainMatch.groupValues[1]
                    }
                    break
                }
            } catch (e: Exception) {}
        }
        
        // 2. 알아낸 메인 플레이어 URL에서 암호화 토큰 추출 (iPhone UA로 Cloudflare 우회)
        try {
            val resp = app.get(playerUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
            if (resp.isSuccessful) {
                val at = extractCredential(resp.text, "authToken")
                val cs = extractCredential(resp.text, "channelSalt")
                if (at != null && cs != null) return Triple(at, cs, playerDomain)
                println("[DaddyLiveExt] 토큰 정규식 파싱 실패. 캡챠/블록 가능성. 응답길이: ${resp.text.length}")
            }
        } catch (e: Exception) { }
        
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
            if (response.contains("premiumtv")) return null
            
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
            val skipHosts = listOf("sstatic", "histats", "adsco", "fidget", "chatango", "facebook.com", "google.com")
            
            for (iframe in innerIframes) {
                val innerUrl = iframe.groupValues[1]
                if (skipHosts.any { innerUrl.contains(it) }) continue
                if (innerUrl.contains("premiumtv")) continue
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
