package ir.armana.forwarder

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** The few building blocks of the screens: Armana colours, Vazirmatn, cards, buttons and fields (no layout files). */
class Ui(private val ctx: Context) {

    companion object {
        val BRAND = Color.parseColor("#1B4654")
        val BRAND_DARK = Color.parseColor("#0C2A33")
        val GOLD = Color.parseColor("#D9A441")
        val BG = Color.parseColor("#F3F6F6")
        val CARD = Color.WHITE
        val LINE = Color.parseColor("#E2E9EA")
        val INK = Color.parseColor("#0F2730")
        val MUTED = Color.parseColor("#5D7178")
        val OK = Color.parseColor("#1A7F37")
        val OK_BG = Color.parseColor("#E7F5EA")
        val BAD = Color.parseColor("#B42318")
        val BAD_BG = Color.parseColor("#FDECEA")
        val WARN = Color.parseColor("#8A5A12")
        val WARN_BG = Color.parseColor("#FDF4E3")

        private var regular: Typeface? = null
        private var bold: Typeface? = null
    }

    init {
        if (regular == null) {
            regular = try { Typeface.createFromAsset(ctx.assets, "fonts/vazirmatn_regular.ttf") } catch (e: Exception) { Typeface.DEFAULT }
            bold = try { Typeface.createFromAsset(ctx.assets, "fonts/vazirmatn_bold.ttf") } catch (e: Exception) { Typeface.DEFAULT_BOLD }
        }
    }

    fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    fun dpf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    fun font(b: Boolean): Typeface = (if (b) bold else regular) ?: Typeface.DEFAULT

    fun text(s: CharSequence, size: Float = 14f, color: Int = INK, isBold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        t.setTextColor(color)
        t.typeface = font(isBold)
        t.setLineSpacing(0f, 1.25f)
        t.textDirection = View.TEXT_DIRECTION_RTL
        return t
    }

    fun ltr(t: TextView): TextView {
        t.textDirection = View.TEXT_DIRECTION_LTR
        t.layoutDirection = View.LAYOUT_DIRECTION_LTR
        return t
    }

    fun column(): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    fun row(): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun rounded(color: Int, radiusDp: Float = 16f, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dpf(radiusDp)
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    fun card(color: Int = CARD, stroke: Int = LINE): LinearLayout {
        val c = column()
        c.background = rounded(color, 18f, stroke)
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        return c
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.topMargin = dp(top)
        p.bottomMargin = dp(bottom)
        return p
    }

    fun weight(w: Float): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
        return p
    }

    private fun button(label: String, bg: Int, fg: Int, stroke: Int, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.typeface = font(true)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        b.setTextColor(fg)
        b.stateListAnimator = null
        b.minHeight = dp(50)
        b.setPadding(dp(16), dp(10), dp(16), dp(10))
        b.background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 0, 0, 0)), rounded(bg, 14f, stroke), null)
        b.setOnClickListener { onClick() }
        return b
    }

    fun primary(label: String, onClick: () -> Unit): Button = button(label, BRAND, Color.WHITE, 0, onClick)

    fun secondary(label: String, onClick: () -> Unit): Button = button(label, CARD, BRAND, LINE, onClick)

    fun danger(label: String, onClick: () -> Unit): Button = button(label, CARD, BAD, LINE, onClick)

    fun field(hint: String, value: String = "", isLtr: Boolean = false, caps: Boolean = false): EditText {
        val e = EditText(ctx)
        e.hint = hint
        e.setText(value)
        e.typeface = font(false)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        e.setTextColor(INK)
        e.setHintTextColor(Color.parseColor("#97A6AB"))
        e.background = rounded(Color.parseColor("#F7FAFA"), 12f, LINE)
        e.setPadding(dp(14), dp(12), dp(14), dp(12))
        e.isSingleLine = true
        if (isLtr) {
            e.textDirection = View.TEXT_DIRECTION_LTR
            e.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        e.inputType = if (caps) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        else if (isLtr) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        else InputType.TYPE_CLASS_TEXT
        return e
    }

    fun chip(label: String, fg: Int, bg: Int): TextView {
        val t = text(label, 12f, fg, true)
        t.background = rounded(bg, 999f)
        t.setPadding(dp(10), dp(2), dp(10), dp(3))
        return t
    }

    fun dot(color: Int): View {
        val v = View(ctx)
        v.background = rounded(color, 99f)
        val p = LinearLayout.LayoutParams(dp(10), dp(10))
        p.marginEnd = dp(8)
        v.layoutParams = p
        return v
    }

    fun space(h: Int): View {
        val v = View(ctx)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(h))
        return v
    }
}
