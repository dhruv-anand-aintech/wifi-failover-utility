package com.wififailover.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class HotspotAccessibilityService : AccessibilityService() {
    private val tag = "HotspotA11yService"
    private var isProcessing = false
    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = Runnable { checkAndClickHotspotToggle() }
    private var lastToggleClickTime = 0L
    private val TOGGLE_COOLDOWN_MS = 5000L // 5 second cooldown between toggle clicks

    override fun onServiceConnected() {
        Log.d(tag, "Accessibility service connected")
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        setServiceInfo(info)
        startPeriodicCheck()
    }

    private fun startPeriodicCheck() {
        handler.postDelayed(checkRunnable, 500)
    }

    private fun checkAndClickHotspotToggle() {
        try {
            val rootNode = rootInActiveWindow ?: run {
                handler.postDelayed(checkRunnable, 500)
                return
            }

            val packageName = rootNode.packageName?.toString() ?: ""
            if (packageName.contains("settings") || packageName.contains("wirelesssettings")) {
                Log.d(tag, "Settings window detected: $packageName")
                val allText = collectAllText(rootNode)

                if (allText.contains("personal hotspot", ignoreCase = true) ||
                    allText.contains("wi-fi hotspot", ignoreCase = true) ||
                    allText.contains("hotspot", ignoreCase = true) ||
                    allText.contains("tether", ignoreCase = true)) {

                    // Check if Personal Hotspot is ON or OFF
                    // Find the position of "Personal hotspot" and check if " On" (with space) follows it
                    val hotspotIndex = allText.indexOf("Personal hotspot", ignoreCase = true)
                    if (hotspotIndex >= 0) {
                        // Look at the next 150 characters after "Personal hotspot"
                        val afterHotspot = allText.substring(hotspotIndex, minOf(hotspotIndex + 150, allText.length))

                        // Check for " On" with a space before it (to avoid matching "on" in "Tethering")
                        val isHotspotOn = afterHotspot.contains(" On", ignoreCase = true) ||
                                afterHotspot.contains("\nOn", ignoreCase = true)

                        Log.d(tag, "Personal hotspot state - ON: $isHotspotOn - Context: ${afterHotspot.take(60)}")

                        if (isHotspotOn) {
                            Log.d(tag, "Personal hotspot is already ON, skipping toggle click")
                            handler.postDelayed(checkRunnable, 500)
                            return
                        }
                    }

                    Log.d(tag, "Hotspot is OFF, attempting to enable...")

                    // Check cooldown to prevent rapid toggling
                    val timeSinceLastClick = System.currentTimeMillis() - lastToggleClickTime
                    if (timeSinceLastClick < TOGGLE_COOLDOWN_MS) {
                        Log.d(tag, "Cooldown active (${TOGGLE_COOLDOWN_MS - timeSinceLastClick}ms remaining), skipping click")
                        handler.postDelayed(checkRunnable, 500)
                        return
                    }

                    if (!isProcessing) {
                        isProcessing = true
                        lastToggleClickTime = System.currentTimeMillis()
                        enableHotspotViaAccessibility()
                        isProcessing = false
                        // Don't stop the periodic check - settings may open multiple times
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "Error in periodic check: ${e.message}")
        }

        handler.postDelayed(checkRunnable, 500)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            Log.d(tag, "Event: type=${event.eventType}, package=${event.packageName}")

            if (isProcessing) return

            // Check current window regardless of event type
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                val packageName = rootNode.packageName?.toString() ?: ""
                Log.d(tag, "Current window: $packageName")

                if (packageName.contains("settings") && isHotspotSettingsPage(packageName, event)) {
                    Log.d(tag, "Hotspot settings page detected, attempting to enable...")
                    isProcessing = true
                    enableHotspotViaAccessibility()
                    isProcessing = false
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error in onAccessibilityEvent: ${e.message}")
        }
    }

    private fun isHotspotSettingsPage(packageName: String, event: AccessibilityEvent): Boolean {
        // Check if it's the Android settings app
        if (packageName != "com.android.settings" && packageName != "com.android.systemui") {
            return false
        }

        // Get the root node to check content
        val rootNode = rootInActiveWindow ?: return false
        val allText = collectAllText(rootNode)

        return allText.contains("hotspot", ignoreCase = true) ||
                allText.contains("tether", ignoreCase = true) ||
                allText.contains("wi-fi hotspot", ignoreCase = true) ||
                allText.contains("mobile hotspot", ignoreCase = true)
    }

    private fun enableHotspotViaAccessibility() {
        val rootNode = rootInActiveWindow ?: return

        try {
            val allText = collectAllText(rootNode)
            Log.d(tag, "enableHotspotViaAccessibility called - Text preview: ${allText.take(200)}")

            // Check if we're already on the Personal Hotspot detail screen (first row with toggle)
            // Detail screen shows: "Personal hotspot Share your mobile data..." with "On/Off" at end
            if (allText.contains("Share your mobile data", ignoreCase = true) ||
                allText.contains("Hotspot settings", ignoreCase = true)) {
                Log.d(tag, "Detected detail screen, clicking toggle...")
                if (clickFirstToggleInScreen(rootNode)) {
                    Log.d(tag, "Successfully clicked toggle on detail screen")
                    return
                }
            }

            // Otherwise we're on the first screen, find and click Personal Hotspot row
            Log.d(tag, "On first screen, finding Personal Hotspot row to click...")
            if (findAndClickRowByText(rootNode, "Personal Hotspot")) {
                Log.d(tag, "Clicked Personal Hotspot row, waiting for detail screen...")
                // Wait for the detail screen to load
                handler.postDelayed({
                    clickToggleOnDetailScreen()
                }, 500)
                return
            }

            Log.w(tag, "Could not find Personal Hotspot row to click")
        } catch (e: Exception) {
            Log.e(tag, "Error enabling hotspot via accessibility: ${e.message}")
        }
    }

    private fun clickToggleOnDetailScreen() {
        try {
            val rootNode = rootInActiveWindow ?: return
            Log.d(tag, "Checking detail screen for toggle...")

            if (clickFirstToggleInScreen(rootNode)) {
                Log.d(tag, "Successfully clicked toggle on detail screen")
            } else {
                Log.w(tag, "Could not find toggle on detail screen")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error clicking toggle on detail screen: ${e.message}")
        }
    }

    private fun findAndClickRowByText(node: AccessibilityNodeInfo, text: String): Boolean {
        try {
            val nodes = node.findAccessibilityNodeInfosByText(text)
            Log.d(tag, "Found ${nodes.size} nodes matching '$text'")

            for (textNode in nodes) {
                // Find the parent row/item that contains this text
                var current = textNode
                while (current != null) {
                    if (current.isClickable) {
                        Log.d(tag, "Found clickable parent, clicking row for: $text")
                        current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    current = current.parent
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "Error finding and clicking row: ${e.message}")
        }
        return false
    }

    private fun logUIStructure(node: AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null || depth > 5) return
        try {
            val indent = "  ".repeat(depth)
            val text = if (!node.text.isNullOrEmpty()) "text='${node.text}'" else ""
            val desc = if (!node.contentDescription.isNullOrEmpty()) "desc='${node.contentDescription}'" else ""
            val className = node.className?.toString()?.substringAfterLast('.') ?: ""
            val clickable = if (node.isClickable) " [CLICKABLE]" else ""
            Log.d(tag, "$indent$className $text $desc$clickable")

            for (i in 0 until node.childCount) {
                logUIStructure(node.getChild(i), depth + 1)
            }
        } catch (e: Exception) {
            Log.d(tag, "Error logging UI structure: ${e.message}")
        }
    }

    private fun clickFirstToggleInScreen(node: AccessibilityNodeInfo): Boolean {
        try {
            // Collect all clickable elements
            val allClickables = mutableListOf<AccessibilityNodeInfo>()
            collectAllClickables(node, allClickables)
            Log.d(tag, "Found ${allClickables.size} clickable elements on screen")

            if (allClickables.isEmpty()) {
                Log.w(tag, "No clickable elements found")
                return false
            }

            // Log first few clickables
            allClickables.take(5).forEachIndexed { idx, clickable ->
                val className = clickable.className?.toString()?.substringAfterLast('.') ?: "?"
                Log.d(tag, "[$idx] $className - text='${clickable.text}' desc='${clickable.contentDescription}'")
            }

            // Strategy 1: Try to find standard Switch/Toggle elements
            for (clickable in allClickables) {
                val className = clickable.className?.toString() ?: ""
                if (className.contains("Switch") || className.contains("Toggle") ||
                    className.contains("CompoundButton") || className.contains("CheckBox")) {
                    Log.d(tag, "Found native toggle element: $className")
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }

            // Strategy 2: Look for elements with toggle/switch descriptive text
            for (clickable in allClickables) {
                val text = (clickable.text?.toString() ?: "").lowercase()
                val desc = (clickable.contentDescription?.toString() ?: "").lowercase()

                if (text.contains("on") || text.contains("off") ||
                    desc.contains("toggle") || desc.contains("switch") ||
                    text.contains("enable") || text.contains("disable")) {
                    Log.d(tag, "Found element with toggle-like text: '$text'")
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }

            // Strategy 3: Click elements on the right side of screen (toggles are usually on the right)
            // Skip elements in the left 70% of the screen width
            for (clickable in allClickables) {
                try {
                    // This will help us identify right-aligned toggles
                    if (clickable.text?.toString()?.isEmpty() != false &&
                        clickable.contentDescription?.toString()?.isEmpty() != false) {
                        // Empty elements on the right are likely visual toggle controls
                        Log.d(tag, "Trying empty clickable element (likely toggle control)")
                        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                } catch (e: Exception) {
                    // Continue
                }
            }

            // Strategy 4: Try clicking elements that have no text (common for toggle buttons)
            for (clickable in allClickables.reversed()) {
                val hasText = !clickable.text.isNullOrEmpty()
                val hasDesc = !clickable.contentDescription.isNullOrEmpty()

                if (!hasText && !hasDesc) {
                    Log.d(tag, "Trying empty clickable (potential toggle): ${clickable.className}")
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }

        } catch (e: Exception) {
            Log.e(tag, "Error clicking toggle: ${e.message}")
        }
        return false
    }

    private fun collectAllToggles(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        try {
            val className = node.className?.toString() ?: ""
            if ((className.contains("Switch") || className.contains("Toggle") ||
                 className.contains("CheckBox") || className.contains("android.widget.Switch") ||
                 className.contains("CompoundButton") || className.contains("SwitchCompat")) &&
                node.isClickable) {
                list.add(node)
            }

            for (i in 0 until node.childCount) {
                collectAllToggles(node.getChild(i), list)
            }
        } catch (e: Exception) {
            Log.d(tag, "Error collecting toggles: ${e.message}")
        }
    }

    private fun collectAllClickables(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        try {
            if (node.isClickable) {
                list.add(node)
            }

            for (i in 0 until node.childCount) {
                collectAllClickables(node.getChild(i), list)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun findAndClickToggle(node: AccessibilityNodeInfo): Boolean {
        // Look for Switch or ToggleButton elements
        try {
            val switches = node.findAccessibilityNodeInfosByViewId("android:id/switchWidget")
            if (switches.isNotEmpty()) {
                for (switchNode in switches) {
                    if (switchNode.isClickable) {
                        Log.d(tag, "Found switch widget, clicking...")
                        switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "No switch widgets found via ID")
        }

        // Search recursively for clickable toggles near hotspot text
        return searchForToggleNearText(node, listOf("hotspot", "tether", "wi-fi", "wireless"))
    }

    private fun findAndClickByText(node: AccessibilityNodeInfo, text: String): Boolean {
        try {
            val nodes = node.findAccessibilityNodeInfosByText(text)
            for (textNode in nodes) {
                // Try to click the text node itself if it's clickable
                if (textNode.isClickable) {
                    Log.d(tag, "Found clickable text: $text")
                    textNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }

                // Try to find a sibling toggle/switch
                if (clickSiblingToggle(textNode)) {
                    Log.d(tag, "Clicked sibling toggle for: $text")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "Error finding text: $text - ${e.message}")
        }
        return false
    }

    private fun clickSiblingToggle(node: AccessibilityNodeInfo): Boolean {
        try {
            val parent = node.parent ?: return false

            // Check if parent itself is clickable and has toggle role
            if (parent.isClickable && (parent.className?.contains("Switch") == true ||
                                       parent.className?.contains("Toggle") == true)) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }

            // Search through siblings for toggles
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                if ((child.className?.contains("Switch") == true ||
                     child.className?.contains("Toggle") == true) && child.isClickable) {
                    child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "Error clicking sibling: ${e.message}")
        }
        return false
    }

    private fun searchForToggleNearText(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        try {
            for (keyword in keywords) {
                val nodes = node.findAccessibilityNodeInfosByText(keyword)
                for (textNode in nodes) {
                    if (clickSiblingToggle(textNode)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "Error searching for toggle near text: ${e.message}")
        }
        return false
    }

    private fun collectAllText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""

        val sb = StringBuilder()
        try {
            if (!node.text.isNullOrEmpty()) {
                sb.append(node.text).append(" ")
            }
            if (!node.contentDescription.isNullOrEmpty()) {
                sb.append(node.contentDescription).append(" ")
            }

            for (i in 0 until node.childCount) {
                sb.append(collectAllText(node.getChild(i)))
            }
        } catch (e: Exception) {
            Log.d(tag, "Error collecting text: ${e.message}")
        }
        return sb.toString()
    }

    override fun onInterrupt() {
        Log.d(tag, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.d(tag, "Accessibility service destroyed")
        handler.removeCallbacks(checkRunnable)
        super.onDestroy()
    }
}
