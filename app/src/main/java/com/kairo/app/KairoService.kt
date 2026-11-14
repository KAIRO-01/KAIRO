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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.concurrent.thread

class KairoService : Service() {

    companion object {
        const val CHANNEL = "kairo_listen"
        const val NOTIF_ID = 11
        const val ACTION_LISTEN = "com.kairo.app.LISTEN"

        @Volatile
        var running = false

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, KairoService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KairoService::class.java))
        }

        fun listenNow(ctx: Context) {
            ctx.startService(Intent(ctx, KairoService::class.java).setAction(ACTION_LISTEN))
        }
    }

    private enum class St { IDLE, LISTENING, THINKING, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    private var wake: WakeWordEngine? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var state = St.IDLE
    private var reqId = 0
    private var listenAfterSpeech = false
    private var uttSeq = 0
    private var curUtt = ""

    private fun hindi(): Boolean =
        getSharedPreferences("kairo", Context.MODE_PRIVATE).getBoolean("hindi", true)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "KAIRO listening", NotificationManager.IMPORTANCE_LOW)
        )
        tts = TextToSpeech(this) { s -> ttsReady = (s == TextToSpeech.SUCCESS) }
        wake = WakeWordEngine(
            this,
            onWake = { main.post { onWake() } },
            onStop = { main.post { onStopWord() } },
            onStatus = { m ->
                main.post {
                    if (m == "READY") {
                        if (state == St.IDLE) KairoLog.setStatus("Hey KAIRO bolo... (sun raha hoon)")
                    } else {
                        KairoLog.setStatus(m)
                    }
                }
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            goForeground()
        } catch (e: Exception) {
            KairoLog.setStatus("Service start nahi hua: " + e.message)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_LISTEN) {
            main.post { startListening() }
        } else {
            main.post { goIdle() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        wake?.stop()
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        KairoLog.mode = 0
        KairoLog.setStatus("KAIRO band hai. LISTEN ON dabao.")
        super.onDestroy()
    }

    private fun goForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("KAIRO sun raha hai")
            .setContentText("Bolo: Hey KAIRO")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------- State helpers ----------

    private fun setMode(s: St, text: String) {
        state = s
        KairoLog.mode = when (s) {
            St.IDLE -> 0
            St.LISTENING -> 1
            St.THINKING -> 2
            St.SPEAKING -> 3
        }
        KairoLog.setStatus(text)
    }

    private fun ensureWake() {
        val w = wake ?: return
        if (!w.isActive) w.start()
    }

    private fun goIdle() {
        setMode(St.IDLE, "Hey KAIRO bolo... (sun raha hoon)")
        ensureWake()
    }

    // ---------- Wake word and stop ----------

    private fun onWake() {
        if (state == St.LISTENING) return
        tts?.stop()
        reqId++
        wake?.stop()
        listenAfterSpeech = true
        setMode(St.SPEAKING, "KAIRO: ji, boliye")
        val greeting = if (hindi()) "\u091C\u0940, \u092C\u094B\u0932\u093F\u090F" else "Yes, I'm listening"
        if (!speak(greeting)) {
            listenAfterSpeech = false
            startListening()
        }
    }

    private fun onStopWord() {
        if (state == St.IDLE || state == St.LISTENING) return
        tts?.stop()
        reqId++
        listenAfterSpeech = false
        goIdle()
    }

    // ---------- Listening ----------

    private fun startListening() {
        wake?.stop()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            goIdle()
            KairoLog.setStatus("Speech recognition service nahi mili")
            return
        }
        setMode(St.LISTENING, "Sun raha hoon... boliye")
        main.postDelayed({
            if (state != St.LISTENING) return@postDelayed
            recognizer?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(listener)
            recognizer = r
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (hindi()) "hi-IN" else "en-IN")
            r.startListening(i)
        }, 400)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val said = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (said.isEmpty()) {
                goIdle()
                KairoLog.setStatus("Kuch samajh nahi aaya. 'Hey KAIRO' bolke dobara try karo.")
            } else {
                handleSpeech(said)
            }
        }

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "Samajh nahi aaya. 'Hey KAIRO' bolke dobara try karo."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Kuch suna nahi. 'Hey KAIRO' bolke dobara try karo."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Speech ke liye internet chahiye"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission allow karo"
                else -> "Speech error code $error"
            }
            goIdle()
            KairoLog.setStatus(msg)
        }
    }

    // ---------- Thinking and speaking ----------

    private fun handleSpeech(text: String) {
        if (PhoneCommands.isStop(text)) {
            goIdle()
            return
        }
        KairoLog.add("user", text)
        setMode(St.THINKING, "KAIRO soch raha hai... ('stop' bolke rok sakte ho)")
        ensureWake()
        val id = ++reqId
        thread {
            val r = Brain.respond(applicationContext, text)
            main.post {
                if (id != reqId) return@post
                KairoLog.add("assistant", r.second)
                setMode(St.SPEAKING, "KAIRO bol raha hai... ('stop' bolke rok sakte ho)")
                ensureWake()
                if (!speak(r.second)) goIdle()
            }
        }
    }

    private val utterListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            if (utteranceId == curUtt) main.post { afterSpeech() }
        }

        override fun onError(utteranceId: String?) {
            if (utteranceId == curUtt) main.post { afterSpeech() }
        }
    }

    private fun speak(content: String): Boolean {
        val engine = tts
        if (engine == null || !ttsReady) return false
        val dev = content.any { it in '\u0900'..'\u097F' }
        val res = engine.setLanguage(Locale.forLanguageTag(if (dev) "hi-IN" else "en-IN"))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) return false
        engine.setOnUtteranceProgressListener(utterListener)
        curUtt = "u" + (++uttSeq)
        engine.speak(content, TextToSpeech.QUEUE_FLUSH, null, curUtt)
        return true
    }

    private fun afterSpeech() {
        if (state != St.SPEAKING) return
        if (listenAfterSpeech) {
            listenAfterSpeech = false
            startListening()
        } else {
            goIdle()
        }
    }
}
