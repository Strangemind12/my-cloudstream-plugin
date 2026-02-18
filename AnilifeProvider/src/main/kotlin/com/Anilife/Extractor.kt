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
import org.json.JSONObject // JSON 파싱용

/**
 * Anilife Proxy Extractor v81.0
 * - [Debug] 다운로드된 데이터(678바이트 등)의 내용을 로그에 텍스트로 출력하여 정체 확인 (HTML vs JSON)
 * - [Feature] JSON 응답일 경우 자동 파싱하여 내부의 'key' 값 추출 시도
 * - [Logic] v79의 Local Tunneling 구조 유지 (다운로드 자체는 성공했으므로)
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
        
        val tunnelingScript = if (targetKeyUrl != null) """
            <script>
            (async function() {
                console.log("[JS] Tunneling Fetch Start");
                try {
                    const response = await fetch("/__tunnel_key", { method: 'GET', cache: 'no-store' });
                    if (response.ok) {
                        console.log("[JS] Tunneling Success!");
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
                        if (msg.contains("[JS]")) println("[Anilife][JS] $msg")
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url.toString()
                        
                        if (reqUrl.contains("__tunnel_key") && targetKeyUrl != null) {
                            println("[Anilife][Tunnel] 가상 요청 감지 -> 실제 키 다운로드")
                            try {
                                val headers = mutableMapOf("User-Agent" to DESKTOP_UA, "Referer" to referer, "Cookie" to cookies)
                                if (!ssid.isNullOrBlank()) headers["x-user-ssid"] = ssid
                                
                                val response = runBlocking { app.get(targetKeyUrl, headers = headers) }
                                val keyBytes = response.body.bytes()
                                val size = keyBytes.size
                                
                                println("[Anilife][Tunnel] 다운로드 완료. Size: $size bytes")

                                // [v81.0 핵심] 데이터 내용 까보기
                                if (size == 16 || size == 32) {
                                    // 바이너리 키일 확률 높음
                                    val hex = keyBytes.joinToString("") { "%02x".format(it) }
                                    sessionKeys.add(hex)
                                    println("[Anilife][Tunnel] ★키 확보 성공 (Binary)★: $hex")
                                    
                                    if (size == 32) {
                                        for (i in 0..16) sessionKeys.add(keyBytes.copyOfRange(i, i + 16).joinToString("") { "%02x".format(it) })
                                    }
                                } else {
                                    // [Debug] 텍스트로 변환하여 확인
                                    val textContent = String(keyBytes)
                                    println("[Anilife][Debug] ★응답 내용 확인★: $textContent")

                                    // JSON 파싱 시도
                                    if (textContent.trim().startsWith("{")) {
                                        try {
                                            println("[Anilife][Tunnel] JSON 감지 -> 파싱 시도")
                                            val json = JSONObject(textContent)
                                            // 일반적인 키 필드명 탐색
                                            val possibleKeys = listOf("key", "data", "token", "secret", "enc_key")
                                            for (k in possibleKeys) {
                                                if (json.has(k)) {
                                                    val valStr = json.getString(k)
                                                    println("[Anilife][Tunnel] JSON Key Found: $k = $valStr")
                                                    // Hex String? Base64? 일단 다 등록 시도
                                                    sessionKeys.add(valStr) 
                                                }
                                            }
                                        } catch (e: Exception) {
                                            println("[Anilife][Tunnel] JSON 파싱 실패: ${e.message}")
                                        }
                                    } else if (textContent.contains("html", true)) {
                                        println("[Anilife][Tunnel] 역시 HTML 에러 페이지였음.")
                                    }
                                }
                                
                                return WebResourceResponse("application/octet-stream", "utf-8", ByteArrayInputStream(keyBytes))
                            } catch (e: Exception) {
                                println("[Anilife][Tunnel] 다운로드 실패: ${e.message}")
                            }
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        // HTML 주입 (Trojan)
                        if (reqUrl.contains("/h/live") && !reqUrl.contains(".js") && !reqUrl.contains(".css")) {
                            println("[Anilife][Inject] HTML 감지 -> 터널링 스크립트 주입")
                            try {
                                val headers = mutableMapOf("User-Agent" to DESKTOP_UA, "Referer" to referer, "Cookie" to cookies)
                                if (!ssid.isNullOrBlank()) headers["x-user-ssid"] = ssid
                                
                                val response = runBlocking { app.get(reqUrl, headers = headers) }
                                var html = response.text
                                
                                if (html.contains("<head>")) {
                                    html = html.replaceFirst("<head>", "<head>\n$tunnelingScript")
                                } else {
                                    html = "$tunnelingScript\n$html"
                                }
                                
                                return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
                            } catch (e: Exception) { println("[Anilife][Inject] HTML 로드 실패: ${e.message}") }
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

                sessionKeys.forEach { keyStr ->
                    try {
                        // Hex String 또는 일반 String 처리
                        val rawKey = try { keyStr.hexToByteArray() } catch(e:Exception) { keyStr.toByteArray() }
                        val candidates = mutableListOf<ByteArray>()
                        
                        if (rawKey.size == 16) candidates.add(rawKey)
                        else if (rawKey.size >= 32) {
                            // 32바이트 이상이면 Hex String일 수도 있고 Base64일 수도 있음
                            // 일단 Hex Decoding 시도
                            try {
                                val hexDecoded = keyStr.hexToByteArray()
                                if (hexDecoded.size == 16) candidates.add(hexDecoded)
                            } catch(e:Exception) {}
                            
                            // Sliding Window (Raw Bytes)
                            if (rawKey.size == 32) {
                                for (i in 0..16) candidates.add(rawKey.copyOfRange(i, i + 16))
                            }
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
