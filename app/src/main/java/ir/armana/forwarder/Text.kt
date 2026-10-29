package ir.armana.forwarder

import java.util.Calendar

/** Persian digits, the Jalali calendar and short human times. */
object Text {

    private const val FA = "۰۱۲۳۴۵۶۷۸۹"
    private const val AR = "٠١٢٣٤٥٦٧٨٩"

    fun fa(s: Any?): String {
        val src = s?.toString() ?: ""
        val b = StringBuilder(src.length)
        for (ch in src) b.append(if (ch in '0'..'9') FA[ch - '0'] else ch)
        return b.toString()
    }

    fun latinDigits(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s) {
            val i = FA.indexOf(ch)
            val j = AR.indexOf(ch)
            b.append(if (i >= 0) ('0' + i) else if (j >= 0) ('0' + j) else ch)
        }
        return b.toString()
    }

    fun number(n: Long): String = fa(String.format(java.util.Locale.US, "%,d", n).replace(',', '٬'))

    /** Gregorian → Jalali [y, m, d]. */
    fun jalali(gy: Int, gm: Int, gd: Int): IntArray {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gdm[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return intArrayOf(jy, jm, jd)
    }

    /** «امروز ۱۴:۲۲»، «دیروز ۰۹:۰۵» or «۱۴۰۵/۰۸/۰۷ ۱۴:۲۲». */
    fun when_(ms: Long): String {
        if (ms <= 0L) return "—"
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        val now = Calendar.getInstance()
        val hm = String.format(java.util.Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val sameDay = c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return fa("امروز $hm")
        val y = now.clone() as Calendar
        y.add(Calendar.DAY_OF_YEAR, -1)
        if (c.get(Calendar.YEAR) == y.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)) return fa("دیروز $hm")
        val j = jalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        return fa(String.format(java.util.Locale.US, "%04d/%02d/%02d %s", j[0], j[1], j[2], hm))
    }

    /** «۱۳:۰۰ و ۲۱:۰۰» */
    fun times(list: List<String>): String = fa(list.joinToString(" و "))
}
