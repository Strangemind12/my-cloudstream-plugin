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
 * Version: v23.7 (Direct Content Inspection & ID-based Proxy)
 * Modification:
 * 1. [FIX] Fetch c.html first and check for 'key7' inside the playlist content.
 * 2. [FIX] Skip WebView if 'key7' is not found in the playlist.
 * 3. [FIX] WebView timeout reduced to 5 seconds.
 * 4. [FIX] Proxy URL format: http://127.0.0.1:port/{videoId}/playlist.m3u8
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
        println("[TVMON][v23.7] getUrl 호출됨. URL: $url")
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
        
        // 1. iframe 주소로부터 실제 c.html 경로 찾기
        if (!cleanUrl.contains("/c.html")) {
            try {
                println("[TVMON] [STEP 1-1] 원본 소스에서 플레이어 URL 추출 시도...")
                val refRes = app.get(cleanUrl, referer = cleanReferer)
                val iframeMatch = Regex("""src=['"](https://[^"']+/v/[ef]/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://[^"']+/v/[ef]/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
                
                if (!cleanUrl.contains("/c.html")) {
                    cleanUrl = if (cleanUrl.contains("?")) {
                        cleanUrl.replace("/v/e/", "/v/e/").replace("/v/f/", "/v/f/").substringBefore("?") + "/c.html?" + cleanUrl.substringAfter("?")
                    } else {
                        "$cleanUrl/c.html"
                    }
                }
            } catch (e: Exception) { println("[TVMON] [ERROR] URL 구성 실패: ${e.message}") }
        }

        println("[TVMON] [STEP 2] c.html 내용 선행 분석 시작: $cleanUrl")
        
        val headers = mutableMapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://player.bunny-frame.online/",
            "Origin" to "https://player.bunny-frame.online"
        )

        val videoId = Regex("/v/[ef]/([^/]+)").find(cleanUrl)?.groupValues?.get(1) ?: "video"
        var playlistContent: String = ""
        try {
            val response = app.get(cleanUrl, headers = headers)
            playlistContent = response.text.trim()
        } catch (e: Exception) {
            println("[TVMON] [ERROR] c.html 로드 실패: ${e.message}")
            return false
        }

        // 2. key7 포함 여부 확인
        val hasKey7 = playlistContent.contains("#EXT-X-KEY") && playlistContent.contains("/v/key7")

        if (hasKey7) {
            println("[TVMON] [STEP 3] key7 감지됨! 웹뷰 후킹 가동 (5초 대기)...")
            capturedKeys.clear()
            verifiedKey = null
            
            // 웹뷰를 통해 토큰과 세션 쿠키, 그리고 WASM에서 생성되는 키를 가로챔
            runWebViewHook(cleanUrl, cleanReferer)
            
            println("[TVMON] [STEP 4] 키 수집 종료. 후보군: ${capturedKeys.size}개. 프록시 설정 시작.")
            
            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply { start(); updateSession(headers) }

            // IV 추출
            val ivMatch = Regex("""IV=("?)(0x[0-9a-fA-F]+)\1""").find(playlistContent)
            val ivHex = ivMatch?.groupValues?.get(2) ?: "0x00000000000000000000000000000000"
            currentIv = ivHex.removePrefix("0x").hexToByteArray()

            val baseUri = try { URI(cleanUrl) } catch (e: Exception) { null }
            val sb = StringBuilder()
            
            playlistContent.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                if (trimmed.startsWith("#")) {
                    if (trimmed.startsWith("#EXT-X-KEY") && trimmed.contains("/v/key7")) {
                        val match = Regex("""URI="([^"]+)"""").find(trimmed)
                        if (match != null) {
                            val absKey = resolveUrl(baseUri, cleanUrl, match.groupValues[1])
                            val encKey = java.net.URLEncoder.encode(absKey, "UTF-8")
                            sb.append(trimmed.replace(match.groupValues[1], "http://127.0.0.1:${proxyServer!!.port}/key?url=$encKey")).append("\n")
                        } else sb.append(trimmed).append("\n")
                    } else sb.append(trimmed).append("\n")
                } else {
                    val absSeg = resolveUrl(baseUri, cleanUrl, trimmed)
                    if (testSegmentUrl == null) testSegmentUrl = absSeg
                    val encSeg = java.net.URLEncoder.encode(absSeg, "UTF-8")
                    sb.append("http://127.0.0.1:${proxyServer!!.port}/seg?url=$encSeg").append("\n")
                }
            }
            proxyServer!!.setPlaylist(sb.toString())

            val proxyUrl = "http://127.0.0.1:${proxyServer!!.port}/$videoId/playlist.m3u8"
            println("[TVMON] [FINISH] 프록시 재생 주소: $proxyUrl")
            
            callback(newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                this.referer = "https://player.bunny-frame.online/"; this.headers = headers
            })
            return true
        } else {
            println("[TVMON] [STEP 3] 일반 영상(key7 없음) 확인. 웹뷰 없이 즉시 재생.")
            callback(newExtractorLink(name, name, cleanUrl, ExtractorLinkType.M3U8) {
                this.referer = "https://player.bunny-frame.online/"; this.headers = headers
            })
            return true
        }
    }

    private suspend fun runWebViewHook(url: String, referer: String) = suspendCancellableCoroutine<Unit> { cont ->
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
                        configurable: true, writable: true
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
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = DESKTOP_UA

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:").removePrefix("[SET]").removePrefix("[CRYPTO]")
                            capturedKeys.add(key)
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        view?.evaluateJavascript(hookScript, null)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(hookScript, null)
                    }
                }

                webView.loadUrl(url, mapOf("Referer" to referer))

                // 5초 후 강제 종료 및 복귀
                Handler(Looper.getMainLooper()).postDelayed({
                    if (cont.isActive) {
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(Unit)
                    }
                }, 5000)
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

        fun stop() { isRunning = false; try { serverSocket?.close() } catch(e: Exception){} }
        fun updateSession(h: Map<String, String>) { currentHeaders = h }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val firstLine = reader.readLine() ?: return
                val path = firstLine.split(" ")[1]
                val output = socket.getOutputStream()

                when {
                    // 비디오 ID가 포함된 경로 처리
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
            } catch (e: Exception) { try { socket.close() } catch(ex: Exception){} }
        }

        private fun verifyMultipleKeys(): ByteArray? {
            val url = testSegmentUrl ?: return null
            val targetIv = currentIv ?: ByteArray(16)
            return try {
                val responseData = runBlocking { app.get(url, headers = currentHeaders).body.bytes() }
                val checkSize = 1024 
                val safeCheckSize = if (responseData.size < checkSize) responseData.size else checkSize
                synchronized(capturedKeys) {
                    println("[VERIFY] ${capturedKeys.size}개의 키 후보 검증 시작...")
                    for (hexKey in capturedKeys) {
                        val keyBytes = hexKey.hexToByteArray()
                        for (offset in 0..512) {
                            if (responseData.size < offset + safeCheckSize) break
                            val testChunk = responseData.copyOfRange(offset, offset + safeCheckSize)
                            val decrypted = decryptAES(testChunk, keyBytes, targetIv)
                            if (decrypted.size >= 377 && decrypted[0] == 0x47.toByte() && decrypted[188] == 0x47.toByte() && decrypted[376] == 0x47.toByte()) {
                                println("[VERIFY] ★ 정답 키 발견: $hexKey")
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
