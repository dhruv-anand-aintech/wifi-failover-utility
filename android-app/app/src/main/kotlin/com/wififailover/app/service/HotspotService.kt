package com.wififailover.app.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.wififailover.app.data.Preferences

class HotspotService(private val context: Context) {
    private val preferences = Preferences(context)
    private val tag = "HotspotService"

    fun enableHotspot(): Boolean {
        Log.d(tag, "Opening hotspot settings page (accessibility service will auto-enable)...")
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.component = android.content.ComponentName(
                "com.android.settings",
                "com.android.settings.TetherSettings"
            )
            context.startActivity(intent)
            Log.d(tag, "Hotspot settings page opened - waiting for accessibility service to click toggle")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to open hotspot settings: ${e.message}")
            false
        }
    }

    fun disableHotspot(): Boolean {
        return true
    }
}
