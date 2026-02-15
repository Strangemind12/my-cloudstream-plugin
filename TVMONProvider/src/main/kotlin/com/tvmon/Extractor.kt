package com.tvmon

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.webkit.WebResourceError
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
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
 * Version: v23.11 (Proxy Reliability Fix)
 * Modification:
 * 1. [FIX] Removed 'companion object' usage to prevent state pollution between plays.
 * 2. [FIX] Added 'Retry Logic' (3 attempts) inside Proxy for segment downloads.
 * 3. [FIX] Improved error handling in Proxy to prevent 'Source error'.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVMON"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    // [v23.11] 인스턴스 변수로 변경하여 상태 격리
    private var proxyServer: ProxyWebServer? = null
    private val capturedKeys: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var verifiedKey: ByteArray? = null
    @Volatile private var currentIv: ByteArray? = null
    @Volatile private var testSegmentUrl: String? = null

    companion object {
        fun getAsActivity(context: Context?): Activity? {
            var ctx = context
            while (ctx is ContextWrapper) {
                if (ctx is Activity) return ctx
                ctx = ctx.baseContext
            }
            return null
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[TVMON][v23.11] getUrl 호출됨.")
        // 상태 초기화
        stopProxy()
        capturedKeys.clear()
        verifiedKey = null
        currentIv = null
        testSegmentUrl = null
        
        extract(url, referer, subtitleCallback, callback)
    }

    private fun stopProxy() {
        try {
            proxyServer?.stop()
            proxyServer = null
        } catch (e: Exception) {}
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"
        
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) { println("[TVMON] [ERROR] iframe 추출 실패") }
        }

        var capturedUrl: String? = cleanUrl

        if (!cleanUrl.contains("/c.html")) {
            println("[TVMON] [STEP 2] WebView 분석 실행 (Priority High)")
            val webViewResult = runWebViewHook(cleanUrl, cleanReferer)
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
                println("[TVMON] [STEP 3] M3U8 분석 중...")
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
                        requestUrl = resolveUrl(try { URI(requestUrl) } catch (e: Exception) { null }, requestUrl, subUrlLine)
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                val isKey7 = content.lines().any { it.startsWith("#EXT-X-KEY") && it.contains("/v/key7") }
                
                if (isKey7) {
                    println("[TVMON] [STEP 4] 프록시 모드 시작 (재시도 로직 포함)")
                    stopProxy()
                    // ProxyWebServer를 내부 클래스로 생성하여 인스턴스 변수에 접근 가능하게 함
                    proxyServer = ProxyWebServer(headers).apply { start() }

                    val videoId = Regex("""/v/[ef]/([^/]+)""").find(capturedUrl!!)?.groupValues?.get(1) ?: "video"
                    val ivMatch = Regex("""IV=("?)(0x[0-9a-fA-F]+)\1""").find(content)
                    currentIv = (ivMatch?.groupValues?.get(2) ?: "0x00000000000000000000000000000000").removePrefix("0x").hexToByteArray()

                    val baseUri = try { URI(requestUrl) } catch (e: Exception) { null }
                    val sb = StringBuilder()
                    content.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEach
                        if (trimmed.startsWith("#")) {
                            if (trimmed.startsWith("#EXT-X-KEY") && trimmed.contains("/v/key7")) {
                                val match = Regex("""URI="([^"]+)"""").find(trimmed)
                                if (match != null) {
                                    val absKey = resolveUrl(baseUri, requestUrl, match.groupValues[1])
                                    val encKey = java.net.URLEncoder.encode(absKey, "UTF-8")
                                    sb.append(trimmed.replace(match.groupValues[1], "http://127.0.0.1:${proxyServer!!.port}/key?url=$encKey")).append("\n")
                                } else sb.append(trimmed).append("\n")
                            } else sb.append(trimmed).append("\n")
                        } else {
                            val absSeg = resolveUrl(baseUri, requestUrl, trimmed)
                            if (testSegmentUrl == null) testSegmentUrl = absSeg
                            val encSeg = java.net.URLEncoder.encode(absSeg, "UTF-8")
                            sb.append("http://127.0.0.1:${proxyServer!!.port}/seg?url=$encSeg").append("\n")
                        }
                    }
                    proxyServer!!.setPlaylist(sb.toString())
                    
                    callback(newExtractorLink(name, name, "http://127.0.0.1:${proxyServer!!.port}/$videoId/playlist.m3u8", ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                } else {
                    println("[TVMON] 일반 영상 재생")
                    callback(newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                }
            } catch (e: Exception) { println("[TVMON] [ERROR] $e") }
        }
        return false
    }

    private suspend fun runWebViewHook(url: String, referer: String) = suspendCancellableCoroutine<String?> { cont ->
        val hookScript = """
            (function() {
                window.G = false;
                if (window.crypto && window.crypto.subtle) {
                    const originalImportKey = window.crypto.subtle.importKey;
                    Object.defineProperty(window.crypto.subtle, 'importKey', {
                        value: function(format, keyData, algorithm, extractable, keyUsages) {
                            if (format === 'raw' && (keyData.byteLength === 16 || keyData.length === 16)) {
                                try {
                                    let hex = Array.from(new Uint8Array(keyData)).map(b => b.toString(16).padStart(2, '0')).join('');
                                    console.log("CapturedKeyHex:" + hex);
                                } catch(e) {}
                            }
                            return originalImportKey.apply(this, arguments);
                        },
                        configurable: true, writable: true
                    });
                }
                const originalSet = Uint8Array.prototype.set;
                Uint8Array.prototype.set = function(source, offset) {
                    if (source && source.length === 16) {
                        try {
                            let hex = Array.from(source).map(b => b.toString(16).padStart(2, '0')).join('');
                            console.log("CapturedKeyHex:" + hex);
                        } catch(e) {}
                    }
                    return originalSet.apply(this, arguments);
                };
            })();
        """.trimIndent()

        Handler(Looper.getMainLooper()).post {
            try {
                var detectedCUrl: String? = null
                val context = (AcraApplication.context ?: app) as Context
                val activity = getAsActivity(context)
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                    cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                }

                val params = ViewGroup.LayoutParams(1, 1)
                if (activity != null) {
                    val root = activity.window.decorView as ViewGroup
                    root.addView(webView, params)
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CapturedKeyHex:")) {
                            capturedKeys.add(msg.substringAfter("CapturedKeyHex:"))
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        if (reqUrl.contains("/c.html") && reqUrl.contains("token=")) {
                            println("[TVMON] [FOUND] c.html 포착: $reqUrl")
                            detectedCUrl = reqUrl
                            view?.post { 
                                view.stopLoading()
                                view.evaluateJavascript(hookScript, null) 
                            }
                            
                            thread {
                                runBlocking {
                                    try {
                                        val res = app.get(reqUrl, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://player.bunny-frame.online/"))
                                        if (!res.text.contains("/v/key7")) {
                                            Handler(Looper.getMainLooper()).post {
                                                if (cont.isActive) {
                                                    (webView.parent as? ViewGroup)?.removeView(webView)
                                                    webView.destroy()
                                                    cont.resume(detectedCUrl)
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(hookScript, null)
                    }
                }

                webView.loadUrl(url, mapOf("Referer" to referer))

                Handler(Looper.getMainLooper()).postDelayed({
                    if (cont.isActive) {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(detectedCUrl)
                    }
                }, 8000)

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

    // [v23.11] Inner Class로 변경하여 외부 클래스의 capturedKeys, verifiedKey 등에 접근
    inner class ProxyWebServer(private val sessionHeaders: Map<String, String>) {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentPlaylist: String = ""

        fun start() {
            try {
                serverSocket = ServerSocket(0).also { port = it.localPort }
                isRunning = true
                thread(isDaemon = true) {
                    while (isRunning) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} }
                }
            } catch (e: Exception) {}
        }

        fun stop() { isRunning = false; try { serverSocket?.close() } catch(e: Exception) {} }
        fun setPlaylist(p: String) { currentPlaylist = p }

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
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(currentPlaylist.toByteArray())
                    }
                    path.contains("/key") -> {
                        if (verifiedKey == null) verifiedKey = verifyMultipleKeys()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(verifiedKey ?: ByteArray(16))
                    }
                    path.contains("/seg") -> {
                        val targetUrl = URLDecoder.decode(path.substringAfter("url="), "UTF-8")
                        runBlocking {
                            // [v23.11] 재시도 로직 추가 (Max 3회)
                            var success = false
                            var attempt = 0
                            while (!success && attempt < 3) {
                                try {
                                    val responseBytes = app.get(targetUrl, headers = sessionHeaders).body.bytes()
                                    output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                                    
                                    var offset = -1
                                    for (i in 0 until responseBytes.size - 376) {
                                        if (responseBytes[i] == 0x47.toByte() && responseBytes[i+188] == 0x47.toByte() && responseBytes[i+376] == 0x47.toByte()) {
                                            offset = i; break
                                        }
                                    }
                                    if (offset != -1) output.write(responseBytes, offset, responseBytes.size - offset)
                                    else output.write(responseBytes)
                                    
                                    success = true
                                } catch (e: Exception) {
                                    attempt++
                                    println("[TVMON] [PROXY-RETRY] 세그먼트 다운로드 실패 ($attempt/3): ${e.message}")
                                    if (attempt < 3) delay(200) // 0.2초 대기 후 재시도
                                }
                            }
                            if (!success) {
                                println("[TVMON] [PROXY-FAIL] 세그먼트 전송 최종 실패")
                                // 플레이어에게 에러 응답
                                output.write("HTTP/1.1 500 Internal Server Error\r\n\r\n".toByteArray())
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2: Exception) {} }
        }

        private fun verifyMultipleKeys(): ByteArray? = runBlocking {
            val url = testSegmentUrl ?: return@runBlocking null
            val targetIv = currentIv ?: ByteArray(16)
            try {
                val responseData = app.get(url, headers = sessionHeaders).body.bytes()
                synchronized(capturedKeys) {
                    for (hexKey in capturedKeys) {
                        val keyBytes = hexKey.hexToByteArray()
                        for (offset in 0..512) {
                            if (responseData.size < offset + 1024) break
                            val decrypted = decryptAES(responseData.copyOfRange(offset, offset + 1024), keyBytes, targetIv)
                            if (decrypted.size >= 377 && decrypted[0] == 0x47.toByte() && decrypted[188] == 0x47.toByte() && decrypted[376] == 0x47.toByte()) {
                                println("[VERIFY] 정답 발견: $hexKey")
                                return@synchronized keyBytes
                            }
                        }
                    }
                    null
                }
            } catch (e: Exception) { null }
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
