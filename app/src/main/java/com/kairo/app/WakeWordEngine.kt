package com.kairo.app

import android.content.Context
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/** Offline wake word listener ("Hey Cairo" sounds like "Hey KAIRO"), built on Vosk. */
class WakeWordEngine(
    private val context: Context,
    private val onWake: () -> Unit,
    private val onStatus: (String) -> Unit
) : org.vosk.android.RecognitionListener {

    private var model: Model? = null
    private var service: SpeechService? = null
    private var loading = false
    private var wantRunning = false

    @Volatile
    private var triggered = false

    val isActive: Boolean
        get() = wantRunning

    fun start() {
        wantRunning = true
        triggered = false
        val m = model
        if (m != null) {
            begin(m)
            return
        }
        if (loading) return
        loading = true
        onStatus("Wake word model load ho raha hai (pehli baar 30-60 second lag sakte hain)...")
        StorageService.unpack(
            context, "model-en-us", "model",
            StorageService.Callback<Model> { loaded ->
                loading = false
                model = loaded
                if (wantRunning) begin(loaded)
            },
            StorageService.Callback<java.io.IOException> { e ->
                loading = false
                wantRunning = false
                onStatus("Wake model error: " + e.message)
            }
        )
    }

    private fun begin(m: Model) {
        try {
            service?.shutdown()
            val rec = Recognizer(
                m, 16000.0f,
                "[\"hey cairo\", \"hi cairo\", \"okay cairo\", \"cairo\", \"[unk]\"]"
            )
            val s = SpeechService(rec, 16000.0f)
            service = s
            s.startListening(this)
            onStatus("Wake word ON: bolo 'Hey KAIRO' (Hey Cairo)")
        } catch (e: Exception) {
            wantRunning = false
            onStatus("Wake word start nahi hua: " + e.message)
        }
    }

    fun stop() {
        wantRunning = false
        try {
            service?.stop()
            service?.shutdown()
        } catch (e: Exception) {
            // ignore
        }
        service = null
    }

    private fun check(h: String?) {
        if (h == null || triggered) return
        if (h.contains("cairo", ignoreCase = true)) {
            triggered = true
            onWake()
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        check(hypothesis)
    }

    override fun onResult(hypothesis: String?) {
        check(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        onStatus("Wake word error: " + exception?.message)
    }

    override fun onTimeout() {}
}
