// v1.5 - CookieManager Sync, MixedContent ALLOW, Forced ArrayBuffer XHR Hook
package com.anilife

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.concurrent.thread

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
        targetKeyUrl: String? = null,
        videoId: String = "unknown_id",
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        synchronized(this) { currentProxyServer?.stop(); currentProxyServer = null }

        println("[Anilife][Proxy] 1. 프록시 초기화 시작")

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

        // 키가 없으면 프록시 우회 로직 (유지)
        if (!m3u8Content.contains("#EXT-X-KEY") && !m3u8Content.contains("key7")) {
            println("[Anilife][Proxy] M3U8에 키 태그가 없습니다. 프록시 우회 직접 재생 적용.")
            callback(newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                this.referer = "https://anilife.live/"
                this.headers = headers
            })
            return true
        }

        var actualTargetKeyUrl = targetKeyUrl
        val baseUri = try { URI(m3u8Url) } catch (e: Exception) { null }
        
        if (actualTargetKeyUrl.isNullOrBlank()) {
            val keyMatch = Regex("""#EXT-X-KEY:.*URI="([^"]+)"""").find(m3u8Content)
            if (keyMatch != null) {
                actualTargetKeyUrl = resolveUrl(baseUri, m3u8Url, keyMatch.groupValues[1])
                println("[Anilife][Proxy] M3U8 자체 파싱 키 URL: $actualTargetKeyUrl")
            }
        }

        val proxy = ProxyWebServer().apply { start() }
        currentProxyServer = proxy

        // 웹뷰 실행 전 쿠키 및 타겟 세팅
        runWebViewKeyFetcher(playerUrl, referer, ssid, cookies, actualTargetKeyUrl) { foundKey ->
            proxy.forceSetKey(foundKey)
        }

        try {
            val sb = StringBuilder()
            for (line in m3u8Content.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                
                if (trimmed.startsWith("#EXT-X-KEY")) {
                    val match = Regex("""URI="([^"]+)"""").find(trimmed)
                    if (match != null) {
                        // 프록시 URL 구조 비디오 ID 포함 (유지)
                        val newKeyLine = trimmed.replace(match.groupValues[1], "http://127.0.0.1:${proxy.port}/$videoId/key.bin")
                        sb.append(newKeyLine).append("\n")
                    } else sb.append(trimmed).append("\n")
                } else if (!trimmed.startsWith("#")) {
                    val absSeg = resolveUrl(baseUri, m3u8Url, trimmed)
                    sb.append(absSeg).append("\n")
                } else {
                    sb.append(trimmed).append("\n")
                }
            }
            
            proxy.setPlaylist(sb.toString())
            val finalProxyUrl = "http://127.0.0.1:${proxy.port}/$videoId/playlist.m3u8"
            
            println("[Anilife][Proxy] 3. 프록시 생성 완료: $finalProxyUrl")
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

    // [v1.5] 쿠키 인자(cookies) 추가 및 보안 설정 해제 웹뷰 로직
    private fun runWebViewKeyFetcher(url: String, referer: String, ssid: String?, cookies: String, targetKeyUrl: String?, onKeyFound: (String) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val fetchTarget = targetKeyUrl ?: ""
        val ssidHeader = ssid ?: ""

        val hookScript = """
            (function() {
                if (window.ultimateHookDone) return;
                window.ultimateHookDone = true;
                console.log("Ultimate Hook v1.5 Initialized.");

                if ("$fetchTarget" !== "" && "$fetchTarget" !== "null") {
                    console.log("Direct fetching target: $fetchTarget");
                    fetch("$fetchTarget", {
                        headers: { "x-user-ssid": "$ssidHeader" },
                        credentials: "omit"
                    }).then(r => r.arrayBuffer()).then(buf => {
                        let hex = Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, '0')).join('');
                        console.log("CapturedKeyHex:[DIRECT]" + hex);
                    }).catch(e => console.log("Direct Fetch Failed: " + e));
                }

                // XHR 통제 (ArrayBuffer 강제 적용)
                const origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function() {
                    this._reqUrl = arguments[1] ? arguments[1].toString() : '';
                    if (this._reqUrl.includes('key') || this._reqUrl.includes('.bin') || this._reqUrl.includes('st/')) {
                        this.responseType = 'arraybuffer';
                    }
                    return origOpen.apply(this, arguments);
                };
                
                const origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function() {
                    this.addEventListener('load', function() {
                        try {
                            if (this.responseType === 'arraybuffer' || this.response instanceof ArrayBuffer) {
                                let buf = this.response;
                                if (buf && (buf.byteLength === 16 || buf.byteLength === 32)) {
                                    let hex = Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, '0')).join('');
                                    console.log("CapturedKeyHex:[XHR]" + hex);
                                }
                            }
                        } catch(e) {}
                    });
                    return origSend.apply(this, arguments);
                };

                const origFetch = window.fetch;
                window.fetch = async function() {
                    const response = await origFetch.apply(this, arguments);
                    try {
                        const cloned = response.clone();
                        cloned.arrayBuffer().then(buf => {
                            if (buf && (buf.byteLength === 16 || buf.byteLength === 32)) {
                                let hex = Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, '0')).join('');
                                console.log("CapturedKeyHex:[FETCH]" + hex);
                            }
                        }).catch(e => {});
                    } catch(e) {}
                    return response;
                };

                setInterval(function() {
                    try {
                        document.querySelectorAll('video').forEach(v => { v.muted = true; v.play(); });
                        document.querySelectorAll('.jw-video, .jw-button-color, .vjs-big-play-button, .plyr__control').forEach(b => b.click());
                    } catch(e) {}
                }, 500);
            })();
        """.trimIndent()

        handler.post {
            try {
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                
                // [v1.5] WebView 내부 쿠키 강제 동기화 (403 Forbidden 방지)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
                cookieManager.setCookie("https://anilife.live", cookies)
                cookieManager.setCookie("https://api.gcdn.app", cookies)
                cookieManager.flush()

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                    mediaPlaybackRequiresUserGesture = false
                    // [v1.5] 교차 출처 리소스 차단 해제
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        println("[Anilife][JS] $msg")
                        
                        if (msg.startsWith("CapturedKeyHex:")) {
                            val keyHex = msg.substringAfter("CapturedKeyHex:").substringAfter("]")
                            println("[Anilife][Hook] ★ 웹뷰 키 확보 성공 ★: $keyHex")
                            onKeyFound(keyHex)
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        view?.post { view.evaluateJavascript(hookScript, null) }
                        return super.shouldInterceptRequest(view, request)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(hookScript, null)
                    }
                }
                
                println("[Anilife][Hook] 웹뷰 로드 시작: $url")
                webView.loadUrl(url, mapOf("Referer" to referer))
                
                // 타임아웃 5초 유지 정책 적용
                handler.postDelayed({ 
                    try { 
                        println("[Anilife][Hook] 웹뷰 파괴 완료 (5초 타임아웃 적용)")
                        webView.destroy() 
                    } catch (e: Exception) {} 
                }, 5000)
                
            } catch (e: Exception) {}
        }
    }

    class ProxyWebServer() {
        var port: Int = 0
        private var server: ServerSocket? = null
        private var isRunning = false
        @Volatile private var playlist: String = ""
        @Volatile private var verifiedKey: ByteArray? = null

        fun forceSetKey(keyHex: String) {
            try {
                val rawBytes = keyHex.hexToByteArray()
                if (rawBytes.size == 16) {
                    verifiedKey = rawBytes
                    println("[Anilife][Proxy] 16바이트 정답 키 세팅 완료")
                } else if (rawBytes.size >= 32) {
                    val text = String(rawBytes).trim()
                    val decoded = text.hexToByteArray()
                    if (decoded.size == 16) {
                        verifiedKey = decoded
                        println("[Anilife][Proxy] 32바이트 -> 16바이트 디코딩 키 세팅 완료")
                    }
                }
            } catch (e: Exception) {}
        }

        fun start() {
            server = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread { 
                while (isRunning) { 
                    try { 
                        val socket = server!!.accept()
                        thread { handle(socket) }
                    } catch (e: Exception) {} 
                } 
            }
        }

        fun stop() { isRunning = false; server?.close() }
        fun setPlaylist(p: String) { playlist = p }

        private fun handle(socket: Socket) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                val path = line.split(" ")[1]

                while (true) {
                    val headerLine = reader.readLine()
                    if (headerLine.isNullOrEmpty()) break
                }

                val out = socket.getOutputStream()

                when {
                    path.contains("playlist.m3u8") -> {
                        println("[Anilife][Proxy] playlist.m3u8 요청됨: $path")
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray())
                        out.write(playlist.toByteArray())
                    }
                    path.contains("key.bin") || path.contains("key7") -> {
                        println("[Anilife][Proxy] key 요청됨: $path")
                        
                        var retries = 0
                        while (verifiedKey == null && retries < 50) {
                            Thread.sleep(100)
                            retries++
                        }
                        
                        if (verifiedKey != null) {
                            println("[Anilife][Proxy] 키 반환 성공")
                            // [v1.5] CORS 허용 헤더 강화
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\n\r\n".toByteArray())
                            out.write(verifiedKey!!)
                        } else {
                            println("[Anilife][Proxy] 키 반환 실패 (타임아웃)")
                            out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    }
                }
                out.flush()
            } catch (e: Exception) {
                println("[Anilife][Proxy] Handle Error: ${e.message}")
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }
}

fun String.hexToByteArray(): ByteArray {
    val data = ByteArray(length / 2)
    for (i in 0 until length step 2) data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
    return data
}
