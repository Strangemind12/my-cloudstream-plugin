package com.tvmon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
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
 * Version: v23.2 (Triple-Check Verification)
 * Modification:
 * 1. [CRITICAL] verifyMultipleKeys now checks for 0x47 at indices 0, 188, and 376.
 * 2. [FIX] Decrypts a larger chunk (1KB) to ensure enough data for 3-packet verification.
 * 3. [MAINTAIN] All previous fixes (Manual WebView, Master Playlist, IV Regex) are preserved.
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
        println("[TVMON][v23.2] getUrl 호출됨. URL: $url")
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
        
        // 1. iframe 주소 추출
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                println("[TVMON] [STEP 1-1] iframe 주소 찾는 중...")
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVMON] iframe 발견됨: $cleanUrl")
                }
            } catch (e: Exception) { println("[TVMON] [ERROR] iframe 파싱 실패: ${e.message}") }
        }

        var capturedUrl: String? = cleanUrl

        // 2. WebView 후킹
        if (!cleanUrl.contains("/c.html")) {
            println("[TVMON] [STEP 2] WebView 직접 실행하여 c.html 및 키 후킹 시도...")
            capturedKeys.clear()
            verifiedKey = null
            
            val webViewResult = runWebViewHook(cleanUrl, cleanReferer)
            
            if (webViewResult != null) {
                capturedUrl = webViewResult
                println("[TVMON] [STEP 2-1] c.html URL 캡처 완료: $capturedUrl")
            } else {
                println("[TVMON] [WARNING] c.html 캡처 실패. iframe 주소로 계속 진행합니다.")
            }
            
            println("[TVMON] 현재까지 수집된 키 후보 개수: ${capturedKeys.size}")
            capturedKeys.forEach { println("[TVMON] [CANDIDATE] 후킹된 키: $it") }
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
                println("[TVMON] 쿠키 적용됨.")
            }

            try {
                println("[TVMON] [STEP 3] M3U8 메인 파일 요청 중...")
                var requestUrl = capturedUrl!!.substringBefore("#")
                var response = app.get(requestUrl, headers = headers)
                var content = response.text.trim()

                // [CASE A] HTML 내용 안에 .m3u8 링크가 숨어있는 경우
                if (!content.startsWith("#EXTM3U")) {
                    println("[TVMON] 응답이 M3U8이 아님. 내부 링크 검색 중...")
                    Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(content)?.let {
                        requestUrl = it.groupValues[1]
                        println("[TVMON] 실제 M3U8 주소 발견: $requestUrl")
                        response = app.get(requestUrl, headers = headers)
                        content = response.text.trim()
                    }
                }

                // [CASE B] Master Playlist (화질 목록)인 경우
                if (content.contains("#EXT-X-STREAM-INF")) {
                    println("[TVMON] [INFO] Master Playlist 감지됨. 하위 스트림 탐색.")
                    val lines = content.lines()
                    var subUrlLine: String? = null
                    for (i in lines.indices.reversed()) {
                        val line = lines[i].trim()
                        if (line.isNotEmpty() && !line.startsWith("#")) {
                            subUrlLine = line
                            break
                        }
                    }

                    if (subUrlLine != null) {
                        val originalUri = try { URI(requestUrl) } catch (e: Exception) { null }
                        requestUrl = resolveUrl(originalUri, requestUrl, subUrlLine)
                        println("[TVMON] [INFO] 미디어 플레이리스트로 진입: $requestUrl")
                        response = app.get(requestUrl, headers = headers)
                        content = response.text.trim()
                    }
                }

                // [CHECK] Key7 및 IV 확인
                val isKey7 = content.lines().any { it.startsWith("#EXT-X-KEY") && it.contains("/v/key7") }
                println("[TVMON] [CHECK] Key7 암호화 적용 여부: $isKey7")

                if (isKey7) {
                    println("[TVMON] [STEP 4] Key7 프록시 서버 초기화...")
                    proxyServer?.stop()
                    proxyServer = ProxyWebServer().apply {
                        start()
                        updateSession(headers)
                    }

                    val ivMatch = Regex("""IV=("?)(0x[0-9a-fA-F]+)\1""").find(content)
                    val ivHex = ivMatch?.groupValues?.get(2) ?: "0x00000000000000000000000000000000"
                    
                    currentIv = ivHex.removePrefix("0x").hexToByteArray()
                    println("[TVMON] [DATA] 추출된 IV: $ivHex")

                    val baseUri = try { URI(requestUrl) } catch (e: Exception) { null }
                    val sb = StringBuilder()

                    println("[TVMON] [STEP 4-1] Playlist 재작성 및 세그먼트 라우팅.")
                    content.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEach

                        if (trimmed.startsWith("#")) {
                            if (trimmed.startsWith("#EXT-X-KEY") && trimmed.contains("/v/key7")) {
                                val match = Regex("""URI="([^"]+)"""").find(trimmed)
                                if (match != null) {
                                    val originalKeyUrl = match.groupValues[1]
                                    val absoluteKeyUrl = resolveUrl(baseUri, requestUrl, originalKeyUrl)
                                    val encodedKeyUrl = java.net.URLEncoder.encode(absoluteKeyUrl, "UTF-8")
                                    val newLine = trimmed.replace(originalKeyUrl, "http://127.0.0.1:${proxyServer!!.port}/key?url=$encodedKeyUrl")
                                    sb.append(newLine).append("\n")
                                } else sb.append(trimmed).append("\n")
                            } else sb.append(trimmed).append("\n")
                        } else {
                            val absoluteSegUrl = resolveUrl(baseUri, requestUrl, trimmed)
                            if (testSegmentUrl == null) {
                                testSegmentUrl = absoluteSegUrl
                                println("[TVMON] 검증용 첫 세그먼트 주소 확보: $testSegmentUrl")
                            }
                            val encodedSegUrl = java.net.URLEncoder.encode(absoluteSegUrl, "UTF-8")
                            sb.append("http://127.0.0.1:${proxyServer!!.port}/seg?url=$encodedSegUrl").append("\n")
                        }
                    }

                    proxyServer!!.setPlaylist(sb.toString())
                    val proxyFinalUrl = "http://127.0.0.1:${proxyServer!!.port}/playlist.m3u8"
                    println("[TVMON] [FINISH] 프록시 M3U8 생성 완료: $proxyFinalUrl")
                    
                    callback(newExtractorLink(name, name, proxyFinalUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                } 

                println("[TVMON] Key7이 아니므로 일반 스트림을 반환합니다.")
                callback(newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                })
                return true

            } catch (e: Exception) { println("[TVMON] [ERROR] 추출 프로세스 오류: ${e.message}") }
        }
        return false
    }

    private suspend fun runWebViewHook(url: String, referer: String) = suspendCancellableCoroutine<String?> { cont ->
        val hookScript = """
            (function() {
                if (typeof G !== 'undefined') window.G = false;
                const originalSet = Uint8Array.prototype.set;
                Uint8Array.prototype.set = function(source, offset) {
                    if (source instanceof Uint8Array && source.length === 16) {
                        var hex = Array.from(source).map(b => b.toString(16).padStart(2, '0')).join('');
                        console.log("CapturedKeyHex:" + hex);
                    }
                    return originalSet.apply(this, arguments);
                };
                console.log("[JS-HOOK] 감시 장치 가동됨.");
            })();
        """.trimIndent()

        Handler(Looper.getMainLooper()).post {
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
                            val key = msg.removePrefix("CapturedKeyHex:")
                            capturedKeys.add(key)
                            println("[TVMON] [HOOK] ★★★ 키 캡처 성공: $key")
                            
                            if (cont.isActive) {
                                cont.resume(null) // URL은 intercept에서 못 잡았어도 키는 잡았으니 진행
                                webView.destroy()
                            }
                        } else if (msg.contains("[JS-HOOK]")) {
                            println("[TVMON] [HOOK] JS 스크립트 정상 주입됨.")
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        if (reqUrl.contains("/c.html") && reqUrl.contains("token=")) {
                            println("[TVMON] [INTERCEPT] c.html 주소 감지됨 (계속 대기): $reqUrl")
                            // URL만 캡처하고 키가 나올 때까지 대기
                            if (cont.isActive) {
                                cont.resume(reqUrl) 
                                // 주의: 여기서 destroy하면 안됨
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        println("[TVMON] 페이지 로드 완료. Hook 주입 시도: $url")
                        view?.evaluateJavascript(hookScript, null)
                    }
                }

                println("[TVMON] Manual WebView 로드 시작: $url")
                webView.loadUrl(url, mapOf("Referer" to referer))

                Handler(Looper.getMainLooper()).postDelayed({
                    if (cont.isActive) {
                        println("[TVMON] WebView 타임아웃 (15초).")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }, 15000)

            } catch (e: Exception) {
                println("[TVMON] WebView 생성 실패: ${e.message}")
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

    data class Layer(@JsonProperty("name") val name: String, @JsonProperty("xor_mask") val xorMask: String? = null, @JsonProperty("pad_len") val padLen: Int? = null, @JsonProperty("segment_lengths") val segmentLengths: List<Int>? = null, @JsonProperty("real_positions") val realPositions: List<Int>? = null, @JsonProperty("init_key") val initKey: String? = null, @JsonProperty("noise_lens") val noiseLens: List<Int>? = null, @JsonProperty("perm") val perm: List<Int>? = null, @JsonProperty("rotations") val rotations: List<Int>? = null, @JsonProperty("inverse_sbox") val inverseSbox: String? = null)
    data class Key7Response(@JsonProperty("encrypted_key") val encryptedKey: String, @JsonProperty("layers") val layers: List<Layer>)

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
                    println("[PROXY] 서버 가동 시작 (포트: $port)")
                    while (isRunning) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} }
                }
            } catch (e: Exception) { println("[PROXY] [ERROR] 시작 실패: ${e.message}") }
        }

        fun stop() { isRunning = false; serverSocket?.close(); println("[PROXY] 서버 중지됨.") }
        fun updateSession(h: Map<String, String>) { currentHeaders = h }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                val parts = line.split(" ")
                if (parts.size < 2) return
                val path = parts[1]
                val output = socket.getOutputStream()

                when {
                    path.contains("/playlist.m3u8") -> {
                        println("[PROXY] [REQ] Playlist 요청 수신.")
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(currentPlaylist.toByteArray())
                    }
                    path.contains("/key") -> {
                        println("[PROXY] [REQ] Key 복호화 요청 수신.")
                        if (verifiedKey == null) {
                            println("[PROXY] [ACTION] 저장된 키 후보군 검증 프로세스 시작...")
                            verifiedKey = verifyMultipleKeys()
                        }
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(verifiedKey ?: ByteArray(16))
                        println("[PROXY] [RES] 검증된 키 반환 완료.")
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
                            if (offset != -1) {
                                output.write(buffer, offset, bytesRead - offset)
                            } else {
                                output.write(buffer, 0, bytesRead)
                            }
                            inputStream.copyTo(output)
                        }
                        inputStream.close()
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { socket.close() }
        }

        // [v23.2] 3단계 정밀 검증 (0, 188, 376 인덱스 모두 0x47인지 확인)
        private fun verifyMultipleKeys(): ByteArray? {
            val url = testSegmentUrl ?: return null
            println("[VERIFY] 검증 타겟 세그먼트: $url")
            
            val targetIv = currentIv ?: ByteArray(16)
            println("[VERIFY] 사용 IV: ${targetIv.joinToString("") { String.format("%02x", it) }}")
            
            return try {
                val responseData = runBlocking { 
                    println("[VERIFY] 세그먼트 데이터 다운로드 중...")
                    // 1KB 정도만 받아도 충분하지만, 확실한 검증을 위해 넉넉히 받음
                    app.get(url, headers = currentHeaders).body.bytes() 
                }
                println("[VERIFY] 다운로드 완료. 전체 크기: ${responseData.size} bytes")

                // 3패킷 검증을 위해 필요한 최소 크기: 377바이트 (376번 인덱스까지 필요하므로)
                val checkSize = 1024 
                val safeCheckSize = if (responseData.size < checkSize) responseData.size else checkSize

                synchronized(capturedKeys) {
                    println("[VERIFY] 총 ${capturedKeys.size}개의 키 후보로 오프셋 스캔을 시작합니다.")
                    
                    for (hexKey in capturedKeys) {
                        val keyBytes = hexKey.hexToByteArray()
                        
                        // 오프셋 0~512 범위 탐색
                        for (offset in 0..512) {
                            // 배열 범위 체크
                            if (responseData.size < offset + safeCheckSize) break

                            val testChunk = responseData.copyOfRange(offset, offset + safeCheckSize)
                            val decrypted = decryptAES(testChunk, keyBytes, targetIv)

                            // [CRITICAL] 3단계 검증: 0, 188, 376 인덱스가 모두 0x47이어야 함
                            // MPEG-TS 패킷 길이는 188바이트 고정임
                            if (decrypted.size >= 377 &&
                                decrypted[0] == 0x47.toByte() &&
                                decrypted[188] == 0x47.toByte() &&
                                decrypted[376] == 0x47.toByte()) {
                                    
                                println("\n========================================")
                                println("[VERIFY] ★★★ 정밀 검증 성공 (MPEG-TS 표준 확인) ★★★")
                                println("1. 정답 키: $hexKey")
                                println("2. 숨겨진 오프셋: $offset 바이트")
                                println("3. Sync Bytes: [0]=0x47, [188]=0x47, [376]=0x47 확인됨")
                                println("========================================\n")
                                return keyBytes
                            }
                        }
                    }
                }
                println("[VERIFY] [FATAL] 모든 오프셋에서 MPEG-TS 패턴(0x47 * 3)을 찾지 못했습니다.")
                null
            } catch (e: Exception) { println("[VERIFY] [ERROR] 검증 로직 중단: ${e.message}"); null }
        }

        // [v23.2] 대용량(1KB) 데이터 복호화용
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
