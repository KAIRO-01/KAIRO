package com.kairo.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
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

    /**
     * Perform an explicitly confirmed WhatsApp Send only when ALL checks pass:
     * 1. WhatsApp is the active application
     * 2. Chat header identifies the intended recipient
     * 3. Editable text equals the user's pending draft exactly
     * 4. Exactly one visible, clickable Send control is found
     *
     * Returns false without clicking if the UI is unfamiliar or ambiguous.
     */
    fun sendVerifiedWhatsAppDraft(recipient: String, phone: String, body: String): Boolean {
        val root = currentVisibleWhatsAppRoot() ?: return false
        val screenHeight = resources.displayMetrics.heightPixels
        val name = recipient.trim().lowercase(Locale.ROOT)
        val lastTen = phone.filter { it.isDigit() }.takeLast(10)

        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)
        var visited = 0
        var headerMatched = false
        var draftMatched = false
        val sendButtons = LinkedHashSet<AccessibilityNodeInfo>()
        while (queue.isNotEmpty() && visited++ < 400) {
            val node = queue.removeFirst()
            if (!node.isVisibleToUser || node.isPassword) continue
            val nodeText = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val rect = Rect()
            node.getBoundsInScreen(rect)

            // Only a visible conversation header near the TOP of the screen
            // can confirm the recipient, not a name occurring in message text.
            if (rect.top >= 0 && rect.top < screenHeight / 5 &&
                !node.isEditable
            ) {
                for (candidate in listOf(nodeText, description)) {
                    val label = candidate.lowercase(Locale.ROOT)
                    val digits = candidate.filter { it.isDigit() }
                    if ((name.isNotBlank() && label == name) ||
                        (lastTen.length == 10 && digits.endsWith(lastTen))
                    ) headerMatched = true
                }
            }

            if (node.isEditable && nodeText == body) {
                draftMatched = true
            }

            val resourceId = node.viewIdResourceName.orEmpty()
            val shortDescription = description.lowercase(Locale.ROOT)
            val isSend = resourceId == "com.whatsapp:id/send" ||
                resourceId == "com.whatsapp.w4b:id/send" ||
                shortDescription == "send" ||
                shortDescription == "send message" ||
                shortDescription == "भेजें"
            if (isSend) {
                var clickNode: AccessibilityNodeInfo? = node
                var hops = 0
                while (clickNode != null && hops++ < 4 && !clickNode.isClickable) {
                    clickNode = clickNode.parent
                }
                if (clickNode?.isVisibleToUser == true && clickNode.isClickable) {
                    sendButtons.add(clickNode)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        if (!headerMatched || !draftMatched || sendButtons.size != 1) return false

        // Never tap without positive recipient AND content verification.
        return sendButtons.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

}
