// v1.0
package com.KingkongTv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KingkongTvPlugin : Plugin() {
    override fun load(context: Context) {
        // 앱 실행 시 KingkongTv 메인 파싱 클래스를 등록합니다.
        registerMainAPI(KingkongTv())
    }
}
