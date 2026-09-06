package ie.shoonya.yantra.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ie.shoonya.yantra.App
import ie.shoonya.yantra.domain.FocusTimer
import ie.shoonya.yantra.domain.SessionNotification
import java.util.concurrent.TimeUnit

/**
 * Runs shortly after a session's planned end so a process-dead finish still lands: restore
 * finalizes the past-end session row, then the widget flips to its finished/idle state.
 * WorkManager's few-seconds slack is fine here — exactness only matters for reminders.
 *
 * It also rings the bell, which is the half that was missing. This worker exists precisely for the
 * case where nobody was watching — the process was gone, so the in-app collector that announces a
 * completed session never ran — and until now it closed the row in silence. A committed session
 * whose whole purpose is to end at a particular moment was the one session that never said so.
 */
class FocusFinalizeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        val restored = container.timer.restoreIfNeeded()
        if (restored is FocusTimer.Restored.RanOut) {
            SessionNotification.showCompleted(
                applicationContext, restored.nodeTitle, restored.nodeId, restored.elapsedSecs,
            )
        }
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
