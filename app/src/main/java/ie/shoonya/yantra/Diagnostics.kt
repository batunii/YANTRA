package ie.shoonya.yantra

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * What the app did, kept on disk instead of in logcat.
 *
 * **Why this exists.** [Trace] already says the right things, but it says them to logcat — a ring
 * buffer the system overwrites, any app can flood, and anyone can wipe with a single `-c`. Three
 * separate times in one debugging session the evidence for a live bug was gone before it could be
 * read: twice because the buffer had been cleared, once because the crash predated the phone being
 * plugged in. A report that arrives hours later ("it crashed earlier") has nothing behind it.
 *
 * So the same lines also go to a file, and the file is chosen so it can be read **without
 * `run-as`**: `getExternalFilesDir()` lives under `/sdcard/Android/data/<pkg>/files/`, which `adb`
 * can pull from a release build. `run-as` refuses on anything not debuggable, which is exactly the
 * build somebody is actually using, and that refusal is what made the app's own database
 * unreachable while a bug was live in it.
 *
 * **Nothing private goes in**, on the same rule [Trace] already keeps: ids, counts and states say
 * everything a bug needs. A task's title is somebody's day, and this file is meant to be handed to
 * whoever is fixing the problem.
 *
 * Pull it with:
 * ```
 * adb pull /sdcard/Android/data/ie.shoonya.yantra/files/diagnostics
 * ```
 */
object Diagnostics {

    /**
     * Big enough to hold the run-up to a crash, small enough to pull over a cable without thinking.
     * Two files, so a crash just after a rotation still has its history behind it.
     */
    const val MAX_BYTES = 1_000_000L

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * One thread, so lines land in the order they happened and no gesture ever waits on a disk.
     * A daemon, because a log is never a reason to keep a process alive.
     */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yantra-diagnostics").apply { isDaemon = true }
    }

    @Volatile private var sink: DiagnosticsSink? = null

    /** Starts recording, and arranges for the last thing a dying process does to be explaining itself. */
    fun install(app: Application) {
        sink = runCatching {
            DiagnosticsSink(File(app.getExternalFilesDir(null), "diagnostics"))
        }.getOrNull()

        append(header(app))

        // Chained, never replaced. The platform handler is what actually shows "app has stopped"
        // and ends the process; swallowing it would turn a crash into a freeze.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Written on this thread rather than handed to the executor: the process is about to
            // die and a queued line would die with it.
            runCatching {
                writeNow(
                    "\n=== CRASH on ${thread.name} at ${stamp.format(Date())} ===\n" +
                        stackOf(error) + "\n"
                )
            }
            previous?.uncaughtException(thread, error)
        }

        // A freeze leaves no exception, so nothing above would ever fire for one — and a freeze is
        // what a person actually reports. See [MainThreadWatchdog].
        runCatching {
            MainThreadWatchdog { stuckMs, stack ->
                record(
                    'E', "watchdog",
                    "main thread has not answered for ${stuckMs}ms",
                )
                append(stack.joinToString("\n") { "\tat $it" } + "\n")
            }.start()
        }
    }

    /** One line, in the same shape [Trace] prints to logcat. */
    fun record(level: Char, area: String, message: String, error: Throwable? = null) {
        val line = buildString {
            append(stamp.format(Date())).append(' ').append(level).append(' ')
            append(area).append(": ").append(message).append('\n')
            if (error != null) append(stackOf(error)).append('\n')
        }
        append(line)
    }

    /** Where the files are, for anything that wants to offer them up. */
    fun directory(context: Context): File? =
        sink?.dir ?: runCatching { File(context.getExternalFilesDir(null), "diagnostics") }.getOrNull()

    private fun append(text: String) {
        runCatching { writer.execute { runCatching { writeNow(text) } } }
    }

    private fun writeNow(text: String) {
        sink?.write(text)
    }

    private fun stackOf(error: Throwable): String {
        val out = StringWriter()
        PrintWriter(out).use { error.printStackTrace(it) }
        return out.toString().trimEnd()
    }

    /**
     * What build this was, said once per session.
     *
     * The build type is in here because a dogfood build is not minified and a release build is, and
     * a stack trace means very different work depending on which one produced it — obfuscated names
     * need the mapping file for that exact build, and mistaking one for the other wastes the first
     * ten minutes of every investigation.
     */
    private fun header(app: Application): String {
        val version = runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("unknown")
        return "\n=== session ${stamp.format(Date())} ===\n" +
            "app      ${app.packageName} $version\n" +
            "build    ${BuildConfig.BUILD_TYPE}${if (BuildConfig.DEBUG) " debuggable" else ""}\n" +
            "device   ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n" +
            "android  ${Build.VERSION.RELEASE} api ${Build.VERSION.SDK_INT}\n"
    }
}
