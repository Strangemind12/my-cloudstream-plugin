package com.kotbc

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KotbcPlugin : Plugin() {
    override fun load(context: Context) {
        // [수정] Main Provider는 registerMainAPI 사용
        registerMainAPI(Kotbc())
        
        // [수정] Extractor는 registerExtractorAPI 사용
        registerExtractorAPI(KotbcExtractor())
    }
}
