/**
 * DaddyLiveExtractor v9.0 (Physical WebView Cloudflare Bypass)
 * - [핵심 해결] OkHttp(app.get)의 TLS 핑거프린트가 Cloudflare에 봇으로 적발되어 캡챠(85KB)에 막히는 현상 해결.
 * - [우회 기술] 백그라운드에 실제 안드로이드 WebView를 띄워 Cloudflare JS 연산을 물리적으로 통과(Auto-solve)시킨 후 토큰을 탈취함.
 * - [Fix] 백업 플레이어(lovecdn 등) 재생 시 발생하던 ExoPlayer 403 차단(M3u8 must contains TS files) 에러를 맞춤형 동적 Referer 주입으로 해결.
 */
package com.DaddyLive

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.coroutines.resume

object DaddyLiveProxy {
    var port = 0
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    
    data class ChannelState(val authToken: String, val channelSalt: String, val m3u8Url: String, val playerDomain: String)
    val activeChannels = mutableMapOf<String, ChannelState>()
    
    // WebView가 Cloudflare를 뚫을 때 썼던 실제 브라우저 UA를 그대로 넘겨받아 핑거프린트 일치시킴
    var userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

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
                                "Cache-Control" to "no-cache"
                            ),
                            timeout = 15L
                        )
                        if (resp.isSuccessful) resp.text else null
                    }
                } catch (e: Exception) { null }
                
                if (responseText == null) { send404(out); return }
                
                var m3u8Body = responseText
                val baseUrl = state.m3u8Url.substringBeforeLast("/") + "/"
                
                // M3U8 내부의 AES 키 요청 주소만 내 로컬 프록시로 변조
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

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        println("[DaddyLiveExt] v9.0 물리적 WebView Cloudflare 캡챠 우회 엔진 가동")

        for ((name, link) in links) {
            val idMatch = Regex("""(?:stream-|id=)(\d+)""").find(link)
            val channelId = idMatch?.groupValues?.get(1)
            
            if (channelId != null) {
                val channelKey = "premium$channelId"
                
                try {
                    // WebView를 띄워 Cloudflare를 완벽히 통과시키고 토큰을 가져옴
                    val credentialsData = fetchAuthCredentialsWithWebView(channelId)
                    
                    if (credentialsData != null) {
                        val (authToken, channelSalt, playerDomain) = credentialsData
                        println("[DaddyLiveExt] [$channelKey] 암호화 토큰 획득 성공! (도메인: $playerDomain)")
                        
                        var serverKey = "zeko"
                        try {
                            val lookupUrl = "https://chevy.vovlacosa.sbs/server_lookup?channel_id=$channelKey"
                            val lookupJson = app.get(lookupUrl, headers = mapOf("User-Agent" to DaddyLiveProxy.userAgent, "Referer" to "$playerDomain/")).text
                            serverKey = Regex(""""server_key"\s*:\s*"([^"]+)"""").find(lookupJson)?.groupValues?.get(1) ?: "zeko"
                        } catch (e: Exception) {}
                        
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
                            this.referer = "$playerDomain/" 
                            this.headers = mapOf(
                                "User-Agent" to DaddyLiveProxy.userAgent,
                                "Origin" to playerDomain,
                                "Referer" to "$playerDomain/"
                            )
                        })
                        break
                    } else {
                        println("[DaddyLiveExt] [$channelKey] 메인 토큰 획득 실패 (WebView 타임아웃). 백업 우회 경로를 탐색합니다.")
                    }
                } catch (e: Exception) {
                    println("[DaddyLiveExt] [$channelKey] 메인 프로세스 예외: ${e.message}")
                }
            }
            
            // 메인 추출 실패 시 백업 AnyPlayer 탐색 (안전한 Referer 강제 주입 포함)
            val fallbackStreamUrl = if (channelId != null) getAnyPlayerStreamForId(channelId) else null
            
            if (fallbackStreamUrl != null) {
                println("[DaddyLiveExt] 백업 우회 성공: $fallbackStreamUrl")
                
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
                    this.referer = targetReferer // ExoPlayer의 M3u8 must contain TS files 에러 원천 차단
                    this.headers = mapOf(
                        "User-Agent" to DaddyLiveProxy.userAgent,
                        "Origin" to targetReferer.trimEnd('/'),
                        "Referer" to targetReferer
                    )
                })
                break
            }
        }
    }

    private suspend fun fetchAuthCredentialsWithWebView(channelId: String): Triple<String, String, String>? {
        // 1. 동적 iframe(메인 플레이어) 주소 탐색
        val paths = listOf("stream", "cast", "watch", "plus", "player")
        val watchUrl = "$mainUrl/watch.php?id=$channelId"
        var playerDomain = "https://adffdafdsafds.sbs"
        var playerUrl = "$playerDomain/premiumtv/daddyhd.php?id=$channelId"

        for (path in paths) {
            try {
                val streamPageUrl = "$mainUrl/$path/stream-$channelId.php"
                val resp = app.get(streamPageUrl, headers = mapOf("Referer" to watchUrl))
                val iframeMatch = Regex("""<iframe[^>]+src=["'](https?://[^"']+premiumtv[^"']+)["']""").find(resp.text)
                if (iframeMatch != null) {
                    playerUrl = iframeMatch.groupValues[1]
                    val domainMatch = Regex("""(https?://[^/]+)""").find(playerUrl)
                    if (domainMatch != null) playerDomain = domainMatch.groupValues[1]
                    break
                }
            } catch (e: Exception) {}
        }
        
        // 2. 실제 안드로이드 브라우저 렌더러(WebView)를 띄워 Cloudflare 물리적 돌파
        return suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                try {
                    val context = AcraApplication.context ?: error("앱 컨텍스트를 찾을 수 없습니다.")
                    val webView = WebView(context)
                    var isFinished = false

                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // WebView 본연의 브라우저 UA를 그대로 사용하여 Cloudflare 핑거프린팅 완벽 회피
                    }
                    
                    // 향후 Proxy 통신 시 일치시킬 용도로 UA 기록
                    DaddyLiveProxy.userAgent = webView.settings.userAgentString

                    // 1초 단위로 Javascript를 주입하여 토큰(authToken)이 복호화되었는지 렌더링된 HTML을 스캔
                    val checkRunnable = object : Runnable {
                        override fun run() {
                            if (isFinished) return
                            webView.evaluateJavascript("(function() { return document.documentElement.innerHTML; })();") { html ->
                                val unescaped = html?.replace("\\u003C", "<")?.replace("\\\"", "\"") ?: ""
                                val at = extractCredential(unescaped, "authToken")
                                val cs = extractCredential(unescaped, "channelSalt")
                                
                                if (at != null && cs != null && !isFinished) {
                                    isFinished = true
                                    println("[DaddyLiveExt] WebView 루프 검사로 Cloudflare 캡챠 돌파 및 토큰 탈취 성공!")
                                    handler.post { webView.destroy() }
                                    if (cont.isActive) cont.resume(Triple(at, cs, playerDomain))
                                } else {
                                    handler.postDelayed(this, 1000)
                                }
                            }
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript("(function() { return document.documentElement.innerHTML; })();") { html ->
                                val unescaped = html?.replace("\\u003C", "<")?.replace("\\\"", "\"") ?: ""
                                val at = extractCredential(unescaped, "authToken")
                                val cs = extractCredential(unescaped, "channelSalt")
                                if (at != null && cs != null && !isFinished) {
                                    isFinished = true
                                    println("[DaddyLiveExt] WebView 로드 직후 캡챠 돌파 및 토큰 탈취 성공!")
                                    handler.post { webView.destroy() }
                                    if (cont.isActive) cont.resume(Triple(at, cs, playerDomain))
                                }
                            }
                        }
                    }
                    
                    // 메인 서버로 접속 (이 때 백그라운드에서 CF 캡챠가 자동으로 풀림)
                    webView.loadUrl(playerUrl, mutableMapOf("Referer" to "$mainUrl/"))
                    handler.postDelayed(checkRunnable, 2000) // 2초 후부터 HTML 스캔 시작

                    // 15초(충분한 캡챠 대기시간) 초과 시 타임아웃
                    handler.postDelayed({
                        if (!isFinished && cont.isActive) {
                            isFinished = true
                            println("[DaddyLiveExt] WebView Cloudflare 우회 타임아웃 (15초 초과)")
                            webView.destroy()
                            cont.resume(null)
                        }
                    }, 15000)
                    
                } catch (e: Exception) {
                    println("[DaddyLiveExt] WebView 에러 발생: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
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
                val html = app.get(streamPageUrl, headers = mapOf("Referer" to watchUrl)).text
                
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
            val response = app.get(iframeUrl, headers = mapOf("User-Agent" to DaddyLiveProxy.userAgent, "Referer" to referer)).text
            if (response.contains("premiumtv")) return null
            
            var sdEmbedUrl = if (iframeUrl.contains("superdinamico.com/embed.php")) iframeUrl else null
            if (sdEmbedUrl == null) {
                val sdMatch = Regex("""<iframe[^>]+src=["'](https://[^"']+\.superdinamico\.com/embed\.php[^"']*)["']""").find(response)
                if (sdMatch != null) sdEmbedUrl = sdMatch.groupValues[1]
            }
            if (sdEmbedUrl != null) {
                val r2 = if (sdEmbedUrl == iframeUrl) response else app.get(sdEmbedUrl, headers = mapOf("User-Agent" to DaddyLiveProxy.userAgent, "Referer" to iframeUrl)).text
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
                    val r2 = app.get(ltUrl, headers = mapOf("User-Agent" to DaddyLiveProxy.userAgent, "Referer" to lcEmbedUrl)).text
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
                    val ri = app.get(innerUrl, headers = mapOf("User-Agent" to DaddyLiveProxy.userAgent, "Referer" to iframeUrl)).text
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
