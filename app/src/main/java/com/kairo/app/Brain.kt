package com.kairo.app

import android.content.Context

/** Shared log and status: the service writes, the screen shows. */
object KairoLog {
    val items = mutableListOf<Pair<String, String>>()

    @Volatile
    var status: String = "KAIRO band hai. LISTEN ON dabao."

    @Volatile
    var mode: Int = 0 // 0 idle, 1 listening, 2 thinking, 3 speaking

    var onChange: (() -> Unit)? = null

    fun add(role: String, text: String) {
        items.add(role to text)
        onChange?.invoke()
    }

    fun setStatus(s: String) {
        status = s
        onChange?.invoke()
    }
}

/** Decides what to do with a sentence: phone command first, otherwise ask the AI. */
object Brain {
    const val DEFAULT_URL = "https://kairo-backend-uyp9.onrender.com"

    private val history = mutableListOf<Pair<String, String>>()

    /** Blocking call: run it on a worker thread. */
    fun respond(ctx: Context, text: String): Pair<Boolean, String> {
        val local = PhoneCommands.handle(ctx, text)
        if (local != null) return Pair(true, local)

        val prefs = ctx.getSharedPreferences("kairo", Context.MODE_PRIVATE)
        val token = (prefs.getString("token", "") ?: "").trim()
        if (token.isBlank()) {
            return Pair(false, "Pehle app mein gear se token daalo.")
        }
        val base = (prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL).ifBlank { DEFAULT_URL }

        val snapshot = synchronized(history) {
            history.add("user" to text)
            if (history.size > 40) {
                repeat(10) { history.removeAt(0) }
            }
            history.toList()
        }
        val result = try {
            BackendClient.chat(base, token, snapshot)
        } catch (e: Exception) {
            Pair(false, "Network error (" + e.javaClass.simpleName + "). Internet check karo.")
        }
        synchronized(history) {
            if (result.first) {
                history.add("assistant" to result.second)
            } else if (history.isNotEmpty()) {
                history.removeAt(history.size - 1)
            }
        }
        return result
    }
}
