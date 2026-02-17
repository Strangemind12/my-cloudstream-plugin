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
import android.webkit.CookieManager
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
 * Anilife Proxy Extractor v65.0
 * - [Critical Fix] JS 후킹 스크립트 주입 시점을 onPageStarted로 변경하여 키 생성 전 가로채기 보장
 * - [Logic] JS Hook(메모리) + Network Hook(트래픽) 이중 감시 체제 구축
 * - [Fix] 32바이트 키 대응 및 슬라이딩 윈도우 검증 로직 유지
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
        
        println("[Anilife][Proxy] 2. 웹뷰 가동 (onPageStarted 후킹 적용)...")
        // [v65.0] onPageStarted 시점에서 후킹
        runWebViewHybridHook(playerUrl, referer, ssid, cookies, sessionKeys)

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
        referer: String, 
        ssid: String?, 
        cookies: String, 
        sessionKeys: MutableSet<String>
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        // [v65.0] JS Hook Script
        val hookScript = """
            (function() {
                // 이미 주입되었는지 확인 (중복 실행 방지)
                if (window.hookInjected) return;
                window.hookInjected = true;
                console.log("CapturedKeyHex: [INIT] Hook Injected at " + new Date().getTime());

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
            })();
        """.trimIndent()

        handler.post {
            try {
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = DESKTOP_UA

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                        val msg = cm?.message() ?: ""
                        if (msg.contains("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:").trim()
                            if (!key.startsWith("[INIT]") && sessionKeys.add(key)) {
                                println("[Anilife][Hook] JS 키 발견: $key")
                            } else if (key.startsWith("[INIT]")) {
                                println("[Anilife][Hook] 스크립트 주입 확인됨.")
                            }
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    // [v65.0 핵심] 페이지 로드 시작 시점에 스크립트 주입
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(hookScript, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // 혹시 모르니 완료 후에도 재주입 시도
                        view?.evaluateJavascript(hookScript, null)
                        // 10초 후 종료
                        handler.postDelayed({ if (cont.isActive) { webView.destroy(); cont.resume(Unit) } }, 10000)
                    }

                    // 네트워크 요청 인터셉트 (이중 안전장치)
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url.toString()
                        if (reqUrl.contains("enc.bin") || reqUrl.contains("/key")) {
                            println("[Anilife][Intercept] 네트워크 키 요청 감지: $reqUrl")
                            thread {
                                runBlocking {
                                    try {
                                        val headers = mutableMapOf(
                                            "User-Agent" to DESKTOP_UA,
                                            "Referer" to referer,
                                            "Cookie" to cookies
                                        )
                                        if (!ssid.isNullOrBlank()) headers["x-user-ssid"] = ssid
                                        
                                        val response = app.get(reqUrl, headers = headers)
                                        val bytes = response.body.bytes()
                                        if (bytes.size == 16 || bytes.size == 32) {
                                            val hex = bytes.joinToString("") { "%02x".format(it) }
                                            if (sessionKeys.add(hex)) println("[Anilife][Intercept] 네트워크 키 확보: $hex")
                                        }
                                    } catch (e: Exception) {
                                        println("[Anilife][Intercept] 실패: ${e.message}")
                                    }
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
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
                        // [v65.0] 슬라이딩 윈도우 (32바이트 중 16바이트 추출)
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
                                println("[Anilife][Verify] 성공! 정답 키 찾음: ${key.joinToString("") { "%02x".format(it) }}")
                                return@runBlocking key
                            }
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) { println("[Anilife][Verify] 검증 실패: ${e.message}") }
            null
        }
    }
}

fun String.hexToByteArray(): ByteArray {
    val data = ByteArray(length / 2)
    for (i in 0 until length step 2) data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
    return data
}
