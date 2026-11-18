package ink.terraria.cmbwidget.sync

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.work.Worker
import androidx.work.WorkerParameters

// The class name is persisted by WorkManager and must survive R8 and app upgrades.
@Keep
class MailSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    private val control = SyncControl { isStopped }

    override fun doWork(): Result {
        val origin = inputData.getString("origin") ?: "periodic"
        Log.i("MailSync", "Worker started: $origin, attempt=$runAttemptCount")
        val outcome = try {
            MailSync.perform(applicationContext, control)
        } finally {
            control.cancel()
        }
        Log.i("MailSync", "Worker finished: $origin, outcome=$outcome")
        return when (outcome) {
            SyncOutcome.SUCCESS, SyncOutcome.CANCELLED -> Result.success()
            SyncOutcome.FAILURE -> Result.failure()
            SyncOutcome.RETRY -> if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    override fun onStopped() {
        control.cancel()
        Log.i("MailSync", "Worker stopped by scheduler")
    }
}
