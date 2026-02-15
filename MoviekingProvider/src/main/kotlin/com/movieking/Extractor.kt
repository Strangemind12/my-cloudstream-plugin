package com.movieking

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * v2.0: TVMON Style JS Hooking Implementation
 * [변경 사항]
 * 1. 기존 Combinatorial(순열 조합) 방식 제거 -> WebView JS Hooking 방식으로 변경.
 * 2. ProxyWebServer 구조를 TVMON과 동일하게 인스턴스 격리형으로 교체.
 * 3. 복잡한 키 해독 로직 없이, 브라우저가 사용하는 키를 직접 캡처하여 사용.
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        @Volatile private var currentProxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // 기존 extractVideoIdDeep 로직은 URL 파싱용으로 유지할 수도 있으나, 
        // TVMON 방식은 전체 URL을 그대로 WebView에 태우는 것이 더 확실합니다.
        extract(url, referer, subtitleCallback, callback)
    }

    private suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[MovieKing][JS-Hook] Extract Start. URL: $url")

        // 1. 기존 프록시 정리
        synchronized(this) {
            if (currentProxyServer != null) {
                currentProxyServer?.stop()
                currentProxyServer = null
            }
        }

        // 2. 세션 키 저장소 생성
        val currentSessionKeys = Collections.synchronizedSet(mutableSetOf<String>())
        
        // 3. WebView 실행 및 키 캡처 (TVMON과 동일한 후킹 로직)
        // MovieKing은 iframe 내부가 아니라 직접 플레이어 URL일 가능성이 높으므로 바로 훅을 겁니다.
        // 만약 iframe이라면 TVMON처럼 iframe src 추출 로직이 필요할 수 있습니다.
        val webViewUrl = url // 필요 시 iframe src 추출 로직 추가
        
        runWebViewHook(webViewUrl, referer ?: "https://mvking6.org/", currentSessionKeys)
        
        // 4. 캡처된 정보로 재생 시도
        // MovieKing은 m3u8 주소를 html 내 'data-m3u8' 속성 등에서 찾아야 할 수도 있고,
        // TVMON처럼 네트워크 요청을 가로채야 할 수도 있습니다. 
        // 여기서는 기존 MovieKing 로직(HTML 파싱)과 TVMON 로직(WebView 쿠키 활용)을 결합합니다.

        try {
            val cookie = CookieManager.getInstance().getCookie(webViewUrl)
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red"
            )
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

            // HTML에서 m3u8 URL 추출 (기존 MovieKing 로직 활용)
            val response = app.get(webViewUrl, headers = headers)
            val playerHtml = response.text
            
            // data-m3u8 추출 시도
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") 
                ?: Regex("""file:\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1) // JWPlayer 패턴 추가

            if (m3u8Url == null) {
                println("[MovieKing] M3U8 URL 파싱 실패.")
                return
            }

            println("[MovieKing] M3U8 Found: $m3u8Url")
            var playlistContent = app.get(m3u8Url, headers = headers).text

            // 5. 프록시 서버 시작
            val newProxy = ProxyWebServer(currentSessionKeys).apply {
                start()
                updateSession(headers)
            }
            currentProxyServer = newProxy

            // IV 추출 (기존 로직 활용)
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistContent)
            val ivHex = keyMatch?.groupValues?.get(2)
            if (ivHex != null) {
                newProxy.setIv(ivHex.removePrefix("0x").hexToByteArray())
            }

            // 6. Playlist Rewrite (TVMON 스타일)
            val baseUri = try { URI(m3u8Url) } catch (e: Exception) { null }
            val sb = StringBuilder()

            playlistContent.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXT-X-KEY")) {
                    // 키 URL을 로컬 프록시로 변경
                    val originalKeyUri = Regex("""URI="([^"]+)"""").find(trimmed)?.groupValues?.get(1)
                    if (originalKeyUri != null) {
                        val absKey = resolveUrl(baseUri, m3u8Url, originalKeyUri)
                        val encKey = java.net.URLEncoder.encode(absKey, "UTF-8")
                        // 여기서 중요: TVMON 방식은 'verifyMultipleKeys'를 통해 캡처된 키 중 맞는 걸 찾음
                        val newLine = trimmed.replace(originalKeyUri, "http://127.0.0.1:${newProxy.port}/key?url=$encKey")
                        sb.append(newLine).append("\n")
                    } else {
                        sb.append(trimmed).append("\n")
                    }
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    // 세그먼트 URL도 프록시를 거치게 할지, 직접 받을지 결정.
                    // TVMON은 세그먼트 검증을 위해 프록시를 거치게 함 (특히 verifyMultipleKeys를 위해)
                    val absSeg = resolveUrl(baseUri, m3u8Url, trimmed)
                    newProxy.setTestSegment(absSeg) // 키 검증용 샘플 등록
                    
                    // MovieKing 기존: 영상은 직접 받음. TVMON: 영상도 프록시(옵션).
                    // 안정성을 위해 TVMON처럼 프록시를 태워 '올바른 키'가 확정되면 그때 복호화 없이 넘기거나 복호화해서 넘김.
                    // 여기서는 TVMON과 동일하게 모든 트래픽을 프록시로 태웁니다.
                    val encSeg = java.net.URLEncoder.encode(absSeg, "UTF-8")
                    sb.append("http://127.0.0.1:${newProxy.port}/seg?url=$encSeg").append("\n")
                } else {
                    sb.append(trimmed).append("\n")
                }
            }
            
            newProxy.setPlaylist(sb.toString())
            
            // Video ID 추출 (URL 매핑용) - 임의 값 사용 가능
            val finalUrl = "http://127.0.0.1:${newProxy.port}/video.m3u8"
            
            callback(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                this.referer = "https://player-v1.bcbc.red/"
            })

        } catch (e: Exception) {
            println("[MovieKing] Error: ${e.message}")
            e.printStackTrace()
        }
    }

    // =====================================================================================
    // 아래는 TVMONProvider에서 가져온 핵심 유틸리티 함수들 (WebView Hook & Proxy)
    // =====================================================================================

    private suspend fun runWebViewHook(url: String, referer: String, sessionKeys: MutableSet<String>) = suspendCancellableCoroutine<Unit> { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        // TVMON과 동일한 후킹 스크립트
        val hookScript = """
            (function() {
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
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.userAgentString = DESKTOP_UA

                // 15초 타임아웃 (키가 안 잡히더라도 진행)
                val timeout = Runnable {
                    if (cont.isActive) {
                        println("[MovieKing] WebView Timeout. Proceeding anyway.")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(Unit)
                    }
                }
                handler.postDelayed(timeout, 15000)

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:").removePrefix("[SET]").removePrefix("[CRYPTO]")
                            if (sessionKeys.add(key)) {
                                println("[MovieKing] Key Captured: $key")
                            }
                            // 키를 하나라도 찾으면 빨리 종료하고 싶다면 여기서 resume 가능 (선택 사항)
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(hookScript, null)
                    }
                }

                webView.loadUrl(url, mapOf("Referer" to referer))

            } catch (e: Exception) {
                if (cont.isActive) cont.resume(Unit)
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
                thread(isDaemon = true) {
                    while (isRunning) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} }
                }
            } catch (e: Exception) {}
        }

        fun stop() { isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} }
        fun updateSession(h: Map<String, String>) { currentHeaders = h }
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun setIv(iv: ByteArray) { currentIv = iv }
        fun setTestSegment(url: String) { if (testSegmentUrl == null) testSegmentUrl = url }

        private fun handleClient(socket: Socket) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                val parts = line.split(" ")
                if (parts.size < 2) return
                val path = parts[1]
                val output = socket.getOutputStream()

                when {
                    path.contains(".m3u8") -> {
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray())
                        output.write(currentPlaylist.toByteArray())
                    }
                    path.contains("/key") -> {
                        if (verifiedKey == null) verifiedKey = verifyMultipleKeys()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                        output.write(verifiedKey ?: ByteArray(16))
                    }
                    path.contains("/seg") -> {
                        val targetUrl = URLDecoder.decode(path.substringAfter("url="), "UTF-8")
                        try {
                            val conn = URL(targetUrl).openConnection() as HttpURLConnection
                            currentHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                            if (conn.responseCode == 200) {
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                                conn.inputStream.use { it.copyTo(output) }
                            }
                        } catch (e: Exception) {}
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2:Exception){} }
        }

        private fun verifyMultipleKeys(): ByteArray? = runBlocking {
            val url = testSegmentUrl ?: return@runBlocking null
            val targetIv = currentIv ?: ByteArray(16) // IV가 없으면 0으로 가정
            
            try {
                val responseData = app.get(url, headers = currentHeaders).body.bytes()
                val checkSize = 1024.coerceAtMost(responseData.size)
                
                synchronized(sessionKeys) {
                    for (hexKey in sessionKeys) {
                        try {
                            val keyBytes = hexKey.hexToByteArray()
                            // 앞부분만 복호화해서 TS Sync Byte(0x47) 체크
                            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(targetIv))
                            val decrypted = cipher.doFinal(responseData.copyOfRange(0, checkSize))
                            
                            if (decrypted.isNotEmpty() && decrypted[0] == 0x47.toByte()) {
                                println("[MovieKing] Valid Key Found: $hexKey")
                                return@synchronized keyBytes
                            }
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {}
            return@runBlocking null
        }
    }
}

// 확장 함수 (기존 파일 하단이나 별도 유틸 파일에 위치 필요)
fun String.hexToByteArray(): ByteArray {
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
