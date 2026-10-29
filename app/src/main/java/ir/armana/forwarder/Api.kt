package ir.armana.forwarder

import android.os.Build
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** An answer of Armana that is not «ok»: [code] is the machine code («revoked», «invalid», …), message is for the user. */
class ApiException(val code: String, message: String, val http: Int) : Exception(message)

/**
 * The two calls of Armana (?nam_fwd=pair, ?nam_fwd=sync, ?nam_fwd=unpair). JSON in, JSON out, the token in the body.
 * A redirect (http → https, or to www.) is followed once and the new address is returned, so it is saved.
 */
object Api {

    data class Reply(val base: String, val json: JSONObject)

    /** «example.com», «https://example.com/fa», or the pairing link «https://example.com/?nam_fwd_pair=CODE» → «https://example.com/». */
    fun normalizeBase(input: String): String {
        var s = Text.latinDigits(input.trim())
        if (s.isEmpty()) return ""
        val q = s.indexOf('?')
        if (q >= 0) s = s.substring(0, q)
        val h = s.indexOf('#')
        if (h >= 0) s = s.substring(0, h)
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        if (!s.endsWith("/")) s += "/"
        return s
    }

    /** Code inside a pasted pairing link, or null. */
    fun codeFromLink(input: String): String? {
        val m = Regex("[?&](?:nam_fwd_pair|c)=([A-Za-z0-9]{8})").find(input) ?: return null
        return m.groupValues[1].uppercase()
    }

    fun post(base: String, op: String, body: JSONObject): Reply {
        var url = base
        var tries = 0
        while (true) {
            tries++
            val conn = (URL(url + "?nam_fwd=" + op).openConnection() as HttpURLConnection)
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000
                conn.readTimeout = 45_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.useCaches = false
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "ArmanaForwarder/" + BuildConfig.VERSION_NAME + " (Android " + Build.VERSION.RELEASE + ")")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                if (code in 301..308 && code != 304 && tries <= 2) {
                    val loc = conn.getHeaderField("Location")
                    if (!loc.isNullOrEmpty()) {
                        url = normalizeBase(URL(URL(url), loc).toString())
                        continue
                    }
                }
                val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.let { read(it) } ?: ""
                val json = try {
                    JSONObject(text)
                } catch (e: JSONException) {
                    throw ApiException(
                        "bad_response",
                        if (code == 404) "نشانی سایت درست نیست یا افزونهٔ آرمانا به‌روز نیست (نسخهٔ ۱٫۱۲۳ به بعد لازم است)."
                        else "پاسخ سایت قابل خواندن نیست (کد $code). ممکن است فایروال یا کش سایت جلوی درخواست را گرفته باشد.",
                        code
                    )
                }
                if (!json.optBoolean("ok", false)) {
                    throw ApiException(json.optString("error", "error"), json.optString("message", "خطا (کد $code)"), code)
                }
                return Reply(url, json)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun read(s: InputStream): String {
        s.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > 2_000_000) throw IOException("response too large")
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
