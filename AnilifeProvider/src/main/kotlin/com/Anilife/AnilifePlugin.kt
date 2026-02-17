package com.anilife

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnilifePlugin: Plugin() {
    override fun load(context: Context) {
        // Anilife 프로바이더 등록
        registerMainAPI(Anilife())
    }
}
