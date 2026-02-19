// SettingsBottomFragment.kt v1.2
package com.phisher98

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.api.Log
import org.json.JSONArray
import org.json.JSONObject

class SettingsBottomFragment(
    private val plugin: StremioXPlugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {
    private val PREF_KEY_LINKS = "stremio_saved_links"
    // v1.2: BuildConfig 의존성 제거를 위해 패키지명 고정
    private val PLUGIN_PACKAGE_NAME = "com.phisher98"

    private val res get() = plugin.resources ?: throw Exception("Resources not found")

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", PLUGIN_PACKAGE_NAME)
        return res.getDrawable(id, null)
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", PLUGIN_PACKAGE_NAME)
        return this.findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", PLUGIN_PACKAGE_NAME)
        return inflater.inflate(id, container, false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = getLayout("bottom_sheet_layout", inflater, container)
        
        val addlinks: ImageView = view.findView("addlinks")
        val showlinks: ImageView = view.findView("showlinks")
        val saveIcon: ImageView = view.findView("saveIcon")

        addlinks.setImageDrawable(getDrawable("settings_icon"))
        showlinks.setImageDrawable(getDrawable("settings_icon"))
        saveIcon.setImageDrawable(getDrawable("save_icon"))

        addlinks.setOnClickListener {
            val dialogView = getLayout("addlinks", inflater, container)
            AlertDialog.Builder(requireContext()).setView(dialogView).show()
        }
        return view
    }
}
