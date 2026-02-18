package com.anilife

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume

/**
 * Anilife Proxy Extractor v82.0
 * - [Paradigm] Clean Room Player: 앱(Kotlin) 요청 전면 배제. 웹뷰가 직접 키를 다운로드하도록 유도.
 * - [Logic] 가상 HTML 플레이어를 생성하고 BaseURL을 위조하여 로드 -> HLS.js가 키를 요청 -> XHR 후킹으로 탈취
 * - [Bypass] 브라우저의 TLS 지문과 쿠키 세션을 100% 활용하여 403/404 차단 완벽 우회
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
        directKeyUrl: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        synchronized(this) { currentProxyServer?.stop(); currentProxyServer = null }

        println("[Anilife][Proxy] 1. 프록시 서버 초기화")
        val sessionKeys = Collections.synchronizedSet(mutableSetOf<String>())

        // M3U8 원본 다운로드 (플레이어 재생용)
        val m3u8Content = try {
            val headers = mutableMapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://anilife.live/", "Cookie" to cookies)
            if (!ssid.isNullOrBlank()) headers["x-user-ssid"] = ssid
            app.get(m3u8Url, headers = headers).text
        } catch (e: Exception) {
            println("[Anilife][Proxy] M3U8 다운로드 실패: ${e.message}")
            return false
        }

        println("[Anilife][Proxy] 2. 클린 룸 플레이어(Clean Room) 가동... 웹뷰가 직접 키를 가져옵니다.")
        runCleanRoomPlayer(m3u8Content, referer, sessionKeys)

        val proxy = ProxyWebServer(sessionKeys).apply { 
            start()
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Origin" to "https://anilife.live",
                "Cookie" to cookies,
                "Accept" to "*/*"
            )
            if (!ssid.isNullOrBlank()) {
                headers["x-user-ssid"] = ssid
                headers["X-User-Ssid"] = ssid
            }
            updateSession(headers)
        }
        currentProxyServer = proxy

        try {
            val baseUri = URI(m3u8Url)
            val sb = StringBuilder()
            
            m3u8Content.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                
                when {
                    trimmed.startsWith("#EXT-X-KEY") -> {
                        val match = Regex("""URI="([^"]+)"""").find(trimmed)
                        if (match != null) {
                            var uri = match.groupValues[1]
                            if (!uri.startsWith("http")) uri = baseUri.resolve(uri).toString()
                            val encKey = java.net.URLEncoder.encode(uri, "UTF-8")
                            sb.append(trimmed.replace(match.groupValues[1], "http://127.0.0.1:${proxy.port}/key?url=$encKey")).append("\n")
                        } else sb.append(trimmed).append("\n")
                    }
                    !trimmed.startsWith("#") -> {
                        val absSeg = baseUri.resolve(trimmed).toString()
                        proxy.setTestSegment(absSeg)
                        val encSeg = java.net.URLEncoder.encode(absSeg, "UTF-8")
                        sb.append("http://127.0.0.1:${proxy.port}/seg?url=$encSeg").append("\n")
                    }
                    else -> sb.append(trimmed).append("\n")
                }
            }
            
            proxy.setPlaylist(sb.toString())
            val finalProxyUrl = "http://127.0.0.1:${proxy.port}/playlist.m3u8"
            
            println("[Anilife][Proxy] 3. 프록시 링크 반환: $finalProxyUrl")
            callback(newExtractorLink(name, name, finalProxyUrl, ExtractorLinkType.M3U8) {
                this.referer = ""
                this.headers = proxy.getCurrentHeaders()
            })
            return true

        } catch (e: Exception) {
            println("[Anilife][Proxy] Error: ${e.message}")
            return false
        }
    }

    // [v82.0] 가상 플레이어 실행 및 키 탈취
    private suspend fun runCleanRoomPlayer(
        m3u8Content: String,
        referer: String,
        sessionKeys: MutableSet<String>
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        // M3U8 내용을 Base64로 인코딩해서 JS에 넘김
        val m3u8Base64 = Base64.encodeToString(m3u8Content.toByteArray(), Base64.NO_WRAP)

        // 가상 HTML: HLS.js를 로드하고 XHR 요청을 후킹함
        val cleanRoomHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <script src="https://cdn.jsdelivr.net/npm/hls.js@1.4.0/dist/hls.min.js"></script>
            </head>
            <body>
                <video id="video" muted autoplay></video>
                <script>
                    // 1. XHR Hooking (가장 중요)
                    (function() {
                        var originalOpen = XMLHttpRequest.prototype.open;
                        XMLHttpRequest.prototype.open = function(method, url) {
                            this.addEventListener('load', function() {
                                // 키 파일 요청 감지
                                if (url.includes('enc.bin') || url.includes('key')) {
                                    if (this.response) {
                                        var bytes = new Uint8Array(this.response);
                                        var hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
                                        console.log("CapturedKeyHex:" + hex);
                                    }
                                }
                            });
                            originalOpen.apply(this, arguments);
                        };
                    })();

                    // 2. Play HLS
                    var m3u8 = atob("$m3u8Base64");
                    if (Hls.isSupported()) {
                        var hls = new Hls({
                            xhrSetup: function(xhr, url) {
                                // 바이너리 데이터를 받기 위해 responseType 설정
                                if (url.includes('enc.bin') || url.includes('key')) {
                                    xhr.responseType = 'arraybuffer';
                                }
                            }
                        });
                        var blob = new Blob([m3u8], {type: 'application/x-mpegURL'});
                        var blobUrl = URL.createObjectURL(blob);
                        hls.loadSource(blobUrl);
                        hls.attachMedia(document.getElementById('video'));
                        hls.on(Hls.Events.ERROR, function(event, data) {
                            console.log("[JS] HLS Error: " + data.details);
                        });
                    }
                </script>
            </body>
            </html>
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
                    // 혼합 콘텐츠 허용 (혹시 모를 HTTP/HTTPS 이슈 방지)
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                        val msg = cm?.message() ?: ""
                        if (msg.contains("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:").trim()
                            if (sessionKeys.add(key)) println("[Anilife][CleanRoom] ★키 확보 성공★: $key")
                        } else if (msg.contains("[JS]")) {
                            println("[Anilife][CleanRoom] $msg")
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // 15초 동안 키 수집 대기
                        handler.postDelayed({ if (cont.isActive) { webView.destroy(); cont.resume(Unit) } }, 15000)
                    }
                }
                
                println("[Anilife][CleanRoom] 가상 플레이어 로드 (BaseURL 위장)")
                // BaseURL을 설정하여 CORS 및 쿠키 문제를 우회
                webView.loadDataWithBaseURL("https://anilife.live", cleanRoomHtml, "text/html", "UTF-8", null)
                
            } catch (e: Exception) { 
                println("[Anilife][CleanRoom] 웹뷰 생성 에러: ${e.message}")
                if (cont.isActive) cont.resume(Unit) 
            }
        }
    }

    // ProxyWebServer (기존과 동일, 검증 로직 유지)
    class ProxyWebServer(private val sessionKeys: MutableSet<String>) {
        var port: Int = 0
        private var server: ServerSocket? = null
        private var isRunning = false
        @Volatile private var headers: Map<String, String> = emptyMap()
        @Volatile private var playlist: String = ""
        @Volatile private var verifiedKey: ByteArray? = null
        @Volatile private var testSegment: String? = null

        fun start() {
            server = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread { while (isRunning) { try { handle(server!!.accept()) } catch (e: Exception) {} } }
        }

        fun stop() { isRunning = false; server?.close() }
        fun updateSession(h: Map<String, String>) { headers = h }
        fun setPlaylist(p: String) { playlist = p }
        fun setTestSegment(u: String) { if (testSegment == null) testSegment = u }
        fun getCurrentHeaders() = headers

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
                path.contains("/key") -> {
                    if (verifiedKey == null) verifiedKey = verify()
                    
                    if (verifiedKey != null) {
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        out.write(verifiedKey!!)
                    } else {
                        out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    }
                }
                path.contains("/seg") -> {
                    val url = URLDecoder.decode(path.substringAfter("url="), "UTF-8")
                    runBlocking {
                        try {
                            val res = app.get(url, headers = headers)
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            out.write(res.body.bytes())
                        } catch (e: Exception) { }
                    }
                }
            }
            out.flush(); socket.close()
        }

        private fun verify(): ByteArray? = runBlocking {
            val url = testSegment ?: return@runBlocking null
            println("[Anilife][Verify] 키 검증 진입 (후보: ${sessionKeys.size}개)")
            
            if (sessionKeys.isEmpty()) {
                println("[Anilife][Verify] 실패: 수집된 키 없음.")
                return@runBlocking null
            }

            try {
                val data = app.get(url, headers = headers).body.bytes()
                val chunk = data.take(1024).toByteArray()

                sessionKeys.forEach { hex ->
                    try {
                        val key = hex.hexToByteArray()
                        val candidates = mutableListOf<ByteArray>()
                        if (key.size == 16) candidates.add(key)
                        else if (key.size == 32) {
                            for (i in 0..16) candidates.add(key.copyOfRange(i, i + 16))
                        }

                        for (k in candidates) {
                            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(ByteArray(16)))
                            val dec = cipher.doFinal(chunk)
                            if (dec.size > 188 && dec[0] == 0x47.toByte() && dec[188] == 0x47.toByte()) {
                                println("[Anilife][Verify] 검증 성공: ${k.joinToString("") { "%02x".format(it) }}")
                                return@runBlocking k
                            }
                        }
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) { println("[Anilife][Verify] 에러: ${e.message}") }
            null
        }
    }
}

fun String.hexToByteArray(): ByteArray {
    val data = ByteArray(length / 2)
    for (i in 0 until length step 2) data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
    return data
}
