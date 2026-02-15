package com.movieking

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import com.lagradost.cloudstream3.utils.*
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
 * v2.2: Fix Verification Logic Return Bug
 * - [Fix] verifyMultipleKeys에서 return@synchronized로 인해 함수가 종료되지 않고 null을 반환하던 버그 수정.
 * - [Imp] JS Hooking 시 MP4 Atom 헤더(00000010...) 등 명백한 노이즈 데이터 필터링 추가.
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
        println("[MovieKing] getUrl 호출됨. URL: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    private suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("================================================================")
        println("[MovieKing] [STEP 1] Extract 시작. URL: $url")

        // 1. 기존 프록시 정리
        synchronized(this) {
            if (currentProxyServer != null) {
                currentProxyServer?.stop()
                currentProxyServer = null
            }
        }

        // 2. 세션 키 저장소 생성
        val currentSessionKeys = Collections.synchronizedSet(mutableSetOf<String>())
        
        // 3. WebView 실행
        val webViewUrl = url 
        runWebViewHook(webViewUrl, referer ?: "https://mvking6.org/", currentSessionKeys)
        
        println("[MovieKing] [STEP 3] WebView Hook 완료. 수집된 키 개수: ${currentSessionKeys.size}")

        try {
            val cookie = CookieManager.getInstance().getCookie(webViewUrl)
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red"
            )
            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
            }

            // M3U8 URL 추출
            val response = app.get(webViewUrl, headers = headers)
            val playerHtml = response.text
            
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") 
                ?: Regex("""file:\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)

            if (m3u8Url == null) {
                println("[MovieKing] [ERROR] M3U8 URL 파싱 실패.")
                return
            }

            println("[MovieKing] [FOUND] M3U8 URL: $m3u8Url")
            var playlistContent = app.get(m3u8Url, headers = headers).text

            // 5. 프록시 서버 시작
            val newProxy = ProxyWebServer(currentSessionKeys).apply {
                start()
                updateSession(headers)
            }
            currentProxyServer = newProxy
            println("[MovieKing] [PROXY] 서버 포트: ${newProxy.port}")

            // IV 추출
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistContent)
            val ivHex = keyMatch?.groupValues?.get(2)
            if (ivHex != null) {
                println("[MovieKing] [INFO] IV 발견: $ivHex")
                newProxy.setIv(ivHex.removePrefix("0x").hexToByteArray())
            } else {
                newProxy.setIv(ByteArray(16))
            }

            // 6. Playlist Rewrite
            val baseUri = try { URI(m3u8Url) } catch (e: Exception) { null }
            val sb = StringBuilder()

            playlistContent.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXT-X-KEY")) {
                    val originalKeyUri = Regex("""URI="([^"]+)"""").find(trimmed)?.groupValues?.get(1)
                    if (originalKeyUri != null) {
                        val absKey = resolveUrl(baseUri, m3u8Url, originalKeyUri)
                        val encKey = java.net.URLEncoder.encode(absKey, "UTF-8")
                        // Key 주소를 로컬 프록시로 변경
                        val newLine = trimmed.replace(originalKeyUri, "http://127.0.0.1:${newProxy.port}/key?url=$encKey")
                        sb.append(newLine).append("\n")
                    } else {
                        sb.append(trimmed).append("\n")
                    }
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val absSeg = resolveUrl(baseUri, m3u8Url, trimmed)
                    newProxy.setTestSegment(absSeg) // 첫 세그먼트 등록 (키 검증용)
                    
                    val encSeg = java.net.URLEncoder.encode(absSeg, "UTF-8")
                    sb.append("http://127.0.0.1:${newProxy.port}/seg?url=$encSeg").append("\n")
                } else {
                    sb.append(trimmed).append("\n")
                }
            }
            
            newProxy.setPlaylist(sb.toString())
            
            val finalUrl = "http://127.0.0.1:${newProxy.port}/video.m3u8"
            println("[MovieKing] [DONE] Callback 호출: $finalUrl")
            
            callback(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                this.referer = "https://player-v1.bcbc.red/"
            })

        } catch (e: Exception) {
            println("[MovieKing] [FATAL] Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun runWebViewHook(url: String, referer: String, sessionKeys: MutableSet<String>) = suspendCancellableCoroutine<Unit> { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        val hookScript = """
            (function() {
                // 노이즈 필터링: MP4 Atom 헤더(00000010...) 등은 무시
                function isValidKey(hex) {
                    if (!hex) return false;
                    // MP4 Atom size (Big Endian 16 = 0x00000010)로 시작하는 경우 무시
                    if (hex.startsWith("00000010")) return false;
                    if (hex.startsWith("000000")) return false; // 0으로 시작하는 데이터 대부분 무시 (실제 키일 확률 낮음)
                    return true;
                }

                if (window.crypto && window.crypto.subtle) {
                    const originalImportKey = window.crypto.subtle.importKey;
                    Object.defineProperty(window.crypto.subtle, 'importKey', {
                        value: function(format, keyData, algorithm, extractable, keyUsages) {
                            if (format === 'raw' && (keyData.byteLength === 16 || keyData.length === 16)) {
                                try {
                                    let bytes = new Uint8Array(keyData);
                                    let hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
                                    if (isValidKey(hex)) {
                                        console.log("CapturedKeyHex:[CRYPTO]" + hex);
                                    }
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
                            if (isValidKey(hex)) {
                                console.log("CapturedKeyHex:[SET]" + hex);
                            }
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
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = DESKTOP_UA
                }

                val timeout = Runnable {
                    if (cont.isActive) {
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(Unit)
                    }
                }
                handler.postDelayed(timeout, 20000) // 20초

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:").removePrefix("[SET]").removePrefix("[CRYPTO]")
                            if (sessionKeys.add(key)) {
                                println("[MovieKing] [WEBVIEW] Key: $key")
                            }
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
                        if (verifiedKey == null) {
                            verifiedKey = verifyMultipleKeys()
                        }
                        
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                        if (verifiedKey != null) {
                            output.write(verifiedKey!!)
                        } else {
                            // 재생 실패를 막기 위해 임시로 0 반환하지만, 결국 에러 발생함.
                            output.write(ByteArray(16))
                        }
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
            val targetIv = currentIv ?: ByteArray(16)
            
            println("[MovieKing] [VERIFY] Key 검증 시작. 후보 키 개수: ${sessionKeys.size}")

            try {
                val responseData = app.get(url, headers = currentHeaders).body.bytes()
                val checkSize = 1024.coerceAtMost(responseData.size)

                // [Fix] 동기화 블록의 결과를 변수에 할당하여 null 체크 수행
                val foundKey: ByteArray? = synchronized(sessionKeys) {
                    for (hexKey in sessionKeys) {
                        try {
                            val keyBytes = hexKey.hexToByteArray()
                            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(targetIv))
                            val decrypted = cipher.doFinal(responseData.copyOfRange(0, checkSize))
                            
                            if (decrypted.isNotEmpty() && decrypted[0] == 0x47.toByte()) {
                                println("[MovieKing] [VERIFY-SUCCESS] ★ 올바른 Key 찾음: $hexKey")
                                return@synchronized keyBytes // 블록의 결과로 keyBytes 반환
                            }
                        } catch (e: Exception) {}
                    }
                    return@synchronized null // 못 찾으면 null 반환
                }

                // [Fix] 찾은 키가 있으면 함수 반환
                if (foundKey != null) {
                    return@runBlocking foundKey
                }

            } catch (e: Exception) {
                println("[MovieKing] [VERIFY-ERR] ${e.message}")
            }
            
            println("[MovieKing] [VERIFY-FAIL] 올바른 키를 찾지 못했습니다.")
            return@runBlocking null
        }
    }
}

fun String.hexToByteArray(): ByteArray {
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
