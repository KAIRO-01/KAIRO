package com.kairo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.concurrent.thread

class KairoService : Service() {

    interface Ui {
        fun onBubble(role: String, text: String)
        fun onState()
        fun onPartial(text: String)
    }

    companion object {
        const val ACTION_STOP = "kairo.STOP"
        const val ACTION_WAKE_ON = "kairo.WAKE_ON"
        const val ACTION_WAKE_OFF = "kairo.WAKE_OFF"
        const val ACTION_MIC = "kairo.MIC"
        const val ACTION_SEND = "kairo.SEND"
        private const val CHANNEL = "kairo_listen"
        private const val NOTIF_ID = 7

        @Volatile var ui: Ui? = null
        @Volatile var running = false
        @Volatile var wakeOn = false
        @Volatile var listening = false
        @Volatile var busy = false
        @Volatile var speaking = false
        @Volatile var note: String? = null

        val history = mutableListOf<Pair<String, String>>()
        val bubbles = mutableListOf<Pair<String, String>>()

        fun send(ctx: Context, action: String, text: String? = null) {
            val i = Intent(ctx, KairoService::class.java).setAction(action)
            if (text != null) i.putExtra("text", text)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wake: WakeWordEngine? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var listenAfter = false
    private var conversation = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this) { s -> ttsReady = (s == TextToSpeech.SUCCESS) }
        wake = WakeWordEngine(
            this,
            onWake = { handler.post { onWakeDetected() } },
            onStatus = { m ->
                handler.post {
                    note = m
                    refresh()
                }
            }
        )
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kairo:listen").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            wakeOn = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startFg()) {
            ui?.onState()
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        when (action) {
            ACTION_WAKE_ON -> {
                wakeOn = true
                note = null
            }
            ACTION_WAKE_OFF -> {
                wakeOn = false
                note = "Wake word band hai"
            }
            ACTION_MIC -> micPressed()
            ACTION_SEND -> {
                val t = intent?.getStringExtra("text")
                if (!t.isNullOrBlank()) sendMessage(t, false)
            }
        }
        refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        wakeOn = false
        listening = false
        busy = false
        speaking = false
        handler.removeCallbacksAndMessages(null)
        wake?.stop()
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        ui?.onState()
        super.onDestroy()
    }

    // ---------- Notification ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "KAIRO listening", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, KairoService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("KAIRO")
            .setContentText("KAIRO active hai. 'Hey KAIRO' bolo.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "BAND KARO", stop)
            .build()
    }

    private fun startFg(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(
                    NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
            true
        } catch (e: Exception) {
            note = "Background service start nahi hui: " + e.message
            false
        }
    }

    // ---------- State ----------

    private fun refresh() {
        updateWake()
        maybeStopIdle()
        ui?.onState()
    }

    private fun updateWake() {
        val engine = wake ?: return
        val desired = running && wakeOn && !listening && !speaking && !busy
        if (desired && !engine.isActive) {
            engine.start()
        } else if (!desired && engine.isActive) {
            engine.stop()
        }
    }

    private fun maybeStopIdle() {
        if (running && !wakeOn && !listening && !busy && !speaking && !conversation && !listenAfter) {
            stopSelf()
        }
    }

    private fun addBubble(role: String, content: String) {
        bubbles.add(role to content)
        ui?.onBubble(role, content)
    }

    private fun isHindi(): Boolean =
        getSharedPreferences("kairo", Context.MODE_PRIVATE).getBoolean("hindi", true)

    // ---------- Wake ----------

    private fun onWakeDetected() {
        if (!running || !wakeOn || listening || busy) return
        wake?.stop()
        note = null
        conversation = true
        listenAfter = true
        val greeting = if (isHindi()) "\u091C\u0940, \u092C\u094B\u0932\u093F\u090F" else "Yes, I'm listening"
        if (!speak(greeting)) {
            listenAfter = false
            startListening()
        } else {
            refresh()
        }
    }

    // ---------- Listening ----------

    private fun micPressed() {
        when {
            listening -> recognizer?.stopListening()
            busy -> {}
            else -> {
                stopSpeaking()
                conversation = false
                startListening()
            }
        }
    }

    private fun startListening() {
        note = null
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            note = "Is phone mein speech recognition service nahi mili"
            conversation = false
            refresh()
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(listener)
        recognizer = r

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (isHindi()) "hi-IN" else "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        listening = true
        refresh()
        r.startListening(intent)
    }

    private fun isEndWord(s: String): Boolean {
        val t = s.lowercase(Locale.ROOT).trim()
        return t in listOf(
            "stop", "bye", "bas", "thanks", "thank you", "band karo",
            "\u092C\u0938", "\u0930\u0941\u0915\u094B", "\u0927\u0928\u094D\u092F\u0935\u093E\u0926"
        )
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
            if (!p.isNullOrBlank()) ui?.onPartial(p)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val said = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (said.isEmpty()) {
                conversation = false
                note = "Kuch samajh nahi aaya, dobara bolo"
                refresh()
                return
            }
            if (isEndWord(said)) {
                addBubble("user", said)
                conversation = false
                note = "Theek hai. Wapas 'Hey KAIRO' ka intezaar..."
                refresh()
                return
            }
            sendMessage(said, true)
        }

        override fun onError(error: Int) {
            listening = false
            note = if (conversation) {
                null
            } else {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Samajh nahi aaya, dobara bolo"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Kuch suna nahi, MIC dabake dobara bolo"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech ke liye internet chahiye"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission allow karo"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy hai, thodi der baad try karo"
                    else -> "Speech error code $error"
                }
            }
            conversation = false
            listenAfter = false
            refresh()
        }
    }

    // ---------- Speaking ----------

    private fun speak(content: String): Boolean {
        val engine = tts
        if (engine == null || !ttsReady) {
            note = "Text-to-speech abhi ready nahi hai"
            return false
        }
        val devanagari = content.any { it in '\u0900'..'\u097F' }
        val loc = Locale.forLanguageTag(if (devanagari) "hi-IN" else "en-IN")
        val res = engine.setLanguage(loc)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            note = "Is bhasha ki awaaz phone mein install nahi hai (Settings > Text-to-speech)"
            return false
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                handler.post {
                    speaking = false
                    afterSpeech()
                }
            }

            override fun onError(utteranceId: String?) {
                handler.post {
                    speaking = false
                    afterSpeech()
                }
            }
        })
        speaking = true
        val r = engine.speak(content, TextToSpeech.QUEUE_FLUSH, null, "kairo-utt")
        if (r != TextToSpeech.SUCCESS) {
            speaking = false
            return false
        }
        return true
    }

    private fun afterSpeech() {
        if (!running) return
        if (listenAfter) {
            listenAfter = false
            startListening()
        } else {
            refresh()
        }
    }

    private fun stopSpeaking() {
        tts?.stop()
        speaking = false
        listenAfter = false
    }

    // ---------- Chat ----------

    private fun sendMessage(text: String, fromVoice: Boolean) {
        if (busy) return
        val prefs = getSharedPreferences("kairo", Context.MODE_PRIVATE)
        val token = (prefs.getString("token", "") ?: "").trim()
        if (token.isBlank()) {
            note = "Pehle Settings (gear) mein KAIRO_TOKEN daalo"
            conversation = false
            refresh()
            return
        }
        val base = (prefs.getString("url", MainActivity.DEFAULT_URL) ?: MainActivity.DEFAULT_URL)
            .ifBlank { MainActivity.DEFAULT_URL }

        stopSpeaking()
        note = null
        history.add("user" to text)
        addBubble("user", text)
        busy = true
        refresh()

        val snapshot = history.toList()
        thread {
            val result = try {
                BackendClient.chat(base, token, snapshot)
            } catch (e: Exception) {
                Pair(false, "Network error (" + e.javaClass.simpleName + "). Internet check karo aur dobara try karo.")
            }
            handler.post {
                if (!running) return@post
                if (result.first) {
                    history.add("assistant" to result.second)
                } else {
                    history.removeAt(history.size - 1)
                }
                addBubble("assistant", result.second)
                busy = false
                var spoke = false
                if (result.first && fromVoice) {
                    listenAfter = conversation
                    spoke = speak(result.second)
                }
                if (!spoke) {
                    listenAfter = false
                    if (result.first && fromVoice && conversation) {
                        startListening()
                        return@post
                    }
                    conversation = false
                }
                refresh()
            }
        }
    }
}
