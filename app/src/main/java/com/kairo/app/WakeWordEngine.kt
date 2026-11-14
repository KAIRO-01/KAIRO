package com.kairo.app

import android.content.Context
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/** Offline listener for "Hey Cairo" (sounds like "Hey KAIRO") and "stop", built on Vosk. */
class WakeWordEngine(
    private val context: Context,
    private val onWake: () -> Unit,
    private val onStop: () -> Unit,
    private val onStatus: (String) -> Unit
) : org.vosk.android.RecognitionListener {

    private var model: Model? = null
    private var service: SpeechService? = null
    private var loading = false
    private var wantRunning = false

    @Volatile
    private var suppress = false

    val isActive: Boolean
        get() = wantRunning

    fun start() {
        wantRunning = true
        suppress = false
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
            val rec = Recognizer(m, 16000.0f, "[\"hey cairo\", \"stop\", \"[unk]\"]")
            val s = SpeechService(rec, 16000.0f)
            service = s
            s.startListening(this)
            onStatus("READY")
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

    private fun check(h: String?, final: Boolean) {
        if (h == null) return
        if (suppress) {
            if (final) suppress = false
            return
        }
        val hit = when {
            h.contains("cairo", ignoreCase = true) -> 1
            h.contains("stop", ignoreCase = true) -> 2
            else -> 0
        }
        if (hit == 0) return
        suppress = !final
        if (hit == 1) onWake() else onStop()
    }

    override fun onPartialResult(hypothesis: String?) {
        check(hypothesis, false)
    }

    override fun onResult(hypothesis: String?) {
        check(hypothesis, true)
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        onStatus("Wake word error: " + exception?.message)
    }

    override fun onTimeout() {}
}
