package com.tvwiki

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
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * Version: v2.1 (Hybrid Proxy & Dynamic Referer)
 * Modification [v2.1]:
 * 1. [STABILITY] 프록시 응답 헤더에 Connection: close 및 Content-Length 명시하여 ExoPlayer 무한 버퍼링 차단
 * 2.[MEMORY] 코루틴 취소(사용자 뒤로가기) 시 백그라운드 WebView 누수(Leak) 명시적 파괴
 * 3. [SPEED] JS 키 후킹 즉시 7초 딜레이 없이 즉시 반환하여 재생 시작 속도 비약적 향상
 * 4. [PERF] Native Thread 대신 코루틴(Dispatchers.IO)으로 로컬 프록시 구동
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    companion object {
        @Volatile private var currentProxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        synchronized(this) {
            if (currentProxyServer != null) {
                currentProxyServer?.stop()
                currentProxyServer = null
            }
        }

        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = if (referer.isNullOrEmpty() || (referer.contains("tvwiki") && !referer.contains(TVWiki.currentMainUrl))) {
            TVWiki.currentMainUrl + "/"
        } else {
            referer.replace(Regex("[\\r\\n\\s]"), "").trim()
        }
        
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/") && !cleanUrl.contains("v/d/")) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) { }
        }

        var capturedUrl: String? = cleanUrl
        val currentSessionKeys = Collections.synchronizedSet(mutableSetOf<String>())

        if (!cleanUrl.contains("/c.html")) {
            val webViewResult = runWebViewHook(cleanUrl, cleanReferer, currentSessionKeys)
            if (webViewResult != null) capturedUrl = webViewResult
        }

        if (capturedUrl != null) {
            val cookie = CookieManager.getInstance().getCookie(capturedUrl)
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online"
            )
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

            try {
                var requestUrl = capturedUrl!!.substringBefore("#")
                var response = app.get(requestUrl, headers = headers)
                var content = response.text.trim()

                if (!content.startsWith("#EXTM3U")) {
                    Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(content)?.let {
                        requestUrl = it.groupValues[1]
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                if (content.contains("#EXT-X-STREAM-INF")) {
                    val subUrlLine = content.lines().lastOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if (subUrlLine != null) {
                        val originalUri = try { URI(requestUrl) } catch (e: Exception) { null }
                        requestUrl = resolveUrl(originalUri, requestUrl, subUrlLine)
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                val isKey7 = content.lines().any { it.startsWith("#EXT-X-KEY") && it.contains("/v/key7") }

                if (isKey7) {
                    val newProxy = ProxyWebServer(currentSessionKeys).apply { 
                        start()
                        updateSession(headers) 
                    }
                    currentProxyServer = newProxy

                    val videoId = Regex("""/v/[ef]/([^/]+)""").find(capturedUrl!!)?.groupValues?.get(1) ?: "video"
                    val ivMatch = Regex("""IV=("?)(0x[0-9a-fA-F]+)\1""").find(content)
                    val ivHex = ivMatch?.groupValues?.get(2) ?: "0x00000000000000000000000000000000"
                    val parsedIv = ivHex.removePrefix("0x").hexToByteArray()
                    
                    newProxy.setIv(parsedIv)

                    val baseUri = try { URI(requestUrl) } catch (e: Exception) { null }
                    val sb = StringBuilder()
                    
                    content.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEach
                        if (trimmed.startsWith("#")) {
                            if (trimmed.startsWith("#EXT-X-KEY") && trimmed.contains("/v/key7")) {
                                val match = Regex("""URI="([^"]+)"""").find(trimmed)
                                if (match != null) {
                                    val newKeyLine = trimmed.replace(match.groupValues[1], "http://127.0.0.1:${newProxy.port}/key.bin")
                                    sb.append(newKeyLine).append("\n")
                                } else sb.append(trimmed).append("\n")
                            } else sb.append(trimmed).append("\n")
                        } else {
                            val absSeg = resolveUrl(baseUri, requestUrl, trimmed)
                            newProxy.setTestSegment(absSeg)
                            sb.append(absSeg).append("\n")
                        }
                    }
                    
                    newProxy.setPlaylist(sb.toString())
                    
                    val finalUrl = "http://127.0.0.1:${newProxy.port}/$videoId/playlist.m3u8"
                    
                    callback(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                } else {
                    callback(newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                }
            } catch (e: Exception) { }
        }
        return false
    }

    private suspend fun runWebViewHook(url: String, referer: String, sessionKeys: MutableSet<String>) = suspendCancellableCoroutine<String?> { cont ->
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var detectedCUrl: String? = null

        //[v2.1 Fix] 사용자 취소 시 WebView 메모리 누수 완벽 방지
        cont.invokeOnCancellation {
            handler.post {
                try { webView?.destroy(); webView = null } catch (e: Exception) {}
            }
        }

        val hookScript = """
            (function() {
                window.G = false;
                if (window.crypto && window.crypto.subtle) {
                    const originalImportKey = window.crypto.subtle.importKey;
                    Object.defineProperty(window.crypto.subtle, 'importKey', {
                        value: function(format, keyData, algorithm, extractable, keyUsages) {
                            if (format === 'raw' && (keyData.byteLength === 16 || keyData.length === 16)) {
                                try {
                                    let bytes = new Uint8Array(keyData);
                                    let hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
                                    console.log("CapturedKeyHex:[CRYPTO]" + hex);
                                } catch(e) {}
                            }
                            return originalImportKey.apply(this, arguments);
                        },
                        configurable: true,
                        writable: true
                    });
                }
                const originalSet = Uint8Array.prototype.set;
                Uint8Array.prototype.set = function(source, offset) {
                    if (source && source.length === 16) {
                        try {
                            let hex = Array.from(source).map(b => b.toString(16).padStart(2, '0')).join('');
                            console.log("CapturedKeyHex:[SET]" + hex);
                        } catch(e) {}
                    }
                    return originalSet.apply(this, arguments);
                };
            })();
        """.trimIndent()

        handler.post {
            try {
                val context: Context = (AcraApplication.context ?: app) as Context
                webView = WebView(context)
                
                webView?.settings?.apply {
                    javaScriptEnabled = true; domStorageEnabled = true; userAgentString = DESKTOP_UA
                }

                val discoveryTimeout = Runnable {
                    if (cont.isActive) {
                        try { webView?.destroy(); webView = null } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(discoveryTimeout, 15000)

                webView?.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:").removePrefix("[SET]").removePrefix("[CRYPTO]")
                            if (sessionKeys.add(key)) { 
                                println("[TVWiki Extractor v2.1] 키 캡처 성공! Key: $key")
                                //[v2.1 Fix] 키 발굴 즉시 무의미한 딜레이를 취소하고 곧바로 스트리밍 시작
                                if (detectedCUrl != null && cont.isActive) {
                                    handler.removeCallbacksAndMessages(null)
                                    handler.post {
                                        try { webView?.destroy(); webView = null } catch (e: Exception) {}
                                        cont.resume(detectedCUrl)
                                    }
                                }
                            }
                        }
                        return true
                    }
                }

                webView?.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon); view?.evaluateJavascript(hookScript, null)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        if (reqUrl.contains("/c.html") && reqUrl.contains("token=")) {
                            detectedCUrl = reqUrl
                            view?.post { view.evaluateJavascript(hookScript, null) }
                            
                            if (sessionKeys.isNotEmpty() && cont.isActive) {
                                handler.removeCallbacksAndMessages(null)
                                handler.post {
                                    try { webView?.destroy(); webView = null } catch (e: Exception) {}
                                    cont.resume(detectedCUrl)
                                }
                            } else {
                                // 기존 무조건 7초 딜레이 방식을 버리고 5초의 짧은 Fallback으로 단축
                                handler.postDelayed({
                                    if (cont.isActive) {
                                        try { webView?.destroy(); webView = null } catch (e: Exception) {}
                                        cont.resume(detectedCUrl)
                                    }
                                }, 5000)
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url); view?.evaluateJavascript(hookScript, null)
                    }
                }
                webView?.loadUrl(url, mapOf("Referer" to referer))
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun resolveUrl(baseUri: URI?, baseUrlStr: String, target: String): String {
        if (target.startsWith("http")) return target
        return try { baseUri?.resolve(target).toString() } catch (e: Exception) {
            if (target.startsWith("/")) "${baseUrlStr.substringBefore("/", "https://")}//${baseUrlStr.split("/")[2]}$target"
            else "${baseUrlStr.substringBeforeLast("/")}/$target"
        }
    }

    class ProxyWebServer(private val sessionKeys: MutableSet<String>) {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var verifiedKey: ByteArray? = null
        @Volatile private var currentIv: ByteArray? = null
        @Volatile private var testSegmentUrl: String? = null

        fun start() {
            try {
                serverSocket = ServerSocket(0).also { port = it.localPort }
                isRunning = true
                
                //[v2.1 Fix] Native Thread 대신 IO 코루틴을 사용하여 리소스 효율 극대화
                CoroutineScope(Dispatchers.IO).launch {
                    while (isRunning) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} 
                    } 
                }
            } catch (e: Exception) { }
        }

        fun stop() { 
            isRunning = false
            try { serverSocket?.close() } catch(e: Exception) {} 
        }
        
        fun updateSession(h: Map<String, String>) { currentHeaders = h }
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun setIv(iv: ByteArray) { currentIv = iv }
        fun setTestSegment(url: String) { 
            if (testSegmentUrl == null) testSegmentUrl = url 
        }

        private fun handleClient(socket: Socket) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                val parts = line.split(" ")
                if (parts.size < 2) return
                
                val path = parts[1]
                val output = socket.getOutputStream()

                when {
                    path.contains("playlist.m3u8") -> {
                        val payload = currentPlaylist.toByteArray()
                        // [v2.1 Fix] Connection: close와 Content-Length로 ExoPlayer의 무한대기 현상 해결
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\nContent-Length: ${payload.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(payload)
                    }
                    path.contains("/key.bin") -> {
                        if (verifiedKey == null) {
                            verifiedKey = verifyMultipleKeys()
                        }
                        
                        val keyPayload = verifiedKey ?: ByteArray(16)
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nConnection: close\r\nContent-Length: ${keyPayload.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(keyPayload)
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2: Exception) {} }
        }

        private fun verifyMultipleKeys(): ByteArray? = runBlocking {
            val url = testSegmentUrl ?: return@runBlocking null
            val targetIv = currentIv ?: ByteArray(16)
            
            try {
                val responseData = app.get(url, headers = currentHeaders).body.bytes()
                val checkSize = 1024 
                val safeCheckSize = if (responseData.size < checkSize) responseData.size else checkSize

                synchronized(sessionKeys) {
                    for ((index, hexKey) in sessionKeys.withIndex()) {
                        try {
                            val keyBytes = hexKey.hexToByteArray()
                            for (offset in 0..512) {
                                if (responseData.size < offset + safeCheckSize) break
                                val testChunk = responseData.copyOfRange(offset, offset + safeCheckSize)
                                val decrypted = decryptAES(testChunk, keyBytes, targetIv)
                                
                                if (decrypted.size >= 377 && decrypted[0] == 0x47.toByte() && decrypted[188] == 0x47.toByte() && decrypted[376] == 0x47.toByte()) {
                                    return@synchronized keyBytes
                                }
                            }
                        } catch (e: Exception) {}
                    }
                    null
                }
            } catch (e: Exception) { 
                null 
            }
        }

        private fun decryptAES(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            return try {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { ByteArray(0) }
        }
    }
}

fun String.hexToByteArray(): ByteArray {
    val len = length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i+1], 16)).toByte()
        i += 2
    }
    return data
}
