package com.anilife

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Anilife Proxy Extractor v83.1
 * - [Paradigm] TVWiki & MovieKing 융합 (Hybrid Proxy + Aggressive Hooking)
 * - [Logic] HTML/JS 변조 없이 원본 플레이어를 백그라운드로 로드하여 융단폭격식 후킹 스크립트 주입
 * - [Performance] 영상(TS)은 프록시를 거치지 않고 직접 CDN에서 다운로드
 */
class AnilifeProxyExtractor : ExtractorApi() {
    override val name = "AnilifeProxy"
    override val mainUrl = "https://api.gcdn.app"
    override val requiresReferer = false

    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    companion object {
        @Volatile private var currentProxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) { }

    fun extractPlayerUrl(html: String, domain: String): String? {
        val patterns = listOf(
            Regex("""location\.href\s*=\s*["']([^"']+)["']"""),
            Regex("""["']([^"']*h\/live\?p=[^"']+)["']""")
        )
        for (regex in patterns) {
            regex.find(html)?.let {
                var url = it.groupValues[1]
                if (url.contains("h/live") && url.contains("p=")) {
                    if (!url.startsWith("http")) url = if (url.startsWith("/")) "$domain$url" else "$domain/$url"
                    return url.replace("\\/", "/")
                }
            }
        }
        return null
    }

    suspend fun extractWithProxy(
        m3u8Url: String,
        playerUrl: String,
        referer: String,
        ssid: String?,
        cookies: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        synchronized(this) { currentProxyServer?.stop(); currentProxyServer = null }

        println("[Anilife][Proxy] 1. 프록시 서버 및 키 저장소 초기화")
        val sessionKeys = Collections.synchronizedSet(mutableSetOf<String>())

        val headers = mutableMapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://anilife.live/",
            "Origin" to "https://anilife.live",
            "Cookie" to cookies,
            "Accept" to "*/*"
        )
        if (!ssid.isNullOrBlank()) {
            headers["x-user-ssid"] = ssid
            headers["X-User-Ssid"] = ssid
        }

        // 원본 M3U8 다운로드
        val m3u8Content = try {
            app.get(m3u8Url, headers = headers).text
        } catch (e: Exception) {
            println("[Anilife][Proxy] M3U8 원본 다운로드 실패: ${e.message}")
            return false
        }

        println("[Anilife][Proxy] 2. TVWiki 스타일 비동기 후킹 시작 (playerUrl: $playerUrl)")
        runWebViewHookAsync(playerUrl, referer, sessionKeys)

        val proxy = ProxyWebServer(sessionKeys).apply { 
            start()
            updateSession(headers)
        }
        currentProxyServer = proxy

        try {
            val baseUri = try { URI(m3u8Url) } catch (e: Exception) { null }
            val sb = StringBuilder()
            
            val lines = m3u8Content.lines()
            var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(m3u8Content)?.groupValues?.get(1)?.toLong() ?: 0L
            val ivMatch = Regex("""IV=(0x[0-9a-fA-F]+)""").find(m3u8Content)
            val hexIv = ivMatch?.groupValues?.get(1)
            
            proxy.setIvInfo(currentSeq, hexIv)

            // MovieKing 스타일 하이브리드 프록시 리라이팅
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                
                if (trimmed.startsWith("#EXT-X-KEY")) {
                    val match = Regex("""URI="([^"]+)"""").find(trimmed)
                    if (match != null) {
                        // 키 파일만 로컬 프록시 주소로 연결
                        val newKeyLine = trimmed.replace(match.groupValues[1], "http://127.0.0.1:${proxy.port}/key.bin")
                        sb.append(newKeyLine).append("\n")
                    } else sb.append(trimmed).append("\n")
                } else if (!trimmed.startsWith("#")) {
                    // 영상 TS 세그먼트는 CDN 원본 주소 직행 (프록시 거치지 않음)
                    val absSeg = resolveUrl(baseUri, m3u8Url, trimmed)
                    proxy.setTestSegment(absSeg) // 첫 번째 세그먼트 주소만 검증용으로 전달
                    sb.append(absSeg).append("\n")
                } else {
                    sb.append(trimmed).append("\n")
                }
            }
            
            proxy.setPlaylist(sb.toString())
            val finalProxyUrl = "http://127.0.0.1:${proxy.port}/playlist.m3u8"
            
            println("[Anilife][Proxy] 3. 하이브리드 프록시 URL 반환: $finalProxyUrl")
            callback(newExtractorLink(name, name, finalProxyUrl, ExtractorLinkType.M3U8) {
                this.referer = "https://anilife.live/"
                this.headers = headers
            })
            return true

        } catch (e: Exception) {
            println("[Anilife][Proxy] Error: ${e.message}")
            return false
        }
    }

    private fun resolveUrl(baseUri: URI?, baseUrlStr: String, target: String): String {
        if (target.startsWith("http")) return target
        return try { baseUri?.resolve(target).toString() } catch (e: Exception) {
            if (target.startsWith("/")) "${baseUrlStr.substringBefore("/", "https://")}//${baseUrlStr.split("/")[2]}$target"
            else "${baseUrlStr.substringBeforeLast("/")}/$target"
        }
    }

    // [v83.1] TVWiki식 무한 스크립트 융단폭격 후킹
    private fun runWebViewHookAsync(url: String, referer: String, sessionKeys: MutableSet<String>) {
        val handler = Handler(Looper.getMainLooper())
        
        val hookScript = """
            (function() {
                window.G = false;
                if (window.crypto && window.crypto.subtle) {
                    const originalImportKey = window.crypto.subtle.importKey;
                    Object.defineProperty(window.crypto.subtle, 'importKey', {
                        value: function(format, keyData, algorithm, extractable, keyUsages) {
                            if (format === 'raw' && (keyData.byteLength === 16 || keyData.byteLength === 32 || keyData.length === 16 || keyData.length === 32)) {
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
                    if (source && (source.length === 16 || source.length === 32)) {
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
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                    mediaPlaybackRequiresUserGesture = false
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:").removePrefix("[SET]").removePrefix("[CRYPTO]")
                            if (sessionKeys.add(key)) {
                                println("[Anilife][Hook] ★키 발견!★: $key")
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
                        // 리소스 로드 시마다 비동기로 스크립트 강제 주입
                        view?.post { view.evaluateJavascript(hookScript, null) }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(hookScript, null)
                    }
                }
                
                println("[Anilife][Hook] 원본 플레이어 백그라운드 로드 시작")
                webView.loadUrl(url, mapOf("Referer" to referer))
                
                // 20초 후 자동 소멸
                handler.postDelayed({ 
                    try { 
                        println("[Anilife][Hook] 20초 경과, 웹뷰 리소스 정리.")
                        webView.destroy() 
                    } catch (e: Exception) {} 
                }, 20000)
                
            } catch (e: Exception) {
                println("[Anilife][Hook] 웹뷰 가동 실패: ${e.message}")
            }
        }
    }

    class ProxyWebServer(private val sessionKeys: MutableSet<String>) {
        var port: Int = 0
        private var server: ServerSocket? = null
        private var isRunning = false
        @Volatile private var headers: Map<String, String> = emptyMap()
        @Volatile private var playlist: String = ""
        @Volatile private var verifiedKey: ByteArray? = null
        @Volatile private var testSegmentUrl: String? = null
        @Volatile private var currentSeq: Long = 0L
        @Volatile private var currentHexIv: String? = null

        fun start() {
            server = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread { while (isRunning) { try { handle(server!!.accept()) } catch (e: Exception) {} } }
        }

        fun stop() { isRunning = false; server?.close() }
        fun updateSession(h: Map<String, String>) { headers = h }
        fun setPlaylist(p: String) { playlist = p }
        fun setTestSegment(u: String) { if (testSegmentUrl == null) testSegmentUrl = u }
        fun setIvInfo(seq: Long, hexIv: String?) { currentSeq = seq; currentHexIv = hexIv }

        private fun handle(socket: Socket) {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val line = reader.readLine() ?: return
            val path = line.split(" ")[1]
            val out = socket.getOutputStream()

            when {
                path.contains("playlist.m3u8") -> {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray())
                    out.write(playlist.toByteArray())
                }
                path.contains("/key.bin") -> {
                    if (verifiedKey == null) verifiedKey = verifyMultipleKeys()
                    
                    if (verifiedKey != null) {
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        out.write(verifiedKey!!)
                    } else {
                        out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    }
                }
            }
            out.flush(); socket.close()
        }

        private fun getIvList(seq: Long, hexIv: String?): List<ByteArray> {
            val ivs = mutableListOf<ByteArray>()
            if (!hexIv.isNullOrEmpty()) {
                try {
                    val hex = hexIv.removePrefix("0x")
                    val iv = ByteArray(16)
                    val chunked = hex.chunked(2)
                    for (i in 0 until minOf(16, chunked.size)) {
                        iv[i] = chunked[i].toInt(16).toByte()
                    }
                    ivs.add(iv)
                } catch(e:Exception) { ivs.add(ByteArray(16)) }
            } else ivs.add(ByteArray(16))
            
            val seqIv = ByteArray(16)
            for (i in 0..7) seqIv[15 - i] = (seq shr (i * 8)).toByte()
            ivs.add(seqIv)
            ivs.add(ByteArray(16)) 
            return ivs
        }

        private fun verifyMultipleKeys(): ByteArray? = runBlocking {
            val url = testSegmentUrl ?: return@runBlocking null
            println("[Anilife][Verify] 키 검증 시작. 샘플 영상: $url")
            
            try {
                val responseData = app.get(url, headers = headers).body.bytes()
                val checkSize = 1024 
                val safeCheckSize = if (responseData.size < checkSize) responseData.size else checkSize
                val ivs = getIvList(currentSeq, currentHexIv)

                var retries = 0
                while (sessionKeys.isEmpty() && retries < 10) {
                    println("[Anilife][Verify] 웹뷰 키 수집 대기 중... (${retries + 1}/10)")
                    kotlinx.coroutines.delay(1000)
                    retries++
                }

                if (sessionKeys.isEmpty()) {
                    println("[Anilife][Verify] 수집된 키가 없습니다.")
                    return@runBlocking null
                }

                synchronized(sessionKeys) {
                    for ((index, hexKey) in sessionKeys.withIndex()) {
                        try {
                            val rawKey = hexKey.hexToByteArray()
                            val candidates = mutableListOf<ByteArray>()
                            if (rawKey.size == 16) candidates.add(rawKey)
                            else if (rawKey.size == 32) {
                                for (i in 0..16) candidates.add(rawKey.copyOfRange(i, i + 16))
                            }

                            for (keyBytes in candidates) {
                                for (iv in ivs) {
                                    // TVWiki 방식: 오프셋 전수 검증 (0~512 바이트)
                                    for (offset in 0..512) {
                                        if (responseData.size < offset + safeCheckSize) break
                                        val testChunk = responseData.copyOfRange(offset, offset + safeCheckSize)
                                        val decrypted = decryptAES(testChunk, keyBytes, iv)
                                        
                                        if (decrypted.size >= 377 && decrypted[0] == 0x47.toByte() && decrypted[188] == 0x47.toByte() && decrypted[376] == 0x47.toByte()) {
                                            println("[Anilife][Verify] ★ 정답 키 확정! Key: $hexKey (Offset: $offset)")
                                            return@synchronized keyBytes
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {}
                    }
                    println("[Anilife][Verify] 매칭되는 키가 없습니다.")
                    null
                }
            } catch (e: Exception) { 
                println("[Anilife][Verify] 검증 에러: ${e.message}")
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
    val data = ByteArray(length / 2)
    for (i in 0 until length step 2) data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
    return data
}
