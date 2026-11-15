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
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
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
        private const val MAX_HISTORY = 20

        // Itni der kuch na bologe to session apne aap so jayega (0 = kabhi nahi)
        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

        @Volatile var ui: Ui? = null
        @Volatile var running = false
        @Volatile var wakeOn = false
        @Volatile var listening = false
        @Volatile var busy = false
        @Volatile var speaking = false
        @Volatile var note: String? = null
        @Volatile var historyLoaded = false

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

    private var session = false
    private var shownSession = false
    private var listenAfter = false
    private var engineBroken = false
    private var pendingStop = false
    private var speakStart = 0L
    private var lastActivity = 0L
    private var uttCounter = 0
    private var curUtt = ""

    private val stopWords = listOf("stop", "cancel", "enough", "quiet")
    private val heyRegex = Regex("\\b(?:(?:hey|hi|okay|ok)\\s+)?(?:cairo|kairo|kyro)\\b")

    private val forgetWords = listOf(
        "bhool jao", "bhul jao", "forget everything", "clear memory", "clear chat",
        "\u092D\u0942\u0932 \u091C\u093E\u0913"
    )

    // "Stop KAIRO" jaisa kuch: stop-jaisa shabd + KAIRO-jaisa shabd
    private val stopish = listOf(
        "stop", "sleep", "band karo", "bas karo", "so jao",
        "\u0938\u094D\u091F\u0949\u092A", "\u0938\u094D\u091F\u093E\u092A",
        "\u092C\u0902\u0926", "\u092C\u0938", "\u0938\u094B \u091C\u093E\u0913"
    )
    private val kairoish = listOf(
        "cairo", "kairo", "kyro", "cyro", "kayro",
        "\u0915\u093E\u092F\u0930\u094B", "\u0915\u0948\u0930\u094B", "\u0915\u094D\u0930\u093E\u092F\u094B",
        "\u0915\u0930\u093E\u092F\u094B", "\u0915\u093E\u0907\u0930\u094B", "\u0915\u0947\u0930\u094B",
        "\u0915\u094D\u092F\u093E\u0930\u094B"
    )
    private val bareStops = listOf(
        "stop", "bas", "ruko", "bas karo",
        "\u0938\u094D\u091F\u0949\u092A", "\u092C\u0938", "\u0930\u0941\u0915\u094B"
    )

    private val restartRunnable = Runnable {
        if (running && session && !listening && !busy && !speaking) startListening()
    }

    private val stopRunnable = Runnable {
        pendingStop = false
        if (running) {
            wake?.stop()
            if (session) {
                startListening()
            } else {
                note = "Ruk gaya."
                refresh()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (!historyLoaded) {
            loadHistory()
            historyLoaded = true
        }
        tts = TextToSpeech(this) { s -> ttsReady = (s == TextToSpeech.SUCCESS) }
        wake = WakeWordEngine(
            this,
            onHeard = { t -> handler.post { onHeard(t) } },
            onStatus = { m ->
                handler.post {
                    if (m.startsWith("Wake model error") || m.startsWith("Wake word start nahi")) {
                        engineBroken = true
                        wakeOn = false
                    }
                    note = if (m.isBlank()) null else m
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
                engineBroken = false
                session = false
                note = null
            }
            ACTION_WAKE_OFF -> {
                wakeOn = false
                goSleep("Wake word band hai", null)
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
        session = false
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
        val text = if (session) {
            "KAIRO sun raha hai. Rokne ke liye 'Stop KAIRO' bolo."
        } else {
            "KAIRO so raha hai. Jagane ke liye 'Hey KAIRO' bolo."
        }
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("KAIRO")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "BAND KARO", stop)
            .build()
    }

    private fun updateNotification() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            // ignore
        }
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

    // ---------- History (yaaddasht) ----------

    private fun trimHistory() {
        while (history.size > MAX_HISTORY) history.removeAt(0)
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
    }

    private fun saveHistory() {
        try {
            val arr = JSONArray()
            for ((r, c) in history) arr.put(JSONObject().put("r", r).put("c", c))
            getSharedPreferences("kairo", Context.MODE_PRIVATE)
                .edit().putString("history", arr.toString()).apply()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun loadHistory() {
        try {
            val s = getSharedPreferences("kairo", Context.MODE_PRIVATE)
                .getString("history", null) ?: return
            val arr = JSONArray(s)
            history.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                history.add(o.getString("r") to o.getString("c"))
            }
            trimHistory()
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------- State ----------

    private fun refresh() {
        updateWake()
        maybeStopIdle()
        if (running && shownSession != session) {
            shownSession = session
            updateNotification()
        }
        ui?.onState()
    }

    private fun updateWake() {
        val engine = wake ?: return
        val desired = running && !engineBroken &&
            ((wakeOn && !session) || speaking) && !listening && !busy
        if (desired && !engine.isActive) {
            engine.start()
        } else if (!desired && engine.isActive) {
            engine.stop()
        }
    }

    private fun maybeStopIdle() {
        if (running && !wakeOn && !listening && !busy && !speaking && !session && !listenAfter) {
            stopSelf()
        }
    }

    private fun addBubble(role: String, content: String) {
        bubbles.add(role to content)
        ui?.onBubble(role, content)
    }

    private fun isHindi(): Boolean =
        getSharedPreferences("kairo", Context.MODE_PRIVATE).getBoolean("hindi", true)

    private fun okAck(): String =
        if (isHindi()) "\u0920\u0940\u0915 \u0939\u0948" else "Okay"

    // ---------- Sleep / Wake / Stop ----------

    private fun goSleep(message: String?, ack: String?) {
        handler.removeCallbacks(restartRunnable)
        handler.removeCallbacks(stopRunnable)
        pendingStop = false
        session = false
        listenAfter = false
        recognizer?.destroy()
        recognizer = null
        listening = false
        wake?.stop()
        stopSpeaking()
        note = message
        if (ack != null) speak(ack)
        refresh()
    }

    private fun onHeard(t: String) {
        if (!running) return
        val kairo = t.contains("cairo") || t.contains("kairo") || t.contains("kyro")
        val stop = stopWords.any { t.contains(it) }

        if (pendingStop) {
            if (kairo) {
                handler.removeCallbacks(stopRunnable)
                pendingStop = false
                goSleep("Mic band. 'Hey KAIRO' bolo jab chahiye.", okAck())
            }
            return
        }

        if (speaking) {
            if (SystemClock.uptimeMillis() - speakStart < 900) return
            if (stop && kairo) {
                goSleep("Mic band. 'Hey KAIRO' bolo jab chahiye.", okAck())
            } else if (stop) {
                // pehle bolna rok do, thodi der dekho ki "kairo" bhi aata hai ya nahi
                pendingStop = true
                stopSpeaking()
                handler.postDelayed(stopRunnable, 900)
            } else if (heyRegex.containsMatchIn(t)) {
                wake?.stop()
                stopSpeaking()
                session = true
                lastActivity = SystemClock.uptimeMillis()
                startListening()
            }
            return
        }

        if (kairo && !stop) onWakeDetected()
    }

    private fun onWakeDetected() {
        if (!running || !wakeOn || session || listening || busy) return
        wake?.stop()
        note = null
        session = true
        lastActivity = SystemClock.uptimeMillis()
        // Silent wake: show listening state, without repeating a spoken acknowledgement.
        listenAfter = false
        startListening()
    }

    // ---------- Listening ----------

    private fun micPressed() {
        when {
            listening -> recognizer?.stopListening()
            busy -> {}
            else -> {
                stopSpeaking()
                session = false
                startListening()
            }
        }
    }

    private fun restartListening(delayMs: Long) {
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private fun startListening() {
        note = null
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            note = "Is phone mein speech recognition service nahi mili"
            session = false
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

    private fun isSleepCommand(s: String): Boolean {
        val t = s.lowercase(Locale.ROOT).trim()
        if (t.split(" ").size > 4) return false
        return stopish.any { t.contains(it) } && kairoish.any { t.contains(it) }
    }

    private fun isBareStop(s: String): Boolean {
        val t = s.lowercase(Locale.ROOT).trim()
        return t in bareStops
    }

    private fun stripWake(s: String): String {
        val r = s.replace(
            Regex("^(?:(?:hey|hi|okay|ok)\\s+)?(?:cairo|kairo|kyro|cyro)[,.]?\\s*", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        return if (r.isEmpty()) s else r
    }

    private fun errText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Samajh nahi aaya, dobara bolo"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Kuch suna nahi, MIC dabake dobara bolo"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech ke liye internet chahiye"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission allow karo"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy hai, thodi der baad try karo"
        else -> "Speech error code $error"
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
                if (session) {
                    restartListening(300)
                } else {
                    note = "Kuch samajh nahi aaya, dobara bolo"
                    refresh()
                }
                return
            }
            lastActivity = SystemClock.uptimeMillis()
            if (isSleepCommand(said)) {
                addBubble("user", said)
                goSleep("Mic band. 'Hey KAIRO' bolo jab chahiye.", okAck())
                return
            }
            if (isBareStop(said)) {
                if (session) restartListening(300) else refresh()
                return
            }
            sendMessage(stripWake(said), true)
        }

        override fun onError(error: Int) {
            listening = false
            if (session) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        val idle = SystemClock.uptimeMillis() - lastActivity
                        if (IDLE_TIMEOUT_MS > 0 && idle > IDLE_TIMEOUT_MS) {
                            goSleep("Kaafi der se kuch suna nahi, so gaya. 'Hey KAIRO' bolo.", null)
                        } else {
                            restartListening(400)
                        }
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartListening(1200)
                    SpeechRecognizer.ERROR_CLIENT -> {}
                    else -> goSleep(errText(error) + " (session band)", null)
                }
                return
            }
            if (error != SpeechRecognizer.ERROR_CLIENT) note = errText(error)
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
        uttCounter += 1
        val id = "kairo-$uttCounter"
        curUtt = id
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                handler.post {
                    if (utteranceId == curUtt) {
                        speaking = false
                        afterSpeech()
                    }
                }
            }

                   override fun onError(utteranceId: String?) {
                handler.post {
                    if (utteranceId == curUtt) {
                        speaking = false
                        afterSpeech()
                    }
                }
            }
        })
        speaking = true
        speakStart = SystemClock.uptimeMillis()
        lastActivity = speakStart
        val r = engine.speak(content, TextToSpeech.QUEUE_FLUSH, null, id)
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
        curUtt = ""
        tts?.stop()
        speaking = false
        listenAfter = false
    }

    // ---------- Chat ----------

    private fun afterReply(reply: String, speakIt: Boolean) {
        var spoke = false
        if (speakIt) {
            listenAfter = session
            spoke = speak(reply)
        }
        if (!spoke) {
            listenAfter = false
            if (session && !listening) {
                startListening()
                return
            }
        }
        refresh()
    }

    private fun localReply(text: String, reply: String, fromVoice: Boolean) {
        addBubble("user", text)
        addBubble("assistant", reply)
        note = null
        afterReply(reply, fromVoice)
    }

    private fun sendMessage(text: String, fromVoice: Boolean) {
        if (busy) return
        if (listening) {
            recognizer?.destroy()
            recognizer = null
            listening = false
        }

        val lower = text.lowercase(Locale.ROOT)
        if (forgetWords.any { lower.contains(it) }) {
            history.clear()
            saveHistory()
            localReply(text, "Theek hai, purani baatein bhool gaya.", fromVoice)
            return
        }

        val screenRequest = KairoScreenActions.summaryPrompt(text)
        if (screenRequest != null && !screenRequest.first) {
            localReply(text, screenRequest.second, fromVoice)
            return
        }
        val phoneReply = KairoScreenActions.localAction(this, text)
            ?: PhoneActions.handle(this, text, ui != null)
        if (phoneReply != null) {
            localReply(text, phoneReply, fromVoice)
            return
        }

        val prefs = getSharedPreferences("kairo", Context.MODE_PRIVATE)
        val token = (prefs.getString("token", "") ?: "").trim()
        if (token.isBlank()) {
            note = "Pehle Settings (gear) mein KAIRO_TOKEN daalo"
            session = false
            refresh()
            return
        }
        val base = (prefs.getString("url", MainActivity.DEFAULT_URL) ?: MainActivity.DEFAULT_URL)
            .ifBlank { MainActivity.DEFAULT_URL }

        stopSpeaking()
        note = null
        history.add("user" to text)
        trimHistory()
        saveHistory()
        addBubble("user", text)
        busy = true
        refresh()

        val snapshot = history.toMutableList()
        if (screenRequest?.first == true && snapshot.isNotEmpty()) {
            snapshot.removeAt(snapshot.lastIndex)
            // Only this user-requested AI call includes visible chat / notification text.
            // It is deliberately not written to the device conversation history.
            snapshot.add("user" to screenRequest.second)
        }
        if (PhoneState.wanted(text) && snapshot.isNotEmpty()) {
            val last = snapshot.removeAt(snapshot.size - 1)
            snapshot.add(last.first to (PhoneState.describe(this) + "\n" + last.second))
        }

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
                    trimHistory()
                } else if (history.isNotEmpty()) {
                    history.removeAt(history.size - 1)
                }
                saveHistory()
                addBubble("assistant", result.second)
                busy = false
                afterReply(result.second, result.first && fromVoice)
            }
        }
    }
}
