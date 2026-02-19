/**
 * DaddyLiveExtractor v3.8 (Body Snatcher)
 * - [핵심] JS XMLHttpRequest Hook: server_lookup의 JSON 응답을 낚아채서 server_key 획득
 * - [핵심] 주소 강제 조립: 획득한 키로 mono.css 주소를 직접 생성
 * - [Fix] WebChromeClient 도입: JS에서 전달한 키를 안드로이드 로그로 수신
 */
package com.DaddyLive

import android.content.Context
import android.os.*
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.resume

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.link"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        coroutineScope {
            links.amap { (name, link) ->
                val result = runWebViewInterceptor(name, link)
                if (result != null) {
                    println("[DaddyLiveExt] ★최종 확정 주소: $result")
                    callback(newExtractorLink(name, name, result, type = ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "https://dlhd.link/"
                        this.headers = mapOf("User-Agent" to userAgent, "Referer" to "https://dlhd.link/", "Origin" to "https://dlhd.link")
                    })
                }
            }
        }
    }

    private suspend fun runWebViewInterceptor(nameTag: String, targetUrl: String): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                var isFinished = false
                val channelId = targetUrl.substringAfter("stream-").substringBefore(".php")

                webView.settings.apply { 
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                }

                // [핵심] JS의 응답을 받기 위한 콘솔 모니터링
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                        val msg = message?.message() ?: ""
                        if (msg.startsWith("KEY_FOUND:") && !isFinished) {
                            val serverKey = msg.substringAfter("KEY_FOUND:")
                            val constructedUrl = "https://${serverKey}new.dvalna.ru/$serverKey/premium$channelId/mono.css"
                            println("[DaddyLiveExt] [$nameTag] ★키 획득 성공 ($serverKey) -> 주소 조립 완료")
                            isFinished = true
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(constructedUrl)
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
                    
                    // 페이지 시작 시 통신 가로채기 스크립트 주입
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        val jsHook = """
                            (function() {
                                var origOpen = XMLHttpRequest.prototype.open;
                                XMLHttpRequest.prototype.open = function() {
                                    this.addEventListener('load', function() {
                                        if (this.responseURL.includes('server_lookup')) {
                                            try {
                                                var data = JSON.parse(this.responseText);
                                                if (data.server_key) {
                                                    console.log('KEY_FOUND:' + data.server_key);
                                                }
                                            } catch(e) {}
                                        }
                                    });
                                    origOpen.apply(this, arguments);
                                };
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(jsHook, null)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        // m3u8이나 mono.css가 네트워크 레벨에서 먼저 잡히면 즉시 반환 (기존 로직 유지)
                        if ((reqUrl.contains("mono.css") || (reqUrl.contains("lovecdn.ru") && reqUrl.contains(".m3u8"))) && !isFinished) {
                            isFinished = true
                            println("[DaddyLiveExt] [$nameTag] ★네트워크 직접 가로채기 성공: $reqUrl")
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(reqUrl)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                
                println("[DaddyLiveExt] [$nameTag] 분석 진입: $targetUrl")
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))

                handler.postDelayed({ 
                    if (!isFinished && cont.isActive) { 
                        isFinished = true
                        webView.destroy()
                        println("[DaddyLiveExt] [$nameTag] 키 추출 실패(타임아웃)")
                        cont.resume(null) 
                    } 
                }, 35000)
            } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }
    }
}
