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
 * v2.1: Debugging & Fixes
 * - 모든 프로세스 상세 로그(println) 추가
 * - WebView 자동 재생 설정 (mediaPlaybackRequiresUserGesture = false)
 * - WebView 내부 콘솔 및 네트워크 트래픽 로깅
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
        println("[MovieKing] Referer: $referer")

        // 1. 기존 프록시 정리
        synchronized(this) {
            if (currentProxyServer != null) {
                println("[MovieKing] [INIT] 기존 프록시 서버 종료 시도.")
                currentProxyServer?.stop()
                currentProxyServer = null
            }
        }

        // 2. 세션 키 저장소 생성
        val currentSessionKeys = Collections.synchronizedSet(mutableSetOf<String>())
        
        // 3. WebView 실행 및 키 캡처
        // MovieKing 플레이어 URL이 맞는지 확인 필요. 만약 ID만 있다면 변환 로직이 필요할 수 있음.
        val webViewUrl = url 
        println("[MovieKing] [STEP 2] WebView Hook 실행 요청. URL: $webViewUrl")
        
        runWebViewHook(webViewUrl, referer ?: "https://mvking6.org/", currentSessionKeys)
        
        println("[MovieKing] [STEP 3] WebView Hook 완료. 수집된 키 개수: ${currentSessionKeys.size}")
        if (currentSessionKeys.isEmpty()) {
            println("[MovieKing] [WARN] 키가 수집되지 않았습니다. 재생 실패 가능성이 높습니다.")
        } else {
            println("[MovieKing] [INFO] 키 수집 성공: $currentSessionKeys")
        }

        try {
            val cookie = CookieManager.getInstance().getCookie(webViewUrl)
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red"
            )
            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
                println("[MovieKing] [INFO] 쿠키 발견: $cookie")
            }

            // M3U8 URL 추출
            println("[MovieKing] [STEP 4] HTML 파싱하여 M3U8 주소 찾기 시도.")
            val response = app.get(webViewUrl, headers = headers)
            val playerHtml = response.text
            
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") 
                ?: Regex("""file:\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)

            if (m3u8Url == null) {
                println("[MovieKing] [ERROR] M3U8 URL 파싱 실패. HTML 내용 일부: ${playerHtml.take(200)}")
                return
            }

            println("[MovieKing] [FOUND] M3U8 URL: $m3u8Url")
            var playlistContent = app.get(m3u8Url, headers = headers).text
            println("[MovieKing] [INFO] Playlist 원본 다운로드 완료. 길이: ${playlistContent.length}")

            // 5. 프록시 서버 시작
            println("[MovieKing] [STEP 5] 로컬 프록시 서버 시작.")
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
                println("[MovieKing] [INFO] IV 없음. 0으로 초기화.")
                newProxy.setIv(ByteArray(16))
            }

            // 6. Playlist Rewrite
            println("[MovieKing] [STEP 6] Playlist 주소 변조(Rewrite) 시작.")
            val baseUri = try { URI(m3u8Url) } catch (e: Exception) { null }
            val sb = StringBuilder()

            playlistContent.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXT-X-KEY")) {
                    val originalKeyUri = Regex("""URI="([^"]+)"""").find(trimmed)?.groupValues?.get(1)
                    if (originalKeyUri != null) {
                        val absKey = resolveUrl(baseUri, m3u8Url, originalKeyUri)
                        val encKey = java.net.URLEncoder.encode(absKey, "UTF-8")
                        val newLine = trimmed.replace(originalKeyUri, "http://127.0.0.1:${newProxy.port}/key?url=$encKey")
                        sb.append(newLine).append("\n")
                        println("[MovieKing] [REWRITE] Key URI 변경: $originalKeyUri -> 로컬 프록시")
                    } else {
                        sb.append(trimmed).append("\n")
                    }
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val absSeg = resolveUrl(baseUri, m3u8Url, trimmed)
                    newProxy.setTestSegment(absSeg) // 첫 번째 세그먼트 등록 (키 검증용)
                    
                    val encSeg = java.net.URLEncoder.encode(absSeg, "UTF-8")
                    sb.append("http://127.0.0.1:${newProxy.port}/seg?url=$encSeg").append("\n")
                } else {
                    sb.append(trimmed).append("\n")
                }
            }
            
            newProxy.setPlaylist(sb.toString())
            println("[MovieKing] [STEP 7] 최종 Playlist 생성 완료.")
            
            val finalUrl = "http://127.0.0.1:${newProxy.port}/video.m3u8"
            println("[MovieKing] [DONE] Callback 호출: $finalUrl")
            
            callback(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                this.referer = "https://player-v1.bcbc.red/"
            })

        } catch (e: Exception) {
            println("[MovieKing] [FATAL] Extract 중 예외 발생: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun runWebViewHook(url: String, referer: String, sessionKeys: MutableSet<String>) = suspendCancellableCoroutine<Unit> { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        val hookScript = """
            (function() {
                console.log("[JS] Hook Script Injected");
                if (window.crypto && window.crypto.subtle) {
                    const originalImportKey = window.crypto.subtle.importKey;
                    Object.defineProperty(window.crypto.subtle, 'importKey', {
                        value: function(format, keyData, algorithm, extractable, keyUsages) {
                            console.log("[JS] importKey called. format=" + format);
                            if (format === 'raw' && (keyData.byteLength === 16 || keyData.length === 16)) {
                                try {
                                    let bytes = new Uint8Array(keyData);
                                    let hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
                                    console.log("CapturedKeyHex:[CRYPTO]" + hex);
                                } catch(e) {
                                    console.log("[JS] Error converting key: " + e);
                                }
                            }
                            return originalImportKey.apply(this, arguments);
                        },
                        configurable: true,
                        writable: true
                    });
                } else {
                    console.log("[JS] window.crypto.subtle not found");
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
                println("[MovieKing] [WEBVIEW] 초기화 시작")
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false // [중요] 자동 재생 허용
                    userAgentString = DESKTOP_UA
                }

                // 20초 타임아웃
                val timeout = Runnable {
                    if (cont.isActive) {
                        println("[MovieKing] [WEBVIEW] 타임아웃 발생 (20초). 키를 찾지 못했으나 진행합니다.")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(Unit)
                    }
                }
                handler.postDelayed(timeout, 20000)

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        // 모든 콘솔 로그 출력 (디버깅용)
                        // println("[MovieKing] [WEBVIEW-CONSOLE] $msg")
                        
                        if (msg.startsWith("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:").removePrefix("[SET]").removePrefix("[CRYPTO]")
                            if (sessionKeys.add(key)) {
                                println("[MovieKing] [WEBVIEW] ★ KEY CAPTURED ★: $key")
                            }
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        println("[MovieKing] [WEBVIEW] 페이지 로드 시작: $url")
                        view?.evaluateJavascript(hookScript, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        println("[MovieKing] [WEBVIEW] 페이지 로드 완료: $url")
                        view?.evaluateJavascript(hookScript, null)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        // println("[MovieKing] [WEBVIEW-REQ] ${request?.url}")
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                println("[MovieKing] [WEBVIEW] loadUrl 호출: $url")
                webView.loadUrl(url, mapOf("Referer" to referer))

            } catch (e: Exception) {
                println("[MovieKing] [WEBVIEW] 생성 중 에러: ${e.message}")
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
                    println("[MovieKing] [PROXY] 쓰레드 시작")
                    while (isRunning) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} }
                }
            } catch (e: Exception) {
                println("[MovieKing] [PROXY] 시작 실패: ${e.message}")
            }
        }

        fun stop() { 
            println("[MovieKing] [PROXY] 서버 종료")
            isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} 
        }
        fun updateSession(h: Map<String, String>) { currentHeaders = h }
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun setIv(iv: ByteArray) { currentIv = iv }
        fun setTestSegment(url: String) { 
            if (testSegmentUrl == null) {
                testSegmentUrl = url 
                println("[MovieKing] [PROXY] 검증용 세그먼트 등록: $url")
            }
        }

        private fun handleClient(socket: Socket) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                val parts = line.split(" ")
                if (parts.size < 2) return
                val path = parts[1]
                val output = socket.getOutputStream()

                // println("[MovieKing] [PROXY-REQ] $path")

                when {
                    path.contains(".m3u8") -> {
                        println("[MovieKing] [PROXY] Playlist 요청 받음")
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray())
                        output.write(currentPlaylist.toByteArray())
                    }
                    path.contains("/key") -> {
                        println("[MovieKing] [PROXY] Key 요청 받음")
                        if (verifiedKey == null) {
                            println("[MovieKing] [PROXY] Key 검증 시도...")
                            verifiedKey = verifyMultipleKeys()
                        }
                        
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                        if (verifiedKey != null) {
                            println("[MovieKing] [PROXY] 검증된 Key 반환")
                            output.write(verifiedKey!!)
                        } else {
                            println("[MovieKing] [PROXY-WARN] Key 검증 실패! 빈(Dummy) Key 반환 -> 재생 에러 예상됨")
                            output.write(ByteArray(16)) // 가짜 키 (0x00...)
                        }
                    }
                    path.contains("/seg") -> {
                        val targetUrl = URLDecoder.decode(path.substringAfter("url="), "UTF-8")
                        // println("[MovieKing] [PROXY] Seg 요청: $targetUrl")
                        try {
                            val conn = URL(targetUrl).openConnection() as HttpURLConnection
                            currentHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                            if (conn.responseCode == 200) {
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                                conn.inputStream.use { it.copyTo(output) }
                            } else {
                                println("[MovieKing] [PROXY-ERR] Seg 다운로드 실패: ${conn.responseCode}")
                            }
                        } catch (e: Exception) {
                            println("[MovieKing] [PROXY-ERR] Seg 스트리밍 중 에러: ${e.message}")
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2:Exception){} }
        }

        private fun verifyMultipleKeys(): ByteArray? = runBlocking {
            val url = testSegmentUrl ?: return@runBlocking null
            val targetIv = currentIv ?: ByteArray(16)
            
            println("[MovieKing] [VERIFY] Key 검증 시작. 후보 키 개수: ${sessionKeys.size}")
            if (sessionKeys.isEmpty()) {
                println("[MovieKing] [VERIFY-FAIL] 후보 키가 없습니다.")
                return@runBlocking null
            }

            try {
                // 검증용 세그먼트 일부 다운로드
                val responseData = app.get(url, headers = currentHeaders).body.bytes()
                val checkSize = 1024.coerceAtMost(responseData.size)
                println("[MovieKing] [VERIFY] 테스트 데이터 다운로드 완료 (${responseData.size} bytes)")

                synchronized(sessionKeys) {
                    for (hexKey in sessionKeys) {
                        try {
                            val keyBytes = hexKey.hexToByteArray()
                            // 복호화 시도
                            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(targetIv))
                            val decrypted = cipher.doFinal(responseData.copyOfRange(0, checkSize))
                            
                            // TS Sync Byte (0x47) 확인
                            if (decrypted.isNotEmpty() && decrypted[0] == 0x47.toByte()) {
                                println("[MovieKing] [VERIFY-SUCCESS] ★ 올바른 Key 찾음: $hexKey")
                                return@synchronized keyBytes
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            } catch (e: Exception) {
                println("[MovieKing] [VERIFY-ERR] 검증 중 에러: ${e.message}")
            }
            println("[MovieKing] [VERIFY-FAIL] 올바른 키를 찾지 못했습니다.")
            return@runBlocking null
        }
    }
}

// 확장 함수
fun String.hexToByteArray(): ByteArray {
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
