package ie.shoonya.yantra

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Notices when the main thread stops answering, and writes down what it was doing.
 *
 * **Why a crash handler was not enough.** The app froze and was killed and [Diagnostics] recorded
 * nothing at all — correctly, because there was nothing to record. An ANR is not an exception: the
 * thread is alive and busy, no throwable is ever constructed, and the process is taken by the
 * system a few seconds later. A log built to catch crashes is blind to the one failure a person
 * actually describes as "it froze".
 *
 * So this watches instead of waiting to be told. A ticker posts a message to the main looper and
 * checks later whether it came back. If it did not, the main thread's stack is dumped — *while it
 * is still stuck*, which is the whole value: the stack after the fact is useless, and the system's
 * own trace file is unreadable without root.
 *
 * Deliberately not a fix for anything. It makes a freeze leave evidence, which is what turned a
 * ten-second hang into a one-line diagnosis the last time: `ViewModelStore.clear → cancel →
 * editPage → writePage → writeBytesAtomically`, blocked in `openat`.
 */
class MainThreadWatchdog(
    private val stuckAfterMs: Long = STUCK_AFTER_MS,
    private val pollMs: Long = POLL_MS,
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val report: (Long, Array<StackTraceElement>) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val watcher = Handler(watcherLooper())

    /** Bumped by the main thread every time it gets a turn; read by the watcher. */
    @Volatile private var lastSeen = now()

    /** So one long freeze is reported once, rather than every poll until it ends. */
    @Volatile private var reported = false

    fun start() {
        watcher.post(object : Runnable {
            override fun run() {
                check()
                watcher.postDelayed(this, pollMs)
            }
        })
    }

    private fun check() {
        val since = now() - lastSeen
        if (since < stuckAfterMs) {
            reported = false
            // Ask again. A message that never comes back is exactly the symptom being watched for,
            // so this is posted rather than awaited.
            main.post { lastSeen = now() }
            return
        }
        if (reported) return
        reported = true
        // Taken while it is still stuck. The system's own trace lands in /data/anr, which needs
        // root to read; this one lands somewhere `adb pull` can reach.
        report(since, Looper.getMainLooper().thread.stackTrace)
    }

    private companion object {
        /**
         * Under the system's own ten seconds, so the evidence is written before the process is
         * taken — and well over any pause a person would not notice.
         */
        const val STUCK_AFTER_MS = 4_000L
        const val POLL_MS = 1_000L

        fun watcherLooper(): Looper =
            android.os.HandlerThread("yantra-watchdog").apply {
                isDaemon = true
                start()
            }.looper
    }
}
