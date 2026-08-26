package ie.napkin.supertasks.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ie.napkin.supertasks.App
import java.util.concurrent.TimeUnit

/**
 * Moves finished work out of the working set, once a day.
 *
 * Daily because the threshold is measured in days: checking more often cannot change the answer, and
 * a sweep is a write that becomes a commit, so doing it hourly would fill the history with nothing.
 *
 * It does nothing at all for a workspace that has not chosen a threshold, which is every workspace
 * until someone says otherwise — see `ARCHITECTURE.md` §5. Nothing is deleted; the tasks move to
 * `archive/` in the same repo and one action in Settings brings them all back.
 *
 * Deliberately not folded into [SyncWorker]. Sync is a capability the app can live without and
 * archiving is what keeps the core loop fast, so hanging one off the other would make the thing that
 * matters depend on the thing that does not.
 */
class ArchiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? App)?.container ?: return Result.success()
        // Joining first: sweeping a workspace whose index has not been read yet would decide what is
        // finished from whatever the last run happened to leave behind.
        container.seeding.join()
        container.archiveNow()
        return Result.success()
    }

    companion object {
        private const val NAME = "yantra-archive"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                // KEEP, so re-scheduling on every launch does not reset the day and leave a
                // frequently-opened app never reaching the end of one.
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ArchiveWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
