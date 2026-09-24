package ie.shoonya.yantra

import java.io.File

/**
 * The two files [Diagnostics] writes, and the rule for when one becomes the other.
 *
 * Separated from [Diagnostics] because that object is all Android — an `Application`, an external
 * files directory, a default exception handler — and none of that is the part that can be wrong.
 * What can be wrong is the part with arithmetic in it: whether the cap holds, whether rotation
 * loses the very lines somebody is about to ask for, whether a full disk takes the app down with
 * it. That part is here, and it is tested on the JVM.
 */
class DiagnosticsSink(
    val dir: File,
    private val maxBytes: Long = Diagnostics.MAX_BYTES,
) {
    val current: File get() = File(dir, "session.log")
    val previous: File get() = File(dir, "previous.log")

    /**
     * Appends, rotating first when the current file has outgrown the cap.
     *
     * Rotated *before* the write rather than after, so the cap is a ceiling rather than a
     * suggestion. Exactly one generation is kept: this is a log to read this week, and an
     * unbounded pile of them on a phone is a different bug.
     *
     * Never throws. A log that can take the app down is worse than no log — the disk being full is
     * already somebody's bad day, and losing their work on top of it because the *diagnostics*
     * failed would be indefensible.
     */
    fun write(text: String) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val file = current
            if (file.length() > maxBytes) {
                previous.delete()
                file.renameTo(previous)
            }
            file.appendText(text)
        }
    }
}
