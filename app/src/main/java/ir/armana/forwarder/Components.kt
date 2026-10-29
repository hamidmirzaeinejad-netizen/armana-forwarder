package ir.armana.forwarder

import android.app.Application
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Scheduler.ensure(this)
        } catch (e: Exception) {
            // never block the start of the app
        }
    }
}

/** The automatic send. A failure that may pass (no internet, server busy) is retried with back-off by JobScheduler. */
class SyncJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        Thread {
            val r = SyncEngine.run(ctx, false)
            val retry = !r.ok && r.retry
            jobFinished(params, retry)
            if (!retry) {
                try {
                    Scheduler.scheduleNext(ctx)
                } catch (e: Exception) {
                }
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

/** The send time arrived (also during Doze): run the job now. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.kick(context.applicationContext)
    }
}

/** After a restart, an update or a clock change the next send time is set again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.ensure(context.applicationContext)
    }
}
