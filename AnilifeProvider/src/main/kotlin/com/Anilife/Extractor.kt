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
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
 * Anilife Proxy Extractor v84.0
 * - [Paradigm] TVWiki & MovieKing Hybrid Architecture
 * - [Logic] XHR & Fetch 통신 후킹 코드를 추가하여 모든 키 다운로드 경로 차단
 * - [Bypass] 확실한 키를 발견 시 OkHttp 검증(타임아웃 유발)을 생략하고 강제 등록
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

        val m3u8Content = try {
            app.get(m3u8Url, headers = headers).text
        } catch (e: Exception) {
            println("[Anilife][Proxy] M3U8 원본 다운로드 실패: ${e.message}")
            return false
        }

        val proxy = ProxyWebServer(sessionKeys).apply { 
            start()
            updateSession(headers)
        }
        currentProxyServer = proxy

        println("[Anilife][Proxy] 2. Ultimate XHR 후킹 시작 (playerUrl: $playerUrl)")
        // 키를 찾는 즉시 프록시에 강제 등록(OkHttp 차단 무시)하는 콜백 전달
        runWebViewHookAsync(playerUrl, referer, sessionKeys) { foundKey ->
            proxy.forceSetKey(foundKey)
        }

        try {
            val baseUri = try { URI(m3u8Url) } catch (e: Exception) { null }
            val sb = StringBuilder()
            
            val lines = m3u8Content.lines()
            var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(m3u8Content)?.groupValues?.get(1)?.toLong() ?: 0L
            val ivMatch = Regex("""IV=(0x[0-9a-fA-F]+)""").find(m3u8Content)
            val hexIv = ivMatch?.groupValues?.get(1)
            
            proxy.setIvInfo(currentSeq, hexIv)

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                
                if (trimmed.startsWith("#EXT-X-KEY")) {
                    val match = Regex("""URI="([^"]+)"""").find(trimmed)
                    if (match != null) {
                        val newKeyLine = trimmed.replace(match.groupValues[1], "http://127.0.0.1:${proxy.port}/key.bin")
                        sb.append(newKeyLine).append("\n")
                    } else sb.append(trimmed).append("\n")
                } else if (!trimmed.startsWith("#")) {
                    val absSeg = resolveUrl(baseUri, m3u8Url, trimmed)
                    proxy.setTestSegment(absSeg)
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

    // [v84.0] XHR + Fetch + Crypto 모든 경로 융단폭격 후킹
    private fun runWebViewHookAsync(url: String, referer: String, sessionKeys: MutableSet<String>, onKeyFound: (ByteArray) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        
        val hookScript = """
            (function() {
                if (window.ultimateXhrHook) return;
                window.ultimateXhrHook = true;

                // 1. XMLHttpRequest Hook
                const origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function() {
                    this._reqUrl = arguments[1] ? arguments[1].toString() : '';
                    return origOpen.apply(this, arguments);
                };
                const origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function() {
                    this.addEventListener('load', function() {
                        if (this._reqUrl && (this._reqUrl.includes('enc.bin') || this._reqUrl.includes('key'))) {
                            try {
                                let buf = null;
                                if (this.responseType === 'arraybuffer' && this.response) {
                                    buf = this.response;
                                } else if ((this.responseType === '' || this.responseType === 'text') && this.responseText) {
                                    buf = new TextEncoder().encode(this.responseText).buffer;
                                }
                                if (buf) {
                                    let bytes = new Uint8Array(buf);
                                    let hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
                                    console.log("CapturedKeyHex:[XHR]" + hex);
                                }
                            } catch(e) {}
                        }
                    });
                    return origSend.apply(this, arguments);
                };

                // 2. Fetch Hook
                const origFetch = window.fetch;
                window.fetch = async function(...args) {
                    const res = await origFetch.apply(this, args);
                    try {
                        let reqUrl = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url);
                        if (reqUrl && (reqUrl.includes('enc.bin') || reqUrl.includes('key'))) {
                            const c = res.clone();
                            c.arrayBuffer().then(buf => {
                                let hex = Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, '0')).join('');
                                console.log("CapturedKeyHex:[FETCH]" + hex);
                            });
                        }
                    } catch(e) {}
                    return res;
                };

                // 3. WebCrypto Hook (Fallback)
                if (window.crypto && window.crypto.subtle) {
                    const oI = window.crypto.subtle.importKey;
                    Object.defineProperty(window.crypto.subtle, 'importKey', {
                        value: function(f, k, ...args) {
                            if (f === 'raw' && (k.byteLength === 16 || k.byteLength === 32 || k.length === 16 || k.length === 32)) {
                                try {
                                    let bytes = new Uint8Array(k);
                                    let hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
                                    console.log("CapturedKeyHex:[CRYPTO]" + hex);
                                } catch(e) {}
                            }
                            return oI.apply(this, [f, k, ...args]);
                        },
                        configurable: true, writable: true
                    });
                }
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
                            val keyHex = msg.substringAfter("CapturedKeyHex:").substringAfter("]")
                            println("[Anilife][Hook] ★키 낚아채기 성공!★: $keyHex")
                            
                            try {
                                val rawBytes = keyHex.hexToByteArray()
                                if (rawBytes.size == 16) {
                                    // 완벽한 16바이트면 묻지도 따지지도 않고 프록시에 강제 등록 (타임아웃 방지)
                                    onKeyFound(rawBytes)
                                    println("[Anilife][Hook] 16바이트 정답 키 강제 등록 완료")
                                } else if (rawBytes.size >= 32) {
                                    // 32바이트(ASCII 텍스트로 인코딩된 Hex)인 경우 디코딩 시도
                                    val text = String(rawBytes)
                                    try {
                                        val decoded = text.trim().hexToByteArray()
                                        if (decoded.size == 16) {
                                            onKeyFound(decoded)
                                            println("[Anilife][Hook] 32바이트 텍스트 -> 16바이트 디코딩 정답 키 강제 등록 완료")
                                        }
                                    } catch(e: Exception) {}
                                }
                                sessionKeys.add(keyHex)
                            } catch(e: Exception) {}
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
                        // 페이지의 리소스가 로드될 때마다 비동기로 융단폭격
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

        fun forceSetKey(key: ByteArray) {
            verifiedKey = key
        }

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
            var retries = 0
            while (verifiedKey == null && sessionKeys.isEmpty() && retries < 15) {
                println("[Anilife][Verify] 웹뷰 키 수집 대기 중... (${retries + 1}/15)")
                kotlinx.coroutines.delay(1000)
                retries++
            }

            // 웹뷰 후킹에서 강제 등록된 키가 있으면 OkHttp 검증을 스킵하고 즉시 반환
            if (verifiedKey != null) {
                println("[Anilife][Verify] 강제 등록된 정답 키 반환")
                return@runBlocking verifiedKey
            }

            if (sessionKeys.isEmpty()) {
                println("[Anilife][Verify] 타임아웃: 수집된 키가 없습니다.")
                return@runBlocking null
            }
            
            val url = testSegmentUrl ?: return@runBlocking null
            println("[Anilife][Verify] 수동 키 검증 시작 (OkHttp 차단 위험 있음)")
            try {
                val responseData = app.get(url, headers = headers).body.bytes()
                val checkSize = 1024 
                val safeCheckSize = if (responseData.size < checkSize) responseData.size else checkSize
                val ivs = getIvList(currentSeq, currentHexIv)

                synchronized(sessionKeys) {
                    for (hexKey in sessionKeys) {
                        try {
                            val rawKey = hexKey.hexToByteArray()
                            val candidates = mutableListOf<ByteArray>()
                            if (rawKey.size == 16) candidates.add(rawKey)
                            else if (rawKey.size == 32) {
                                for (i in 0..16) candidates.add(rawKey.copyOfRange(i, i + 16))
                            }

                            for (keyBytes in candidates) {
                                for (iv in ivs) {
                                    for (offset in 0..512) {
                                        if (responseData.size < offset + safeCheckSize) break
                                        val testChunk = responseData.copyOfRange(offset, offset + safeCheckSize)
                                        val decrypted = decryptAES(testChunk, keyBytes, iv)
                                        
                                        if (decrypted.size >= 377 && decrypted[0] == 0x47.toByte() && decrypted[188] == 0x47.toByte() && decrypted[376] == 0x47.toByte()) {
                                            println("[Anilife][Verify] ★ 오프셋 검증 성공! Key: $hexKey")
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
                println("[Anilife][Verify] 검증 에러 (WAF 차단 등): ${e.message}")
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
