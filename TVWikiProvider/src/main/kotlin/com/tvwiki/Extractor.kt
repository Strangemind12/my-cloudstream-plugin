package com.tvmon

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.MainActivity
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.mapper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
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
 * Version: v23.0 (Native WebView Hooking - The Powerful Method)
 * Modification:
 * 1. [REMOVED] WebViewResolver 사용 중단 (기능 제한 및 빌드 에러 원인).
 * 2. [ADDED] 'runNativeWebViewHook' 함수 구현: Android Native WebView를 직접 생성하여 제어.
 * 3. [ADDED] 'JavaScriptInterface': JS와 Kotlin 간의 데이터 브릿지(Bridge) 구현.
 * 4. [LOGIC] onPageFinished 타이밍에 Hooking 스크립트를 강제 주입하여 키 탈취 성공률 극대화.
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
        println("[TVMON][v23.0] getUrl 호출됨. URL: $url")
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
        println("[TVMON] 대상 URL: $cleanUrl, 레퍼러: $cleanReferer")

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

        var targetUrl = cleanUrl
        
        // 2. Native WebView Hooking (가장 강력한 방식)
        println("[TVMON] [STEP 2] Native WebView Hooking 시작...")
        
        // c.html URL이 아니면 원본 URL에서 시작, 스크립트가 리다이렉트를 따라감
        val extractedKeyHex = runNativeWebViewHook(targetUrl, cleanReferer)
        
        if (extractedKeyHex != null) {
            println("[TVMON] [SUCCESS] Native Hooking으로 키 확보: $extractedKeyHex")
            verifiedKey = extractedKeyHex.hexToByteArray() // 확보된 키를 즉시 검증키로 등록
        } else {
            println("[TVMON] [FAIL] 키 확보 실패. 기존 로직(Key7 Proxy)으로 진행합니다.")
        }

        // 3. M3U8 요청 및 재생 (확보된 키가 있다면 Proxy가 그것을 사용함)
        // Hooking 과정에서 쿠키가 갱신되었을 수 있으므로 CookieManager 확인
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
                                // 키가 이미 Hooking으로 확보되었다면 Proxy가 바로 반환할 것임
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
    
    // JS와 통신하기 위한 브릿지 인터페이스
    class JSBridge(val onResult: (String) -> Unit) {
        @JavascriptInterface
        fun sendKey(key: String) {
            println("[JSBridge] 키 수신됨: $key")
            onResult(key)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runNativeWebViewHook(url: String, referer: String): String? {
        return withContext(Dispatchers.Main) { // UI 스레드에서 실행 필수
            suspendCancellableCoroutine<String?> { continuation ->
                val activity = com.lagradost.cloudstream3.MainActivity.activity // Cloudstream MainActivity 참조
                if (activity == null) {
                    println("[NativeHook] [FATAL] Activity 참조 불가. Hooking 중단.")
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                // 1. Native WebView 생성
                val webView = WebView(activity)
                
                // 2. 설정: JS 활성화 및 디버깅
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                }

                // 3. 브릿지 연결: JS에서 'window.TVMONBridge.sendKey()'로 호출 가능
                var isResumed = false
                val bridge = JSBridge { key ->
                    if (!isResumed) {
                        isResumed = true
                        continuation.resume(key)
                        // 작업 완료 후 WebView 제거 (메모리 누수 방지)
                        Handler(Looper.getMainLooper()).post { 
                            try { webView.destroy() } catch(e:Exception){} 
                        }
                    }
                }
                webView.addJavascriptInterface(bridge, "TVMONBridge")

                // 4. 클라이언트 설정: 페이지 로드 완료 시 스크립트 주입
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        println("[NativeHook] 페이지 로드 완료: $url")
                        
                        // 강력한 Hooking 스크립트 주입
                        // Uint8Array.prototype.set을 납치하여 키 생성 순간을 포착
                        val hookScript = """
                            javascript:(function() {
                                console.log("[NativeHook] 스크립트 주입됨");
                                const originalSet = Uint8Array.prototype.set;
                                var keyFound = false;
                                Uint8Array.prototype.set = function(source, offset) {
                                    if (!keyFound && source instanceof Uint8Array && source.length === 16) {
                                        var hex = Array.from(source).map(b => b.toString(16).padStart(2, '0')).join('');
                                        console.log("[NativeHook] Key Captured: " + hex);
                                        // 브릿지를 통해 앱으로 전송
                                        if (window.TVMONBridge) {
                                            window.TVMONBridge.sendKey(hex);
                                            keyFound = true;
                                        }
                                    }
                                    return originalSet.apply(this, arguments);
                                };
                            })();
                        """.trimIndent()
                        
                        view?.loadUrl(hookScript)
                    }
                }

                // 5. 로딩 시작
                println("[NativeHook] WebView 로딩 시작: $url")
                val headers = mapOf("Referer" to referer)
                webView.loadUrl(url, headers)

                // 6. 타임아웃 설정 (15초)
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

    // Proxy Server (기존 유지)
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
                        println("[PROXY] [REQ] Key 요청 수신. (Hooking된 키 사용)")
                        // Hooking으로 확보된 키(verifiedKey)가 있으면 바로 반환
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
