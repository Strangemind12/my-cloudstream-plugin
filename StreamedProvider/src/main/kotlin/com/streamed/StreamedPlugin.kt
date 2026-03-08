// 버전 정보: v1.0
package com.streamed

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class StreamedPlugin : Plugin() {
    override fun load(context: Context) {
        println("[Streamed v1.0] 디버깅 - StreamedPlugin 로드 시작")
        registerMainAPI(StreamedProvider())
        println("[Streamed v1.0] 디버깅 - StreamedProvider 등록 완료")
    }
}
