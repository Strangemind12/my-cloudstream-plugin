package com.tvmon

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
 * Version: v23.9 (Foreground Priority Injection)
 * Modification:
 * 1. [FIX] Attach WebView to Activity's RootView (1x1) for maximum priority.
 * 2. [FIX] Improved c.html interception stability.
 * 3. [FIX] Auto-cleanup of injected View.
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

        // Context에서 Activity를 찾아내는 헬퍼 함수
        fun getAsActivity(context: Context?): Activity? {
            var ctx = context
            while (ctx is ContextWrapper) {
                if (ctx is Activity) return ctx
                ctx = ctx.baseContext
            }
            return null
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[TVMON][v23.9] getUrl 호출됨.")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"
        
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) {}
        }

        var capturedUrl: String? = cleanUrl

        if (!cleanUrl.contains("/c.html")) {
            println("[TVMON] [STEP 2] 웹뷰 우선순위 격상 모드 가동 (8초)")
            capturedKeys.clear()
            verifiedKey = null
            val webViewResult = runWebViewHook(cleanUrl, cleanReferer)
            if (webViewResult != null) capturedUrl = webViewResult
        }

        if (capturedUrl != null) {
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online"
            )
            val cookie = CookieManager.getInstance().getCookie(capturedUrl)
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

            try {
                var requestUrl = capturedUrl!!.substringBefore("#")
                var content = app.get(requestUrl, headers = headers).text.trim()

                if (!content.startsWith("#EXTM3U")) {
                    Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(content)?.let {
                        requestUrl = it.groupValues[1]
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                if (content.contains("#EXT-X-STREAM-INF")) {
                    val subUrlLine = content.lines().lastOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if (subUrlLine != null) {
                        requestUrl = resolveUrl(try { URI(requestUrl) } catch (e: Exception) { null }, requestUrl, subUrlLine)
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                if (content.contains("/v/key7")) {
                    proxyServer?.stop()
                    proxyServer = ProxyWebServer().apply { start(); updateSession(headers) }

                    val videoId = Regex("""/v/[ef]/([^/]+)""").find(capturedUrl!!)?.groupValues?.get(1) ?: "video"
                    val ivMatch = Regex("""IV=("?)(0x[0-9a-fA-F]+)\1""").find(content)
                    currentIv = (ivMatch?.groupValues?.get(2) ?: "0x00000000000000000000000000000000").removePrefix("0x").hexToByteArray()

                    val baseUri = try { URI(requestUrl) } catch (e: Exception) { null }
                    val sb = StringBuilder()
                    content.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("#")) {
                            if (trimmed.contains("/v/key7")) {
                                val match = Regex("""URI="([^"]+)"""").find(trimmed)
                                if (match != null) {
                                    val absKey = resolveUrl(baseUri, requestUrl, match.groupValues[1])
                                    sb.append(trimmed.replace(match.groupValues[1], "http://127.0.0.1:${proxyServer!!.port}/key?url=${java.net.URLEncoder.encode(absKey, "UTF-8")}")).append("\n")
                                } else sb.append(trimmed).append("\n")
                            } else sb.append(trimmed).append("\n")
                        } else if (trimmed.isNotBlank()) {
                            val absSeg = resolveUrl(baseUri, requestUrl, trimmed)
                            if (testSegmentUrl == null) testSegmentUrl = absSeg
                            sb.append("http://127.0.0.1:${proxyServer!!.port}/seg?url=${java.net.URLEncoder.encode(absSeg, "UTF-8")}").append("\n")
                        }
                    }
                    proxyServer!!.setPlaylist(sb.toString())
                    callback(newExtractorLink(name, name, "http://127.0.0.1:${proxyServer!!.port}/$videoId/playlist.m3u8", ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                } else {
                    callback(newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                }
            } catch (e: Exception) { println("[TVMON] [ERROR] $e") }
        }
        return false
    }

    private suspend fun runWebViewHook(url: String, referer: String) = suspendCancellableCoroutine<String?> { cont ->
        val hookScript = """(function(){window.G=false;const oI=window.crypto.subtle.importKey;Object.defineProperty(window.crypto.subtle,'importKey',{value:function(f,k,a,e,u){if(f==='raw'&&(k.byteLength===16||k.length===16)){try{let b=new Uint8Array(k);let h=Array.from(b).map(x=>x.toString(16).padStart(2,'0')).join('');console.log("CapturedKeyHex:"+h);}catch(e){}}return oI.apply(this,arguments);},configurable:true,writable:true});const oS=Uint8Array.prototype.set;Uint8Array.prototype.set=function(s,o){if(s&&s.length===16){try{let h=Array.from(s).map(x=>x.toString(16).padStart(2,'0')).join('');console.log("CapturedKeyHex:"+h);}catch(e){}}return oS.apply(this,arguments);};})();"""

        Handler(Looper.getMainLooper()).post {
            try {
                var detectedCUrl: String? = null
                val context = (AcraApplication.context ?: app) as Context
                val activity = getAsActivity(context) // Activity 찾기
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                    cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                }

                // [Requirement] 1x1 픽셀 강제 주입 로직
                val params = ViewGroup.LayoutParams(1, 1)
                if (activity != null) {
                    val root = activity.window.decorView as ViewGroup
                    root.addView(webView, params) // 실제 레이아웃에 추가
                    webView.alpha = 0.1f // 거의 안 보이게 설정
                    println("[TVMON] [INJECT] 웹뷰를 DecorView에 부착했습니다. (Activity 포커스 획득)")
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CapturedKeyHex:")) {
                            capturedKeys.add(msg.substringAfter("CapturedKeyHex:"))
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        if (reqUrl.contains("/c.html") && reqUrl.contains("token=")) {
                            println("[TVMON] [FOUND] c.html 포착: $reqUrl")
                            detectedCUrl = reqUrl
                            view?.post { view.evaluateJavascript(hookScript, null) }
                            
                            thread {
                                runBlocking {
                                    try {
                                        val res = app.get(reqUrl, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://player.bunny-frame.online/"))
                                        if (!res.text.contains("/v/key7")) {
                                            Handler(Looper.getMainLooper()).post {
                                                if (cont.isActive) {
                                                    (webView.parent as? ViewGroup)?.removeView(webView) // 제거
                                                    webView.destroy()
                                                    cont.resume(detectedCUrl)
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                webView.loadUrl(url, mapOf("Referer" to referer))

                Handler(Looper.getMainLooper()).postDelayed({
                    if (cont.isActive) {
                        println("[TVMON] 8초 타임아웃 종료. (최종 반환)")
                        (webView.parent as? ViewGroup)?.removeView(webView) // 제거
                        webView.destroy()
                        cont.resume(detectedCUrl)
                    }
                }, 8000)

            } catch (e: Exception) {
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
                    while (isRunning) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} }
                }
            } catch (e: Exception) {}
        }

        fun stop() { isRunning = false; try { serverSocket?.close() } catch(e: Exception) {} }
        fun updateSession(h: Map<String, String>) { currentHeaders = h }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                when {
                    path.contains("playlist.m3u8") -> {
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(currentPlaylist.toByteArray())
                    }
                    path.contains("/key") -> {
                        if (verifiedKey == null) verifiedKey = verifyMultipleKeys()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(verifiedKey ?: ByteArray(16))
                    }
                    path.contains("/seg") -> {
                        val targetUrl = URLDecoder.decode(path.substringAfter("url="), "UTF-8")
                        runBlocking {
                            try {
                                val resData = app.get(targetUrl, headers = currentHeaders).body.bytes()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                                var offset = -1
                                for (i in 0 until resData.size - 376) {
                                    if (resData[i] == 0x47.toByte() && resData[i+188] == 0x47.toByte() && resData[i+376] == 0x47.toByte()) {
                                        offset = i; break
                                    }
                                }
                                if (offset != -1) output.write(resData, offset, resData.size - offset)
                                else output.write(resData)
                            } catch (e: Exception) {}
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2: Exception) {} }
        }

        private fun verifyMultipleKeys(): ByteArray? = runBlocking {
            val url = testSegmentUrl ?: return@runBlocking null
            try {
                val responseData = app.get(url, headers = currentHeaders).body.bytes()
                val targetIv = currentIv ?: ByteArray(16)
                synchronized(capturedKeys) {
                    for (hexKey in capturedKeys) {
                        val keyBytes = hexKey.hexToByteArray()
                        for (offset in 0..512) {
                            if (responseData.size < offset + 1024) break
                            val decrypted = decryptAES(responseData.copyOfRange(offset, offset + 1024), keyBytes, targetIv)
                            if (decrypted.size >= 377 && decrypted[0] == 0x47.toByte() && decrypted[188] == 0x47.toByte() && decrypted[376] == 0x47.toByte()) {
                                return@synchronized keyBytes
                            }
                        }
                    }
                    null
                }
            } catch (e: Exception) { null }
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
