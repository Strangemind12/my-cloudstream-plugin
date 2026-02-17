package com.anilife

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
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
 * Anilife Proxy Extractor v66.0
 * - [Feature] 'Active Fetch': onPageStarted 시점에 JS를 주입하여 M3U8과 Key를 강제로 fetch()
 * - [Fix] 32바이트 키에 대한 Sliding Window 전수 조사 유지
 * - [Fix] 웹뷰 미디어 제스처 요구 해제 (자동 재생/로딩 유도)
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
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        synchronized(this) { currentProxyServer?.stop(); currentProxyServer = null }

        println("[Anilife][Proxy] 1. 키 저장소 초기화")
        val sessionKeys = Collections.synchronizedSet(mutableSetOf<String>())
        
        println("[Anilife][Proxy] 2. 하이브리드(Active+Passive) 후킹 시작...")
        // [v66.0] m3u8Url 전달
        runWebViewHybridHook(playerUrl, m3u8Url, referer, ssid, cookies, sessionKeys)

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
            
            println("[Anilife][Proxy] 4. 프록시 링크 반환: $finalProxyUrl")
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

    private suspend fun runWebViewHybridHook(
        url: String,
        m3u8Url: String, 
        referer: String, 
        ssid: String?, 
        cookies: String, 
        sessionKeys: MutableSet<String>
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        // [v66.0] Active Fetch Script: M3U8을 직접 다운로드하고 파싱하여 키를 가져옴
        val hookScript = """
            (async function() {
                // 1. Passive Hook (기존 로직)
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
                const oS = Uint8Array.prototype.set;
                Uint8Array.prototype.set = function(src, off) {
                    if (src && (src.length === 16 || src.length === 32)) {
                        let hex = Array.from(src).map(b => b.toString(16).padStart(2, '0')).join('');
                        console.log("CapturedKeyHex:" + hex);
                    }
                    return oS.apply(this, arguments);
                };

                // 2. Active Fetch (강제 키 추출)
                try {
                    console.log("[JS] Fetching M3U8: $m3u8Url");
                    // no-referrer 정책 준수
                    const response = await fetch("$m3u8Url", { referrerPolicy: "no-referrer" });
                    const text = await response.text();
                    
                    // 정규식으로 URI="..." 추출
                    const match = text.match(/URI="([^"]+)"/);
                    if (match && match[1]) {
                        let keyUrl = match[1];
                        // 상대 주소 처리
                        if (!keyUrl.startsWith('http')) {
                            keyUrl = new URL(keyUrl, "$m3u8Url").href;
                        }
                        console.log("[JS] Key URL Found: " + keyUrl);
                        
                        const keyResp = await fetch(keyUrl, { referrerPolicy: "no-referrer" });
                        const keyBuf = await keyResp.arrayBuffer();
                        const bytes = new Uint8Array(keyBuf);
                        const hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
                        console.log("CapturedKeyHex:" + hex); // 결과 전송
                    }
                } catch(e) {
                    console.log("[JS] Active fetch failed: " + e.message);
                }
            })();
        """.trimIndent()

        handler.post {
            try {
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                
                // [v66.0] 자동 재생 및 제스처 무시 설정
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                    mediaPlaybackRequiresUserGesture = false // 중요!
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                        val msg = cm?.message() ?: ""
                        if (msg.contains("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:")
                            if (sessionKeys.add(key)) println("[Anilife][Hook] 키 확보 성공: $key")
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        // [v66.0] onPageStarted 즉시 주입
                        view?.evaluateJavascript(hookScript, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // 혹시 모르니 다시 주입
                        view?.evaluateJavascript(hookScript, null)
                        handler.postDelayed({ if (cont.isActive) { webView.destroy(); cont.resume(Unit) } }, 10000)
                    }
                }
                
                webView.loadUrl(url, mapOf("Referer" to referer))
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
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    out.write(verifiedKey ?: ByteArray(16))
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
                    
                    if (rawKey.size == 16) {
                        candidates.add(rawKey)
                    } else if (rawKey.size == 32) {
                        // [v66.0] 32바이트 Sliding Window 검증 유지
                        for (i in 0..16) {
                            candidates.add(rawKey.copyOfRange(i, i + 16))
                        }
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
