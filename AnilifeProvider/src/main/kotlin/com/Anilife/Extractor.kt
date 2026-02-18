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
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedReader
import java.io.ByteArrayInputStream
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
 * Anilife Proxy Extractor v78.0
 * - [Debug] 모든 단계에 상세 로그 추가 (성공/실패/URL/데이터크기)
 * - [Logic] 리소스 변조(Trojan) 방식 유지
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

        println("[Anilife][Proxy] 1. 프록시 서버 시작 준비")
        val sessionKeys = Collections.synchronizedSet(mutableSetOf<String>())

        println("[Anilife][Proxy] 2. 리소스 변조 웹뷰 실행 요청 (TargetKey: $directKeyUrl)")
        runWebViewResourceInjection(playerUrl, referer, ssid, cookies, directKeyUrl, sessionKeys)

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
            println("[Anilife][Proxy] M3U8 원본 다운로드 완료 (Size: ${res.text.length})")
            
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
                            var uri = match.groupValues[1]
                            if (!uri.startsWith("http")) uri = baseUri.resolve(uri).toString()
                            val encKey = java.net.URLEncoder.encode(uri, "UTF-8")
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
            
            println("[Anilife][Proxy] 3. 프록시 URL 반환: $finalProxyUrl")
            callback(newExtractorLink(name, name, finalProxyUrl, ExtractorLinkType.M3U8) {
                this.referer = ""
                this.headers = proxy.getCurrentHeaders()
            })
            return true

        } catch (e: Exception) {
            println("[Anilife][Proxy] 에러: ${e.message}")
            return false
        }
    }

    private suspend fun runWebViewResourceInjection(
        url: String, 
        referer: String, 
        ssid: String?,
        cookies: String,
        targetKeyUrl: String?,
        sessionKeys: MutableSet<String>
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        // Passive Hook Code
        val passiveHookCode = """
            (function() {
                if (window.keyHookInjected) return;
                window.keyHookInjected = true;
                console.log("CapturedKeyHex: [INIT] Injected into " + window.location.href);

                if (window.crypto && window.crypto.subtle) {
                    const oI = window.crypto.subtle.importKey;
                    window.crypto.subtle.importKey = function(f, k, ...args) {
                        if (f === 'raw' && (k.byteLength === 16 || k.byteLength === 32)) {
                            let hex = Array.from(new Uint8Array(k)).map(b => b.toString(16).padStart(2, '0')).join('');
                            console.log("CapturedKeyHex:" + hex);
                        }
                        return oI.apply(this, [f, k, ...args]);
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

        // Active Fetch Code
        val activeFetchCode = if (targetKeyUrl != null) """
            <script>
            (async function() {
                console.log("[JS] Active Fetching: $targetKeyUrl");
                try {
                    const response = await fetch("$targetKeyUrl", { referrerPolicy: "no-referrer", credentials: "include" });
                    if (!response.ok) {
                        console.log("[JS] Fetch Failed: " + response.status);
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
            </script>
        """.trimIndent() else ""

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
                            if (!key.startsWith("[INIT]") && sessionKeys.add(key)) {
                                println("[Anilife][Hook] ★키 발견★: $key")
                            } else if (key.startsWith("[INIT]")) {
                                println("[Anilife][Hook] 초기화 완료.")
                            }
                        } else if (msg.contains("[JS]")) {
                            println("[Anilife][JS] $msg")
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url.toString()
                        
                        // 1. HTML 변조 (Active Fetch 주입)
                        if (reqUrl.contains("/h/live") && !reqUrl.endsWith(".js") && !reqUrl.endsWith(".css") && !reqUrl.endsWith(".png")) {
                            println("[Anilife][Inject] HTML 요청 감지: $reqUrl")
                            try {
                                val headers = mutableMapOf("User-Agent" to DESKTOP_UA, "Referer" to referer, "Cookie" to cookies)
                                if (!ssid.isNullOrBlank()) headers["x-user-ssid"] = ssid
                                
                                val response = runBlocking { app.get(reqUrl, headers = headers) }
                                if (response.code != 200) {
                                    println("[Anilife][Inject] 원본 HTML 로드 실패: ${response.code}")
                                    return super.shouldInterceptRequest(view, request)
                                }

                                var html = response.text
                                // <head> 태그 뒤에 주입, 없으면 <html> 뒤, 없으면 맨 앞
                                if (html.contains("<head>")) {
                                    html = html.replaceFirst("<head>", "<head>\n$activeFetchCode")
                                } else if (html.contains("<html>")) {
                                    html = html.replaceFirst("<html>", "<html>\n$activeFetchCode")
                                } else {
                                    html = "$activeFetchCode\n$html"
                                }
                                
                                println("[Anilife][Inject] HTML 변조 완료 및 반환")
                                return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
                            } catch (e: Exception) { println("[Anilife][Inject] HTML 처리 중 예외: ${e.message}") }
                        }
                        
                        // 2. JS 변조 (Passive Hook 주입)
                        // 플레이어 스크립트, hls 관련, 또는 일반 js 파일
                        if (reqUrl.endsWith(".js") || reqUrl.contains(".js?") || reqUrl.contains("player") || reqUrl.contains("hls")) {
                            // println("[Anilife][Inject] JS 요청 감지: $reqUrl")
                            try {
                                val headers = mutableMapOf("User-Agent" to DESKTOP_UA, "Referer" to referer, "Cookie" to cookies)
                                if (!ssid.isNullOrBlank()) headers["x-user-ssid"] = ssid
                                
                                val response = runBlocking { app.get(reqUrl, headers = headers) }
                                val js = passiveHookCode + "\n" + response.text
                                
                                return WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(js.toByteArray()))
                            } catch (e: Exception) {}
                        }
                        
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        println("[Anilife][Inject] 페이지 로드 완료. (15초 대기 시작)")
                        handler.postDelayed({ if (cont.isActive) { webView.destroy(); cont.resume(Unit) } }, 15000)
                    }
                }
                
                println("[Anilife][Inject] 웹뷰 loadUrl 시작: $url")
                webView.loadUrl(url, mapOf("Referer" to referer))
            } catch (e: Exception) { 
                println("[Anilife][Inject] 웹뷰 생성 실패: ${e.message}")
                if (cont.isActive) cont.resume(Unit) 
            }
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
            println("[Anilife][Proxy] 서버 시작됨 (Port: $port)")
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

            // println("[Anilife][Proxy] 요청 수신: $path")

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
                        println("[Anilife][Proxy] 키 반환 실패 (검증 실패)")
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
            println("[Anilife][Verify] 키 검증 진입 (후보: ${sessionKeys.size}개)")
            
            if (sessionKeys.isEmpty()) {
                println("[Anilife][Verify] 실패: 수집된 키가 0개입니다.")
                return@runBlocking null
            }

            try {
                val data = app.get(url, headers = headers).body.bytes()
                val chunk = data.take(1024).toByteArray()

                sessionKeys.forEach { hex ->
                    try {
                        val key = hex.hexToByteArray()
                        val candidates = mutableListOf<ByteArray>()
                        
                        if (key.size == 16) candidates.add(key)
                        else if (key.size == 32) {
                            for (i in 0..16) candidates.add(key.copyOfRange(i, i + 16))
                        }

                        for (k in candidates) {
                            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(ByteArray(16)))
                            val dec = cipher.doFinal(chunk)
                            if (dec.size > 188 && dec[0] == 0x47.toByte() && dec[188] == 0x47.toByte()) {
                                println("[Anilife][Verify] 검증 성공! Key: ${k.joinToString("") { "%02x".format(it) }}")
                                return@runBlocking k
                            }
                        }
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) { println("[Anilife][Verify] 검증 예외: ${e.message}") }
            null
        }
    }
}

fun String.hexToByteArray(): ByteArray {
    val data = ByteArray(length / 2)
    for (i in 0 until length step 2) data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
    return data
}
