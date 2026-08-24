package ie.napkin.supertasks.ui.pomodoro

/**
 * YantraFocus — the pomodoro feature's rendering layer.
 *
 * Separate from the task checkbox by design: the checkbox owns task state
 * (open / in progress / done); this file owns focus time. They share only
 * the design language in YantraGlyph.kt — the bhupura path, the inks.
 *
 * THE VISUAL SYSTEM (as converged, in full):
 *
 *  - The strata are a sequential timeline reading outward from the
 *    center: a trikona OPENS each day, followed by one circle per
 *    session that day.  Day 1: triangle, ring, ring, ring.  Day 2:
 *    triangle, ring, ring.  One mark per event, no legend needed.
 *  - Trikonas alternate orientation per day — day one points DOWN
 *    (the Shakti trikona, the traditional innermost orientation),
 *    day two up, and so on, echoing the interlocking convention.
 *  - The live session draws a coral arc at the track (radius 8 — the
 *    task's own circle, so list glyph and focus glyph agree). Breaks
 *    drain a neutral arc: rest is the ink receding.
 *  - When a session closes, its ring travels from the track inward and
 *    parks on the stack (bright while moving, fading to rest weight).
 *    The THUD haptic fires when the ring PARKS — the reward is the
 *    deposit, not the bell. A day's first session lays its trikona
 *    first (fade-in with a 12°→0° settle — the single sanctioned
 *    rotation in the app), then its ring follows.
 *  - Every arrival redistributes the whole stack: existing marks slide
 *    to their recomputed slots, so arbitrary day/session counts fit
 *    forever. A canceled session deposits nothing.
 *  - The frame is ALWAYS neutral here. Priority never enters the focus
 *    view (staring at a crimson enclosure for 25 minutes is ambient
 *    alarm); it survives as a text label on the screen, not on the
 *    glyph. Coral marks only the user's own effort.
 *  - The bindu is constant (1.6u). It is the center, not a gauge.
 *    Exact counts belong to the numeral/tally BELOW the glyph — digits
 *    over the ring band fight the strata.
 *  - Nothing moves at rest. The only continuous motion anywhere is the
 *    session arc itself: one revolution per 25 minutes.
 *
 * Everything renders as a pure function of the session log
 * (List<Instant> per task) — nothing new to store or sync. Day
 * boundaries are derived from timestamps: the first session after
 * midnight simply opens with a trikona.
 */

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ie.napkin.supertasks.ui.components.YantraInk
import ie.napkin.supertasks.ui.components.bhupuraPath
import ie.napkin.supertasks.ui.components.drawPartialPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Data: session log → per-day counts → marks
// ---------------------------------------------------------------------------

/** Group a task's session timestamps into chronological per-day counts.
 *  [3, 2] = 3 sessions the first day, 2 the next. */
fun groupSessionsByDay(
    sessions: List<Instant>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Int> =
    sessions.sorted()
        .groupBy { it.atZone(zone).toLocalDate() }
        .toSortedMap()
        .values.map { it.size }

sealed interface StrataMark {
    /** Opens a day. Day index 0 points DOWN (Shakti), alternating after. */
    data class DayOpen(val pointsUp: Boolean) : StrataMark
    object Session : StrataMark
}

/** The sequential timeline: trikona, then that day's rings; repeat. */
fun strataMarks(dayCounts: List<Int>): List<StrataMark> = buildList {
    dayCounts.forEachIndexed { day, count ->
        add(StrataMark.DayOpen(pointsUp = day % 2 == 1))
        repeat(count) { add(StrataMark.Session) }
    }
}

// ---------------------------------------------------------------------------
// Constants (28-unit design space; "u" = px per unit at render)
// ---------------------------------------------------------------------------

object Strata {
    const val INNER = 2.8f          // innermost slot
    const val OUTER = 7.2f          // outermost slot
    const val TRACK = 8f            // the task's circle — sessions draw here
    const val BINDU_R = 1.6f

    const val RING_W = 0.16f        // session ring stroke
    const val RING_REST_A = 0.42f
    const val RING_TRAVEL_A = 0.9f  // bright while depositing
    const val TRI_W = 0.28f         // trikona stroke
    const val TRI_REST_A = 0.55f
    const val TRI_SETTLE_DEG = 12f  // the one sanctioned rotation
    const val TRACK_W = 0.8f
    const val ARC_W = 1.7f

    const val TRI_IN_MS = 350       // trikona fade + settle
    const val DEPOSIT_MS = 550      // ring travel from track to slot
    const val REDISTRIBUTE_MS = 500 // existing marks sliding to new slots
    const val GIVE_UP_MS = 350      // caller: arc reversal, no deposit
}

fun slotRadius(index: Int, total: Int): Float =
    if (total > 1) Strata.INNER + (Strata.OUTER - Strata.INNER) * index / (total - 1)
    else Strata.INNER

// ---------------------------------------------------------------------------
// Per-mark animation state
// ---------------------------------------------------------------------------

private class MarkAnim(radius: Float, alpha: Float, rotation: Float) {
    val radius = Animatable(radius)
    val alpha = Animatable(alpha)
    val rotationDeg = Animatable(rotation)
}

private fun restAlpha(mark: StrataMark) = when (mark) {
    is StrataMark.DayOpen -> Strata.TRI_REST_A
    StrataMark.Session -> Strata.RING_REST_A
}

// ---------------------------------------------------------------------------
// The focus glyph
// ---------------------------------------------------------------------------

/**
 * [dayCounts]: from groupSessionsByDay — the whole lifetime, not just today.
 * [sessionProgress]: 0..1 through the current session; null = idle.
 * [onBreak]: true renders the arc neutral; the caller drives progress
 *            1f → 0f over the break (rest as the ink receding).
 * [reducedMotion]: marks snap to their slots; no travel, no settle.
 * [onDeposit]: fired when a session ring PARKS — call haptics.thud() here.
 *
 * Give up = the caller animates sessionProgress back to 0f over
 * GIVE_UP_MS and never appends to the session log — a canceled loop
 * leaves no trace, so this composable never sees it.
 */
@Composable
fun YantraFocusGlyph(
    dayCounts: List<Int>,
    sessionProgress: Float?,
    onBreak: Boolean,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false,
    reducedMotion: Boolean = false,
    onDeposit: (() -> Unit)? = null,
) {
    val coral = YantraInk.coral(darkTheme)
    val neutral = YantraInk.neutral(darkTheme)
    val marks = strataMarks(dayCounts)
    val anims = remember { mutableStateListOf<MarkAnim>() }

    LaunchedEffect(marks.size) {
        // Reset (e.g. task switched): rebuild in place, no ceremony.
        if (anims.size > marks.size) {
            anims.clear()
        }

        val firstNew = anims.size
        // Instantiate newcomers at their entrance states.
        for (i in firstNew until marks.size) {
            anims += when (val m = marks[i]) {
                is StrataMark.DayOpen -> MarkAnim(
                    radius = slotRadius(i, marks.size),   // appears AT its slot
                    alpha = 0f,
                    rotation = Strata.TRI_SETTLE_DEG,
                )
                StrataMark.Session -> MarkAnim(
                    radius = Strata.TRACK,                // travels FROM the track
                    alpha = Strata.RING_TRAVEL_A,
                    rotation = 0f,
                )
            }
        }

        if (reducedMotion) {
            marks.forEachIndexed { i, m ->
                launch {
                    anims[i].radius.snapTo(slotRadius(i, marks.size))
                    anims[i].alpha.snapTo(restAlpha(m))
                    anims[i].rotationDeg.snapTo(0f)
                }
            }
            if (firstNew < marks.size && marks.last() == StrataMark.Session) {
                onDeposit?.invoke()   // the thud still lands
            }
            return@LaunchedEffect
        }

        // Existing marks redistribute to their recomputed slots.
        for (i in 0 until firstNew) {
            launch {
                anims[i].radius.animateTo(
                    slotRadius(i, marks.size), tween(Strata.REDISTRIBUTE_MS)
                )
            }
        }

        // Newcomers: trikona settles first, its ring follows.
        var stagger = 0L
        for (i in firstNew until marks.size) {
            val m = marks[i]
            val startAfter = stagger
            launch {
                delay(startAfter)
                when (m) {
                    is StrataMark.DayOpen -> {
                        launch { anims[i].alpha.animateTo(Strata.TRI_REST_A, tween(Strata.TRI_IN_MS)) }
                        anims[i].rotationDeg.animateTo(0f, tween(Strata.TRI_IN_MS))
                    }
                    StrataMark.Session -> {
                        launch { anims[i].alpha.animateTo(Strata.RING_REST_A, tween(Strata.DEPOSIT_MS)) }
                        anims[i].radius.animateTo(
                            slotRadius(i, marks.size), tween(Strata.DEPOSIT_MS)
                        )
                        onDeposit?.invoke()   // fires when the ring PARKS
                    }
                }
            }
            stagger += if (m is StrataMark.DayOpen) Strata.TRI_IN_MS.toLong() else 0L
        }
    }

    Canvas(modifier.aspectRatio(1f)) {
        val u = size.minDimension / 28f

        // Frame: ALWAYS neutral in the focus view.
        drawPartialPath(bhupuraPath(size.minDimension), 1f, neutral, 1.4.dp.toPx())

        // Track: where sessions happen.
        drawCircle(
            neutral.copy(alpha = 0.25f), radius = Strata.TRACK * u, center = center,
            style = Stroke(Strata.TRACK_W * u)
        )

        // Strata pass 1 — trikonas, UNDER the rings.
        marks.forEachIndexed { i, m ->
            if (m is StrataMark.DayOpen && i < anims.size) {
                drawTrikona(
                    circumradius = anims[i].radius.value * u,
                    pointsUp = m.pointsUp,
                    rotationDeg = anims[i].rotationDeg.value,
                    color = coral.copy(alpha = anims[i].alpha.value),
                    strokeWidth = Strata.TRI_W * u,
                )
            }
        }

        // Strata pass 2 — session rings, over.
        marks.forEachIndexed { i, m ->
            if (m == StrataMark.Session && i < anims.size) {
                drawCircle(
                    coral.copy(alpha = anims[i].alpha.value),
                    radius = anims[i].radius.value * u,
                    center = center,
                    style = Stroke(Strata.RING_W * u)
                )
            }
        }

        // Now: the live arc, sweeping from 12 o'clock.
        sessionProgress?.let { p ->
            drawArc(
                color = if (onBreak) neutral else coral,
                startAngle = -90f,
                sweepAngle = 360f * p.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = center - Offset(Strata.TRACK * u, Strata.TRACK * u),
                size = Size(Strata.TRACK * u * 2, Strata.TRACK * u * 2),
                style = Stroke(Strata.ARC_W * u, cap = StrokeCap.Round)
            )
        }

        // Center: constant. The bindu is the center, not a gauge.
        drawCircle(coral, radius = Strata.BINDU_R * u, center = center)
    }
}

private fun DrawScope.drawTrikona(
    circumradius: Float,
    pointsUp: Boolean,
    rotationDeg: Float,
    color: Color,
    strokeWidth: Float,
) {
    val base = (if (pointsUp) -90f else 90f) + rotationDeg
    val path = Path()
    for (k in 0..2) {
        val a = Math.toRadians((base + k * 120f).toDouble())
        val x = center.x + circumradius * cos(a).toFloat()
        val y = center.y + circumradius * sin(a).toFloat()
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color, style = Stroke(strokeWidth, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

// ---------------------------------------------------------------------------
// Wiring notes (the screen around the glyph):
//
//  - Numeral + phase + tally render BELOW the glyph as ordinary text:
//    "09:12 / focus / session 8 · day 3 · 3h 05m on this task".
//  - Priority appears as a small text label on the screen — never as
//    frame color here.
//  - Session close order: arc reaches full → arc hides → trikona (if
//    day-first) → ring deposit → onDeposit → haptics.thud() → break.
//  - Break: sessionProgress 1f → 0f with onBreak = true. Skipping a
//    break just starts the next session.
//  - Give up: animate sessionProgress → 0f over GIVE_UP_MS; do NOT
//    append to the session log.
//  - Long break: schedule by accumulated focus time (~90 min), never by
//    session count — the timeline assumes no cadence.
//  - Day rollover: derived. The first session whose timestamp falls on a
//    new local date opens with a trikona; there is no rollover event.
//  - Only COMPLETED sessions mark the timeline: the log is appended at
//    session close, so a trikona and its day's first ring always arrive
//    together — a lone trikona cannot occur, and an abandoned session
//    (any time of day) leaves no trace.
//  - The task LIST glyph never shows strata; between sessions it shows
//    the ordinary in-progress circle. During a live session the list row
//    may show the arc on its own checkbox — the single sanctioned
//    at-rest motion (one revolution per session) — or a static
//    minute-step variant if even that proves too loud.
//  - Ambient/AOD: this glyph is deliberately still and hairline; it can
//    serve as an always-on view without modification.
// ---------------------------------------------------------------------------
