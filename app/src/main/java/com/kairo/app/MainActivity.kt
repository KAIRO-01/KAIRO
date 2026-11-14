package com.kairo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        const val DEFAULT_URL = "https://kairo-backend-uyp9.onrender.com"
        const val REQ_MIC = 1
        const val REQ_WAKE = 2
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
    private lateinit var wakeBtn: TextView

    private var busy = false
    private var listening = false
    private var speaking = false
    private var note: String? = null
    private var hindi = true

    private var wakeOn = false
    private var resumed = false
    private var listenAfter = false
    private var wake: WakeWordEngine? = null

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
        wakeBtn = TextView(this).apply {
            text = "WAKE OFF"
            textSize = 13f
            setTextColor(Color.parseColor("#2979FF"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { toggleWake() }
        }
        bar.addView(wakeBtn)
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
            setOnClickListener { sendMessage(false) }
        }
        row.addView(sendBtn)
        root.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        setContentView(root)

        tts = TextToSpeech(this) { s -> ttsReady = (s == TextToSpeech.SUCCESS) }

        wake = WakeWordEngine(
            this,
            onWake = { runOnUiThread { onWakeDetected() } },
            onStatus = { m ->
                runOnUiThread {
                    note = m
                    refreshUi()
                }
            }
        )

        addBubble("assistant", "Namaste! Main KAIRO hoon. MIC dabake bolo, likh ke poochho, ya WAKE ON karke 'Hey KAIRO' bolo.")
        if ((prefs.getString("token", "") ?: "").isBlank()) showSettings()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateWake()
    }

    override fun onPause() {
        resumed = false
        updateWake()
        super.onPause()
    }

    override fun onDestroy() {
        wake?.stop()
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
        updateWake()
    }

    // ---------- Wake word ----------

    private fun toggleWake() {
        if (!wakeOn) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_WAKE)
                return
            }
            wakeOn = true
            note = null
        } else {
            wakeOn = false
            note = "Wake word band hai"
        }
        wakeBtn.text = if (wakeOn) "WAKE ON" else "WAKE OFF"
        refreshUi()
    }

    private fun updateWake() {
        val engine = wake ?: return
        val desired = wakeOn && resumed && !listening && !speaking && !busy
        if (desired && !engine.isActive) {
            engine.start()
        } else if (!desired && engine.isActive) {
            engine.stop()
        }
    }

    private fun onWakeDetected() {
        if (!wakeOn || listening || busy) return
        wake?.stop()
        note = null
        listenAfter = true
        val greeting = if (hindi) "\u091C\u0940, \u092C\u094B\u0932\u093F\u090F" else "Yes, I'm listening"
        if (!speak(greeting)) {
            listenAfter = false
            startListening()
        }
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
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_MIC) {
            if (granted) {
                startListening()
            } else {
                note = "Mic permission chahiye: Settings > Apps > KAIRO > Permissions mein allow karo"
                refreshUi()
            }
        } else if (requestCode == REQ_WAKE) {
            if (granted) {
                wakeOn = true
                note = null
                wakeBtn.text = "WAKE ON"
            } else {
                note = "Mic permission chahiye: Settings > Apps > KAIRO > Permissions mein allow karo"
            }
            refreshUi()
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

    private fun speak(content: String): Boolean {
        val engine = tts
        if (engine == null || !ttsReady) {
            note = "Text-to-speech abhi ready nahi hai"
            refreshUi()
            return false
        }
        val devanagari = content.any { it in '\u0900'..'\u097F' }
        val loc = Locale.forLanguageTag(if (devanagari) "hi-IN" else "en-IN")
        val res = engine.setLanguage(loc)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            note = "Is bhasha ki awaaz phone mein install nahi hai (Settings > Text-to-speech)"
            refreshUi()
            return false
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
                    afterSpeech()
                }
            }

            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    speaking = false
                    afterSpeech()
                }
            }
        })
        engine.speak(content, TextToSpeech.QUEUE_FLUSH, null, "kairo-utt")
        return true
    }

    private fun afterSpeech() {
        if (listenAfter) {
            listenAfter = false
            startListening()
        } else {
            refreshUi()
        }
    }

    private fun stopSpeaking() {
        tts?.stop()
        speaking = false
        listenAfter = false
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
                BackendClient.chat(base, token, snapshot)
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
}
