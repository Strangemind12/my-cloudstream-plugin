package com.kotbc

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class KotbcPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Kotbc())
    }
}
