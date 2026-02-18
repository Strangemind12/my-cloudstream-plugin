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
import com.lagradost.cloudstream3.utils.*
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
 * Anilife Proxy Extractor v79.0
 * - [Strategy] Local Tunneling (Fake Path Request)
 * - [Difference] 기존 v66/v73은 외부 도메인 직접 요청으로 CORS 에러 발생 -> v79는 로컬 가짜 경로로 요청하여 CORS 원천 배제
 * - [Logic] JS: fetch("/__tunnel_key") -> Kotlin: Intercept & Download -> JS: Receive Data
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

        println("[Anilife][Proxy] 2. 로컬 터널링 웹뷰 가동 (Target Key: $directKeyUrl)")
        runWebViewTunneling(playerUrl, referer, ssid, cookies, directKeyUrl, sessionKeys)

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

    private suspend fun runWebViewTunneling(
        url: String, 
        referer: String, 
        ssid: String?,
        cookies: String,
        targetKeyUrl: String?,
        sessionKeys: MutableSet<String>
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        // [v79.0 핵심] 터널링 스크립트
        // fetch 요청 주소가 "/__tunnel_key" (로컬 경로)임에 주목하세요.
        val tunnelingScript = if (targetKeyUrl != null) """
            <script>
            (async function() {
                console.log("[JS] Tunneling Fetch Start (Fake Path)");
                try {
                    // CORS를 피하기 위해 자기 자신 도메인의 가짜 경로로 요청
                    const response = await fetch("/__tunnel_key", { 
                        method: 'GET',
                        cache: 'no-store'
                    });
                    
                    if (response.ok) {
                        const buffer = await response.arrayBuffer();
                        const bytes = new Uint8Array(buffer);
                        const hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
                        console.log("CapturedKeyHex:" + hex);
                    } else {
                        console.log("[JS] Tunneling Failed: " + response.status);
                    }
                } catch(e) {
                    console.log("[JS] Tunneling Error: " + e.message);
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
                            if (sessionKeys.add(key)) println("[Anilife][Tunnel] ★키 확보 성공★: $key")
                        } else if (msg.contains("[JS]")) {
                            println("[Anilife][JS] $msg")
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url.toString()
                        
                        // [v79.0 핵심] 가짜 경로(__tunnel_key) 요청을 낚아채서 실제 키를 다운로드
                        if (reqUrl.contains("__tunnel_key") && targetKeyUrl != null) {
                            println("[Anilife][Tunnel] 가짜 요청 감지 -> 실제 키 다운로드 수행")
                            try {
                                val headers = mutableMapOf("User-Agent" to DESKTOP_UA, "Referer" to referer, "Cookie" to cookies)
                                if (!ssid.isNullOrBlank()) headers["x-user-ssid"] = ssid
                                
                                // OkHttp로 실제 외부 키 다운로드 (CORS 무시)
                                val response = runBlocking { app.get(targetKeyUrl, headers = headers) }
                                val keyBytes = response.body.bytes()
                                
                                if (keyBytes.isNotEmpty()) {
                                    val hex = keyBytes.joinToString("") { "%02x".format(it) }
                                    sessionKeys.add(hex)
                                    println("[Anilife][Tunnel] 키 다운로드 완료 (Size: ${keyBytes.size})")
                                    
                                    // 32바이트 대응
                                    if (keyBytes.size == 32) {
                                        for (i in 0..16) {
                                            val part = keyBytes.copyOfRange(i, i + 16)
                                            sessionKeys.add(part.joinToString("") { "%02x".format(it) })
                                        }
                                        println("[Anilife][Tunnel] 32바이트 분해 완료")
                                    }
                                    
                                    // JS에게 키 데이터 반환
                                    return WebResourceResponse("application/octet-stream", "utf-8", ByteArrayInputStream(keyBytes))
                                }
                            } catch (e: Exception) {
                                println("[Anilife][Tunnel] 대리 다운로드 실패: ${e.message}")
                            }
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        // HTML 변조 (스크립트 주입) - v78의 식별 로직 적용
                        if (reqUrl.contains("/h/live") && !reqUrl.contains(".js") && !reqUrl.contains(".css")) {
                            println("[Anilife][Inject] HTML 감지: $reqUrl")
                            try {
                                val headers = mutableMapOf("User-Agent" to DESKTOP_UA, "Referer" to referer, "Cookie" to cookies)
                                if (!ssid.isNullOrBlank()) headers["x-user-ssid"] = ssid
                                
                                val response = runBlocking { app.get(reqUrl, headers = headers) }
                                var html = response.text
                                
                                // <head> 태그 뒤에 주입
                                if (html.contains("<head>")) {
                                    html = html.replaceFirst("<head>", "<head>\n$tunnelingScript")
                                } else if (html.contains("<html>")) {
                                    html = html.replaceFirst("<html>", "<html>\n$tunnelingScript")
                                } else {
                                    html = "$tunnelingScript\n$html"
                                }
                                
                                return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
                            } catch (e: Exception) { println("[Anilife][Inject] HTML 변조 실패: ${e.message}") }
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

    // ProxyWebServer 클래스는 v78과 동일 (생략 가능, 위 코드에 포함됨)
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
            } catch (e: Exception) { println("[Anilife][Verify] 검증 에러: ${e.message}") }
            null
        }
    }
}

fun String.hexToByteArray(): ByteArray {
    val data = ByteArray(length / 2)
    for (i in 0 until length step 2) data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
    return data
}
