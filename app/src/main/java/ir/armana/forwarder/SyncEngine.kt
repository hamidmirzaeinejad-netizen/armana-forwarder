package ir.armana.forwarder

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

/**
 * One send: reads the inbox from the watermark (with a six-hour overlap, so a late SMS is never missed), keeps only the
 * chosen senders, leaves out what Armana already acknowledged, and posts the rest in batches. The watermark only moves
 * past SMS that Armana acknowledged. Every run also tells Armana the phone is alive and takes its settings (send times).
 */
object SyncEngine {

    data class Result(val ok: Boolean, val sent: Int, val message: String, val retry: Boolean)

    private const val OVERLAP_MS = 6 * 3_600_000L
    private val lock = ReentrantLock()

    @Volatile
    var running = false
        private set

    fun run(ctx: Context, manual: Boolean): Result {
        if (!lock.tryLock()) return Result(false, 0, "ارسال دیگری در جریان است.", false)
        running = true
        val prefs = Prefs(ctx)
        val r = try {
            doRun(ctx, prefs)
        } catch (e: ApiException) {
            if (e.code == "revoked") {
                prefs.revoked = true
                Scheduler.cancel(ctx)
                Result(false, 0, "اتصال این گوشی در آرمانا لغو شده است؛ دوباره متصل شوید.", false)
            } else {
                Result(false, 0, e.message ?: "خطا", e.http >= 500 || e.http == 429)
            }
        } catch (e: IOException) {
            Result(false, 0, "اتصال به سایت برقرار نشد (اینترنت گوشی یا در دسترس بودن سایت را بررسی کنید).", true)
        } catch (e: SecurityException) {
            Result(false, 0, "اجازهٔ خواندن پیامک داده نشده است.", false)
        } catch (e: Exception) {
            Result(false, 0, "خطای غیرمنتظره: " + (e.message ?: e.javaClass.simpleName), true)
        } finally {
            running = false
            lock.unlock()
        }
        prefs.lastSyncAt = System.currentTimeMillis()
        prefs.lastOk = r.ok
        prefs.lastMessage = r.message
        prefs.addLog(r.ok, r.sent, r.message, manual)
        return r
    }

    private fun doRun(ctx: Context, prefs: Prefs): Result {
        if (!prefs.paired()) return Result(false, 0, "گوشی به آرمانا متصل نیست.", false)
        val canRead = SmsReader.canRead(ctx)
        val senders = prefs.senders
        val store = SentStore(ctx)
        var sent = 0
        var batches = 0
        var lastReply: JSONObject? = null

        if (canRead && senders.isNotEmpty()) {
            val since = maxOf(prefs.since, prefs.watermark - OVERLAP_MS)
            val scanned = SmsReader.since(ctx, since, 3000) // every sender, oldest first
            val all = scanned.filter { senders.contains(it.address) }
            val known = store.known(all.map { it.key })
            val todo = all.filter { !known.contains(it.key) }
            val size = prefs.maxBatch.coerceIn(10, 200)
            var complete = true
            for (part in todo.chunked(size)) {
                val reply = call(ctx, prefs, part, senders)
                lastReply = reply
                batches++
                val accepted = HashSet<String>()
                val arr = reply.optJSONArray("accepted") ?: JSONArray()
                for (i in 0 until arr.length()) accepted.add(arr.optString(i))
                store.add(accepted)
                sent += reply.optInt("stored", 0)
                // the watermark never passes an SMS that was not acknowledged
                val firstMissing = part.firstOrNull { !accepted.contains(it.key) }
                if (firstMissing != null) {
                    prefs.watermark = maxOf(prefs.watermark, firstMissing.date - 1)
                    complete = false
                    break
                }
                prefs.watermark = maxOf(prefs.watermark, part.last().date)
            }
            // everything read so far is done: the next run starts after it (also past other senders' SMS)
            if (complete && scanned.isNotEmpty()) {
                prefs.watermark = maxOf(prefs.watermark, scanned.last().date)
            }
        }
        if (batches == 0) {
            lastReply = call(ctx, prefs, emptyList(), senders) // heartbeat + settings
        }
        lastReply?.let { applyConfig(ctx, prefs, it) }
        prefs.totalSent = prefs.totalSent + sent
        if (Math.random() < 0.1) store.purge(System.currentTimeMillis() - 150 * 86_400_000L)

        if (!canRead) return Result(false, 0, "اجازهٔ خواندن پیامک داده نشده است؛ فقط وضعیت گوشی فرستاده شد.", false)
        if (senders.isEmpty()) return Result(true, 0, "هیچ فرستنده‌ای برای ارسال انتخاب نشده است.", false)
        return Result(true, sent, if (sent > 0) "${Text.fa(sent)} پیامک تازه فرستاده شد." else "پیامک تازه‌ای نبود.", false)
    }

    private fun call(ctx: Context, prefs: Prefs, part: List<Sms>, senders: Set<String>): JSONObject {
        val msgs = JSONArray()
        for (m in part) {
            val o = JSONObject()
            o.put("k", m.key)
            o.put("a", m.address)
            o.put("b", m.body)
            o.put("t", m.date)
            o.put("s", m.sim)
            msgs.put(o)
        }
        val body = JSONObject()
        body.put("token", prefs.token)
        body.put("app_version", BuildConfig.VERSION_NAME)
        body.put("model", Build.MANUFACTURER + " " + Build.MODEL)
        body.put("senders", JSONArray(senders.toList()))
        body.put("messages", msgs)
        val reply = Api.post(prefs.baseUrl, "sync", body)
        if (reply.base != prefs.baseUrl) prefs.baseUrl = reply.base
        return reply.json
    }

    /** Settings from Armana: send times, device name, batch size. */
    fun applyConfig(ctx: Context, prefs: Prefs, reply: JSONObject) {
        prefs.serverPending = reply.optInt("pending", prefs.serverPending)
        val cfg = reply.optJSONObject("config") ?: return
        cfg.optString("device_name", "").takeIf { it.isNotEmpty() }?.let { prefs.deviceName = it }
        cfg.optString("site_name", "").takeIf { it.isNotEmpty() }?.let { prefs.siteName = it }
        prefs.maxBatch = cfg.optInt("max_batch", prefs.maxBatch)
        val arr = cfg.optJSONArray("schedule")
        if (arr != null) {
            val list = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val t = arr.optString(i)
                if (t.matches(Regex("^\\d{2}:\\d{2}$"))) list.add(t)
            }
            // the caller sets the next send time afterwards (a running job must not be replaced from inside)
            if (list.isNotEmpty() && list != prefs.schedule) prefs.schedule = list
        }
    }
}
