// v1.12
package com.phisher98.settings // v1.12: 폴더 구조에 맞게 패키지명 수정

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.phisher98.StremioXPlugin
import com.phisher98.BuildConfig // v1.12: BuildConfig 명시적 임포트

class SettingsBottomFragment(
    private val plugin: StremioXPlugin,
    private val sharedPref: android.content.SharedPreferences
) : BottomSheetDialogFragment() {
    private val PLUGIN_PACKAGE_NAME = "com.phisher98"

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val res = plugin.resources ?: throw Exception("Resources not found")
        val id = res.getIdentifier(name, "layout", PLUGIN_PACKAGE_NAME)
        return inflater.inflate(id, container, false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = getLayout("bottom_sheet_layout", inflater, container)
        // ... (버튼 연결 및 클릭 리스너 로직)
        return view
    }
}
