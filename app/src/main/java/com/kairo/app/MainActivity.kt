package com.kairo.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import java.util.Locale
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

    fun setSpeed(ms: Long) {
        animator.duration = ms
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
        const val REQ_MIC = 1
    }

    private val history = mutableListOf<Pair<String, String>>()
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var micBtn: Button
    private lateinit var langBtn: TextView

    private var busy = false
    private var listening = false
    private var speaking = false
    private var note: String? = null
    private var hindi = true

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

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
        langBtn = TextView(this).apply {
            text = if (hindi) "HI" else "EN"
            textSize = 16f
            setTextColor(Color.parseColor("#2979FF"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
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
            setOnClickListener { sendMessage(false) }
        }
        row.addView(sendBtn)
        root.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        setContentView(root)

        tts = TextToSpeech(this) { s -> ttsReady = (s == TextToSpeech.SUCCESS) }

        addBubble("assistant", "Namaste! Main KAIRO hoon. MIC dabake bolo ya likh ke poochho.")
        if ((prefs.getString("token", "") ?: "").isBlank()) showSettings()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- UI state ----------

    private fun refreshUi() {
        when {
            listening -> {
                status.text = "Sun raha hoon... boliye"
                orb.setSpeed(1100L)
                micBtn.text = "STOP"
            }
            busy -> {
                status.text = "KAIRO soch raha hai... (pehli baar 1 minute lag sakta hai)"
                orb.setSpeed(600L)
                micBtn.text = "MIC"
            }
            speaking -> {
                status.text = "KAIRO bol raha hai... (MIC dabake rok sakte ho)"
                orb.setSpeed(900L)
                micBtn.text = "MIC"
            }
            else -> {
                status.text = note ?: "Ready"
                orb.setSpeed(2400L)
                micBtn.text = "MIC"
            }
        }
        sendBtn.isEnabled = !busy
    }

    // ---------- Voice: listening ----------

    private fun onMicClicked() {
        when {
            listening -> recognizer?.stopListening()
            busy -> {}
            else -> {
                stopSpeaking()
                startListening()
            }
        }
    }

    private fun startListening() {
        note = null
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            note = "Is phone mein speech recognition service nahi mili"
            refreshUi()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(listener)
        recognizer = r

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (hindi) "hi-IN" else "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        listening = true
        input.setText("")
        refreshUi()
        r.startListening(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                note = "Mic permission chahiye: Settings > Apps > KAIRO > Permissions mein allow karo"
                refreshUi()
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!p.isNullOrBlank()) input.setText(p)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val said = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (said.isEmpty()) {
                note = "Kuch samajh nahi aaya, MIC dabake dobara bolo"
                refreshUi()
                return
            }
            input.setText(said)
            sendMessage(true)
        }

        override fun onError(error: Int) {
            listening = false
            note = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "Samajh nahi aaya, dobara bolo"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Kuch suna nahi, MIC dabake dobara bolo"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Speech ke liye internet chahiye"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission allow karo"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy hai, thodi der baad try karo"
                else -> "Speech error code $error"
            }
            refreshUi()
        }
    }

    // ---------- Voice: speaking ----------

    private fun speak(content: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            note = "Text-to-speech abhi ready nahi hai"
            refreshUi()
            return
        }
        val devanagari = content.any { it in '\u0900'..'\u097F' }
        val loc = Locale.forLanguageTag(if (devanagari) "hi-IN" else "en-IN")
        val res = engine.setLanguage(loc)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            note = "Is bhasha ki awaaz phone mein install nahi hai (Settings > Text-to-speech)"
            refreshUi()
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    speaking = true
                    refreshUi()
                }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    speaking = false
                    refreshUi()
                }
            }

            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    speaking = false
                    refreshUi()
                }
            }
        })
        engine.speak(content, TextToSpeech.QUEUE_FLUSH, null, "kairo-utt")
    }

    private fun stopSpeaking() {
        tts?.stop()
        speaking = false
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

    // ---------- Chat ----------

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

    private fun sendMessage(fromVoice: Boolean) {
        if (busy) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val token = (prefs.getString("token", "") ?: "").trim()
        if (token.isBlank()) {
            showSettings()
            return
        }
        val base = (prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL).ifBlank { DEFAULT_URL }

        stopSpeaking()
        note = null
        input.setText("")
        history.add("user" to text)
        addBubble("user", text)
        busy = true
        refreshUi()

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
                busy = false
                refreshUi()
                if (result.first && fromVoice) speak(result.second)
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
