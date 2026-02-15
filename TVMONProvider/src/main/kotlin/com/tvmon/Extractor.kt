package com.tvmon

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
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mapper
import kotlinx.coroutines.runBlocking
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
 * Version: v23.6 (Conditional Hooking & ID Path)
 * Modification:
 * 1. [FIX] key7 미감지 시 키 수집 스킵 및 즉시 재생 (논프록시)
 * 2. [FIX] WebView 최대 대기 시간 5초로 단축
 * 3. [FIX] 프록시 재생 URL에 Video ID 포함 (/{videoId}/playlist.m3u8)
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVMON"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        val capturedKeys: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())
        @Volatile var verifiedKey: ByteArray? = null
        @Volatile var currentIv: ByteArray? = null
        @Volatile var testSegmentUrl: String? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[TVMON][v23.6] getUrl 호출됨. URL: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVMON] [STEP 1] extract() 프로세스 시작.")
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"
        
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                println("[TVMON] [STEP 1-1] iframe 주소 찾는 중...")
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVMON] iframe 발견됨: $cleanUrl")
                }
            } catch (e: Exception) { println("[TVMON] [ERROR] iframe 파싱 실패: ${e.message}") }
        }

        var capturedUrl: String? = cleanUrl

        // WebView를 통해 c.html URL 확보 및 필요 시 키 수집
        if (!cleanUrl.contains("/c.html")) {
            println("[TVMON] [STEP 2] WebView 분석 모드 실행 (최대 5초 대기)...")
            capturedKeys.clear()
            verifiedKey = null
            
            val webViewResult = runWebViewHook(cleanUrl, cleanReferer)
            if (webViewResult != null) {
                capturedUrl = webViewResult
                println("[TVMON] [STEP 2-1] 분석 완료: $capturedUrl")
            }
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
                println("[TVMON] [STEP 3] M3U8 플레이리스트 확인 중...")
                var requestUrl = capturedUrl!!.substringBefore("#")
                var response = app.get(requestUrl, headers = headers)
                var content = response.text.trim()

                // m3u8이 아닌 경우 내부 링크 검색
                if (!content.startsWith("#EXTM3U")) {
                    Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(content)?.let {
                        requestUrl = it.groupValues[1]
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                // 마스터 플레이리스트 처리
                if (content.contains("#EXT-X-STREAM-INF")) {
                    val subUrlLine = content.lines().lastOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if (subUrlLine != null) {
                        val originalUri = try { URI(requestUrl) } catch (e: Exception) { null }
                        requestUrl = resolveUrl(originalUri, requestUrl, subUrlLine)
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                // Key7 존재 여부 최종 확인
                val isKey7 = content.lines().any { it.startsWith("#EXT-X-KEY") && it.contains("/v/key7") }
                
                if (isKey7) {
                    println("[TVMON] [STEP 4] Key7 감지됨. 프록시 모드 활성화.")
                    proxyServer?.stop()
                    proxyServer = ProxyWebServer().apply { start(); updateSession(headers) }

                    // 비디오 ID 추출 (Requirement 3)
                    val videoId = Regex("""/v/[ef]/([^/]+)""").find(capturedUrl!!)?.groupValues?.get(1) ?: "video"
                    
                    val ivMatch = Regex("""IV=("?)(0x[0-9a-fA-F]+)\1""").find(content)
                    val ivHex = ivMatch?.groupValues?.get(2) ?: "0x00000000000000000000000000000000"
                    currentIv = ivHex.removePrefix("0x").hexToByteArray()

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
                    
                    // ID가 포함된 로컬 주소 반환 (Requirement 3)
                    callback(newExtractorLink(name, name, "http://127.0.0.1:${proxyServer!!.port}/$videoId/playlist.m3u8", ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                } else {
                    println("[TVMON] [STEP 4] 일반 영상 감지. 논프록시 직접 재생.")
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
                console.log("[JS-HOOK] Watchdog Activating...");
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

        Handler(Looper.getMainLooper()).post {
            try {
                var detectedCUrl: String? = null
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:").removePrefix("[SET]").removePrefix("[CRYPTO]")
                            if (capturedKeys.add(key)) {
                                println("[TVMON] [HOOK] 키 후보 획득 ($key)")
                            }
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(hookScript, null)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        if (reqUrl.contains("/c.html") && reqUrl.contains("token=")) {
                            println("[TVMON] [INTERCEPT] c.html 감지: $reqUrl")
                            detectedCUrl = reqUrl
                            view?.post { view.evaluateJavascript(hookScript, null) }
                            
                            // [Requirement 1] key7 여부 선행 검사
                            thread {
                                try {
                                    val checkRes = app.get(reqUrl, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://player.bunny-frame.online/"))
                                    if (!checkRes.text.contains("/v/key7")) {
                                        println("[TVMON] [SKIP] key7 없음 확인됨. 즉시 반환.")
                                        Handler(Looper.getMainLooper()).post {
                                            if (cont.isActive) {
                                                try { webView.destroy() } catch (e: Exception) {}
                                                cont.resume(detectedCUrl)
                                            }
                                        }
                                    } else {
                                        println("[TVMON] [KEEP] key7 감지됨. 키 수집 대기 유지.")
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(hookScript, null)
                    }
                }

                println("[TVMON] WebView 로드 시작: $url")
                webView.loadUrl(url, mapOf("Referer" to referer))

                // [Requirement 2] 5초 후 자동 종료
                Handler(Looper.getMainLooper()).postDelayed({
                    if (cont.isActive) {
                        println("[TVMON] WebView 타임아웃 종료 (5초).")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(detectedCUrl)
                    }
                }, 5000)

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

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
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

        fun stop() { isRunning = false; serverSocket?.close() }
        fun updateSession(h: Map<String, String>) { currentHeaders = h }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                val parts = line.split(" ")
                val path = parts[1]
                val output = socket.getOutputStream()

                when {
                    // Requirement 3: 어떤 ID 경로로 들어와도 playlist.m3u8만 포함되면 응답
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
                        val conn = URL(targetUrl).openConnection() as HttpURLConnection
                        currentHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                        val inputStream = conn.inputStream
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        val buffer = ByteArray(65536)
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead > 0) {
                            var offset = -1
                            for (i in 0 until bytesRead - 376) {
                                if (buffer[i] == 0x47.toByte() && buffer[i+188] == 0x47.toByte() && buffer[i+376] == 0x47.toByte()) {
                                    offset = i; break
                                }
                            }
                            if (offset != -1) output.write(buffer, offset, bytesRead - offset)
                            else output.write(buffer, 0, bytesRead)
                            inputStream.copyTo(output)
                        }
                        inputStream.close()
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { socket.close() }
        }

        private fun verifyMultipleKeys(): ByteArray? {
            val url = testSegmentUrl ?: return null
            val targetIv = currentIv ?: ByteArray(16)
            return try {
                val responseData = runBlocking { app.get(url, headers = currentHeaders).body.bytes() }
                val checkSize = 1024 
                val safeCheckSize = if (responseData.size < checkSize) responseData.size else checkSize
                synchronized(capturedKeys) {
                    println("[VERIFY] ${capturedKeys.size}개 후보 검증 시작.")
                    for (hexKey in capturedKeys) {
                        val keyBytes = hexKey.hexToByteArray()
                        for (offset in 0..512) {
                            if (responseData.size < offset + safeCheckSize) break
                            val testChunk = responseData.copyOfRange(offset, offset + safeCheckSize)
                            val decrypted = decryptAES(testChunk, keyBytes, targetIv)
                            if (decrypted.size >= 377 && decrypted[0] == 0x47.toByte() && decrypted[188] == 0x47.toByte() && decrypted[376] == 0x47.toByte()) {
                                println("[VERIFY] ★ 정답 발견: $hexKey")
                                return keyBytes
                            }
                        }
                    }
                }
                null
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
