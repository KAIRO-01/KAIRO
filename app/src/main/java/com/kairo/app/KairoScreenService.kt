package com.kairo.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque
import java.util.Locale

/**
 * Opt-in accessibility assistant limited to currently visible WhatsApp controls.
 * No screenshots, hidden conversation scraping, message sending or payments.
 */
class KairoScreenService : AccessibilityService() {
    companion object {
        @Volatile var active: KairoScreenService? = null
        private val allowedApps = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val blockedLabels = listOf(
            "send", "delete", "remove", "pay", "payment", "transfer", "confirm",
            "purchase", "buy", "block", "report", "logout", "log out", "clear chat",
            "verify", "otp", "pin", "पासवर्ड", "भेज", "डिलीट",
            "पेमेंट", "भुगतान", "हटाएं", "कन्फर्म"
        )
        private val sensitiveText = Regex(
            "(?i)\\b(otp|one.time password|verification code|upi pin|cvv|password|passcode)\\b"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            packageNames = arrayOf("com.whatsapp", "com.whatsapp.w4b")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    /**
     * Re-query Android's current active application window on every request.
     * Never keep an old WhatsApp chat root or use a background chat window.
     * Some devices expose an empty window list; fall back to the current active root.
     */
    private fun currentVisibleWhatsAppRoot(): AccessibilityNodeInfo? {
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val activeApp = appWindows.firstOrNull { it.isActive }
            ?: appWindows.firstOrNull { it.isFocused }
        val root = if (activeApp != null) activeApp.root else rootInActiveWindow
        return root?.takeIf { it.packageName?.toString() in allowedApps }
    }

    /**
     * Extract accessible, user-visible text only after a spoken request.
     * Accessibility cannot read image pixels, older off-screen messages or encrypted backups.
     */
    fun readVisibleWhatsApp(): String? {
        val root = currentVisibleWhatsAppRoot() ?: return null
        if (root.packageName?.toString() !in allowedApps) return null
        val seen = LinkedHashSet<String>()
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)
        var visited = 0
        var total = 0
        while (queue.isNotEmpty() && visited < 250 && total < 4000) {
            val node = queue.removeFirst()
            visited++
            if (!node.isVisibleToUser || node.isPassword) continue
            for (candidate in listOf(node.text, node.contentDescription)) {
                val t = candidate?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                if (t.isNotEmpty() && t.length <= 420 && !sensitiveText.containsMatchIn(t) && seen.add(t)) {
                    total += t.length + 1
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return seen.joinToString("\n").take(4000).ifBlank { null }
    }

    /** Only exact, user-specified, visible WhatsApp labels can be tapped. */
    fun tapVisibleWhatsApp(target: String): Boolean {
        val label = target.trim().lowercase(Locale.ROOT)
        if (label.length !in 2..72 || blockedLabels.any { label == it || label.startsWith("$it ") }) {
            return false
        }
        val root = currentVisibleWhatsAppRoot() ?: return false
        if (root.packageName?.toString() !in allowedApps) return false
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 250) {
            val node = queue.removeFirst()
            if (!node.isVisibleToUser || node.isPassword) continue
            val candidates = listOf(node.text, node.contentDescription)
                .mapNotNull { it?.toString()?.trim()?.lowercase(Locale.ROOT) }
            if (candidates.any { it == label }) {
                var clickable: AccessibilityNodeInfo? = node
                var depth = 0
                while (clickable != null && depth++ < 5) {
                    if (clickable.isVisibleToUser && clickable.isClickable) {
                        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    clickable = clickable.parent
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }
}
