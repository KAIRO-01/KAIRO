package com.kairo.app

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale

/**
 * Strict offline wake/stop detector.
 * Never act on partial guesses; only exact final Vosk phrases are accepted.
 * This is phrase recognition, NOT biometric speaker identification.
 */
class WakeWordEngine(
    private val context: Context,
    private val onHeard: (String) -> Unit,
    private val onStatus: (String) -> Unit
) : org.vosk.android.RecognitionListener {

    private var model: Model? = null
    private var service: SpeechService? = null
    private var loading = false
    private var wantRunning = false
    private var lastAcceptedMs = 0L

    private val wakePhrases = setOf(
        "hey cairo", "hi cairo", "hey kairo", "hi kairo",
        "cairo", "kairo"
    )
    private val stopPhrases = setOf(
        "stop", "stop cairo", "cairo stop", "stop kairo", "kairo stop"
    )

    val isActive: Boolean
        get() = wantRunning

    fun start() {
        wantRunning = true
        val m = model
        if (m != null) {
            begin(m)
            return
        }
        if (loading) return
        loading = true
        onStatus("Wake word model load ho raha hai...")
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
            val recognizer = Recognizer(
                m, 16000.0f,
                "[\"hey cairo\", \"hi cairo\", \"cairo\", \"hey kairo\", \"hi kairo\", \"kairo\", " +
                    "\"stop\", \"stop cairo\", \"cairo stop\", \"stop kairo\", \"kairo stop\", \"[unk]\"]"
            )
            recognizer.setWords(true) // finalized word confidence scores
            val speech = SpeechService(recognizer, 16000.0f)
            service = speech
            speech.startListening(this)
            onStatus("")
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
        } catch (_: Exception) { }
        service = null
    }

    private fun checkFinal(hypothesis: String?) {
        if (!wantRunning || hypothesis.isNullOrBlank()) return
        val data = try { JSONObject(hypothesis) } catch (_: Exception) { return }
        val phrase = data.optString("text")
            .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
        if (phrase !in wakePhrases && phrase !in stopPhrases) return

        val words = data.optJSONArray("result")
        var confidenceSum = 0.0
        var count = 0
        if (words != null) {
            for (index in 0 until words.length()) {
                val word = words.optJSONObject(index) ?: continue
                if (word.has("conf")) {
                    confidenceSum += word.optDouble("conf", 0.0)
                    count++
                }
            }
        }
        // One-word wake words are especially vulnerable to false triggers.
        // Unknown confidence is not treated as evidence that a word was spoken.
        if (count == 0) return
        val meanConfidence = confidenceSum / count
        val threshold = when {
            phrase in stopPhrases -> 0.86
            phrase == "cairo" || phrase == "kairo" -> 0.90
            else -> 0.76
        }
        if (meanConfidence < threshold) return

        val now = SystemClock.uptimeMillis()
        if (now - lastAcceptedMs < 1400L) return
        lastAcceptedMs = now
        onHeard(if (phrase in stopPhrases) "stop" else if (phrase.contains(" ")) "wake" else "bare_wake")
    }

    // Partial Vosk results are NOT reliable enough for safety-critical stop/wake.
    override fun onPartialResult(hypothesis: String?) = Unit
    override fun onResult(hypothesis: String?) = checkFinal(hypothesis)
    override fun onFinalResult(hypothesis: String?) = checkFinal(hypothesis)

    override fun onError(exception: Exception?) {
        onStatus("Wake word error: " + exception?.message)
    }

    override fun onTimeout() = Unit
}
