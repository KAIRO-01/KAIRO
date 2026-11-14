package com.kairo.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import java.util.Locale

object PhoneActions {

    private const val NEED_OVERLAY =
        "Background mein ye karne ke liye phone Settings > Apps > KAIRO mein 'Display over other apps' allow karo."

    private val openWords = listOf(
        "open", "kholo", "khol", "chalao", "launch", "start",
        "\u0916\u094B\u0932\u094B", "\u0916\u094B\u0932", "\u091A\u0932\u093E\u0913", "\u0913\u092A\u0928"
    )

    private val fillers = listOf(
        "karo", "kardo", "kar do", "do", "please", "app", "ko", "ka", "the", "mera", "meri",
        "\u0915\u0930\u094B", "\u0915\u0930 \u0926\u094B", "\u0915\u094B", "\u0915\u093E", "\u0910\u092A"
    )

    private val aliases = mapOf(
        "\u0935\u094D\u0939\u093E\u091F\u094D\u0938\u090F\u092A" to "whatsapp",
        "\u0935\u094D\u0939\u093E\u091F\u094D\u0938\u0905\u092A" to "whatsapp",
        "\u092F\u0942\u091F\u094D\u092F\u0942\u092C" to "youtube",
        "\u0915\u0948\u092E\u0930\u093E" to "camera",
        "\u0917\u0942\u0917\u0932" to "google",
        "\u0915\u094D\u0930\u094B\u092E" to "chrome",
        "\u0907\u0902\u0938\u094D\u091F\u093E\u0917\u094D\u0930\u093E\u092E" to "instagram",
        "\u092E\u0948\u092A\u094D\u0938" to "maps",
        "\u091C\u0940\u092E\u0947\u0932" to "gmail",
        "\u0917\u0948\u0932\u0930\u0940" to "gallery",
        "\u0915\u0948\u0932\u0915\u0941\u0932\u0947\u091F\u0930" to "calculator",
        "\u0915\u0949\u0932\u0947\u0902\u0921\u0930" to "calendar",
        "\u0915\u0948\u0932\u0947\u0902\u0921\u0930" to "calendar",
        "\u0938\u094D\u0928\u0948\u092A\u091A\u0948\u091F" to "snapchat"
    )

    private fun has(t: String, vararg w: String): Boolean = w.any { t.contains(it) }

    fun handle(ctx: Context, raw: String, appVisible: Boolean): String? {
        val t = raw.lowercase(Locale.ROOT).trim()
        if (t.isEmpty()) return null

        volume(ctx, t)?.let { return it }

        val timerWord = has(t, "timer", "\u091F\u093E\u0907\u092E\u0930")
        val alarmWord = has(t, "alarm", "\u0905\u0932\u093E\u0930\u094D\u092E")
        val secs = parseSeconds(t)

        if ((timerWord || alarmWord) && secs > 0 &&
            (timerWord || has(t, "baad", "\u092C\u093E\u0926", "mein", "\u092E\u0947\u0902", " in "))
        ) {
            if (blocked(ctx, appVisible)) return NEED_OVERLAY
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "KAIRO")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            return if (launch(ctx, i)) "Theek hai, " + describe(secs) + " ka timer laga diya."
            else "Timer set nahi ho paaya."
        }

        if (timerWord) {
            return "Kitne minute ka timer? Jaise bolo: 10 minute ka timer lagao."
        }

        if (alarmWord) {
            if (has(t, "band", "cancel", "delete", "hatao", "\u092C\u0902\u0926", "\u0939\u091F\u093E\u0913")) {
                return "Alarm band karna abhi nahi aata. Clock app mein se band kar lo."
            }
            val time = parseTime(t)
                ?: return "Kis time ka alarm? Digits mein bolo, jaise: 7 baje ka alarm lagao."
            if (blocked(ctx, appVisible)) return NEED_OVERLAY
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, time.first)
                .putExtra(AlarmClock.EXTRA_MINUTES, time.second)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "KAIRO")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            val label = String.format(Locale.US, "%02d:%02d", time.first, time.second)
            return if (launch(ctx, i)) "Theek hai, $label ka alarm laga diya."
            else "Alarm set nahi ho paaya."
        }

        val wantsOpen = has(t, *openWords.toTypedArray())

        if (wantsOpen && has(t, "wifi", "wi-fi", "\u0935\u093E\u0908\u092B\u093E\u0908", "\u0935\u093E\u0908-\u092B\u093E\u0908")) {
            if (blocked(ctx, appVisible)) return NEED_OVERLAY
            return if (launch(ctx, Intent(Settings.ACTION_WIFI_SETTINGS))) "WiFi settings khol di."
            else "Settings nahi khul paayi."
        }
        if (wantsOpen && has(t, "bluetooth", "\u092C\u094D\u0932\u0942\u091F\u0942\u0925")) {
            if (blocked(ctx, appVisible)) return NEED_OVERLAY
            return if (launch(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) "Bluetooth settings khol di."
            else "Settings nahi khul paayi."
        }
        if (wantsOpen && has(t, "settings", "\u0938\u0947\u091F\u093F\u0902\u0917")) {
            if (blocked(ctx, appVisible)) return NEED_OVERLAY
            return if (launch(ctx, Intent(Settings.ACTION_SETTINGS))) "Settings khol di."
            else "Settings nahi khul paayi."
        }

        if (wantsOpen) {
            var name = t
            for (w in openWords) name = name.replace(w, " ")
            for (w in fillers) name = name.replace(Regex("(^|\\s)" + Regex.escape(w) + "(?=\\s|$)"), " ")
            name = name.trim().replace(Regex("\\s+"), " ")
            if (name.isEmpty()) return null
            aliases[name.replace(" ", "")]?.let { name = it }
            return openApp(ctx, name, appVisible)
        }

        return null
    }

    // ---------- helpers ----------

    private fun blocked(ctx: Context, appVisible: Boolean): Boolean =
        Build.VERSION.SDK_INT >= 29 && !appVisible && !Settings.canDrawOverlays(ctx)

    private fun launch(ctx: Context, i: Intent): Boolean {
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun openApp(ctx: Context, name: String, appVisible: Boolean): String? {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try {
            pm.queryIntentActivities(main, 0)
        } catch (e: Exception) {
            emptyList()
        }
        var bestPkg: String? = null
        var bestLabel = ""
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString().lowercase(Locale.ROOT)
            if (label.length < 3) continue
            if (label == name || label.contains(name) || name.contains(label)) {
                if (bestPkg == null || label.length < bestLabel.length) {
                    bestPkg = ri.activityInfo.packageName
                    bestLabel = label
                }
            }
        }
        if (bestPkg == null) return "'$name' naam ka app mujhe nahi mila."
        if (blocked(ctx, appVisible)) return NEED_OVERLAY
        val i = pm.getLaunchIntentForPackage(bestPkg) ?: return "Ye app khul nahi paaya."
        return if (launch(ctx, i)) "$bestLabel khol diya." else "Ye app khul nahi paaya."
    }

    private fun volume(ctx: Context, t: String): String? {
        if (!has(t, "volume", "\u0935\u0949\u0932\u094D\u092F\u0942\u092E", "\u0906\u0935\u093E\u091C", "\u0906\u0935\u093E\u095B", "awaaz", "awaz")) {
            if (has(t, "mute", "\u092E\u094D\u092F\u0942\u091F")) return setVolume(ctx, AudioManager.ADJUST_MUTE, "Mute kar diya.")
            return null
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when {
            has(t, "mute", "\u092E\u094D\u092F\u0942\u091F") -> setVolume(ctx, AudioManager.ADJUST_MUTE, "Mute kar diya.")
            has(t, "badha", "increase", " up", "zyada", "jyada", "full", "\u092C\u0922\u093C\u093E", "\u092C\u0921\u093C\u093E", "\u092C\u0922\u093E", "\u091C\u093C\u094D\u092F\u093E\u0926\u093E", "\u091C\u094D\u092F\u093E\u0926\u093E") -> {
                for (n in 1..3) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, if (n == 3) AudioManager.FLAG_SHOW_UI else 0)
                "Volume badha diya."
            }
            has(t, "kam", "ghata", "decrease", "down", "low", "\u0915\u092E", "\u0918\u091F\u093E") -> {
                for (n in 1..3) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, if (n == 3) AudioManager.FLAG_SHOW_UI else 0)
                "Volume kam kar diya."
            }
            else -> "Volume badhana hai ya kam karna? Bolo: volume badhao ya volume kam karo."
        }
    }

    private fun setVolume(ctx: Context, dir: Int, reply: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return reply
    }

    private fun parseSeconds(t: String): Int {
        fun num(re: Regex): Int = re.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val h = num(Regex("(\\d+)\\s*(hour|hours|hr|ghanta|ghante|\u0918\u0902\u091F\u093E|\u0918\u0902\u091F\u0947|\u0918\u0923\u094D\u091F\u0947)"))
        val m = num(Regex("(\\d+)\\s*(minute|minutes|min|mins|\u092E\u093F\u0928\u091F)"))
        val s = num(Regex("(\\d+)\\s*(second|seconds|sec|secs|\u0938\u0947\u0915\u0902\u0921|\u0938\u0947\u0915\u0947\u0902\u0921)"))
        return h * 3600 + m * 60 + s
    }

    private fun describe(secs: Int): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts.add("$h ghante")
        if (m > 0) parts.add("$m minute")
        if (s > 0) parts.add("$s second")
        return parts.joinToString(" ")
    }

    private fun parseTime(t: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})(?:[:.](\\d{2}))?").find(t) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h > 23 || min > 59) return null
        val pmWord = Regex("\\bpm\\b|p\\.m").containsMatchIn(t) ||
            has(t, "shaam", "sham", "raat", "dopahar", "evening", "night", "afternoon",
                "\u0936\u093E\u092E", "\u0930\u093E\u0924", "\u0926\u094B\u092A\u0939\u0930")
        val amWord = Regex("\\bam\\b|a\\.m").containsMatchIn(t) ||
            has(t, "subah", "morning", "\u0938\u0941\u092C\u0939")
        val night = has(t, "raat", "night", "\u0930\u093E\u0924")
        if (pmWord && h in 1..11) h += 12
        if (night && h == 12) h = 0
        if (amWord && h == 12) h = 0
        return Pair(h, min)
    }
}
