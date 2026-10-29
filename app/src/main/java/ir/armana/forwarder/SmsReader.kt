package ir.armana.forwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri

/** One SMS of the inbox. */
data class Sms(val id: Long, val address: String, val body: String, val date: Long, val sim: Int) {
    /** Stable key of this SMS on this phone. */
    val key: String get() = "${id}_$date"
}

/** A sender seen in the inbox, for the «which SMS to forward» list. */
data class SenderInfo(val address: String, val count: Int, val sample: String, val bankLike: Boolean, val last: Long)

object SmsReader {

    private val INBOX: Uri = Uri.parse("content://sms/inbox")

    /** Words that bank SMS carry (Persian and Arabic letters, a few Latin ones). */
    private val BANK = Regex("مانده|موجودی|واریز|واريز|برداشت|بانک|بانك|حساب|کارت|كارت|انتقال|خرید|خريد|Bank|BANK")

    fun canRead(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Same sender written the same way everywhere: Latin digits, no spaces or dashes. */
    fun normalize(address: String?): String {
        val s = Text.latinDigits(address ?: "").trim()
        return s.replace(Regex("[\\s\\-()]"), "")
    }

    fun isBankLike(body: String): Boolean = BANK.containsMatchIn(body)

    private fun cursor(ctx: Context, selection: String?, args: Array<String>?, order: String, withSim: Boolean): Cursor? {
        val cols = if (withSim) arrayOf("_id", "address", "body", "date", "sub_id") else arrayOf("_id", "address", "body", "date")
        return ctx.contentResolver.query(INBOX, cols, selection, args, order)
    }

    /** Inbox SMS received after [sinceMs], oldest first (at most [limit]). Some phones have no «sub_id» column. */
    fun since(ctx: Context, sinceMs: Long, limit: Int): List<Sms> {
        val out = ArrayList<Sms>()
        val sel = "date > ?"
        val args = arrayOf(sinceMs.toString())
        var c: Cursor? = try {
            cursor(ctx, sel, args, "date ASC", true)
        } catch (e: Exception) {
            null
        }
        var withSim = c != null
        if (c == null) {
            c = cursor(ctx, sel, args, "date ASC", false)
            withSim = false
        }
        c?.use { cur ->
            val iId = cur.getColumnIndex("_id")
            val iAddr = cur.getColumnIndex("address")
            val iBody = cur.getColumnIndex("body")
            val iDate = cur.getColumnIndex("date")
            val iSim = if (withSim) cur.getColumnIndex("sub_id") else -1
            while (cur.moveToNext() && out.size < limit) {
                val body = if (iBody >= 0) cur.getString(iBody) ?: "" else ""
                if (body.isEmpty()) continue
                out.add(
                    Sms(
                        id = cur.getLong(iId),
                        address = normalize(if (iAddr >= 0) cur.getString(iAddr) else ""),
                        body = body,
                        date = cur.getLong(iDate),
                        sim = if (iSim >= 0 && !cur.isNull(iSim)) cur.getInt(iSim) else -1
                    )
                )
            }
        }
        return out
    }

    /** Senders of the last [days] days, bank-like ones first. */
    fun senders(ctx: Context, days: Int = 120): List<SenderInfo> {
        val from = System.currentTimeMillis() - days * 86_400_000L
        val map = LinkedHashMap<String, IntArray>()
        val sample = HashMap<String, String>()
        val bank = HashMap<String, Boolean>()
        val last = HashMap<String, Long>()
        cursor(ctx, "date > ?", arrayOf(from.toString()), "date DESC", false)?.use { c ->
            val iAddr = c.getColumnIndex("address")
            val iBody = c.getColumnIndex("body")
            val iDate = c.getColumnIndex("date")
            var n = 0
            while (c.moveToNext() && n < 5000) {
                n++
                val a = normalize(c.getString(iAddr))
                if (a.isEmpty()) continue
                val body = c.getString(iBody) ?: ""
                val cnt = map.getOrPut(a) { IntArray(1) }
                cnt[0]++
                if (!sample.containsKey(a)) {
                    sample[a] = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
                    last[a] = c.getLong(iDate)
                }
                if (bank[a] != true && isBankLike(body)) bank[a] = true
            }
        }
        return map.entries
            .map { SenderInfo(it.key, it.value[0], (sample[it.key] ?: "").take(60), bank[it.key] == true, last[it.key] ?: 0L) }
            .sortedWith(compareByDescending<SenderInfo> { it.bankLike }.thenByDescending { it.count })
    }
}
