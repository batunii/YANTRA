package ie.napkin.supertasks.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ie.napkin.supertasks.App
import java.util.concurrent.TimeUnit

/**
 * Runs shortly after a session's planned end so a process-dead finish still lands: restore
 * finalizes the past-end session row, then the widget flips to its finished/idle state.
 * WorkManager's few-seconds slack is fine here — exactness only matters for reminders.
 */
class FocusFinalizeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        container.timer.restoreIfNeeded()
        WidgetRefresh.refreshAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "focus-finalize"

        fun schedule(context: Context, remainingSecs: Int) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FocusFinalizeWorker>()
                    .setInitialDelay(remainingSecs.toLong() + 2, TimeUnit.SECONDS)
                    .build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
