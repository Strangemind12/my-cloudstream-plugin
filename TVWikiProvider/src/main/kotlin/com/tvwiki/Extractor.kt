package com.tvwiki

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
 * Version: v1.5 (Ported from TVMON v23.10)
 * Modification:
 * 1. [LOG] 모든 프로세스(진입, 분기, 루프, 예외, HTTP 요청/응답)에 상세 디버그 로그 추가.
 * 2. [FIX] verifiedKey, currentIv, capturedKeys 등을 ProxyWebServer 인스턴스 변수로 격리하여 세션 혼선 방지.
 * 3. [FIX] extract() 진입 시 기존 ProxyWebServer를 명시적으로 종료 및 null 처리.
 * 4. [PORT] TVWiki 도메인 및 패키지 적용.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    // [중요] Companion Object는 현재 실행 중인 프록시 서버 인스턴스 하나만 관리
    companion object {
        @Volatile private var currentProxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[TVWiki][v1.5] getUrl 호출됨. URL: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        // 1. 기존 프록시 정리
        synchronized(this) {
            if (currentProxyServer != null) {
                println("[TVWiki] [INIT] 기존 실행 중인 프록시 서버 종료 시도.")
                currentProxyServer?.stop()
                currentProxyServer = null
                println("[TVWiki] [INIT] 기존 프록시 서버 종료 완료.")
            } else {
                println("[TVWiki] [INIT] 기존 실행 중인 프록시 없음.")
            }
        }

        println("[TVWiki] [STEP 1] extract() 로직 시작.")
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvwiki5.net/"
        println("[TVWiki] [INFO] CleanUrl: $cleanUrl, Referer: $cleanReferer")
        
        // 2. Iframe / Embed URL 파싱 (직접 링크가 아닌 경우)
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/") && !cleanUrl.contains("v/d/")) {
            println("[TVWiki] [CHECK] 직접 비디오 링크가 아님. Referer 페이지($cleanReferer)에서 bunny-frame 링크 탐색 시도.")
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                println("[TVWiki] [HTTP] Referer 페이지 응답 코드: ${refRes.code}")
                
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVWiki] [FOUND] Iframe 링크 발견: $cleanUrl")
                } else {
                    println("[TVWiki] [WARN] Iframe 링크 발견 실패. 원본 URL 사용.")
                }
            } catch (e: Exception) { 
                println("[TVWiki] [ERROR] Iframe 파싱 중 예외 발생: ${e.message}") 
                e.printStackTrace()
            }
        }

        var capturedUrl: String? = cleanUrl
        
        // [중요] 현재 세션 전용 키 저장소 생성 (전역 변수 아님)
        val currentSessionKeys = Collections.synchronizedSet(mutableSetOf<String>())
        println("[TVWiki] [DATA] 새로운 키 세션 저장소(Set) 생성 완료.")

        // 3. WebView 실행 (c.html이 없는 경우)
        if (!cleanUrl.contains("/c.html")) {
            println("[TVWiki] [STEP 2] WebView 훅 실행 필요. runWebViewHook 호출.")
            val webViewResult = runWebViewHook(cleanUrl, cleanReferer, currentSessionKeys)
            if (webViewResult != null) {
                capturedUrl = webViewResult
                println("[TVWiki] [RESULT] WebView 훅 성공. 캡처된 URL: $capturedUrl")
            } else {
                println("[TVWiki] [FAIL] WebView 훅 실패 또는 타임아웃. 기존 URL 유지.")
            }
        } else {
            println("[TVWiki] [SKIP] 이미 c.html 링크임. WebView 훅 건너뜀.")
        }

        if (capturedUrl != null) {
            val cookie = CookieManager.getInstance().getCookie(capturedUrl)
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online"
            )
            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
                println("[TVWiki] [INFO] 쿠키 발견 및 헤더 적용.")
            }

            try {
                println("[TVWiki] [STEP 3] M3U8 플레이리스트 분석 시작.")
                var requestUrl = capturedUrl!!.substringBefore("#")
                println("[TVWiki] [HTTP] M3U8 요청: $requestUrl")
                
                var response = app.get(requestUrl, headers = headers)
                println("[TVWiki] [HTTP] M3U8 응답 코드: ${response.code}")
                var content = response.text.trim()

                // Master Playlist 처리
                if (!content.startsWith("#EXTM3U")) {
                    println("[TVWiki] [CHECK] 내용이 #EXTM3U로 시작하지 않음. 내부 M3U8 탐색.")
                    Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(content)?.let {
                        requestUrl = it.groupValues[1]
                        println("[TVWiki] [FOUND] 내부 M3U8 발견: $requestUrl")
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                // Stream Inf 처리 (해상도 선택 등)
                if (content.contains("#EXT-X-STREAM-INF")) {
                    println("[TVWiki] [CHECK] 멀티 비트레이트 파일 감지. 마지막 스트림 선택.")
                    val subUrlLine = content.lines().lastOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if (subUrlLine != null) {
                        val originalUri = try { URI(requestUrl) } catch (e: Exception) { null }
                        requestUrl = resolveUrl(originalUri, requestUrl, subUrlLine)
                        println("[TVWiki] [RESOLVE] 서브 플레이리스트 URL: $requestUrl")
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                val isKey7 = content.lines().any { it.startsWith("#EXT-X-KEY") && it.contains("/v/key7") }
                println("[TVWiki] [CHECK] 암호화 여부(Key7): $isKey7")

                if (isKey7) {
                    println("[TVWiki] [STEP 4] 암호화 감지됨. 로컬 프록시 서버 구성 시작.")
                    
                    // [중요] 새 프록시 인스턴스 생성 및 키 저장소 전달
                    val newProxy = ProxyWebServer(currentSessionKeys).apply { 
                        start()
                        updateSession(headers) 
                    }
                    currentProxyServer = newProxy // Companion Object에 참조 저장
                    println("[TVWiki] [PROXY] 프록시 서버 시작됨. 포트: ${newProxy.port}")

                    val videoId = Regex("""/v/[ef]/([^/]+)""").find(capturedUrl!!)?.groupValues?.get(1) ?: "video"
                    
                    val ivMatch = Regex("""IV=("?)(0x[0-9a-fA-F]+)\1""").find(content)
                    val ivHex = ivMatch?.groupValues?.get(2) ?: "0x00000000000000000000000000000000"
                    val parsedIv = ivHex.removePrefix("0x").hexToByteArray()
                    println("[TVWiki] [DATA] IV 추출 완료: $ivHex")
                    
                    newProxy.setIv(parsedIv)

                    val baseUri = try { URI(requestUrl) } catch (e: Exception) { null }
                    val sb = StringBuilder()
                    
                    println("[TVWiki] [LOOP] M3U8 라인 리라이팅 시작.")
                    content.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEach
                        if (trimmed.startsWith("#")) {
                            if (trimmed.startsWith("#EXT-X-KEY") && trimmed.contains("/v/key7")) {
                                val match = Regex("""URI="([^"]+)"""").find(trimmed)
                                if (match != null) {
                                    val absKey = resolveUrl(baseUri, requestUrl, match.groupValues[1])
                                    val encKey = java.net.URLEncoder.encode(absKey, "UTF-8")
                                    // 로컬 프록시의 /key 엔드포인트로 변경
                                    val newKeyLine = trimmed.replace(match.groupValues[1], "http://127.0.0.1:${newProxy.port}/key?url=$encKey")
                                    sb.append(newKeyLine).append("\n")
                                    // println("[TVWiki] [REWRITE] Key URI 변경 완료.") 
                                } else sb.append(trimmed).append("\n")
                            } else sb.append(trimmed).append("\n")
                        } else {
                            // 세그먼트 URL
                            val absSeg = resolveUrl(baseUri, requestUrl, trimmed)
                            newProxy.setTestSegment(absSeg) // 검증용 샘플로 등록
                            
                            val encSeg = java.net.URLEncoder.encode(absSeg, "UTF-8")
                            sb.append("http://127.0.0.1:${newProxy.port}/seg?url=$encSeg").append("\n")
                        }
                    }
                    println("[TVWiki] [LOOP] M3U8 리라이팅 완료.")
                    
                    newProxy.setPlaylist(sb.toString())
                    
                    val finalUrl = "http://127.0.0.1:${newProxy.port}/$videoId/playlist.m3u8"
                    println("[TVWiki] [DONE] 최종 URL 반환: $finalUrl")
                    
                    callback(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                } else {
                    println("[TVWiki] [DONE] 암호화되지 않은 영상. 직접 링크 반환.")
                    callback(newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                }
            } catch (e: Exception) { 
                println("[TVWiki] [ERROR] M3U8 분석/처리 중 치명적 오류: ${e.message}")
                e.printStackTrace()
            }
        }
        println("[TVWiki] [EXIT] extract() 종료. 처리 실패.")
        return false
    }

    // [중요] sessionKeys를 인자로 받아 해당 세션에만 키를 저장
    private suspend fun runWebViewHook(url: String, referer: String, sessionKeys: MutableSet<String>) = suspendCancellableCoroutine<String?> { cont ->
        println("[TVWiki] [WEBVIEW] runWebViewHook 시작. URL: $url")
        val handler = Handler(Looper.getMainLooper())
        
        val hookScript = """
            (function() {
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
                var detectedCUrl: String? = null
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                }

                val discoveryTimeout = Runnable {
                    if (cont.isActive) {
                        println("[TVWiki] [WEBVIEW] 타임아웃 발생 (15초). c.html 발견 못함.")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(discoveryTimeout, 15000)

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:").removePrefix("[SET]").removePrefix("[CRYPTO]")
                            if (sessionKeys.add(key)) { 
                                println("[TVWiki] [WEBVIEW] 키 캡처 성공! Key: $key (Total: ${sessionKeys.size})")
                            }
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        // println("[TVWiki] [WEBVIEW] 페이지 로드 시작: $url")
                        view?.evaluateJavascript(hookScript, null)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        // println("[TVWiki] [WEBVIEW] 리소스 요청: $reqUrl")
                        
                        if (reqUrl.contains("/c.html") && reqUrl.contains("token=")) {
                            println("[TVWiki] [WEBVIEW] c.html 패턴 발견! URL: $reqUrl")
                            detectedCUrl = reqUrl
                            
                            handler.removeCallbacks(discoveryTimeout)
                            println("[TVWiki] [WEBVIEW] 타임아웃 타이머 해제됨.")
                            
                            view?.post { view.evaluateJavascript(hookScript, null) }
                            
                            thread {
                                try {
                                    runBlocking {
                                        println("[TVWiki] [CHECK] 발견된 c.html 내용을 검증합니다.")
                                        val checkRes = app.get(reqUrl, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://player.bunny-frame.online/"))
                                        
                                        if (!checkRes.text.contains("/v/key7")) {
                                            println("[TVWiki] [CHECK] 일반 영상(No Key7) 확인. WebView 즉시 종료.")
                                            handler.post {
                                                if (cont.isActive) {
                                                    try { webView.destroy() } catch (e: Exception) {}
                                                    cont.resume(detectedCUrl)
                                                }
                                            }
                                        } else {
                                            println("[TVWiki] [CHECK] Key7 영상 확인. 키 캡처를 위해 7초 대기.")
                                            handler.postDelayed({
                                                if (cont.isActive) {
                                                    println("[TVWiki] [WEBVIEW] 키 수집 대기 종료. URL 반환.")
                                                    try { webView.destroy() } catch (e: Exception) {}
                                                    cont.resume(detectedCUrl)
                                                }
                                            }, 7000) 
                                        }
                                    }
                                } catch (e: Exception) {
                                    println("[TVWiki] [ERROR] c.html 검증 중 오류: ${e.message}")
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // println("[TVWiki] [WEBVIEW] 페이지 로드 완료.")
                        view?.evaluateJavascript(hookScript, null)
                    }
                }

                println("[TVWiki] [WEBVIEW] loadUrl 호출: $url")
                webView.loadUrl(url, mapOf("Referer" to referer))

            } catch (e: Exception) {
                println("[TVWiki] [WEBVIEW] WebView 초기화 실패: ${e.message}")
                if (cont.isActive) cont.resume(null)
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

    /**
     * [ProxyWebServer]
     * 이제 모든 상태 변수(Key, IV, Playlist, Headers)를 이 클래스의 인스턴스 멤버로 가짐.
     * 따라서 extract() 호출 시마다 새로 생성되므로 이전 영상의 데이터와 섞일 일이 없음.
     */
    class ProxyWebServer(private val sessionKeys: MutableSet<String>) {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        // 인스턴스별 상태 변수 (격리됨)
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var verifiedKey: ByteArray? = null
        @Volatile private var currentIv: ByteArray? = null
        @Volatile private var testSegmentUrl: String? = null

        fun start() {
            try {
                serverSocket = ServerSocket(0).also { port = it.localPort }
                isRunning = true
                println("[TVWiki] [PROXY] 서버 소켓 오픈 성공. Port: $port")
                
                thread(isDaemon = true) {
                    println("[TVWiki] [PROXY] 요청 수신 대기 스레드 시작.")
                    while (isRunning) { 
                        try { 
                            val client = serverSocket!!.accept()
                            handleClient(client) 
                        } catch (e: Exception) {
                            if (isRunning) println("[TVWiki] [PROXY] accept 에러: ${e.message}")
                        } 
                    }
                    println("[TVWiki] [PROXY] 요청 수신 스레드 종료.")
                }
            } catch (e: Exception) {
                println("[TVWiki] [PROXY] 서버 시작 실패: ${e.message}")
            }
        }

        fun stop() { 
            isRunning = false
            try { 
                serverSocket?.close() 
                println("[TVWiki] [PROXY] 서버 소켓 닫음.")
            } catch(e: Exception) {} 
        }
        
        fun updateSession(h: Map<String, String>) { currentHeaders = h }
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun setIv(iv: ByteArray) { currentIv = iv }
        fun setTestSegment(url: String) { 
            if (testSegmentUrl == null) {
                testSegmentUrl = url 
                println("[TVWiki] [PROXY] 검증용 세그먼트 URL 설정됨: $url")
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
                
                // println("[TVWiki] [PROXY] 클라이언트 요청 수신: $path")

                when {
                    path.contains("playlist.m3u8") -> {
                        println("[TVWiki] [PROXY] 플레이리스트 요청 처리.")
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(currentPlaylist.toByteArray())
                    }
                    path.contains("/key") -> {
                        println("[TVWiki] [PROXY] 키 요청 수신.")
                        if (verifiedKey == null) {
                            println("[TVWiki] [PROXY] 검증된 키가 없음. verifyMultipleKeys() 실행.")
                            verifiedKey = verifyMultipleKeys()
                        }
                        
                        if (verifiedKey != null) {
                            println("[TVWiki] [PROXY] 키 반환 성공 (Verified).")
                        } else {
                            println("[TVWiki] [PROXY] 키 검증 실패 또는 키 없음. 빈 바이트 반환.")
                        }

                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(verifiedKey ?: ByteArray(16))
                    }
                    path.contains("/seg") -> {
                        val targetUrl = URLDecoder.decode(path.substringAfter("url="), "UTF-8")
                        // println("[TVWiki] [PROXY] 세그먼트 다운로드 요청: $targetUrl")
                        
                        try {
                            val conn = URL(targetUrl).openConnection() as HttpURLConnection
                            currentHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                            
                            val responseCode = conn.responseCode
                            if (responseCode == 200) {
                                val inputStream = conn.inputStream
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                                
                                val buffer = ByteArray(65536)
                                val bytesRead = inputStream.read(buffer)
                                
                                if (bytesRead > 0) {
                                    // MPEG-TS Sync Byte (0x47) 확인
                                    var offset = -1
                                    for (i in 0 until bytesRead - 376) {
                                        if (buffer[i] == 0x47.toByte() && buffer[i+188] == 0x47.toByte() && buffer[i+376] == 0x47.toByte()) {
                                            offset = i; break
                                        }
                                    }
                                    
                                    if (offset != -1) {
                                        output.write(buffer, offset, bytesRead - offset)
                                        // println("[TVWiki] [PROXY] MPEG-TS Sync 맞춤. offset=$offset")
                                    } else {
                                        output.write(buffer, 0, bytesRead)
                                        // println("[TVWiki] [PROXY] MPEG-TS Sync 못 찾음. 원본 전송.")
                                    }
                                    inputStream.copyTo(output)
                                }
                                inputStream.close()
                            } else {
                                println("[TVWiki] [PROXY] 원본 서버 오류 코드: $responseCode")
                            }
                        } catch (e: Exception) {
                            println("[TVWiki] [PROXY] 세그먼트 스트리밍 중 에러: ${e.message}")
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2: Exception) {} }
        }

        private fun verifyMultipleKeys(): ByteArray? = runBlocking {
            val url = testSegmentUrl ?: return@runBlocking null
            val targetIv = currentIv ?: ByteArray(16)
            
            println("[TVWiki] [VERIFY] 키 검증 프로세스 시작. 대상 URL: $url")
            println("[TVWiki] [VERIFY] 현재 수집된 키 개수: ${sessionKeys.size}")
            
            try {
                val responseData = app.get(url, headers = currentHeaders).body.bytes()
                val checkSize = 1024 
                val safeCheckSize = if (responseData.size < checkSize) responseData.size else checkSize
                println("[TVWiki] [VERIFY] 테스트 세그먼트 다운로드 완료 (${responseData.size} bytes).")

                // 생성자에서 전달받은 sessionKeys 사용 (동기화 블록)
                synchronized(sessionKeys) {
                    for ((index, hexKey) in sessionKeys.withIndex()) {
                        try {
                            val keyBytes = hexKey.hexToByteArray()
                            // println("[TVWiki] [VERIFY] #${index+1} 테스트 중: $hexKey")
                            
                            for (offset in 0..512) {
                                if (responseData.size < offset + safeCheckSize) break
                                val testChunk = responseData.copyOfRange(offset, offset + safeCheckSize)
                                val decrypted = decryptAES(testChunk, keyBytes, targetIv)
                                
                                // TS Sync Byte (0x47) 체크
                                if (decrypted.size >= 377 && decrypted[0] == 0x47.toByte() && decrypted[188] == 0x47.toByte() && decrypted[376] == 0x47.toByte()) {
                                    println("[TVWiki] [SUCCESS] 올바른 키 찾음! Key: $hexKey (Offset: $offset)")
                                    return@synchronized keyBytes
                                }
                            }
                        } catch (e: Exception) {
                            println("[TVWiki] [WARN] 키 테스트 중 예외: ${e.message}")
                        }
                    }
                    println("[TVWiki] [FAIL] 모든 키 테스트 실패.")
                    null
                }
            } catch (e: Exception) { 
                println("[TVWiki] [ERROR] 검증 데이터 다운로드 실패: ${e.message}")
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
    val len = length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i+1], 16)).toByte()
        i += 2
    }
    return data
}
