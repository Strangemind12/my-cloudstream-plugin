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
 * - [Critical Fix] URL 식별 로직 수정: HTML URL에 'player' 파라미터가 있어도 JS로 오판하지 않도록 우선순위 조정
 * - [Logic] HTML(/h/live)은 무조건 Active Fetch 스크립트 삽입, JS(.js)는 Passive Hook 삽입
 * - [Fix] 32바이트 키 대응 및 Sliding Window 검증 유지
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

        println("[Anilife][Proxy] 1. 프록시 서버 및 키 저장소 초기화")
        val sessionKeys = Collections.synchronizedSet(mutableSetOf<String>())

        println("[Anilife][Proxy] 2. 트로이 목마 웹뷰(Trojan Injection v78) 가동... (Target Key: $directKeyUrl)")
        runWebViewTrojanInjection(playerUrl, referer, ssid, cookies, directKeyUrl, sessionKeys)

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

    private suspend fun runWebViewTrojanInjection(
        url: String, 
        referer: String, 
        ssid: String?,
        cookies: String,
        targetKeyUrl: String?,
        sessionKeys: MutableSet<String>
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        // 1. 수동 감시 코드 (JS 파일용)
        val passiveHookCode = """
            (function() {
                if (window.passiveHookInjected) return;
                window.passiveHookInjected = true;
                // console.log("[Hook] Passive JS Hook Loaded");
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
            })();
        """.trimIndent()

        // 2. 능동 다운로드 코드 (HTML 주입용)
        val activeFetchCode = if (targetKeyUrl != null) """
            <script>
            (async function() {
                console.log("[JS] Trojan Active Fetch Start: $targetKeyUrl");
                try {
                    const response = await fetch("$targetKeyUrl", { referrerPolicy: "no-referrer", credentials: "include" });
                    if (!response.ok) {
                        console.log("[JS] Fetch Failed: " + response.status);
                    } else {
                        const buffer = await response.arrayBuffer();
                        const bytes = new Uint8Array(buffer);
                        const hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
                        console.log("CapturedKeyHex:" + hex);
                    }
                } catch(e) {
                    console.log("[JS] Trojan Fetch Error: " + e.message);
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
                            if (sessionKeys.add(key)) println("[Anilife][Hook] ★키 발견★: $key")
                        } else if (msg.contains("[JS]")) {
                            println("[Anilife][JS] $msg")
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url.toString()
                        
                        // [v78.0 Fix] HTML과 JS 식별 로직 분리 및 우선순위 조정
                        
                        // 1. HTML (플레이어 페이지) -> Active Fetch 스크립트 주입
                        // .js가 '아닌' 것만 확실히 체크
                        if (reqUrl.contains("/h/live") && !reqUrl.contains(".js") && !reqUrl.contains(".css")) {
                            println("[Anilife][Inject] HTML 감지 (Trojan 대상): $reqUrl")
                            try {
                                val headers = mutableMapOf("User-Agent" to DESKTOP_UA, "Referer" to referer, "Cookie" to cookies)
                                if (!ssid.isNullOrBlank()) headers["x-user-ssid"] = ssid
                                
                                val response = runBlocking { app.get(reqUrl, headers = headers) }
                                var html = response.text
                                
                                // <head> 태그 뒤에 주입. 없으면 <html> 뒤.
                                if (html.contains("<head>")) {
                                    html = html.replaceFirst("<head>", "<head>\n$activeFetchCode")
                                } else if (html.contains("<html>")) {
                                    html = html.replaceFirst("<html>", "<html>\n$activeFetchCode")
                                } else {
                                    html = "$activeFetchCode\n$html"
                                }
                                
                                println("[Anilife][Inject] HTML 변조 완료 -> 반환")
                                return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
                            } catch (e: Exception) { println("[Anilife][Inject] HTML 변조 실패: ${e.message}") }
                        }
                        
                        // 2. JS 파일 -> Passive Hook 코드 전위
                        // 플레이어 URL이 아니면서 .js로 끝나는 경우만
                        else if (reqUrl.contains(".js") && !reqUrl.contains("/h/live")) {
                            // println("[Anilife][Inject] JS 감지: $reqUrl")
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
                        handler.postDelayed({ if (cont.isActive) { webView.destroy(); cont.resume(Unit) } }, 15000)
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
            println("[Anilife][Verify] 키 검증 진입 (후보: ${sessionKeys.size}개)")
            
            if (sessionKeys.isEmpty()) {
                println("[Anilife][Verify] 실패: 수집된 키 없음.")
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
                                println("[Anilife][Verify] 정답 키 확정: ${k.joinToString("") { "%02x".format(it) }}")
                                return@runBlocking k
                            }
                        }
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) { println("[Anilife][Verify] 에러: ${e.message}") }
            null
        }
    }
}

fun String.hexToByteArray(): ByteArray {
    val data = ByteArray(length / 2)
    for (i in 0 until length step 2) data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
    return data
}
