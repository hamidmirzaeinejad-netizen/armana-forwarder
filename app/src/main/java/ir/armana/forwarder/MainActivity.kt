package ir.armana.forwarder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject

/**
 * The only screen of the app, in four views: connect, home, senders and history. Built in code (no layout files) with
 * Armana's colours and the Vazirmatn font, right to left.
 */
class MainActivity : Activity() {

    private enum class Screen { PAIR, HOME, SENDERS, LOG }

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private var screen = Screen.HOME
    private var busy = false
    private var afterPermission: (() -> Unit)? = null

    // pairing form (kept while the screen is shown)
    private var siteField: EditText? = null
    private var codeField: EditText? = null
    private var nameField: EditText? = null
    private var daysBack = 7
    private var pairError: TextView? = null

    companion object {
        private const val REQ_SMS = 41
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        if (!handleLink(intent)) route()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        if (screen == Screen.HOME && prefs.paired()) showHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen == Screen.SENDERS || screen == Screen.LOG) {
            if (prefs.paired()) showHome() else showPair()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun route() {
        when {
            prefs.revoked -> showHome()
            !prefs.paired() -> showPair()
            !prefs.sendersChosen -> askSmsThen { showSenders(true) }
            else -> showHome()
        }
    }

    /* ================================================================== deep link from the pairing page */

    private fun handleLink(i: Intent?): Boolean {
        val data: Uri = i?.data ?: return false
        if (data.scheme != "armanafwd" || data.host != "pair") return false
        val u = data.getQueryParameter("u") ?: ""
        val c = (data.getQueryParameter("c") ?: "").uppercase()
        if (u.isEmpty() || c.length != 8) return false
        val go = {
            showPair(u, c)
            connect()
        }
        if (prefs.paired()) {
            showHome()
            dialog(
                "اتصال جدید",
                "این گوشی الان به «${prefs.siteName.ifEmpty { prefs.baseUrl }}» وصل است. اتصال تازه جایگزین شود؟",
                "بله، وصل شو"
            ) { go() }
        } else {
            go()
        }
        return true
    }

    /* ================================================================== shared chrome */

    private fun page(title: String, sub: String?, back: Boolean): LinearLayout {
        val root = ui.column()
        root.setBackgroundColor(Ui.BG)

        val head = ui.column()
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.BRAND, Ui.BRAND_DARK))
        head.background = g
        head.setPadding(ui.dp(20), ui.dp(22), ui.dp(20), ui.dp(26))
        val top = ui.row()
        val icon = ImageView(this)
        icon.setImageDrawable(applicationInfo.loadIcon(packageManager))
        top.addView(icon, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        val tt = ui.column()
        tt.setPadding(ui.dp(12), 0, ui.dp(12), 0)
        tt.addView(ui.text(title, 19f, Color.WHITE, true))
        if (!sub.isNullOrEmpty()) tt.addView(ui.text(sub, 13f, Color.parseColor("#C9D8DC")))
        top.addView(tt, ui.weight(1f))
        if (back) {
            val b = ui.text("بازگشت", 14f, Ui.GOLD, true)
            b.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            b.setOnClickListener { onBackPressed() }
            top.addView(b)
        }
        head.addView(top)
        root.addView(head)

        val body = ui.column()
        body.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(28))
        root.addView(body)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.setBackgroundColor(Ui.BG)
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.layoutDirection = View.LAYOUT_DIRECTION_RTL
        setContentView(scroll)
        return body
    }

    private fun dialog(title: String, message: String, ok: String, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun bg(work: () -> Unit) {
        Thread { work() }.start()
    }

    private fun onUi(work: () -> Unit) {
        runOnUiThread {
            if (!isFinishing) work()
        }
    }

    /* ================================================================== connect */

    private fun showPair(site: String = "", code: String = "") {
        screen = Screen.PAIR
        val body = page("فورواردر آرمانا", "ارسال پیامک‌های بانکی به آرمانا", false)

        val intro = ui.card()
        intro.addView(ui.text("اتصال به آرمانا", 17f, Ui.INK, true))
        intro.addView(ui.space(6))
        intro.addView(ui.text("۱. در آرمانا بروید به: بانک › فورواردر › «ساخت QR اتصال».", 14f, Ui.MUTED))
        intro.addView(ui.text("۲. QR را با دوربین همین گوشی اسکن کنید و «اتصال این گوشی» را بزنید.", 14f, Ui.MUTED))
        intro.addView(ui.text("یا نشانی سایت و کد ۸ حرفی را این‌جا وارد کنید:", 14f, Ui.MUTED))
        body.addView(intro, ui.lp(bottom = 12))

        val form = ui.card()
        form.addView(ui.text("نشانی سایت", 13f, Ui.MUTED, true))
        val sf = ui.field("example.com", site, isLtr = true)
        form.addView(sf, ui.lp(top = 4, bottom = 10))
        form.addView(ui.text("کد اتصال", 13f, Ui.MUTED, true))
        val cf = ui.field("XXXXXXXX", code, isLtr = true, caps = true)
        cf.letterSpacing = 0.25f
        form.addView(cf, ui.lp(top = 4, bottom = 10))
        form.addView(ui.text("نام این گوشی (در آرمانا نمایش داده می‌شود)", 13f, Ui.MUTED, true))
        val nf = ui.field("گوشی دفتر", prefs.deviceName.ifEmpty { Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL })
        form.addView(nf, ui.lp(top = 4, bottom = 12))

        form.addView(ui.text("پیامک‌های قدیمی‌تر تا چه زمانی فرستاده شوند؟", 13f, Ui.MUTED, true))
        val chips = ui.row()
        val options = listOf(1 to "امروز", 3 to "۳ روز", 7 to "۷ روز", 30 to "۳۰ روز")
        val views = ArrayList<TextView>()
        fun paint() {
            for ((i, v) in views.withIndex()) {
                val on = options[i].first == daysBack
                v.setTextColor(if (on) Color.WHITE else Ui.BRAND)
                v.background = ui.rounded(if (on) Ui.BRAND else Ui.CARD, 999f, if (on) 0 else Ui.LINE)
            }
        }
        for ((d, label) in options) {
            val t = ui.text(label, 13f, Ui.BRAND, true)
            t.gravity = Gravity.CENTER
            t.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            t.setOnClickListener {
                daysBack = d
                paint()
            }
            views.add(t)
            val p = ui.weight(1f)
            p.marginEnd = ui.dp(6)
            chips.addView(t, p)
        }
        paint()
        form.addView(chips, ui.lp(top = 6, bottom = 14))

        val err = ui.text("", 13f, Ui.BAD)
        err.visibility = View.GONE
        form.addView(err, ui.lp(bottom = 8))
        form.addView(ui.primary("اتصال") { connect() }, ui.lp())
        body.addView(form, ui.lp(bottom = 12))

        val note = ui.text("فقط پیامک فرستنده‌هایی که خودتان انتخاب می‌کنید (بانک‌ها) از گوشی خارج می‌شود.", 12f, Ui.MUTED)
        note.gravity = Gravity.CENTER
        body.addView(note)

        siteField = sf
        codeField = cf
        nameField = nf
        pairError = err
    }

    private fun connect() {
        if (busy) return
        val siteIn = siteField?.text?.toString() ?: ""
        var codeIn = Text.latinDigits(codeField?.text?.toString() ?: "").uppercase().replace(Regex("[^A-Z0-9]"), "")
        Api.codeFromLink(siteIn)?.let { if (codeIn.isEmpty()) codeIn = it }
        val base = Api.normalizeBase(siteIn)
        val name = nameField?.text?.toString()?.trim() ?: ""
        if (base.isEmpty() || codeIn.length != 8) {
            showPairError("نشانی سایت و کد ۸ حرفی را وارد کنید.")
            return
        }
        busy = true
        showPairError(null)
        val wait = progress("در حال اتصال…")
        bg {
            try {
                val body = JSONObject()
                body.put("code", codeIn)
                body.put("device_name", name)
                body.put("model", Build.MANUFACTURER + " " + Build.MODEL)
                body.put("app_version", BuildConfig.VERSION_NAME)
                body.put("android", Build.VERSION.RELEASE)
                val reply = Api.post(base, "pair", body)
                val token = reply.json.getString("token")
                prefs.clearPairing()
                SentStore(this).clear()
                prefs.baseUrl = reply.base
                prefs.token = token
                prefs.revoked = false
                prefs.deviceName = reply.json.optJSONObject("device")?.optString("name", name) ?: name
                val now = System.currentTimeMillis()
                prefs.since = now - daysBack * 86_400_000L
                prefs.watermark = prefs.since
                SyncEngine.applyConfig(this, prefs, reply.json)
                onUi {
                    busy = false
                    wait.dismiss()
                    askSmsThen { showSenders(true) }
                }
            } catch (e: ApiException) {
                onUi {
                    busy = false
                    wait.dismiss()
                    showPairError(e.message)
                }
            } catch (e: Exception) {
                onUi {
                    busy = false
                    wait.dismiss()
                    showPairError("اتصال به سایت برقرار نشد. نشانی سایت و اینترنت گوشی را بررسی کنید.")
                }
            }
        }
    }

    private fun showPairError(msg: String?) {
        val e = pairError ?: return
        if (msg.isNullOrEmpty()) {
            e.visibility = View.GONE
        } else {
            e.text = msg
            e.visibility = View.VISIBLE
        }
    }

    private fun progress(label: String): AlertDialog {
        val box = ui.row()
        box.setPadding(ui.dp(24), ui.dp(22), ui.dp(24), ui.dp(22))
        box.addView(ProgressBar(this), LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))
        val t = ui.text(label, 15f)
        t.setPadding(ui.dp(16), 0, ui.dp(16), 0)
        box.addView(t)
        val d = AlertDialog.Builder(this).setView(box).setCancelable(false).create()
        d.show()
        return d
    }

    /* ================================================================== SMS permission */

    private fun askSmsThen(next: () -> Unit) {
        if (SmsReader.canRead(this)) {
            next()
            return
        }
        afterPermission = next
        requestPermissions(arrayOf(Manifest.permission.READ_SMS), REQ_SMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_SMS) return
        val next = afterPermission
        afterPermission = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            next?.invoke()
        } else {
            showHome()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: Exception) {
        }
    }

    /* ================================================================== senders */

    private fun showSenders(first: Boolean) {
        screen = Screen.SENDERS
        val body = page("پیامک‌های قابل ارسال", "فقط پیامک این فرستنده‌ها به آرمانا می‌رود", !first)
        val loading = ui.text("در حال خواندن پیامک‌های گوشی…", 14f, Ui.MUTED)
        loading.gravity = Gravity.CENTER
        body.addView(loading, ui.lp(top = 20))
        bg {
            val list = try {
                SmsReader.senders(this)
            } catch (e: Exception) {
                emptyList()
            }
            onUi { fillSenders(body, loading, list, first) }
        }
    }

    private fun fillSenders(body: LinearLayout, loading: View, list: List<SenderInfo>, first: Boolean) {
        body.removeView(loading)
        val chosen = if (first && !prefs.sendersChosen) list.filter { it.bankLike }.map { it.address }.toMutableSet() else prefs.senders.toMutableSet()

        val head = ui.card(Ui.WARN_BG, 0)
        head.addView(ui.text(if (first) "فرستنده‌های بانکی خودکار انتخاب شده‌اند. بررسی کنید و «ذخیره» را بزنید." else "تیک فرستنده‌هایی را بزنید که پیامکشان باید به آرمانا برود.", 14f, Ui.WARN))
        body.addView(head, ui.lp(bottom = 12))

        // senders chosen before that have no SMS in the inbox now
        val missing = prefs.senders.filter { a -> list.none { it.address == a } }
        val all = list + missing.map { SenderInfo(it, 0, "", true, 0L) }
        if (all.isEmpty()) {
            body.addView(ui.text("پیامکی در گوشی پیدا نشد. اگر اجازهٔ خواندن پیامک را نداده‌اید، از تنظیمات گوشی بدهید.", 14f, Ui.MUTED), ui.lp(bottom = 12))
            body.addView(ui.secondary("تنظیمات اجازه‌ها") { openAppSettings() }, ui.lp(bottom = 12))
        }
        val listCard = ui.card()
        for ((i, s) in all.withIndex()) {
            val row = ui.row()
            row.setPadding(0, ui.dp(10), 0, ui.dp(10))
            val cb = CheckBox(this)
            cb.isChecked = chosen.contains(s.address)
            cb.buttonTintList = android.content.res.ColorStateList.valueOf(Ui.BRAND)
            cb.setOnCheckedChangeListener { _, on -> if (on) chosen.add(s.address) else chosen.remove(s.address) }
            row.addView(cb)
            val col = ui.column()
            val top = ui.row()
            top.addView(ui.ltr(ui.text(s.address, 15f, Ui.INK, true)))
            if (s.bankLike) {
                val c = ui.chip("بانکی", Ui.WARN, Ui.WARN_BG)
                val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                p.marginStart = ui.dp(8)
                top.addView(c, p)
            }
            col.addView(top)
            val sub = (if (s.sample.isNotEmpty()) s.sample + " · " else "") + (if (s.count > 0) Text.fa(s.count) + " پیامک" else "در صندوق پیامک نیست")
            col.addView(ui.text(sub, 12f, Ui.MUTED))
            row.addView(col, ui.weight(1f))
            row.setOnClickListener { cb.isChecked = !cb.isChecked }
            listCard.addView(row)
            if (i < all.size - 1) {
                val line = View(this)
                line.setBackgroundColor(Ui.LINE)
                listCard.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            }
        }
        if (all.isNotEmpty()) body.addView(listCard, ui.lp(bottom = 14))

        body.addView(ui.primary("ذخیره") {
            prefs.senders = chosen
            prefs.sendersChosen = true
            Scheduler.ensure(this)
            showHome()
            if (first && chosen.isNotEmpty()) sendNow()
        }, ui.lp())
    }

    /* ================================================================== home */

    private fun showHome() {
        screen = Screen.HOME
        if (!prefs.paired() && !prefs.revoked) {
            showPair()
            return
        }
        val body = page("فورواردر آرمانا", prefs.siteName.ifEmpty { prefs.baseUrl }, false)

        if (prefs.revoked) {
            val c = ui.card(Ui.BAD_BG, 0)
            c.addView(ui.text("اتصال این گوشی در آرمانا لغو شده است.", 15f, Ui.BAD, true))
            c.addView(ui.text("برای ادامه، در آرمانا QR تازه بسازید و دوباره وصل شوید.", 13f, Ui.BAD))
            c.addView(ui.primary("اتصال دوباره") {
                prefs.clearPairing()
                showPair()
            }, ui.lp(top = 10))
            body.addView(c, ui.lp(bottom = 12))
            return
        }

        // status
        val st = ui.card()
        val line = ui.row()
        val ok = prefs.lastSyncAt == 0L || prefs.lastOk
        line.addView(ui.dot(if (ok) Ui.OK else Ui.BAD))
        line.addView(ui.text(if (prefs.lastSyncAt == 0L) "متصل؛ هنوز ارسالی انجام نشده" else if (ok) "متصل و فعال" else "ارسال آخر انجام نشد", 16f, Ui.INK, true), ui.weight(1f))
        st.addView(line)
        st.addView(ui.space(6))
        if (prefs.lastSyncAt > 0) {
            st.addView(ui.text("آخرین ارسال: " + Text.when_(prefs.lastSyncAt), 13f, Ui.MUTED))
            st.addView(ui.text(prefs.lastMessage, 13f, if (ok) Ui.MUTED else Ui.BAD))
        }
        st.addView(ui.text(if (prefs.autoEnabled) "ارسال خودکار بعدی: " + Text.when_(Scheduler.nextSlot(prefs.schedule)) else "ارسال خودکار خاموش است", 13f, Ui.MUTED))
        val send = ui.primary("ارسال الان") { sendNow() }
        st.addView(send, ui.lp(top = 12))
        body.addView(st, ui.lp(bottom = 12))

        // numbers
        val nums = ui.row()
        nums.addView(stat("فرستاده‌شده", Text.number(prefs.totalSent)), ui.weight(1f))
        val gap = View(this)
        nums.addView(gap, LinearLayout.LayoutParams(ui.dp(10), 1))
        nums.addView(stat("در انتظار بررسی در آرمانا", if (prefs.serverPending >= 0) Text.number(prefs.serverPending.toLong()) else "—"), ui.weight(1f))
        body.addView(nums, ui.lp(bottom = 12))

        // warnings
        if (!SmsReader.canRead(this)) {
            val c = ui.card(Ui.BAD_BG, 0)
            c.addView(ui.text("اجازهٔ خواندن پیامک داده نشده است.", 15f, Ui.BAD, true))
            c.addView(ui.text("بدون آن هیچ پیامکی فرستاده نمی‌شود. اگر گزینهٔ پیامک در تنظیمات خاکستری است: اطلاعات برنامه › منوی ⋮ › «اجازه دادن به تنظیمات محدودشده».", 13f, Ui.BAD))
            val r = ui.row()
            r.addView(ui.primary("دادن اجازه") { askSmsThen { showHome() } }, ui.weight(1f))
            val g2 = View(this)
            r.addView(g2, LinearLayout.LayoutParams(ui.dp(8), 1))
            r.addView(ui.secondary("تنظیمات") { openAppSettings() }, ui.weight(1f))
            c.addView(r, ui.lp(top = 10))
            body.addView(c, ui.lp(bottom = 12))
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val c = ui.card(Ui.WARN_BG, 0)
            c.addView(ui.text("برای ارسال به‌موقع", 15f, Ui.WARN, true))
            c.addView(ui.text("اندروید ممکن است برای صرفه‌جویی باتری ارسال خودکار را عقب بیندازد. اجازه دهید فورواردر از «بهینه‌سازی باتری» خارج شود (مصرف باتری تقریباً صفر است).", 13f, Ui.WARN))
            c.addView(ui.secondary("خارج کردن از بهینه‌سازی باتری") { askBattery() }, ui.lp(top = 10))
            body.addView(c, ui.lp(bottom = 12))
        }

        // senders
        val sc = ui.card()
        val sh = ui.row()
        sh.addView(ui.text("پیامک‌های قابل ارسال", 15f, Ui.INK, true), ui.weight(1f))
        val edit = ui.text("تغییر", 14f, Ui.BRAND, true)
        edit.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
        edit.setOnClickListener { askSmsThen { showSenders(false) } }
        sh.addView(edit)
        sc.addView(sh)
        val ss = prefs.senders
        sc.addView(ui.ltr(ui.text(if (ss.isEmpty()) "هیچ فرستنده‌ای انتخاب نشده است" else ss.sorted().joinToString("  ·  "), 13f, if (ss.isEmpty()) Ui.BAD else Ui.MUTED)), ui.lp(top = 4))
        body.addView(sc, ui.lp(bottom = 12))

        // automatic
        val ac = ui.card()
        val ar = ui.row()
        val acol = ui.column()
        acol.addView(ui.text("ارسال خودکار", 15f, Ui.INK, true))
        acol.addView(ui.text("هر روز ساعت " + Text.times(prefs.schedule) + " (از تنظیمات آرمانا)", 13f, Ui.MUTED))
        ar.addView(acol, ui.weight(1f))
        val sw = Switch(this)
        sw.isChecked = prefs.autoEnabled
        sw.setOnCheckedChangeListener { _, on ->
            prefs.autoEnabled = on
            Scheduler.ensure(this)
            showHome()
        }
        ar.addView(sw)
        ac.addView(ar)
        body.addView(ac, ui.lp(bottom = 12))

        // history
        val lc = ui.card()
        val lh = ui.row()
        lh.addView(ui.text("گزارش ارسال‌ها", 15f, Ui.INK, true), ui.weight(1f))
        val more = ui.text("همه", 14f, Ui.BRAND, true)
        more.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
        more.setOnClickListener { showLog() }
        lh.addView(more)
        lc.addView(lh)
        addLogRows(lc, 4)
        body.addView(lc, ui.lp(bottom = 16))

        val un = ui.danger("قطع اتصال از آرمانا") { unpair() }
        body.addView(un, ui.lp(bottom = 10))
        val v = ui.text("نسخهٔ " + Text.fa(BuildConfig.VERSION_NAME) + " · " + prefs.deviceName, 12f, Ui.MUTED)
        v.gravity = Gravity.CENTER
        body.addView(v)
    }

    private fun stat(label: String, value: String): LinearLayout {
        val c = ui.card()
        c.addView(ui.text(value, 22f, Ui.BRAND, true))
        c.addView(ui.text(label, 12f, Ui.MUTED))
        return c
    }

    private fun addLogRows(parent: LinearLayout, max: Int) {
        val arr = prefs.log()
        if (arr.length() == 0) {
            parent.addView(ui.text("هنوز ارسالی انجام نشده است.", 13f, Ui.MUTED), ui.lp(top = 6))
            return
        }
        for (i in 0 until minOf(arr.length(), max)) {
            val o = arr.optJSONObject(i) ?: continue
            val r = ui.row()
            r.setPadding(0, ui.dp(8), 0, 0)
            r.addView(ui.dot(if (o.optBoolean("ok")) Ui.OK else Ui.BAD))
            val col = ui.column()
            col.addView(ui.text(Text.when_(o.optLong("t")) + (if (o.optBoolean("manual")) " · دستی" else " · خودکار"), 12f, Ui.MUTED))
            col.addView(ui.text(o.optString("m"), 13f, Ui.INK))
            r.addView(col, ui.weight(1f))
            parent.addView(r)
        }
    }

    private fun showLog() {
        screen = Screen.LOG
        val body = page("گزارش ارسال‌ها", "۳۰ ارسال آخر", true)
        val c = ui.card()
        addLogRows(c, 30)
        body.addView(c)
    }

    private fun sendNow() {
        if (busy) return
        busy = true
        val wait = progress("در حال ارسال پیامک‌ها…")
        bg {
            val r = SyncEngine.run(this, true)
            try {
                Scheduler.scheduleNext(this) // the send times may have changed in Armana
            } catch (e: Exception) {
            }
            onUi {
                busy = false
                wait.dismiss()
                showHome()
                AlertDialog.Builder(this).setMessage(r.message).setPositiveButton("باشه", null).show()
            }
        }
    }

    private fun askBattery() {
        try {
            @Suppress("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
            }
        }
        prefs.batteryAsked = true
    }

    private fun unpair() {
        dialog("قطع اتصال", "این گوشی دیگر پیامکی به آرمانا نمی‌فرستد و در آرمانا هم «لغو شده» نشان داده می‌شود. ادامه می‌دهید؟", "قطع اتصال") {
            val wait = progress("در حال قطع اتصال…")
            val base = prefs.baseUrl
            val token = prefs.token
            bg {
                try {
                    val b = JSONObject()
                    b.put("token", token)
                    Api.post(base, "unpair", b)
                } catch (e: Exception) {
                    // offline: the phone forgets the token anyway; it can be revoked in Armana too
                }
                Scheduler.cancel(this)
                prefs.clearPairing()
                prefs.token = ""
                SentStore(this).clear()
                onUi {
                    wait.dismiss()
                    showPair()
                }
            }
        }
    }
}
