package ir.armana.forwarder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Keys of the SMS Armana has acknowledged, so nothing is sent twice even when the time window overlaps.
 * (Armana also refuses a duplicate on its side: this only saves traffic.)
 */
class SentStore(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "sent.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sent (k TEXT PRIMARY KEY, t INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_sent_t ON sent (t)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Which of these keys were already sent. */
    fun known(keys: Collection<String>): Set<String> {
        val out = HashSet<String>()
        if (keys.isEmpty()) return out
        val db = readableDatabase
        keys.chunked(400).forEach { part ->
            val q = part.joinToString(",") { "?" }
            db.rawQuery("SELECT k FROM sent WHERE k IN ($q)", part.toTypedArray()).use { c ->
                while (c.moveToNext()) out.add(c.getString(0))
            }
        }
        return out
    }

    fun add(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (k in keys) {
                val v = ContentValues()
                v.put("k", k)
                v.put("t", now)
                db.insertWithOnConflict("sent", null, v, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun purge(olderThanMs: Long) {
        writableDatabase.delete("sent", "t < ?", arrayOf(olderThanMs.toString()))
    }

    fun clear() {
        writableDatabase.delete("sent", null, null)
    }
}
