package ie.shoonya.yantra

import android.util.Log

/**
 * A running commentary the app writes about itself, readable over `adb logcat -s YANTRA`.
 *
 * **Why this exists.** Reporting a bug in a phone app costs the person reporting it far more than it
 * costs the person fixing it: you have to notice, remember, describe, and then describe again when
 * the description turns out to be ambiguous. Three rounds of "it disappears sometimes" is three
 * rounds nobody enjoys, and the thing that ends it is the app saying plainly what it just did.
 *
 * So the rule here is that every line answers **what happened and what was decided**, not what
 * function was entered. "matched meeting to node n1" is worth a line; "onCreate" is not.
 *
 * It survives minification deliberately — no `assumenosideeffects` rule strips it — because the
 * build that needs explaining is the release build somebody is actually using. The cost is a handful
 * of short strings on gestures a person makes by hand, which is nothing.
 *
 * Nothing private goes in. A meeting's title is somebody's day and an attendee list is somebody's
 * address book; ids and counts say everything a bug needs and nothing a person would mind being in
 * a log file.
 */
object Trace {

    const val TAG = "YANTRA"

    /** One thing that happened, in the area it happened in. */
    fun log(area: String, message: String) {
        Log.i(TAG, "$area: $message")
    }

    /** Something that went wrong but did not stop the app — the interesting kind. */
    fun warn(area: String, message: String) {
        Log.w(TAG, "$area: $message")
    }

    /** An id, shortened to something a person can compare at a glance without being a UUID. */
    fun id(value: String?): String = when {
        value == null -> "none"
        value.length <= 8 -> value
        else -> value.take(8)
    }

    /**
     * An external identity, said without saying it.
     *
     * A UID is not secret but it is a handle on somebody's meeting, and there is no reason for a log
     * to carry the whole of one. The head and the length identify it well enough to tell two apart
     * and to see when one changed.
     */
    fun uid(value: String?): String = when {
        value == null -> "none"
        value.length <= 10 -> value
        else -> "${value.take(6)}…${value.length}"
    }
}
