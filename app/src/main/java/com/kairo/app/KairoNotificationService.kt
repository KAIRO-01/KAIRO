package com.kairo.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.ArrayDeque

/**
 * Only WhatsApp notifications received after the user grants Notification Access.
 * Kept in memory, never automatically sent to a server.
 */
class KairoNotificationService : NotificationListenerService() {
    companion object {
        @Volatile var active: KairoNotificationService? = null
        private val messages = ArrayDeque<String>()
        private val allowed = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val sensitive = Regex(
            "(?i)\\b(otp|one.time password|verification code|upi pin|cvv|password|passcode)\\b"
        )

        fun recentWhatsApp(): String? = synchronized(messages) {
            messages.toList().takeLast(12).joinToString("\n").ifBlank { null }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        active = this
    }

    override fun onListenerDisconnected() {
        if (active === this) active = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        if (item.packageName !in allowed) return
        val extras = item.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(100).orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(350).orEmpty()
        if (body.isBlank() || sensitive.containsMatchIn(body)) return
        synchronized(messages) {
            messages.addLast("$title: $body")
            while (messages.size > 30) messages.removeFirst()
        }
    }

    override fun onDestroy() {
        if (active === this) active = null
        synchronized(messages) { messages.clear() }
        super.onDestroy()
    }
}
