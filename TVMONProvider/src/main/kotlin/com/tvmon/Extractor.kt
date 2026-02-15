package com.tvmon

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.* // 요청하신 import 적용
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.mapper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Version: v23.2 (Build Fix: Use app.context)
 * Modification:
 * 1. [FIX] 'MainActivity.activity' 참조 불가 에러 해결 -> 'app.context' 사용.
 * 2. [IMPORT] 'com.lagradost.cloudstream3.utils.*' 선언.
 * 3. [KEEP] Native WebView Hooking 기능 유지.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVMON"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
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
        println("[TVMON][v23.2] getUrl 호출됨. URL: $url")
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
        
        // 1. iframe 주소 추출
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) { println("[TVMON] [ERROR] iframe 파싱 실패: ${e.message}") }
        }

        val targetUrl = cleanUrl
        
        // 2. Native WebView Hooking
        println("[TVMON] [STEP 2] Native WebView Hooking 시작...")
        val extractedKeyHex = runNativeWebViewHook(targetUrl, cleanReferer)
        
        if (extractedKeyHex != null) {
            println("[TVMON] [SUCCESS] Native Hooking으로 키 확보: $extractedKeyHex")
            verifiedKey = extractedKeyHex.hexToByteArray()
        } else {
            println("[TVMON] [FAIL] 키 확보 실패. Proxy 단계에서 실패할 가능성이 높습니다.")
        }

        // 3. M3U8 요청 및 재생
        val cookie = CookieManager.getInstance().getCookie(targetUrl)
        val headers = mutableMapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://player.bunny-frame.online/",
            "Origin" to "https://player.bunny-frame.online"
        )
        if (!cookie.isNullOrEmpty()) {
            headers["Cookie"] = cookie
        }

        try {
            println("[TVMON] [STEP 3] M3U8 메인 파일 요청 중...")
            var requestUrl = targetUrl.substringBefore("#")
            
            var response = app.get(requestUrl, headers = headers)
            var content = response.text.trim()

            if (!content.startsWith("#EXTM3U")) {
                Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(content)?.let {
                    requestUrl = it.groupValues[1]
                    println("[TVMON] 실제 M3U8 주소 발견: $requestUrl")
                    content = app.get(requestUrl, headers = headers).text.trim()
                }
            }

            val isKey7 = content.lines().any { it.startsWith("#EXT-X-KEY") && it.contains("/v/key7") }
            println("[TVMON] [CHECK] Key7 암호화 적용 여부: $isKey7")

            if (isKey7) {
                println("[TVMON] [STEP 4] Key7 프록시 서버 초기화...")
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply {
                    start()
                    updateSession(headers)
                }

                val ivMatch = Regex("""IV=(0x[0-9a-fA-F]+)""").find(content)
                val ivHex = ivMatch?.groupValues?.get(1) ?: "0x00000000000000000000000000000000"
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
                                val originalKeyUrl = match.groupValues[1]
                                val absoluteKeyUrl = resolveUrl(baseUri, requestUrl, originalKeyUrl)
                                val encodedKeyUrl = java.net.URLEncoder.encode(absoluteKeyUrl, "UTF-8")
                                val newLine = trimmed.replace(originalKeyUrl, "http://127.0.0.1:${proxyServer!!.port}/key?url=$encodedKeyUrl")
                                sb.append(newLine).append("\n")
                            } else sb.append(trimmed).append("\n")
                        } else sb.append(trimmed).append("\n")
                    } else {
                        val absoluteSegUrl = resolveUrl(baseUri, requestUrl, trimmed)
                        if (testSegmentUrl == null) {
                            testSegmentUrl = absoluteSegUrl
                        }
                        val encodedSegUrl = java.net.URLEncoder.encode(absoluteSegUrl, "UTF-8")
                        sb.append("http://127.0.0.1:${proxyServer!!.port}/seg?url=$encodedSegUrl").append("\n")
                    }
                }

                proxyServer!!.setPlaylist(sb.toString())
                val proxyFinalUrl = "http://127.0.0.1:${proxyServer!!.port}/playlist.m3u8"
                
                callback(newExtractorLink(name, name, proxyFinalUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                })
                return true
            } 

            callback(newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                this.referer = "https://player.bunny-frame.online/"; this.headers = headers
            })
            return true

        } catch (e: Exception) { println("[TVMON] [ERROR] 추출 프로세스 오류: ${e.message}") }
        
        return false
    }

    // ==========================================
    // Native WebView Hooking Implementation
    // ==========================================
    
    class JSBridge(val onResult: (String) -> Unit) {
        @JavascriptInterface
        fun sendKey(key: String) {
            println("[JSBridge] 키 수신됨: $key")
            onResult(key)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runNativeWebViewHook(url: String, referer: String): String? {
        return withContext(Dispatchers.Main) { 
            suspendCancellableCoroutine<String?> { continuation ->
                // [FIX] app.context 사용 (Application Context)
                val context = app.context
                if (context == null) {
                    println("[NativeHook] [FATAL] Context 참조 불가.")
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                // Application Context로 WebView 생성 (UI 조작 없는 Headless 모드에 적합)
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                }

                var isResumed = false
                
                val bridge = JSBridge { key ->
                    if (!isResumed) {
                        isResumed = true
                        continuation.resume(key)
                        Handler(Looper.getMainLooper()).post { 
                            try { webView.destroy() } catch(e:Exception){} 
                        }
                    }
                }
                webView.addJavascriptInterface(bridge, "TVMONBridge")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        println("[NativeHook] 페이지 로드 완료: $url")
                        
                        val hookScript = """
                            javascript:(function() {
                                if (window.isHooked) return;
                                window.isHooked = true;
                                console.log("[NativeHook] 스크립트 주입됨");
                                const originalSet = Uint8Array.prototype.set;
                                
                                Uint8Array.prototype.set = function(source, offset) {
                                    if (source instanceof Uint8Array && source.length === 16) {
                                        var hex = Array.from(source).map(b => b.toString(16).padStart(2, '0')).join('');
                                        console.log("[NativeHook] Key Captured: " + hex);
                                        if (window.TVMONBridge) {
                                            window.TVMONBridge.sendKey(hex);
                                        }
                                    }
                                    return originalSet.apply(this, arguments);
                                };
                            })();
                        """.trimIndent()
                        
                        view?.loadUrl(hookScript)
                    }
                }

                println("[NativeHook] WebView 로딩 시작: $url")
                val headers = mapOf("Referer" to referer)
                webView.loadUrl(url, headers)

                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isResumed) {
                        println("[NativeHook] [TIMEOUT] 시간 초과.")
                        isResumed = true
                        continuation.resume(null)
                        try { webView.destroy() } catch(e:Exception){}
                    }
                }, 15000)
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

    // Proxy Server
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
            } catch (e: Exception) { println("[PROXY] [ERROR] 시작 실패: ${e.message}") }
        }

        fun stop() { isRunning = false; serverSocket?.close() }
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
                    path.contains("/playlist.m3u8") -> {
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(currentPlaylist.toByteArray())
                    }
                    path.contains("/key") -> {
                        val keyToSend = verifiedKey ?: ByteArray(16)
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(keyToSend)
                    }
                    path.contains("/seg") -> {
                        val targetUrl = URLDecoder.decode(path.substringAfter("url="), "UTF-8")
                        val conn = URL(targetUrl).openConnection() as HttpURLConnection
                        currentHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                        val inputStream = conn.inputStream
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        inputStream.copyTo(output)
                        inputStream.close()
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { socket.close() }
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
