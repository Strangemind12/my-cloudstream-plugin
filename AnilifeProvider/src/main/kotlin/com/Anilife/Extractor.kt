package com.anilife

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * Anilife Proxy Extractor v74.0
 * - [Critical Fix] 웹뷰 타임아웃 해결: 무거운 플레이어 페이지 대신 가벼운 'robots.txt'를 로드하여 즉시 JS 실행 유도
 * - [Logic] Origin(https://anilife.live)은 유지하면서 로딩 속도를 최적화하여 JS Fetch 성공률 극대화
 * - [Fix] 32바이트 키 슬라이딩 윈도우 검증 유지
 */
class AnilifeProxyExtractor : ExtractorApi() {
    override val name = "AnilifeProxy"
    override val mainUrl = "https://api.gcdn.app"
    override val requiresReferer = false

    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    companion object {
        @Volatile private var currentProxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) { }

    fun extractPlayerUrl(html: String, domain: String): String? {
        val patterns = listOf(
            Regex("""location\.href\s*=\s*["']([^"']+)["']"""),
            Regex("""["']([^"']*h\/live\?p=[^"']+)["']""")
        )
        for (regex in patterns) {
            regex.find(html)?.let {
                var url = it.groupValues[1]
                if (url.contains("h/live") && url.contains("p=")) {
                    if (!url.startsWith("http")) url = if (url.startsWith("/")) "$domain$url" else "$domain/$url"
                    return url.replace("\\/", "/")
                }
            }
        }
        return null
    }

    suspend fun extractWithProxy(
        m3u8Url: String,
        playerUrl: String,
        referer: String,
        ssid: String?,
        cookies: String,
        directKeyUrl: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        synchronized(this) { currentProxyServer?.stop(); currentProxyServer = null }

        println("[Anilife][Proxy] 1. 프록시 서버 초기화")
        val sessionKeys = Collections.synchronizedSet(mutableSetOf<String>())

        // [v74.0] 키 URL이 있을 때만 Active Fetch 시도 (가벼운 페이지 로드)
        if (directKeyUrl != null) {
            println("[Anilife][Proxy] 2. Fast JS Fetch 시작 (Target: $directKeyUrl)...")
            // playerUrl 대신 가벼운 페이지 사용
            runWebViewFastFetch(referer, directKeyUrl, sessionKeys)
        } else {
            println("[Anilife][Proxy] 2. 키 URL 없음. Passive Hook 대기.")
            runWebViewFastFetch(referer, null, sessionKeys)
        }

        val proxy = ProxyWebServer(sessionKeys).apply { 
            start()
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Origin" to "https://anilife.live",
                "Cookie" to cookies,
                "Accept" to "*/*"
            )
            if (!ssid.isNullOrBlank()) {
                headers["x-user-ssid"] = ssid
                headers["X-User-Ssid"] = ssid
            }
            updateSession(headers)
        }
        currentProxyServer = proxy

        try {
            val res = app.get(m3u8Url, headers = proxy.getCurrentHeaders())
            val content = res.text
            val baseUri = URI(m3u8Url)
            val sb = StringBuilder()
            
            content.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                
                when {
                    trimmed.startsWith("#EXT-X-KEY") -> {
                        val match = Regex("""URI="([^"]+)"""").find(trimmed)
                        if (match != null) {
                            val absKey = baseUri.resolve(match.groupValues[1]).toString()
                            val encKey = java.net.URLEncoder.encode(absKey, "UTF-8")
                            sb.append(trimmed.replace(match.groupValues[1], "http://127.0.0.1:${proxy.port}/key?url=$encKey")).append("\n")
                        } else sb.append(trimmed).append("\n")
                    }
                    !trimmed.startsWith("#") -> {
                        val absSeg = baseUri.resolve(trimmed).toString()
                        proxy.setTestSegment(absSeg)
                        val encSeg = java.net.URLEncoder.encode(absSeg, "UTF-8")
                        sb.append("http://127.0.0.1:${proxy.port}/seg?url=$encSeg").append("\n")
                    }
                    else -> sb.append(trimmed).append("\n")
                }
            }
            
            proxy.setPlaylist(sb.toString())
            val finalProxyUrl = "http://127.0.0.1:${proxy.port}/playlist.m3u8"
            
            println("[Anilife][Proxy] 3. 프록시 링크 반환: $finalProxyUrl")
            callback(newExtractorLink(name, name, finalProxyUrl, ExtractorLinkType.M3U8) {
                this.referer = ""
                this.headers = proxy.getCurrentHeaders()
            })
            return true

        } catch (e: Exception) {
            println("[Anilife][Proxy] Error: ${e.message}")
            return false
        }
    }

    // [v74.0] 무거운 페이지 대신 가벼운 페이지(robots.txt)를 로드하여 JS 실행 속도 향상
    private suspend fun runWebViewFastFetch(
        referer: String, 
        targetKeyUrl: String?, 
        sessionKeys: MutableSet<String>
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        // JS 스크립트 (Fetch with Credentials)
        val script = StringBuilder()
        
        // Passive Hook
        script.append("""
            (function() {
                if (window.crypto && window.crypto.subtle) {
                    const oI = window.crypto.subtle.importKey;
                    window.crypto.subtle.importKey = function(f, k, ...) {
                        if (f === 'raw' && (k.byteLength === 16 || k.byteLength === 32 || k.length === 16 || k.length === 32)) {
                            let hex = Array.from(new Uint8Array(k)).map(b => b.toString(16).padStart(2, '0')).join('');
                            console.log("CapturedKeyHex:" + hex);
                        }
                        return oI.apply(this, arguments);
                    };
                }
            })();
        """.trimIndent())

        // Active Fetch
        if (targetKeyUrl != null) {
            script.append("""
                ; (async function() {
                    console.log("[JS] Fast Fetching: $targetKeyUrl");
                    try {
                        const response = await fetch("$targetKeyUrl", { 
                            referrerPolicy: "no-referrer",
                            credentials: "include" 
                        });
                        if (!response.ok) {
                            console.log("[JS] Fetch Failed. Status: " + response.status);
                            return;
                        }
                        const buffer = await response.arrayBuffer();
                        const bytes = new Uint8Array(buffer);
                        const hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
                        console.log("CapturedKeyHex:" + hex);
                    } catch(e) {
                        console.log("[JS] Fetch Error: " + e.message);
                    }
                })();
            """.trimIndent())
        }

        handler.post {
            try {
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                    mediaPlaybackRequiresUserGesture = false
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                        val msg = cm?.message() ?: ""
                        if (msg.contains("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:").trim()
                            if (sessionKeys.add(key)) println("[Anilife][Hook] 키 확보 성공: $key")
                        } else if (msg.contains("[JS]")) {
                            println("[Anilife][JS] $msg")
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(script.toString(), null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(script.toString(), null)
                        handler.postDelayed({ if (cont.isActive) { webView.destroy(); cont.resume(Unit) } }, 5000) // 5초 후 종료 (가벼운 페이지라 충분)
                    }
                }
                
                // [v74.0] 중요: 도메인은 같지만 가벼운 페이지 로드
                // anilife.live 도메인의 쿠키를 사용하기 위해 같은 도메인 내의 리소스 로드
                val lightPage = "https://anilife.live/robots.txt" 
                println("[Anilife][Proxy] 가벼운 페이지 로드: $lightPage")
                webView.loadUrl(lightPage, mapOf("Referer" to referer))
            } catch (e: Exception) { if (cont.isActive) cont.resume(Unit) }
        }
    }

    class ProxyWebServer(private val sessionKeys: MutableSet<String>) {
        var port: Int = 0
        private var server: ServerSocket? = null
        private var isRunning = false
        @Volatile private var headers: Map<String, String> = emptyMap()
        @Volatile private var playlist: String = ""
        @Volatile private var verifiedKey: ByteArray? = null
        @Volatile private var testSegment: String? = null

        fun start() {
            server = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread { while (isRunning) { try { handle(server!!.accept()) } catch (e: Exception) {} } }
        }

        fun stop() { isRunning = false; server?.close() }
        fun updateSession(h: Map<String, String>) { headers = h }
        fun setPlaylist(p: String) { playlist = p }
        fun setTestSegment(u: String) { if (testSegment == null) testSegment = u }
        fun getCurrentHeaders() = headers

        private fun handle(socket: Socket) {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val line = reader.readLine() ?: return
            val path = line.split(" ")[1]
            val out = socket.getOutputStream()

            when {
                path.contains("playlist.m3u8") -> {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray())
                    out.write(playlist.toByteArray())
                }
                path.contains("/key") -> {
                    if (verifiedKey == null) verifiedKey = verify()
                    
                    if (verifiedKey != null) {
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        out.write(verifiedKey!!)
                    } else {
                        out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    }
                }
                path.contains("/seg") -> {
                    val url = URLDecoder.decode(path.substringAfter("url="), "UTF-8")
                    runBlocking {
                        try {
                            val res = app.get(url, headers = headers)
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            out.write(res.body.bytes())
                        } catch (e: Exception) { }
                    }
                }
            }
            out.flush(); socket.close()
        }

        private fun verify(): ByteArray? = runBlocking {
            val url = testSegment ?: return@runBlocking null
            println("[Anilife][Verify] 키 검증 시도 (후보: ${sessionKeys.size}개)")
            try {
                val data = app.get(url, headers = headers).body.bytes()
                val chunk = data.take(1024).toByteArray()

                sessionKeys.forEach { hex ->
                    val rawKey = hex.hexToByteArray()
                    val candidates = mutableListOf<ByteArray>()
                    
                    if (rawKey.size == 16) candidates.add(rawKey)
                    else if (rawKey.size == 32) {
                        for (i in 0..16) candidates.add(rawKey.copyOfRange(i, i + 16))
                    }

                    for (key in candidates) {
                        try {
                            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
                            val dec = cipher.doFinal(chunk)
                            if (dec.size > 188 && dec[0] == 0x47.toByte() && dec[188] == 0x47.toByte()) {
                                println("[Anilife][Verify] 검증 성공: ${key.joinToString("") { "%02x".format(it) }}")
                                return@runBlocking key
                            }
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) { println("[Anilife][Verify] 실패: ${e.message}") }
            null
        }
    }
}

fun String.hexToByteArray(): ByteArray {
    val data = ByteArray(length / 2)
    for (i in 0 until length step 2) data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
    return data
}
