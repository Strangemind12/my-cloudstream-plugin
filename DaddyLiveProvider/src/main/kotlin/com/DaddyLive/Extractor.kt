/**
 * DaddyLiveExtractor v9.1 (Server Lookup Auth & Auto-Fallback Fix)
 * - [Fix] server_lookup API 요청 시 Authorization(Bearer 토큰) 누락으로 인해 서버 키가 항상 'zeko'로 고정되어 일부 채널에서 ExoPlayer 2004 에러(404 Not Found)가 발생하던 치명적 버그 해결.
 * - [Fix] 서버 상태에 따라 mono.css 와 mono.m3u8 을 혼용하는 현상을 극복하기 위해, 프록시 내부에서 404 에러 감지 시 즉시 확장자를 스왑하여 재요청하는 Auto-Fallback 로직 추가.
 * - [Apply] WebView 키 요청 대기 타임아웃을 5초(5000ms)로 단축 적용.
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
                
                var responseText: String? = null
                var finalM3u8Url = state.m3u8Url
                
                // 1차 M3U8 다운로드 시도
                try {
                    val resp = runBlocking {
                        app.get(
                            finalM3u8Url, 
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to "${state.playerDomain}/",
                                "Origin" to state.playerDomain,
                                "Authorization" to "Bearer ${state.authToken}",
                                "X-Channel-Key" to channelKey,
                                "X-User-Agent" to userAgent,
                                "Cache-Control" to "no-cache"
                            ),
                            timeout = 10L
                        )
                    }
                    if (resp.isSuccessful) responseText = resp.text
                } catch (e: Exception) {
                    println("[DaddyLiveProxy] 1차 M3U8 요청 실패 (${finalM3u8Url}): ${e.message}")
                }
                
                // 1차 실패 시 (404/403) 파일명 확장자를 스왑하여 2차 재시도 (ExoPlayer 2004 에러 방지 핵심 로직)
                if (responseText == null) {
                    finalM3u8Url = if (finalM3u8Url.endsWith(".css")) finalM3u8Url.replace(".css", ".m3u8") else finalM3u8Url.replace(".m3u8", ".css")
                    println("[DaddyLiveProxy] 확장자 스왑하여 2차 M3U8 재시도: $finalM3u8Url")
                    
                    try {
                        val resp2 = runBlocking {
                            app.get(
                                finalM3u8Url, 
                                headers = mapOf(
                                    "User-Agent" to userAgent,
                                    "Referer" to "${state.playerDomain}/",
                                    "Origin" to state.playerDomain,
                                    "Authorization" to "Bearer ${state.authToken}",
                                    "X-Channel-Key" to channelKey,
                                    "X-User-Agent" to userAgent,
                                    "Cache-Control" to "no-cache"
                                ),
                                timeout = 10L
                            )
                        }
                        if (resp2.isSuccessful) responseText = resp2.text
                    } catch (e: Exception) {
                        println("[DaddyLiveProxy] 2차 M3U8 재시도 실패: ${e.message}")
                    }
                }
                
                // 2차까지 모두 실패 시 404 반환 -> ExoPlayer에서 2004 에러 발생
                if (responseText == null) { 
                    println("[DaddyLiveProxy] 최종 M3U8 획득 실패. 404 반환.")
                    send404(out)
                    return 
                }
                
                var m3u8Body = responseText
                val baseUrl = finalM3u8Url.substringBeforeLast("/") + "/"
                
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
        println("[DaddyLiveExt] v9.1 Server Lookup Auth & Auto-Fallback 엔진 가동")

        for ((name, link) in links) {
            val idMatch = Regex("""(?:stream-|id=)(\d+)""").find(link)
            val channelId = idMatch?.groupValues?.get(1)
            
            if (channelId != null) {
                val channelKey = "premium$channelId"
                
                try {
                    val credentialsData = fetchAuthCredentialsWithWebView(channelId)
                    
                    if (credentialsData != null) {
                        val (authToken, channelSalt, playerDomain) = credentialsData
                        println("[DaddyLiveExt] [$channelKey] 암호화 토큰 획득 성공! (도메인: $playerDomain)")
                        
                        var serverKey = "zeko"
                        try {
                            val lookupUrl = "https://chevy.vovlacosa.sbs/server_lookup?channel_id=$channelKey"
                            
                            // [Fix] 2004 에러의 핵심 원인: Authorization 헤더 누락 수정
                            val lookupJson = app.get(
                                lookupUrl, 
                                headers = mapOf(
                                    "User-Agent" to DaddyLiveProxy.userAgent, 
                                    "Referer" to "$playerDomain/",
                                    "Origin" to playerDomain,
                                    "Authorization" to "Bearer $authToken"
                                )
                            ).text
                            
                            serverKey = Regex(""""server_key"\s*:\s*"([^"]+)"""").find(lookupJson)?.groupValues?.get(1) ?: "zeko"
                            println("[DaddyLiveExt] server_lookup 성공. 할당된 서버 키: $serverKey")
                        } catch (e: Exception) {
                            println("[DaddyLiveExt] server_lookup API 예외 발생(403 등). 기본값(zeko) 사용: ${e.message}")
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
                            this.referer = "$playerDomain/" 
                            this.headers = mapOf(
                                "User-Agent" to DaddyLiveProxy.userAgent,
                                "Origin" to playerDomain,
                                "Referer" to "$playerDomain/"
                            )
                        })
                        break
                    } else {
                        println("[DaddyLiveExt] [$channelKey] 메인 토큰 획득 실패. 백업 우회 경로를 탐색합니다.")
                    }
                } catch (e: Exception) {
                    println("[DaddyLiveExt] [$channelKey] 메인 프로세스 예외: ${e.message}")
                }
            }
            
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
                    this.referer = targetReferer 
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
                    }
                    
                    DaddyLiveProxy.userAgent = webView.settings.userAgentString

                    val checkRunnable = object : Runnable {
                        override fun run() {
                            if (isFinished) return
                            webView.evaluateJavascript("(function() { return document.documentElement.innerHTML; })();") { html ->
                                val unescaped = html?.replace("\\u003C", "<")?.replace("\\\"", "\"") ?: ""
                                val at = extractCredential(unescaped, "authToken")
                                val cs = extractCredential(unescaped, "channelSalt")
                                
                                if (at != null && cs != null && !isFinished) {
                                    isFinished = true
                                    println("[DaddyLiveExt] WebView 루프 검사로 Cloudflare 통과 및 토큰 획득 성공!")
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
                                    println("[DaddyLiveExt] WebView 로드 직후 캡챠 돌파 및 토큰 획득 성공!")
                                    handler.post { webView.destroy() }
                                    if (cont.isActive) cont.resume(Triple(at, cs, playerDomain))
                                }
                            }
                        }
                    }
                    
                    webView.loadUrl(playerUrl, mutableMapOf("Referer" to "$mainUrl/"))
                    handler.postDelayed(checkRunnable, 1000) 

                    // [Fix] 사용자 설정: WebView 키 요청 대기 타임아웃을 정확히 5초(5000ms)로 적용
                    handler.postDelayed({
                        if (!isFinished && cont.isActive) {
                            isFinished = true
                            println("[DaddyLiveExt] WebView Cloudflare 우회 타임아웃 (5초 초과)")
                            webView.destroy()
                            cont.resume(null)
                        }
                    }, 5000)
                    
                } catch (e: Exception) {
                    println("[DaddyLiveExt] WebView 예외 발생: ${e.message}")
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
