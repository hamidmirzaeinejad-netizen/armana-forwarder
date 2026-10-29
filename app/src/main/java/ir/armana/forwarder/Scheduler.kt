package ir.armana.forwarder

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Automatic sending at the times Armana gives (default 13:00 and 21:00, phone time).
 *
 * Two ways reach the same job, so a sleeping phone still sends: a persisted JobScheduler job that waits until the next
 * time and for the network (survives restarts), and an alarm allowed during Doze that brings the job forward at that
 * time. When the job is done it schedules the next time. Running twice is harmless (one lock, nothing sent twice).
 */
object Scheduler {

    const val JOB_ID = 7301
    private const val ALARM_REQ = 7302

    /** Next send time (ms) after now (+1 minute) from «HH:mm» times; today or tomorrow. */
    fun nextSlot(times: List<String>, nowMs: Long = System.currentTimeMillis()): Long {
        val list = if (times.isEmpty()) listOf("13:00", "21:00") else times
        var best = Long.MAX_VALUE
        for (day in 0..1) {
            for (t in list) {
                val parts = t.split(":")
                if (parts.size != 2) continue
                val h = parts[0].toIntOrNull() ?: continue
                val m = parts[1].toIntOrNull() ?: continue
                val c = Calendar.getInstance()
                c.timeInMillis = nowMs
                c.add(Calendar.DAY_OF_YEAR, day)
                c.set(Calendar.HOUR_OF_DAY, h)
                c.set(Calendar.MINUTE, m)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                val at = c.timeInMillis
                if (at > nowMs + 60_000L && at < best) best = at
            }
        }
        return if (best == Long.MAX_VALUE) nowMs + 12 * 3_600_000L else best
    }

    /**
     * Makes sure the automatic send is set (app start, phone restart, switching it on). A job that already waits — or
     * runs right now — is left alone: replacing it would stop a send in the middle. The alarm is set again (it does not
     * survive a restart).
     */
    fun ensure(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.paired() || !p.autoEnabled) {
            cancel(ctx)
            return
        }
        val at = nextSlot(p.schedule)
        if (!hasJob(ctx)) schedule(ctx, maxOf(0L, at - System.currentTimeMillis()))
        setAlarm(ctx, at)
    }

    /** Sets the next send time again, replacing the waiting job (after a send, or when the times changed). */
    fun scheduleNext(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.paired() || !p.autoEnabled) {
            cancel(ctx)
            return
        }
        val at = nextSlot(p.schedule)
        schedule(ctx, maxOf(0L, at - System.currentTimeMillis()))
        setAlarm(ctx, at)
    }

    private fun setAlarm(ctx: Context, at: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx))
    }

    private fun hasJob(ctx: Context): Boolean {
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        return js.allPendingJobs.any { it.id == JOB_ID }
    }

    /** Runs the job as soon as the network allows (from the alarm). */
    fun kick(ctx: Context) {
        if (SyncEngine.running) return
        schedule(ctx, 0L)
    }

    private fun schedule(ctx: Context, delayMs: Long) {
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, SyncJobService::class.java))
            .setMinimumLatency(delayMs)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setBackoffCriteria(10 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        js.schedule(job)
    }

    fun cancel(ctx: Context) {
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        js.cancel(JOB_ID)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(ctx))
    }

    private fun alarmIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
        return PendingIntent.getBroadcast(ctx, ALARM_REQ, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
