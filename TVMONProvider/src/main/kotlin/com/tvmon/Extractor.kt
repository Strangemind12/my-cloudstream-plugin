package com.tvmon

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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

/**
 * Version: v1.1 Fixed (Restored Logic + Hybrid Hooking)
 * Modification:
 * 1. [RESTORE] Key7 check logic: Only use Proxy if '/v/key7' is present.
 * 2. [RESTORE] IV Parsing: Extract IV from M3U8 tag (Essential for decryption).
 * 3. [HYBRID] Hooking: Uses TVWiki's advanced JS hooking (Constructor + defineProperty).
 * 4. [HYBRID] Sync: Uses CountDownLatch for immediate playback upon key found.
 * 5. [HYBRID] Collection: Collects ALL 16-byte arrays (TVMON style).
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVMON"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private const val TAG = "[TVMON-v1.1]"
        
        private var proxyServer: ProxyWebServer? = null
        
        // [Hybrid] TVMON Style Collection (Thread-Safe)
        val capturedKeys: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())
        
        // [Restored] Global State
        @Volatile var verifiedKey: ByteArray? = null
        @Volatile var currentIv: ByteArray? = null // M3U8에서 파싱한 IV
        @Volatile var testSegmentUrl: String? = null
        
        // [Hybrid] TVWiki Style Sync
        @Volatile var keyLatch: CountDownLatch = CountDownLatch(1)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 초기화
        proxyServer?.stop()
        proxyServer = null
        capturedKeys.clear()
        verifiedKey = null
        currentIv = null
        testSegmentUrl = null
        keyLatch = CountDownLatch(1)
        
        println("$TAG getUrl 호출됨. URL: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("$TAG [STEP 1] extract() 시작")
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"

        // 1. iframe 추출 로직 (기존 유지)
        if (!cleanUrl.contains("/v/") && !cleanUrl.contains("/e/")) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("$TAG iframe 발견: $cleanUrl")
                }
            } catch (e: Exception) { println("$TAG iframe 파싱 실패") }
        }

        var capturedUrl: String? = cleanUrl

        // 2. c.html 처리 (기존 로직 유지)
        if (cleanUrl.contains("/c.html")) {
             // 이미 c.html이면 그대로 진행
        } else {
             // WebViewResolver 로직이 필요하다면 여기에 추가 (현재는 생략하고 바로 요청 시도)
        }

        // 3. M3U8 내용 확인 및 Key7 판별
        try {
            val headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://player.bunny-frame.online/")
            println("$TAG [STEP 2] M3U8 요청 중... URL: $capturedUrl")
            
            var requestUrl = capturedUrl?.substringBefore("#") ?: return false
            var response = app.get(requestUrl, headers = headers)
            var content = response.text.trim()

            // 내부 M3U8 리다이렉트 처리
            if (!content.startsWith("#EXTM3U")) {
                Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(content)?.let {
                    requestUrl = it.groupValues[1]
                    println("$TAG 실제 M3U8 주소 발견: $requestUrl")
                    content = app.get(requestUrl, headers = headers).text.trim()
                }
            }

            // [RESTORED] Key7 판별 로직
            val isKey7 = content.lines().any { it.startsWith("#EXT-X-KEY") && it.contains("/v/key7") }
            println("$TAG [CHECK] Key7 암호화 여부: $isKey7")

            if (!isKey7) {
                // [RESTORED] Key7이 아니면 프록시 없이 즉시 반환
                println("$TAG Key7이 아니므로 원본 스트림 반환 (Non-Proxy)")
                callback(newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.headers = headers
                })
                return true
            }

            // ==========================================
            // Key7 Case: Start Proxy & JS Hooking
            // ==========================================
            println("$TAG [STEP 3] Key7 감지됨. 프록시 및 후킹 시작.")

            // 1. IV 파싱 (M3U8 태그에서 추출)
            val ivMatch = Regex("""IV=(0x[0-9a-fA-F]+)""").find(content)
            val ivHex = ivMatch?.groupValues?.get(1) ?: "0x00000000000000000000000000000000"
            currentIv = hexToBytes(ivHex.removePrefix("0x"))
            println("$TAG [DATA] 추출된 IV: $ivHex")

            // 2. JS Hooking 시작 (TVWiki Style)
            thread {
                runBlocking {
                    JsKeyStealer.startStealing(cleanUrl, DESKTOP_UA, cleanReferer)
                }
            }

            // 3. 프록시 서버 시작
            val proxy = ProxyWebServer()
            proxy.start()
            proxyServer = proxy
            val proxyPort = proxy.port
            val proxyRoot = "http://127.0.0.1:$proxyPort"

            // 4. Playlist 재작성 (Proxy Routing)
            val sb = StringBuilder()
            val baseUri = try { URI(requestUrl) } catch (e: Exception) { null }

            content.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach

                if (trimmed.startsWith("#")) {
                    if (trimmed.startsWith("#EXT-X-KEY") && trimmed.contains("/v/key7")) {
                        // 키 라인은 제거 (프록시 내부에서 처리하므로 플레이어에게 노출 X)
                        // 또는 더미 키 URL로 대체 가능하나, 여기선 삭제/무시
                    } else {
                        sb.append(trimmed).append("\n")
                    }
                } else {
                    val absoluteSegUrl = resolveUrl(baseUri, requestUrl, trimmed)
                    if (testSegmentUrl == null) {
                        testSegmentUrl = absoluteSegUrl
                        proxy.setTestSegmentUrl(absoluteSegUrl) // 검증용 URL 설정
                        println("$TAG 검증용 세그먼트 확보: $testSegmentUrl")
                    }
                    val encodedSegUrl = URLEncoder.encode(absoluteSegUrl, "UTF-8")
                    sb.append("$proxyRoot/proxy?url=$encodedSegUrl").append("\n")
                }
            }
            
            proxy.setPlaylist(sb.toString())
            println("$TAG [FINISH] 프록시 M3U8 생성 완료")

            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                this.referer = "https://player.bunny-frame.online/"
                this.headers = headers
            })
            return true

        } catch (e: Exception) {
            println("$TAG [ERROR] 추출 중 예외 발생: ${e.message}")
            e.printStackTrace()
        }
        return false
    }

    private fun resolveUrl(baseUri: URI?, baseUrlStr: String, target: String): String {
        if (target.startsWith("http")) return target
        return try { baseUri?.resolve(target).toString() } catch (e: Exception) {
            if (target.startsWith("/")) "${baseUrlStr.substringBefore("/", "https://")}//${baseUrlStr.split("/")[2]}$target"
            else "${baseUrlStr.substringBeforeLast("/")}/$target"
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    // ==========================================
    // JS Injection (TVWiki Hooking + TVMON Collection)
    // ==========================================
    object JsKeyStealer {
        private const val HYBRID_HOOK_SCRIPT = """
            (function() {
                try {
                    if (window.isHooked) return;
                    window.isHooked = true;

                    // 1. 전역 변수 초기화
                    if (typeof G !== 'undefined') window.G = false;

                    // 2. 키 보고 (무차별 수집)
                    function reportKey(source) {
                        if (source && source.length === 16) {
                            var hex = Array.from(source).map(function(b) {
                                return ('0' + (b & 0xFF).toString(16)).slice(-2);
                            }).join('');
                            console.log("CANDIDATE_KEY:" + hex);
                        }
                    }

                    // 3. Uint8Array.prototype.set 후킹 (TVWiki Style: defineProperty)
                    const originalSet = Uint8Array.prototype.set;
                    Object.defineProperty(Uint8Array.prototype, 'set', {
                        value: function(source, offset) {
                            if (source) reportKey(source);
                            return originalSet.apply(this, arguments);
                        },
                        writable: true,
                        configurable: true
                    });

                    // 4. 생성자 후킹 (TVWiki Style)
                    const OriginalUint8Array = window.Uint8Array;
                    function HookedUint8Array(arg1, arg2, arg3) {
                        var arr;
                        if (arguments.length === 0) arr = new OriginalUint8Array();
                        else if (arguments.length === 1) arr = new OriginalUint8Array(arg1);
                        else if (arguments.length === 2) arr = new OriginalUint8Array(arg1, arg2);
                        else arr = new OriginalUint8Array(arg1, arg2, arg3);
                        reportKey(arr);
                        return arr;
                    }
                    HookedUint8Array.prototype = OriginalUint8Array.prototype;
                    HookedUint8Array.BYTES_PER_ELEMENT = OriginalUint8Array.BYTES_PER_ELEMENT;
                    HookedUint8Array.from = OriginalUint8Array.from;
                    HookedUint8Array.of = OriginalUint8Array.of;
                    
                    try {
                        Object.defineProperty(window, 'Uint8Array', {
                            value: HookedUint8Array, writable: true, configurable: true
                        });
                    } catch(e) { window.Uint8Array = HookedUint8Array; }

                    console.log("HOOK_INSTALLED");
                } catch(e) { console.log("HOOK_ERROR:" + e.message); }
            })();
        """

        suspend fun startStealing(url: String, ua: String, referer: String) {
            withContext(Dispatchers.Main) {
                val webView = WebView(AcraApplication.context!!)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = ua
                    blockNetworkImage = true
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CANDIDATE_KEY:")) {
                            val keyHex = msg.substringAfter("CANDIDATE_KEY:")
                            if (BunnyPoorCdn.capturedKeys.add(keyHex)) {
                                println("$TAG [COLLECT] 키 후보 수집: $keyHex")
                            }
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(HYBRID_HOOK_SCRIPT, null)
                    }
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                         super.onPageStarted(view, url, favicon)
                         view?.evaluateJavascript(HYBRID_HOOK_SCRIPT, null)
                    }
                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                         super.doUpdateVisitedHistory(view, url, isReload)
                         view?.evaluateJavascript(HYBRID_HOOK_SCRIPT, null)
                    }
                }

                val handler = Handler(Looper.getMainLooper())
                handler.postDelayed({ try { webView.destroy() } catch(e:Exception){} }, 20000)

                val headers = mapOf("Referer" to referer)
                webView.loadUrl(url, headers)
            }
        }
    }

    // ==========================================
    // Proxy Server (Decryption with M3U8 IV)
    // ==========================================
    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var testSegmentUrl: String? = null

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                thread(isDaemon = true) { 
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} 
                    } 
                }
            } catch (e: Exception) {}
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
        }
        
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun setTestSegmentUrl(url: String) { testSegmentUrl = url }

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val parts = line.split(" ")
                if (parts.size < 2) return@thread
                val path = parts[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    val body = currentPlaylist.toByteArray(charset("UTF-8"))
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(body)
                } else if (path.contains("/proxy")) {
                    // 1. 키 검증 로직 (TVMON Verify)
                    if (BunnyPoorCdn.verifiedKey == null) {
                        println("$TAG [VERIFY] 키 검증 시도...")
                        verifyKeyInternal() 
                    }

                    // 2. 동기화 (TVWiki Sync Latch)
                    if (BunnyPoorCdn.verifiedKey == null) {
                        println("$TAG [WAIT] 키 대기 중...")
                        BunnyPoorCdn.keyLatch.await(10, TimeUnit.SECONDS)
                    }

                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")

                    runBlocking {
                        try {
                            val res = app.get(targetUrl, headers = mapOf("User-Agent" to "Mozilla/5.0"))
                            if (res.isSuccessful) {
                                val rawData = res.body.bytes()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                                
                                val key = BunnyPoorCdn.verifiedKey
                                val iv = BunnyPoorCdn.currentIv // [IMPORTANT] Use M3U8 IV

                                val decrypted = if (key != null && iv != null) {
                                    decryptAES(rawData, key, iv)
                                } else {
                                    println("$TAG [WARN] Key 또는 IV 부재. 원본 반환.")
                                    rawData
                                }
                                output.write(decrypted ?: rawData)
                            } else {
                                output.write("HTTP/1.1 ${res.code} Error\r\n\r\n".toByteArray())
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2:Exception){} }
        }

        private fun verifyKeyInternal() {
            val url = testSegmentUrl ?: return
            val iv = BunnyPoorCdn.currentIv ?: return

            if (BunnyPoorCdn.capturedKeys.isEmpty()) return

            val sampleData = try {
                runBlocking {
                    app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0")).body.bytes().copyOfRange(0, 1024)
                }
            } catch (e: Exception) { return }

            val candidates = synchronized(BunnyPoorCdn.capturedKeys) { BunnyPoorCdn.capturedKeys.toList() }
            
            for (hex in candidates) {
                val keyBytes = hexToBytes(hex)
                // [IMPORTANT] Verify using Key + M3U8 IV
                val decrypted = decryptAES(sampleData, keyBytes, iv)
                
                if (decrypted != null && decrypted.isNotEmpty() && decrypted[0] == 0x47.toByte()) {
                    println("$TAG [SUCCESS] 키 확정: $hex")
                    BunnyPoorCdn.verifiedKey = keyBytes
                    BunnyPoorCdn.keyLatch.countDown()
                    return
                }
            }
        }

        // [RESTORED] Standard AES Decryption with Explicit IV
        private fun decryptAES(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
            return try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { null }
        }

        private fun hexToBytes(hex: String): ByteArray {
            val len = hex.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }
    }
}
