package ie.shoonya.yantra.domain

import java.util.Locale

/**
 * How long a session reads, everywhere it is read.
 *
 * `M:SS`, growing an hours field only once there is an hour to report — the shape Android's own
 * [android.widget.Chronometer] ticks in, which matters because half the surfaces here show a
 * chronometer while it runs and this string the moment it stops. A running session that switched
 * format on pause would look like a different measurement.
 *
 * This was four functions: [SessionNotification]'s, the two widgets', and the running row's, each
 * with its own copy of the same arithmetic, the same doc comment claiming to match the chronometer,
 * and the same missing locale. One rule, one place.
 *
 * The locale is the device's, deliberately and explicitly. `Chronometer` formats with the default
 * locale, so on a phone set to a numeral system other than Latin the notification would otherwise
 * change digits at the moment you paused it.
 */
fun sessionClock(secs: Int): String {
    val s = secs.coerceAtLeast(0)
    val h = s / 3600
    val l = Locale.getDefault()
    return if (h > 0) String.format(l, "%d:%02d:%02d", h, (s % 3600) / 60, s % 60)
    else String.format(l, "%d:%02d", s / 60, s % 60)
}
