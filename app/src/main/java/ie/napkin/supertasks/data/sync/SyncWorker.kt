package ie.napkin.supertasks.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ie.napkin.supertasks.App
import java.util.concurrent.TimeUnit

/**
 * Sync while the app is closed.
 *
 * Everything else about sync happens because you did something — a task was finished, a batch went
 * quiet, the app went to the background, you pressed the button. This is the only path that brings
 * *other people's* work down without you asking, which is what makes an assignment notice possible
 * at all.
 *
 * Fifteen minutes is Android's floor, not a choice, and even that is a request rather than a
 * promise: Samsung in particular will stretch it or drop it entirely for an app it has decided is
 * idle. That is survivable here — the worst case is "syncs when you open it", which for a task app
 * is a delay rather than a failure — but it is exactly why "Sync now" exists and why the UI should
 * never imply this is prompt.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? App)?.container ?: return Result.success()
        // Joining first: on a cold start the worker can arrive before the index exists, and syncing
        // a workspace that has not been read yet would commit whatever the last run left behind.
        container.seeding.join()
        container.syncNow("scheduled")
        return Result.success()
    }

    companion object {
        private const val NAME = "yantra-sync"

        fun schedule(context: Context, unmeteredOnly: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                // KEEP, not UPDATE: re-scheduling on every launch would reset the interval each
                // time and a frequently-opened app would never reach the end of one.
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(
                                if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                            )
                            .build()
                    )
                    .build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
