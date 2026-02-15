package com.tvmon

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
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
 * Version: v22.5 (Context Reference Fix)
 * Modification:
 * 1. [FIX] 'app.context' -> 'app' (app itself is the Context).
 * 2. [FIX] Cleaned up AcraApplication reference logic.
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
        println("[TVMON][v22.5] getUrl 호출됨. URL: $url")
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
        println("[TVMON] 대상 URL: $cleanUrl, 레퍼러: $cleanReferer")

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

        // 2. WebView 후킹 (직접 구현 모드)
        if (!cleanUrl.contains("/c.html")) {
            println("[TVMON] [STEP 2] WebView 직접 실행하여 키 후킹 시도...")
            capturedKeys.clear()
            verifiedKey = null
            
            // 수동 WebView 실행 (Main Thread)
            runWebViewHook(cleanUrl, cleanReferer)
            
            // WebView 실행 후 수집된 키 확인
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

                if (!content.startsWith("#EXTM3U")) {
                    println("[TVMON] 응답이 M3U8이 아님. 내부 링크 검색 중...")
                    Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(content)?.let {
                        requestUrl = it.groupValues[1]
                        println("[TVMON] 실제 M3U8 주소 발견: $requestUrl")
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                val isKey7 = content.lines().any { it.startsWith("#EXT-X-KEY") && it.contains("/v/key7") }
                println("[TVMON] [CHECK] Key7 암호화 적용 여부: $isKey7")

                if (isKey7) {
                    println("[TVMON] [STEP 4] Key7 프록시 서버 초기화...")
                    proxyServer?.stop()
                    proxyServer = ProxyWebServer().apply {
                        start()
                        updateSession(headers)
                    }

                    val ivMatch = Regex("""IV=(0x[0-9a-fA-F]+)""").find(content)
                    val ivHex = ivMatch?.groupValues?.get(1) ?: "0x00000000000000000000000000000000"
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

    private suspend fun runWebViewHook(url: String, referer: String) = suspendCancellableCoroutine<Unit> { cont ->
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
                // [수정] app 객체 자체가 Context이므로 바로 사용
                val context = try { AcraApplication.context ?: app } catch(e: Exception) { app }
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                    // 미디어 자동 재생 허용 (필요시)
                    mediaPlaybackRequiresUserGesture = false
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CapturedKeyHex:")) {
                            val key = msg.removePrefix("CapturedKeyHex:")
                            capturedKeys.add(key)
                            println("[TVMON] [HOOK] ★★★ 키 캡처 성공: $key")
                        } else if (msg.contains("[JS-HOOK]")) {
                            println("[TVMON] [HOOK] JS 스크립트 정상 주입됨.")
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        println("[TVMON] 페이지 로드 완료. Hook 주입 시도: $url")
                        view?.evaluateJavascript(hookScript, null)
                    }
                }

                println("[TVMON] Manual WebView 로드 시작: $url")
                webView.loadUrl(url, mapOf("Referer" to referer))

                Handler(Looper.getMainLooper()).postDelayed({
                    println("[TVMON] WebView 타임아웃 (10초). 종료합니다.")
                    try { webView.destroy() } catch (e: Exception) {}
                    if (cont.isActive) cont.resume(Unit)
                }, 10000)

            } catch (e: Exception) {
                println("[TVMON] WebView 생성 실패: ${e.message}")
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

        private fun verifyMultipleKeys(): ByteArray? {
            val url = testSegmentUrl ?: return null
            println("[VERIFY] 검증 타겟 세그먼트: $url")
            
            return try {
                val responseData = runBlocking { 
                    println("[VERIFY] 세그먼트 데이터 다운로드 중...")
                    app.get(url, headers = currentHeaders).body.bytes() 
                }
                println("[VERIFY] 다운로드 완료. 크기: ${responseData.size} bytes")
                val testChunk = responseData.copyOfRange(0, 1024)

                synchronized(capturedKeys) {
                    println("[VERIFY] 총 ${capturedKeys.size}개의 키 후보를 대입합니다.")
                    for (hex in capturedKeys) {
                        val keyBytes = hex.hexToByteArray()
                        try {
                            val decrypted = decryptAES(testChunk, keyBytes, currentIv ?: ByteArray(16))
                            if (decrypted.isNotEmpty()) {
                                if (decrypted[0] == 0x47.toByte()) {
                                    println("[VERIFY] [SUCCESS] 유효한 키 발견! 진짜 키: $hex")
                                    return keyBytes
                                }
                            }
                        } catch (e: Exception) { }
                    }
                }
                println("[VERIFY] [FATAL] 모든 후보 키가 일치하지 않습니다.")
                null
            } catch (e: Exception) { println("[VERIFY] [ERROR] 검증 로직 중단: ${e.message}"); null }
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
