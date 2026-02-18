package com.DaddyLive

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DaddyLivePlugin: Plugin() {
    override fun load(context: Context) {
        // MainAPI 등록
        registerMainAPI(DaddyLiveScheduleProvider())
        // ExtractorAPI 등록 (이제 Extractor.kt는 WebView를 사용하므로 별도 등록 필요)
        registerExtractorAPI(DaddyLiveExtractor())
    }
}
