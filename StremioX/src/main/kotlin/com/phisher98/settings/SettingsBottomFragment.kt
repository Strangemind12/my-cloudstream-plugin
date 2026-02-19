// v1.13
package com.phisher98.settings // v1.13: 폴더 구조에 맞게 패키지 경로 수정

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.api.Log
import com.phisher98.StremioXPlugin // v1.13: 상위 클래스 임포트

class SettingsBottomFragment(
    private val plugin: StremioXPlugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {
    private val PREF_KEY_LINKS = "stremio_saved_links"
    // v1.13: BuildConfig 대신 고정 패키지명을 사용하여 리소스 참조 에러 차단
    private val PLUGIN_PACKAGE_NAME = "com.phisher98"

    private val res get() = plugin.resources ?: throw Exception("Unable to access plugin resources")

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", PLUGIN_PACKAGE_NAME)
        return res.getDrawable(id, null) ?: throw Exception("Drawable $name not found")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", PLUGIN_PACKAGE_NAME)
        if (id == 0) throw Exception("View ID $name not found.")
        return this.findViewById(id)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", PLUGIN_PACKAGE_NAME)
        this.background = res.getDrawable(outlineId, null)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", PLUGIN_PACKAGE_NAME)
        val layout = res.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    // ... (이후 로직은 원본과 동일하게 유지)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = getLayout("bottom_sheet_layout", inflater, container)
        // ... (나머지 코드 생략, v1.12와 로직 동일)
        return view
    }
}
