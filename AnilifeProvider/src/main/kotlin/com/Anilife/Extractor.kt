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
import android.webkit.CookieManager
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.*
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
 * Anilife Proxy Extractor v56.0
 * - [Source] TVWikiProvider/src/main/kotlin/com/tvwiki/Extractor.kt 기반 구현
 * - [Logic] 웹뷰 메모리 후킹을 통해 16바이트 AES 키를 가로채고 로컬 프록시로 전달
 */
class AnilifeProxyExtractor : ExtractorApi() {
    override val name = "AnilifeProxy"
    override val mainUrl = "https://api.gcdn.app"
    override val requiresReferer = false

    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    companion object {
        @Volatile private var currentProxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Anilife 클래스에서 처리되므로 여기서는 기본 구현만 유지
    }

    suspend fun extractWithProxy(
        m3u8Url: String,
        playerUrl: String,
        ssid: String?,
        cookies: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[Anilife][Proxy] 기존 프록시 서버 종료 중...")
        synchronized(this) {
            currentProxyServer?.stop()
            currentProxyServer = null
        }

        println("[Anilife][Proxy] 1단계: 키 후킹을 위한 세션 저장소 생성")
        val sessionKeys = Collections.synchronizedSet(mutableSetOf<String>())
        
        // 1. 웹뷰를 띄워 메모리 상의 키를 낚아챔 (TVWiki 방식)
        println("[Anilife][Proxy] 2단계: 웹뷰 후킹 시작 ($playerUrl)")
        runWebViewKeyHook(playerUrl, sessionKeys)

        // 2. 로컬 프록시 서버 시작
        val proxy = ProxyWebServer(sessionKeys).apply { 
            start()
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Origin" to "https://anilife.live",
                "Cookie" to cookies,
                "Accept" to "*/*"
            )
            if (!ssid.isNullOrBlank()) headers["x-user-ssid"] = ssid
            updateSession(headers)
        }
        currentProxyServer = proxy

        // 3. M3U8 다운로드 및 리라이팅
        println("[Anilife][Proxy] 3단계: M3U8 리라이팅 시작 ($m3u8Url)")
        try {
            val res = app.get(m3u8Url, headers = proxy.getCurrentHeaders())
            var content = res.text
            val baseUri = URI(m3u8Url)
            
            val sb = StringBuilder()
            content.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                
                when {
                    trimmed.startsWith("#EXT-X-KEY") -> {
                        val match = Regex("""URI="([^"]+)"""").find(trimmed)
                        if (match != null) {
                            val absKey = baseUri.resolve(match.groupValues[1]).toString()
                            val encKey = java.net.URLEncoder.encode(absKey, "UTF-8")
                            // 키 주소를 로컬 프록시로 변경
                            sb.append(trimmed.replace(match.groupValues[1], "http://127.0.0.1:${proxy.port}/key?url=$encKey")).append("\n")
                        } else sb.append(trimmed).append("\n")
                    }
                    !trimmed.startsWith("#") -> {
                        // 세그먼트 주소를 로컬 프록시로 변경
                        val absSeg = baseUri.resolve(trimmed).toString()
                        proxy.setTestSegment(absSeg)
                        val encSeg = java.net.URLEncoder.encode(absSeg, "UTF-8")
                        sb.append("http://127.0.0.1:${proxy.port}/seg?url=$encSeg").append("\n")
                    }
                    else -> sb.append(trimmed).append("\n")
                }
            }
            
            proxy.setPlaylist(sb.toString())
            val proxyUrl = "http://127.0.0.1:${proxy.port}/playlist.m3u8"
            
            println("[Anilife][Proxy] 4단계: 최종 프록시 링크 반환: $proxyUrl")
            callback(newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                this.referer = "" // no-referrer 정책
                this.headers = proxy.getCurrentHeaders()
            })
            return true

        } catch (e: Exception) {
            println("[Anilife][Proxy] 에러: ${e.message}")
            return false
        }
    }

    private suspend fun runWebViewKeyHook(url: String, sessionKeys: MutableSet<String>) = suspendCancellableCoroutine<Unit> { cont ->
        val handler = Handler(Looper.getMainLooper())
        // TVWiki에서 검증된 메모리 후킹 스크립트
        val hookScript = """
            (function() {
                if (window.crypto && window.crypto.subtle) {
                    const originalImportKey = window.crypto.subtle.importKey;
                    window.crypto.subtle.importKey = function(format, keyData, ...) {
                        if (format === 'raw' && (keyData.byteLength === 16 || keyData.length === 16)) {
                            let hex = Array.from(new Uint8Array(keyData)).map(b => b.toString(16).padStart(2, '0')).join('');
                            console.log("CapturedKeyHex:" + hex);
                        }
                        return originalImportKey.apply(this, arguments);
                    };
                }
                const originalSet = Uint8Array.prototype.set;
                Uint8Array.prototype.set = function(source, offset) {
                    if (source && source.length === 16) {
                        let hex = Array.from(source).map(b => b.toString(16).padStart(2, '0')).join('');
                        console.log("CapturedKeyHex:" + hex);
                    }
                    return originalSet.apply(this, arguments);
                };
            })();
        """.trimIndent()

        handler.post {
            try {
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = DESKTOP_UA

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                        val msg = cm?.message() ?: ""
                        if (msg.contains("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:")
                            if (sessionKeys.add(key)) println("[Anilife][Hook] 키 발견: $key")
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView?, u: String?) {
                        v?.evaluateJavascript(hookScript, null)
                        // 키가 충분히 수집될 수 있도록 5초 후 코루틴 종료
                        handler.postDelayed({ if (cont.isActive) { webView.destroy(); cont.resume(Unit) } }, 5000)
                    }
                }
                webView.loadUrl(url)
            } catch (e: Exception) { if (cont.isActive) cont.resume(Unit) }
        }
    }

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
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                    out.write(verifiedKey ?: ByteArray(16))
                }
                path.contains("/seg") -> {
                    val url = URLDecoder.decode(path.substringAfter("url="), "UTF-8")
                    runBlocking {
                        val res = app.get(url, headers = headers)
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                        out.write(res.body.bytes())
                    }
                }
            }
            out.flush(); socket.close()
        }

        private fun verify(): ByteArray? = runBlocking {
            val url = testSegment ?: return@runBlocking null
            println("[Anilife][Verify] 수집된 키 ${sessionKeys.size}개 검증 시작...")
            val data = app.get(url, headers = headers).body.bytes()
            
            sessionKeys.forEach { hex ->
                val key = hex.hexToByteArray()
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
                val dec = cipher.doFinal(data.take(1024).toByteArray())
                if (dec.size > 188 && dec[0] == 0x47.toByte() && dec[188] == 0x47.toByte()) {
                    println("[Anilife][Verify] 성공! 올바른 키 확정: $hex")
                    return@runBlocking key
                }
            }
            null
        }
    }
}

fun String.hexToByteArray(): ByteArray {
    val data = ByteArray(length / 2)
    for (i in 0 until length step 2) data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
    return data
}
