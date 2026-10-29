package ir.armana.forwarder

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the app remembers. Values written by the background sync use commit() so a killed process never loses the
 * watermark or the token.
 */
class Prefs(ctx: Context) {

    private val p: SharedPreferences = ctx.applicationContext.getSharedPreferences("fwd", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = p.getString("base", "") ?: ""
        set(v) { p.edit().putString("base", v).commit() }

    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) { p.edit().putString("token", v).commit() }

    var deviceName: String
        get() = p.getString("device_name", "") ?: ""
        set(v) { p.edit().putString("device_name", v).apply() }

    var siteName: String
        get() = p.getString("site_name", "") ?: ""
        set(v) { p.edit().putString("site_name", v).apply() }

    /** «HH:mm» send times, from Armana. */
    var schedule: List<String>
        get() = (p.getString("schedule", "13:00,21:00") ?: "").split(",").map { it.trim() }.filter { it.matches(Regex("^\\d{2}:\\d{2}$")) }
        set(v) { p.edit().putString("schedule", v.joinToString(",")).commit() }

    /** Senders whose SMS are forwarded (normalized addresses). Nothing else ever leaves the phone. */
    var senders: Set<String>
        get() = HashSet(p.getStringSet("senders", emptySet()) ?: emptySet())
        set(v) { p.edit().putStringSet("senders", HashSet(v)).commit() }

    var sendersChosen: Boolean
        get() = p.getBoolean("senders_chosen", false)
        set(v) { p.edit().putBoolean("senders_chosen", v).apply() }

    var autoEnabled: Boolean
        get() = p.getBoolean("auto", true)
        set(v) { p.edit().putBoolean("auto", v).commit() }

    /** SMS older than this (ms) are never sent: chosen when connecting. */
    var since: Long
        get() = p.getLong("since", 0L)
        set(v) { p.edit().putLong("since", v).commit() }

    /** Date (ms) up to which every selected SMS has been sent. */
    var watermark: Long
        get() = p.getLong("watermark", 0L)
        set(v) { p.edit().putLong("watermark", v).commit() }

    var lastSyncAt: Long
        get() = p.getLong("last_sync", 0L)
        set(v) { p.edit().putLong("last_sync", v).apply() }

    var lastOk: Boolean
        get() = p.getBoolean("last_ok", true)
        set(v) { p.edit().putBoolean("last_ok", v).apply() }

    var lastMessage: String
        get() = p.getString("last_msg", "") ?: ""
        set(v) { p.edit().putString("last_msg", v).apply() }

    var totalSent: Long
        get() = p.getLong("total", 0L)
        set(v) { p.edit().putLong("total", v).apply() }

    var serverPending: Int
        get() = p.getInt("server_pending", -1)
        set(v) { p.edit().putInt("server_pending", v).apply() }

    var maxBatch: Int
        get() = p.getInt("max_batch", 100)
        set(v) { p.edit().putInt("max_batch", v).apply() }

    var revoked: Boolean
        get() = p.getBoolean("revoked", false)
        set(v) { p.edit().putBoolean("revoked", v).commit() }

    var batteryAsked: Boolean
        get() = p.getBoolean("battery_asked", false)
        set(v) { p.edit().putBoolean("battery_asked", v).apply() }

    fun paired(): Boolean = token.isNotEmpty() && baseUrl.isNotEmpty() && !revoked

    fun clearPairing() {
        p.edit()
            .remove("token").remove("revoked").remove("watermark").remove("since").remove("senders").remove("senders_chosen")
            .remove("last_sync").remove("last_ok").remove("last_msg").remove("server_pending").remove("log").remove("total")
            .commit()
    }

    /* ------------------------------------------------------------------ log (last 30 runs) */

    fun addLog(ok: Boolean, sent: Int, message: String, manual: Boolean) {
        val arr = log()
        val o = JSONObject()
        o.put("t", System.currentTimeMillis())
        o.put("ok", ok)
        o.put("n", sent)
        o.put("m", message)
        o.put("manual", manual)
        val out = JSONArray()
        out.put(o)
        for (i in 0 until minOf(arr.length(), 29)) out.put(arr.get(i))
        p.edit().putString("log", out.toString()).apply()
    }

    fun log(): JSONArray = try {
        JSONArray(p.getString("log", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }
}
