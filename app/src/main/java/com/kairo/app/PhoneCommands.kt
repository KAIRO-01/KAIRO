package com.kairo.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings

/** Phone commands that run locally (no AI): stop, volume, settings, apps, alarm, timer, dial. */
object PhoneCommands {

    private val stopWords = listOf("stop", "ruko", "ruk jao", "chup", "bas", "रुको", "रुक जाओ", "चुप", "बस", "स्टॉप")
    private val openTokens = setOf("open", "kholo", "khol", "खोलो", "खोल", "चलाओ", "chalao")
    private val fillerTokens = setOf(
        "karo", "kar", "do", "दो", "करो", "कर", "ko", "को", "please", "app", "application",
        "ऐप", "एप", "एप्लीकेशन", "mera", "meri", "mere", "मेरा", "मेरी", "मेरे", "the", "a", "ka", "का"
    )
    private val aliases = mapOf(
        "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "वॉट्सएप" to "whatsapp",
        "यूट्यूब" to "youtube", "क्रोम" to "chrome", "कैमरा" to "camera", "गैलरी" to "gallery",
        "फोटो" to "photos", "फोटोज" to "photos", "मैप्स" to "maps", "जीमेल" to "gmail",
        "इंस्टाग्राम" to "instagram", "टेलीग्राम" to "telegram", "कैलकुलेटर" to "calculator",
        "घड़ी" to "clock", "फोन" to "phone", "मैसेज" to "messages", "स्पॉटिफाई" to "spotify",
        "फेसबुक" to "facebook"
    )

    fun isStop(raw: String): Boolean {
        val t = raw.lowercase().trim().trimEnd('.', '!', '?', '।')
        return stopWords.any { t == it || t.startsWith("$it ") }
    }

    /** Returns a reply if the text was a phone command, or null so it goes to the AI chat. */
    fun handle(ctx: Context, raw: String): String? {
        val t = raw.lowercase().trim()
        if (t.isEmpty()) return null
        val tokens = t.split(Regex("[\\s,.!?।]+")).filter { it.isNotBlank() }
        return tryVolume(ctx, t)
            ?: trySettings(ctx, t, tokens)
            ?: tryAlarm(ctx, t)
            ?: tryTimer(ctx, t)
            ?: tryCall(ctx, t, tokens)
            ?: tryOpenApp(ctx, tokens)
    }

    private fun hasAny(t: String, words: List<String>): Boolean = words.any { t.contains(it) }

    private fun launch(ctx: Context, intent: Intent, okReply: String): String {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            okReply
        } catch (e: Exception) {
            "Ye nahi khul paya (" + e.javaClass.simpleName + ")"
        }
    }

    // ---------- Volume ----------

    private fun tryVolume(ctx: Context, t: String): String? {
        val volWords = listOf("volume", "वॉल्यूम", "वाल्यूम", "awaaz", "आवाज", "sound")
        if (!hasAny(t, volWords)) return null
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            when {
                hasAny(t, listOf("mute", "म्यूट", "silent", "साइलेंट")) -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    "Volume mute kar diya"
                }
                hasAny(t, listOf("down", "kam", "ghata", "decrease", "lower", "कम", "घटा", "धीमा", "धीरे")) -> {
                    repeat(3) {
                        am.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER,
                            if (it == 0) AudioManager.FLAG_SHOW_UI else 0
                        )
                    }
                    "Volume kam kar diya"
                }
                hasAny(t, listOf("up", "badha", "increase", "zyada", "jyada", "louder", "बढ़ा", "बढा", "ज्यादा", "ज़्यादा", "तेज")) -> {
                    repeat(3) {
                        am.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE,
                            if (it == 0) AudioManager.FLAG_SHOW_UI else 0
                        )
                    }
                    "Volume badha diya"
                }
                else -> null
            }
        } catch (e: Exception) {
            "Volume badalne mein error aaya (" + e.javaClass.simpleName + ")"
        }
    }

    // ---------- Settings ----------

    private fun trySettings(ctx: Context, t: String, tokens: List<String>): String? {
        if (tokens.none { it in openTokens }) return null
        val keys = listOf(
            "settings", "setting", "सेटिंग", "wifi", "wi-fi", "वाईफाई", "वाई-फाई",
            "bluetooth", "ब्लूटूथ", "battery", "बैटरी", "brightness", "ब्राइटनेस", "display", "location", "लोकेशन"
        )
        if (!hasAny(t, keys)) return null
        val pair = when {
            hasAny(t, listOf("wifi", "wi-fi", "वाईफाई", "वाई-फाई")) -> Pair(Settings.ACTION_WIFI_SETTINGS, "WiFi settings")
            hasAny(t, listOf("bluetooth", "ब्लूटूथ")) -> Pair(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings")
            hasAny(t, listOf("battery", "बैटरी")) -> Pair(Settings.ACTION_BATTERY_SAVER_SETTINGS, "Battery settings")
            hasAny(t, listOf("brightness", "ब्राइटनेस", "display")) -> Pair(Settings.ACTION_DISPLAY_SETTINGS, "Display settings")
            hasAny(t, listOf("location", "लोकेशन")) -> Pair(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location settings")
            else -> Pair(Settings.ACTION_SETTINGS, "Settings")
        }
        return launch(ctx, Intent(pair.first), pair.second + " khol di")
    }

    // ---------- Alarm and timer ----------

    private fun parseTime(t: String): Pair<Int, Int>? {
        val full = Regex("(\\d{1,2})\\s*[:.]\\s*(\\d{2})").find(t)
        var hour: Int
        var min = 0
        if (full != null) {
            hour = full.groupValues[1].toInt()
            min = full.groupValues[2].toInt()
        } else {
            val n = Regex("(\\d{1,2})").find(t) ?: return null
            hour = n.groupValues[1].toInt()
        }
        if (hour > 23 || min > 59) return null
        val pm = Regex("\\bp\\.?m\\.?\\b").containsMatchIn(t) ||
            hasAny(t, listOf("shaam", "sham", "raat", "dopahar", "evening", "night", "afternoon", "शाम", "रात", "दोपहर"))
        val am = Regex("\\ba\\.?m\\.?\\b").containsMatchIn(t) ||
            hasAny(t, listOf("subah", "morning", "सुबह"))
        if (pm && hour in 1..11) hour += 12
        if (am && hour == 12) hour = 0
        return Pair(hour, min)
    }

    private fun tryAlarm(ctx: Context, t: String): String? {
        if (!hasAny(t, listOf("alarm", "अलार्म"))) return null
        val time = parseTime(t) ?: return "Alarm kitne baje ka lagana hai? Jaise: subah 7 baje ka alarm"
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, time.first)
            .putExtra(AlarmClock.EXTRA_MINUTES, time.second)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "KAIRO")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val label = String.format("%02d:%02d", time.first, time.second)
        return launch(ctx, intent, "Clock app ko $label ka alarm bhej diya. Ek baar clock app mein check kar lena.")
    }

    private fun tryTimer(ctx: Context, t: String): String? {
        if (!hasAny(t, listOf("timer", "टाइमर"))) return null
        val n = Regex("(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: return "Timer kitni der ka? Jaise: 5 minute ka timer"
        val secs = when {
            hasAny(t, listOf("hour", "ghante", "ghanta", "घंटे", "घंटा")) -> n * 3600
            hasAny(t, listOf("second", "sec", "सेकंड", "सेकेंड")) -> n
            else -> n * 60
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(ctx, intent, "Timer set karne ke liye clock app ko bhej diya. Ek baar check kar lena.")
    }

    // ---------- Call (opens the dialer, you press the call button) ----------

    private fun findNumber(ctx: Context, name: String): String? {
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null
        ) ?: return null
        c.use {
            while (it.moveToNext()) {
                val n = it.getString(0) ?: continue
                if (n.lowercase().contains(name)) return it.getString(1)
            }
        }
        return null
    }

    private fun tryCall(ctx: Context, t: String, tokens: List<String>): String? {
        val callTokens = setOf("call", "dial", "कॉल")
        val hasCall = tokens.any { it in callTokens } ||
            (tokens.any { it == "phone" || it == "फोन" } &&
                tokens.any { it in setOf("karo", "करो", "lagao", "लगाओ") })
        if (!hasCall) return null

        val digits = t.filter { it.isDigit() }
        if (digits.length in 6..13) {
            return launch(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")), "Dialer khol diya. Call button aap dabaiye.")
        }
        val skip = setOf(
            "call", "dial", "कॉल", "phone", "फोन", "karo", "करो", "kar", "कर", "lagao", "लगाओ",
            "ko", "को", "do", "दो", "please", "mujhe", "मुझे", "ek", "एक"
        )
        val name = tokens.filter { it !in skip }.joinToString(" ")
        if (name.isBlank()) return "Kisko call karna hai? Naam ya number boliye."
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "Naam se call ke liye Contacts permission chahiye. Number bolke bhi dial kar sakta hoon."
        }
        val number = findNumber(ctx, name) ?: return "'" + name + "' naam ka contact nahi mila."
        return launch(
            ctx,
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))),
            name + " ka number dial screen par laga diya. Call button aap dabaiye."
        )
    }

    // ---------- Open an app ----------

    private fun tryOpenApp(ctx: Context, tokens: List<String>): String? {
        if (tokens.none { it in openTokens }) return null
        val nameTokens = tokens.filter { it !in openTokens && it !in fillerTokens }
        if (nameTokens.isEmpty() || nameTokens.size > 3) return null
        val name = nameTokens.joinToString(" ")
        val key = aliases[name] ?: name
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit = pm.queryIntentActivities(launcher, 0)
            .firstOrNull { it.loadLabel(pm).toString().lowercase().contains(key) } ?: return null
        val label = hit.loadLabel(pm).toString()
        val intent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return null
        return launch(ctx, intent, label + " khol raha hoon")
    }
}
