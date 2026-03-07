// [v1.7] 
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
 * Version: v1.7 (Hybrid Proxy Performance Patch)
 * Modification:
 * 1. [PERF] WebView 훅의 최대 대기 시간(discoveryTimeout)을 15초에서 5초로 단축하여 불필요한 로딩 지연 방지.
 * 2. [PERF] /v/key7 감지 시 7초간 무조건 대기하던 병목 제거. 키가 캡처되거나 이미 존재할 경우 즉시 반환하도록 단축 로직 추가.
 * 3. [PERF] 영상에 key7 태그가 없을 시 훅 대기를 거치지 않고 다이렉트 플레이가 실행되도록 즉시 우회.
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
        println("[TVWiki][v1.7] getUrl 호출됨. URL: $url")
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
                println("[TVWiki][v1.7] 기존 실행 중인 프록시 서버 종료 시도.")
                currentProxyServer?.stop()
                currentProxyServer = null
            }
        }

        println("[TVWiki][v1.7] extract() 로직 시작.")
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvwiki5.net/"
        
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/") && !cleanUrl.contains("v/d/")) {
            println("[TVWiki][v1.7] Referer 페이지($cleanReferer)에서 iframe 링크 탐색 시도.")
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVWiki][v1.7] Iframe 링크 발견: $cleanUrl")
                }
            } catch (e: Exception) { 
                println("[TVWiki][v1.7] Iframe 파싱 중 예외 발생: ${e.message}") 
            }
        }

        var capturedUrl: String? = cleanUrl
        val currentSessionKeys = Collections.synchronizedSet(mutableSetOf<String>())

        if (!cleanUrl.contains("/c.html")) {
            println("[TVWiki][v1.7] WebView 훅 실행 필요. runWebViewHook 호출.")
            val webViewResult = runWebViewHook(cleanUrl, cleanReferer, currentSessionKeys)
            if (webViewResult != null) {
                capturedUrl = webViewResult
                println("[TVWiki][v1.7] WebView 훅 최종 캡처된 URL: $capturedUrl")
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
                println("[TVWiki][v1.7] M3U8 플레이리스트 분석 시작.")
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
                    println("[TVWiki][v1.7] 암호화 감지됨. 하이브리드 로컬 프록시 구성 시작.")
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
                    
                    println("[TVWiki][v1.7] 하이브리드 M3U8 라인 리라이팅 시작.")
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
                    println("[TVWiki][v1.7] 하이브리드 URL 반환: $finalUrl")
                    
                    callback(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                } else {
                    println("[TVWiki][v1.7] 일반 영상 다이렉트 링크 반환. (key7 우회)")
                    callback(newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                }
            } catch (e: Exception) { 
                println("[TVWiki][v1.7] 분석 치명적 오류: ${e.message}")
            }
        }
        return false
    }

    private suspend fun runWebViewHook(url: String, referer: String, sessionKeys: MutableSet<String>) = suspendCancellableCoroutine<String?> { cont ->
        val handler = Handler(Looper.getMainLooper())
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
                var detectedCUrl: String? = null
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true; domStorageEnabled = true; userAgentString = DESKTOP_UA
                }

                val discoveryTimeout = Runnable {
                    if (cont.isActive) {
                        println("[TVWiki][v1.7] 5초 WebView 탐색 타임아웃 초과. 강제 종료 수행.")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                // [최적화] 15000ms를 5000ms로 단축
                handler.postDelayed(discoveryTimeout, 5000)

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:").removePrefix("[SET]").removePrefix("[CRYPTO]")
                            if (sessionKeys.add(key)) { 
                                println("[TVWiki][v1.7] 콘솔(Console) 후킹 키 캡처 성공! Key: $key")
                                // [최적화] 무조건 대기하던 시간 낭비 방지. 키가 잡히면 즉시 진행 반환.
                                if (detectedCUrl != null && cont.isActive) {
                                    println("[TVWiki][v1.7] 키 확보 확인으로 지연 없이 즉시 반환 실행.")
                                    handler.post {
                                        if (cont.isActive) {
                                            try { webView.destroy() } catch (e: Exception) {}
                                            cont.resume(detectedCUrl)
                                        }
                                    }
                                }
                            }
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon); view?.evaluateJavascript(hookScript, null)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        if (reqUrl.contains("/c.html") && reqUrl.contains("token=")) {
                            println("[TVWiki][v1.7] /c.html 감지 완료. URL: $reqUrl")
                            detectedCUrl = reqUrl
                            handler.removeCallbacks(discoveryTimeout)
                            view?.post { view.evaluateJavascript(hookScript, null) }
                            
                            thread {
                                try {
                                    runBlocking {
                                        val checkRes = app.get(reqUrl, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://player.bunny-frame.online/"))
                                        // [최적화] key7 태그가 없거나 암호화 키를 이미 얻었다면 추가 딜레이 패스
                                        if (!checkRes.text.contains("/v/key7") || sessionKeys.isNotEmpty()) {
                                            println("[TVWiki][v1.7] key7 무시 혹은 이미 키 존재 판별 -> 즉시 반환 우회.")
                                            handler.post {
                                                if (cont.isActive) {
                                                    try { webView.destroy() } catch (e: Exception) {}
                                                    cont.resume(detectedCUrl)
                                                }
                                            }
                                        } else {
                                            println("[TVWiki][v1.7] key7 획득 필요 구간. 최대 5초 대기 시작.")
                                            handler.postDelayed({
                                                if (cont.isActive) {
                                                    println("[TVWiki][v1.7] 최대 타임아웃 5초 만료. 현 상태에서 반환 수행.")
                                                    try { webView.destroy() } catch (e: Exception) {}
                                                    cont.resume(detectedCUrl)
                                                }
                                            }, 5000) 
                                        }
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url); view?.evaluateJavascript(hookScript, null)
                    }
                }
                webView.loadUrl(url, mapOf("Referer" to referer))
            } catch (e: Exception) {
                println("[TVWiki][v1.7] WebView 초기화 중 예외 발생: ${e.message}")
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
                println("[TVWiki][v1.7] 초경량 하이브리드 프록시 서버 시작. Port: $port")
                
                thread(isDaemon = true) {
                    while (isRunning) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} 
                    } 
                }
            } catch (e: Exception) { println("[TVWiki][v1.7] 프록시 시작 실패: ${e.message}") }
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
                        println("[TVWiki][v1.7] 플레이어가 하이브리드 M3U8을 요청함")
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(currentPlaylist.toByteArray())
                    }
                    path.contains("/key.bin") -> {
                        println("[TVWiki][v1.7] 플레이어가 복호화 Key를 요청함")
                        if (verifiedKey == null) {
                            verifiedKey = verifyMultipleKeys()
                        }
                        
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(verifiedKey ?: ByteArray(16))
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2: Exception) {} }
        }

        private fun verifyMultipleKeys(): ByteArray? = runBlocking {
            val url = testSegmentUrl ?: return@runBlocking null
            val targetIv = currentIv ?: ByteArray(16)
            
            println("[TVWiki][v1.7] 키 1회 선행 검증 시작. 대상 URL: $url")
            
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
                                    println("[TVWiki][v1.7] 정답 키 매칭 성공! Key: $hexKey")
                                    return@synchronized keyBytes
                                }
                            }
                        } catch (e: Exception) {}
                    }
                    println("[TVWiki][v1.7] 키 매칭 실패")
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
