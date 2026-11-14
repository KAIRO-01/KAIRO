package com.kairo.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class OrbView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pulse = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    fun setThinking(thinking: Boolean) {
        animator.duration = if (thinking) 700L else 2400L
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.22f * (1f + 0.08f * pulse)

        paint.shader = RadialGradient(
            cx, cy, r * 2f,
            intArrayOf(
                Color.parseColor("#CC2979FF"),
                Color.parseColor("#442979FF"),
                Color.parseColor("#00000000")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 2f, paint)

        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(
                Color.parseColor("#FFBFD9FF"),
                Color.parseColor("#FF2979FF"),
                Color.parseColor("#FF0A1A4A")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
    }
}

class MainActivity : Activity() {

    companion object {
        const val DEFAULT_URL = "https://kairo-backend-uyp9.onrender.com"
    }

    private val history = mutableListOf<Pair<String, String>>()
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private var busy = false

    private val prefs by lazy { getSharedPreferences("kairo", Context.MODE_PRIVATE) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        val gear = TextView(this).apply {
            text = "\u2699"
            textSize = 26f
            setTextColor(Color.parseColor("#2979FF"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
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
            hint = "KAIRO se kuch poocho..."
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
        sendBtn = Button(this).apply {
            text = "Send"
            setOnClickListener { sendMessage() }
        }
        row.addView(sendBtn)
        root.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        setContentView(root)

        addBubble("assistant", "Namaste! Main KAIRO hoon. Kuch bhi poochho.")
        if ((prefs.getString("token", "") ?: "").isBlank()) showSettings()
    }

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

    private fun addBubble(role: String, content: String) {
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

    private fun setBusy(value: Boolean) {
        busy = value
        orb.setThinking(value)
        status.text = if (value) "KAIRO soch raha hai... (pehli baar 1 minute lag sakta hai)" else "Ready"
        sendBtn.isEnabled = !value
    }

    private fun sendMessage() {
        if (busy) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val token = (prefs.getString("token", "") ?: "").trim()
        if (token.isBlank()) {
            showSettings()
            return
        }
        val base = (prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL).ifBlank { DEFAULT_URL }

        input.setText("")
        history.add("user" to text)
        addBubble("user", text)
        setBusy(true)

        val snapshot = history.toList()
        thread {
            val result = try {
                callBackend(base, token, snapshot)
            } catch (e: Exception) {
                Pair(false, "Network error (" + e.javaClass.simpleName + "). Internet check karo aur dobara try karo.")
            }
            runOnUiThread {
                if (result.first) {
                    history.add("assistant" to result.second)
                } else {
                    history.removeAt(history.size - 1)
                }
                addBubble("assistant", result.second)
                setBusy(false)
            }
        }
    }

    private fun callBackend(base: String, token: String, msgs: List<Pair<String, String>>): Pair<Boolean, String> {
        val arr = JSONArray()
        for (m in msgs) {
            arr.put(JSONObject().put("role", m.first).put("text", m.second))
        }
        val body = JSONObject().put("messages", arr)

        val conn = URL(base.trimEnd('/') + "/chat").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("x-kairo-token", token)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

        if (code == 200) {
            return Pair(true, JSONObject(raw).getString("reply"))
        }
        if (code == 401) {
            return Pair(false, "Token galat hai. Upar \u2699 dabake sahi KAIRO_TOKEN daalo.")
        }
        val detail = try {
            JSONObject(raw).optString("detail", "")
        } catch (e: Exception) {
            ""
        }
        return Pair(false, "Server error " + code + ": " + detail.take(200))
    }
}
