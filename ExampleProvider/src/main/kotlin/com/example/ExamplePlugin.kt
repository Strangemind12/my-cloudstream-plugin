package com.moviezwap

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class moviezwapPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerExtractorAPI(Filelion())
        registerMainAPI(moviezwapProvider())
        registerExtractorAPI(StreamwishHG())
        registerExtractorAPI(mivalyo())
    }
}
