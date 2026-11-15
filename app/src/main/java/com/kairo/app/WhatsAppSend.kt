package com.kairo.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import java.util.Locale

/**
 * One-to-one WhatsApp message drafts initiated by the phone owner.
 * A separate voice confirmation can tap Send only when the current visible
 * WhatsApp chat, recipient and draft text all match. Fails closed otherwise.
 */
object WhatsAppSend {
    private data class Pending(
        val recipient: String, val phone: String, val body: String,
        val createdAtMs: Long
    )
    private var pending: Pending? = null
    private const val MAX_DRAFT_AGE_MS = 120000L
    private val confirmations = setOf(
        "haan bhejo", "han bhejo", "haan send karo", "yes send", "confirm send",
        "send kar do", "bhej do", "हाँ भेजो", "हां भेजो", "भेज दो"
    )
    private val cancellations = setOf(
        "message cancel", "cancel message", "mat bhejo", "nahi bhejo",
        "don't send", "मत भेजो", "नहीं भेजो", "रद्द करो"
    )

    fun handle(ctx: Context, raw: String): String? {
        val spoken = raw.trim()
        val normalized = spoken.lowercase(Locale.ROOT)
            .replace(Regex("[.!?]+$"), "").replace(Regex("\\s+"), " ").trim()
        if (normalized in cancellations) {
            pending = null
            return "WhatsApp draft cancel kar diya. Kuch send nahi kiya."
        }
        if (normalized in confirmations) {
            val draft = pending ?: return "Koi WhatsApp message pending nahi. Pehle recipient aur message bolo."
            if (SystemClock.uptimeMillis() - draft.createdAtMs > MAX_DRAFT_AGE_MS) {
                pending = null
                return "Confirmation expire ho gayi. Dobara message bolo."
            }
            val screen = KairoScreenService.active
                ?: return "WhatsApp Screen Access enable karo, ya WhatsApp mein Send khud dabao."
            if (!screen.sendVerifiedWhatsAppDraft(draft.recipient, draft.phone, draft.body)) {
                return "Safety check fail: current WhatsApp chat, recipient ya message match nahi hua. Sahi chat check karke manually Send dabao."
            }
            pending = null
            return "WhatsApp ka Send button tap kiya. Delivery WhatsApp mein verify karo."
        }

        // Supported: "Aman ko WhatsApp par message bhejo: kal 10 baje aana"
        // and "WhatsApp par Aman ko message bhejo: kal 10 baje aana".
        val stripped = spoken.replace(
            Regex("""^(?:whatsapp|whats\s*app|व्हाट्सएप|व्हाट्सऐप|वाट्सएप)\s+(?:par|pe|पर|पे)\s+""",
                RegexOption.IGNORE_CASE), ""
        )
        val pattern = Regex(
            """^(.{2,75}?)\s+(?:ko|को)\s+(?:(?:whatsapp|whats\s*app|व्हाट्सएप|व्हाट्सऐप|वाट्सएप)\s+(?:par|pe|पर|पे)\s+)?(?:message|msg|मैसेज|संदेश)\s+(?:bhejo|bhej\s*do|send(?:\s+karo)?|भेजो|भेज\s*दो)\s*[:\-–]?\s+(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val command = pattern.matchEntire(stripped.trim()) ?: return null
        val who = command.groupValues[1].trim()
        val body = command.groupValues[2].trim()
        if (body.isEmpty() || body.length > 1000) return "Message 1 se 1000 characters ka hona chahiye."

        val resolved = resolveRecipient(ctx, who)
        if (resolved.error != null) return resolved.error
        val number = resolved.phone ?: return "Contact nahi mila."
        val label = resolved.name ?: who

        val url = Uri.parse("https://wa.me/$number?text=" + Uri.encode(body))
        val intent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = ctx.packageManager
        val app = when {
            pm.getLaunchIntentForPackage("com.whatsapp") != null -> "com.whatsapp"
            pm.getLaunchIntentForPackage("com.whatsapp.w4b") != null -> "com.whatsapp.w4b"
            else -> return "WhatsApp install nahi mila."
        }
        intent.setPackage(app)
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
            return "WhatsApp chat khul nahi paayi. KAIRO ko foreground mein rakhkar try karo."
        }
        pending = Pending(label, number, body, SystemClock.uptimeMillis())
        return "$label ke liye WhatsApp draft kholne ki koshish ki hai: $body. Recipient aur message check karo. Phir 'Hey KAIRO' bolkar 'haan bhejo' bolo. Ya WhatsApp ka Send khud dabao."
    }

    private data class Recipient(
        val phone: String? = null, val name: String? = null, val error: String? = null
    )
    private fun normalizeNumber(value: String): String? {
        val digits = value.filter { it.isDigit() }
        val international = when {
            digits.length == 10 -> "91" + digits
            digits.length == 11 && digits.startsWith("0") -> "91" + digits.drop(1)
            digits.length in 11..15 -> digits
            else -> return null
        }
        return international.takeIf { it.length in 11..15 }
    }

    private fun resolveRecipient(ctx: Context, who: String): Recipient {
        if (who.any { it.isDigit() } && who.all { it.isDigit() || it in "+- ()" }) {
            val phone = normalizeNumber(who)
                ?: return Recipient(error = "Valid country-code mobile number bolo.")
            return Recipient(phone = phone, name = phone)
        }
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) {
            return Recipient(error = "Name se WhatsApp bhejne ke liye KAIRO Settings mein 'Allow Contacts Access' karo. Ya mobile number bolo.")
        }

        val exact = LinkedHashMap<String, String>()
        val prefix = LinkedHashMap<String, String>()
        try {
            ctx.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
                Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf("%" + who.replace("%", "").replace("_", "") + "%"),
                null
            )?.use { cursor ->
                val nc = cursor.getColumnIndexOrThrow(Phone.DISPLAY_NAME)
                val pc = cursor.getColumnIndexOrThrow(Phone.NUMBER)
                var count = 0
                while (cursor.moveToNext() && count++ < 300) {
                    val name = cursor.getString(nc)?.trim().orEmpty()
                    val phone = normalizeNumber(cursor.getString(pc).orEmpty()) ?: continue
                    when {
                        name.equals(who, ignoreCase = true) -> exact[phone] = name
                        name.startsWith("$who ", ignoreCase = true) -> prefix[phone] = name
                    }
                }
            }
        } catch (_: Exception) {
            return Recipient(error = "Contacts read nahi ho paaye. Permission check karo.")
        }
        val matches = if (exact.isNotEmpty()) exact else prefix
        return when (matches.size) {
            0 -> Recipient(error = "'$who' contact nahi mila. Full saved name ya number bolo.")
            1 -> Recipient(phone = matches.keys.first(), name = matches.values.first())
            else -> Recipient(error = "'$who' ke multiple contacts/numbers hain. Exact phone number bolo.")
        }
    }
}
