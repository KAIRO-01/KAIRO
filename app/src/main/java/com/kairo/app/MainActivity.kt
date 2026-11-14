package com.kairo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        const val DEFAULT_URL = Brain.DEFAULT_URL
        const val REQ_PERM = 1
    }

    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var micBtn: Button
    private lateinit var langBtn: TextView
    private lateinit var listenBtn: TextView

    private var hindi = true
    private var shown = 0
    private var typedBusy = false
    private var pendingAction = 0

    private val prefs by lazy { getSharedPreferences("kairo", Context.MODE_PRIVATE) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hindi = prefs.getBoolean("hindi", true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050B1A"))
        }
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left + dp(16), b.top + dp(8), b.right + dp(16), b.bottom + dp(8))
                WindowInsets.CONSUMED
            }
        } else {
            root.fitsSystemWindows = true
            root.setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        // Top bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "KAIRO"
            textSize = 22f
            letterSpacing = 0.3f
            setTextColor(Color.parseColor("#E8F0FF"))
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listenBtn = TextView(this).apply {
            text = "LISTEN OFF"
            textSize = 13f
            setTextColor(Color.parseColor("#2979FF"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { toggleService() }
        }
        bar.addView(listenBtn)
        langBtn = TextView(this).apply {
            text = if (hindi) "HI" else "EN"
            textSize = 16f
            setTextColor(Color.parseColor("#2979FF"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                hindi = !hindi
                prefs.edit().putBoolean("hindi", hindi).apply()
                text = if (hindi) "HI" else "EN"
            }
        }
        bar.addView(langBtn)
        val gear = TextView(this).apply {
            text = "\u2699"
            textSize = 26f
            setTextColor(Color.parseColor("#2979FF"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { showSettings() }
        }
        bar.addView(gear)
        root.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Orb
        orb = OrbView(this)
        root.addView(orb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))

        // Status
        status = TextView(this).apply {
            text = "Ready"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6C8CCF"))
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Messages
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(messages) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Input row
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Likho ya MIC dabao..."
            setHintTextColor(Color.parseColor("#5A6C99"))
            setTextColor(Color.parseColor("#E8F0FF"))
            maxLines = 4
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#0B1633"))
                setStroke(dp(1), Color.parseColor("#2979FF"))
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        micBtn = Button(this).apply {
            text = "MIC"
            setOnClickListener { onMicClicked() }
        }
        row.addView(micBtn)
        sendBtn = Button(this).apply {
            text = "SEND"
            setOnClickListener { sendTyped() }
        }
        row.addView(sendBtn)
        root.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        setContentView(root)

        if (KairoLog.items.isEmpty()) {
            KairoLog.items.add(
                "assistant" to "Namaste! Main KAIRO hoon. MIC dabake bolo, likh ke poochho, ya LISTEN ON karke 'Hey KAIRO' bolo."
            )
        }
        if ((prefs.getString("token", "") ?: "").isBlank()) showSettings()
    }

    override fun onResume() {
        super.onResume()
        KairoLog.onChange = { runOnUiThread { sync() } }
        messages.removeAllViews()
        shown = 0
        sync()
    }

    override fun onPause() {
        KairoLog.onChange = null
        super.onPause()
    }

    // ---------- Screen update ----------

    private fun sync() {
        val items = KairoLog.items.toList()
        if (items.size < shown) {
            messages.removeAllViews()
            shown = 0
        }
        for (i in shown until items.size) {
            addBubbleView(items[i].first, items[i].second)
        }
        shown = items.size
        refreshUi()
    }

    private fun refreshUi() {
        status.text = KairoLog.status
        when (KairoLog.mode) {
            1 -> orb.setSpeed(1100L)
            2 -> orb.setSpeed(600L)
            3 -> orb.setSpeed(900L)
            else -> orb.setSpeed(2400L)
        }
        listenBtn.text = if (KairoService.running) "LISTEN ON" else "LISTEN OFF"
        sendBtn.isEnabled = !typedBusy
    }

    // ---------- Actions ----------

    private fun hasMic(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensurePerms(action: Int): Boolean {
        if (hasMic()) return true
        pendingAction = action
        val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add("android.permission.POST_NOTIFICATIONS")
        requestPermissions(perms.toTypedArray(), REQ_PERM)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        val action = pendingAction
        pendingAction = 0
        if (hasMic()) {
            if (action == 1) KairoService.start(this)
            if (action == 2) KairoService.listenNow(this)
        } else {
            KairoLog.setStatus("Mic permission chahiye: Settings > Apps > KAIRO > Permissions mein allow karo")
        }
    }

    private fun toggleService() {
        if (KairoService.running) {
            KairoService.stop(this)
            refreshUi()
            return
        }
        if (!ensurePerms(1)) return
        KairoService.start(this)
    }

    private fun onMicClicked() {
        if (!ensurePerms(2)) return
        KairoService.listenNow(this)
    }

    private fun sendTyped() {
        if (typedBusy) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val token = (prefs.getString("token", "") ?: "").trim()
        if (token.isBlank()) {
            showSettings()
            return
        }
        input.setText("")
        KairoLog.add("user", text)
        typedBusy = true
        refreshUi()
        val app = applicationContext
        thread {
            val r = try {
                Brain.respond(app, text)
            } catch (e: Exception) {
                Pair(false, "Error: " + e.message)
            }
            runOnUiThread {
                KairoLog.add("assistant", r.second)
                typedBusy = false
                refreshUi()
            }
        }
    }

    // ---------- Settings ----------

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val urlIn = EditText(this).apply {
            hint = "Backend URL"
            setText(prefs.getString("url", DEFAULT_URL))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        val tokIn = EditText(this).apply {
            hint = "KAIRO_TOKEN (tumhara password)"
            setText(prefs.getString("token", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        box.addView(urlIn)
        box.addView(tokIn)
        AlertDialog.Builder(this)
            .setTitle("Backend settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("url", urlIn.text.toString().trim())
                    .putString("token", tokIn.text.toString().trim())
                    .apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Chat bubbles ----------

    private fun addBubbleView(role: String, content: String) {
        val isUser = role == "user"
        val tv = TextView(this).apply {
            text = content
            textSize = 16f
            setTextColor(Color.parseColor("#E8F0FF"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor(if (isUser) "#1E4FD8" else "#10204A"))
            }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (isUser) Gravity.END else Gravity.START
            topMargin = dp(6)
            bottomMargin = dp(6)
            if (isUser) marginStart = dp(48) else marginEnd = dp(48)
        }
        messages.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
