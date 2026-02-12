package com.wififailover.app.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.wififailover.app.data.Preferences

class HotspotService(private val context: Context) {
    private val preferences = Preferences(context)
    private val tag = "HotspotService"

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains("com.wififailover.app/") &&
               enabledServices.contains("HotspotAccessibilityService")
    }

    fun enableHotspot(): Boolean {
        // Check if accessibility service is enabled
        if (!isAccessibilityServiceEnabled()) {
            Log.e(tag, "❌ Accessibility Service not enabled - cannot auto-enable hotspot")
            return false
        }

        Log.d(tag, "Opening hotspot settings page (accessibility service will auto-enable)...")
        return try {
            // Tell accessibility service to act on settings window
            HotspotAccessibilityService.requestHotspotEnable()

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
