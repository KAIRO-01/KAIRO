package com.kairo.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhoneState {

    private val keys = listOf(
        "phone", "chal raha", "chal rha", "battery", "charge", "music", "gaana", "gana", "song",
        "app", "kaunsa", "khula",
        "\u092B\u094B\u0928", "\u092B\u093C\u094B\u0928", "\u091A\u0932 \u0930\u0939\u093E",
        "\u092C\u0948\u091F\u0930\u0940", "\u0910\u092A", "\u0917\u093E\u0928\u093E"
    )

    fun wanted(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT)
        return keys.any { t.contains(it) }
    }

    fun describe(ctx: Context): String {
        val parts = mutableListOf<String>()
        parts.add("time " + SimpleDateFormat("EEE dd MMM, hh:mm a", Locale.ENGLISH).format(Date()))

        try {
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) {
                val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                if (level >= 0 && scale > 0) {
                    val extra = if (charging) " (charging)" else ""
                    parts.add("battery " + (level * 100 / scale) + "%" + extra)
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            parts.add(if (am.isMusicActive) "music/audio chal raha hai" else "koi music/audio nahi chal raha")
        } catch (e: Exception) {
            // ignore
        }

        if (hasUsageAccess(ctx)) {
            val app = foregroundApp(ctx)
            parts.add(if (app != null) "abhi screen par app: $app" else "abhi koi app khula nahi (home screen)")
        } else {
            parts.add("khula hua app pata nahi (Usage access off hai)")
        }

        return "[Phone ki live info, sirf zaroorat ho to use karo: " + parts.joinToString("; ") + "]"
    }

    private fun hasUsageAccess(ctx: Context): Boolean {
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun foregroundApp(ctx: Context): String? {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 10 * 60 * 1000, end)
            val e = UsageEvents.Event()
            var pkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                // 1 = app foreground mein aaya, 2 = app background mein gaya
                if (e.eventType == 1) {
                    pkg = e.packageName
                } else if (e.eventType == 2 && e.packageName == pkg) {
                    pkg = null
                }
            }
            val p = pkg ?: return null
            if (p == ctx.packageName) return "KAIRO"
            try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()
            } catch (ex: Exception) {
                p
            }
        } catch (e: Exception) {
            null
        }
    }
}
