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
 * Version: v23.10 (Total Optimization & Smart Intercept)
 * Modification:
 * 1. [FIX] 1x1 Pixel Injection into DecorView for High Priority.
 * 2. [FIX] Call 'view.stopLoading()' immediately after c.html detection.
 * 3. [FIX] Detailed logging for PageStarted, Error, and Console.
 * 4. [FIX] Auto-cleanup logic for Injected Views.
 * 5. [FIX] Proxy URL with Video ID and full executable code provided.
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

        // Context로부터 Activity를 찾는 헬퍼
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
        println("[TVMON][v23.10] getUrl 호출됨. 대상 URL: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVMON] [STEP 1] 추출 프로세스 시작.")
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"
        
        // iframe 주소 추출
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                println("[TVMON] [STEP 1-1] iframe 주소 검색 중...")
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVMON] iframe 발견: $cleanUrl")
                }
            } catch (e: Exception) { println("[TVMON] [ERROR] iframe 추출 실패: ${e.message}") }
        }

        var capturedUrl: String? = cleanUrl

        // WebView 터널링 실행
        if (!cleanUrl.contains("/c.html")) {
            println("[TVMON] [STEP 2] WebView 분석 모드 실행 (고우선순위 주입)...")
            capturedKeys.clear()
            verifiedKey = null
            
            val webViewResult = runWebViewHook(cleanUrl, cleanReferer)
            if (webViewResult != null) {
                capturedUrl = webViewResult
                println("[TVMON] [STEP 2-1] c.html URL 캡처 완료: $capturedUrl")
            } else {
                println("[TVMON] [STEP 2-1] [WARNING] c.html 캡처 실패. 원본 URL 사용.")
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
                println("[TVMON] [STEP 3] 최종 M3U8 데이터 획득 중...")
                var requestUrl = capturedUrl!!.substringBefore("#")
                var response = app.get(requestUrl, headers = headers)
                var content = response.text.trim()

                // 내부 m3u8 주소 재탐색
                if (!content.startsWith("#EXTM3U")) {
                    Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(content)?.let {
                        requestUrl = it.groupValues[1]
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                // 마스터 플레이리스트 대응
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
                    println("[TVMON] [STEP 4] Key7 영상 감지. 프록시 서버 기동.")
                    proxyServer?.stop()
                    proxyServer = ProxyWebServer().apply { start(); updateSession(headers) }

                    val videoId = Regex("""/v/[ef]/([^/]+)""").find(capturedUrl!!)?.groupValues?.get(1) ?: "video_id"
                    
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
                    println("[TVMON] [STEP 4] 일반 영상 감지. 프록시 없이 직접 재생.")
                    callback(newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                }
            } catch (e: Exception) { println("[TVMON] [ERROR] 분석 실패: ${e.message}") }
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
                val context: Context = (AcraApplication.context ?: app) as Context
                val activity = getAsActivity(context)
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                    cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                }

                // [v23.10] 웹뷰 우선순위 격상: DecorView에 1x1 픽셀 주입
                val params = ViewGroup.LayoutParams(1, 1)
                if (activity != null) {
                    val root = activity.window.decorView as ViewGroup
                    root.addView(webView, params)
                    println("[TVMON] [INJECT] 웹뷰를 DecorView에 부착하여 우선순위를 격상했습니다.")
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:")
                            if (capturedKeys.add(key)) {
                                println("[TVMON] [HOOK] 키 캡처 성공: $key")
                            }
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        println("[TVMON] [WEBVIEW] 페이지 로드 시작: $url")
                        view?.evaluateJavascript(hookScript, null)
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            println("[TVMON] [WEBVIEW] 메인 프레임 로드 에러: ${error?.description}")
                        }
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        if (reqUrl.contains("/c.html") && reqUrl.contains("token=")) {
                            println("[TVMON] [FOUND] c.html 포착: $reqUrl")
                            detectedCUrl = reqUrl
                            
                            // [v23.10] URL 포착 즉시 로딩 중단하여 리소스 절약
                            view?.post { 
                                view.stopLoading() 
                                view.evaluateJavascript(hookScript, null)
                            }
                            
                            // key7 존재 여부 선행 검사 (코루틴 사용)
                            thread {
                                try {
                                    runBlocking {
                                        val res = app.get(reqUrl, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://player.bunny-frame.online/"))
                                        if (!res.text.contains("/v/key7")) {
                                            println("[TVMON] [SKIP] key7 없음 확인. 수집 종료.")
                                            Handler(Looper.getMainLooper()).post {
                                                if (cont.isActive) {
                                                    (webView.parent as? ViewGroup)?.removeView(webView)
                                                    webView.destroy()
                                                    cont.resume(detectedCUrl)
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(hookScript, null)
                    }
                }

                webView.loadUrl(url, mapOf("Referer" to referer))

                // 타임아웃 8초 설정 (스마트 종료 병행)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (cont.isActive) {
                        println("[TVMON] [TIMEOUT] 8초 경과로 웹뷰 종료.")
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

        fun stop() { isRunning = false; try { serverSocket?.close() } catch(e: Exception) {} }
        fun updateSession(h: Map<String, String>) { currentHeaders = h }
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
                            try {
                                val responseBytes = app.get(targetUrl, headers = currentHeaders).body.bytes()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                                
                                var offset = -1
                                for (i in 0 until responseBytes.size - 376) {
                                    if (responseBytes[i] == 0x47.toByte() && responseBytes[i+188] == 0x47.toByte() && responseBytes[i+376] == 0x47.toByte()) {
                                        offset = i; break
                                    }
                                }
                                if (offset != -1) output.write(responseBytes, offset, responseBytes.size - offset)
                                else output.write(responseBytes)
                            } catch (e: Exception) { println("[TVMON] [PROXY-ERROR] 세그먼트 전송 실패") }
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
                val responseData = app.get(url, headers = currentHeaders).body.bytes()
                synchronized(capturedKeys) {
                    println("[VERIFY] ${capturedKeys.size}개의 후보 키 검증 시작.")
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
