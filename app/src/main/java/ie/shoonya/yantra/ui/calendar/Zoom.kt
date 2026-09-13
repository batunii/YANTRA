package ie.shoonya.yantra.ui.calendar

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How tall an hour is — CALENDAR_PLAN.md §17.
 *
 * A composition local rather than a parameter, because it is read in nineteen places across six
 * composables and every one of them is already in a composition. Threading it would work and would
 * also be nineteen chances for one caller to pass something different from its neighbour, which on a
 * timeline means a block drawn against a ruler it does not agree with.
 */
val LocalHourHeight = compositionLocalOf { DEFAULT_HOUR_HEIGHT }

/** The height it has always been, and what it goes back to. */
val DEFAULT_HOUR_HEIGHT: Dp = 60.dp

/**
 * Small enough that a working day fits a phone without scrolling — about eight in the morning to
 * eight at night on a 360dp screen. Below this the words stop fitting and it becomes a chart.
 */
val MIN_HOUR_HEIGHT: Dp = 26.dp

/**
 * Large enough that a fifteen-minute block is a real target rather than a line. Above this you are
 * scrolling through a day a couple of hours at a time, which is a worse way to see one.
 */
val MAX_HOUR_HEIGHT: Dp = 180.dp

/** Clamped, so a pinch can be enthusiastic without producing a timeline nobody can read. */
fun Dp.clampedHourHeight(): Dp = coerceIn(MIN_HOUR_HEIGHT, MAX_HOUR_HEIGHT)

/**
 * Remembered on this device, and only here.
 *
 * How tall you like your hours is not a fact about the work, so it has no business in a repository
 * that two people might share — and re-pinching on every visit would be worse than having no zoom at
 * all, which is what makes persisting it the whole point rather than a nicety.
 */
fun loadHourHeight(context: Context): Dp {
    val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY, 0f)
    return if (saved <= 0f) DEFAULT_HOUR_HEIGHT else saved.dp.clampedHourHeight()
}

fun saveHourHeight(context: Context, height: Dp) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putFloat(KEY, height.value)
        .apply()
}

private const val PREFS = "yantra_settings"
private const val KEY = "calendar_hour_height"
