package ie.napkin.supertasks.ui.components

/**
 * YantraCheckbox — three-state task glyph with completion choreography.
 *
 * States: OPEN (bhupura gated square) → IN_PROGRESS (coral circle inside
 * neutral frame) → DONE (bare bindu + ink strike on the row title).
 *
 * Encodes all four fixes from the design audit:
 *  1. Static in-progress — the fill is constant; the only motion is the
 *     ring drawing in when the state is entered. No pulsing, ever.
 *  2. Reduced motion — when the system animator scale is 0, every
 *     transition becomes a ~200ms crossfade (alpha/color only). Haptic
 *     choreography is preserved as the feel channel.
 *  3. Bulk fast path — completions fired within BULK_WINDOW_MS of the
 *     previous one run the ~200ms short form (fade frame, pop bindu,
 *     quick strike) instead of the full ~450ms sequence.
 *  4. Theme-aware ink — coral steps lighter in dark theme to clear the
 *     3:1 non-text contrast bar at hairline stroke widths.
 *
 * Interaction mapping: tap = complete; tap a DONE task to undo. Marking a
 * task in progress is a swipe on the row, not a gesture on this glyph — see
 * SwipeToProgress in NodePageScreen.kt. The glyph therefore has one meaning
 * per press, and the two states you can reach by touching it are the two the
 * pen actually decides: finished, or not.
 */

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

// ---------------------------------------------------------------------------
// State + ink
// ---------------------------------------------------------------------------

enum class TaskState { OPEN, IN_PROGRESS, DONE }

// YantraInk, bhupuraPath, and drawPartialPath live in YantraGlyph.kt
// (same package) — the single source of the design language.

// ---------------------------------------------------------------------------
// Timing constants (single source of truth for the choreography)
// ---------------------------------------------------------------------------

private object Timing {
    const val FRAME_UNDRAW_MS = 280
    const val FRAME_UNDRAW_DELAY_MS = 40L
    const val RING_TRACE_MS = 220
    const val RING_COLLAPSE_MS = 180
    const val STRIKE_MS = 280
    const val STRIKE_DELAY_MS = 140L
    const val BINDU_DELAY_MS = 300L      // THUD haptic lands here
    const val REDUCED_MOTION_MS = 200
    const val SHORT_FORM_MS = 200        // bulk fast path
    const val BULK_WINDOW_MS = 800L      // Fix 3
    const val HOLD_ARM_MS = 400          // long-press windup draw duration
    const val HOLD_PREVIEW_DELAY_MS = 120L
}

/**
 * The choreography's two shared pieces, provided once by the theme.
 *
 * Tempo is deliberately app-wide rather than per-list: "am I clearing a backlog right now" is a
 * fact about the hand, not about which list the hand is over, so completing two tasks on two
 * screens inside the bulk window should still feel like one sweep.
 */
val LocalCompletionTempo = staticCompositionLocalOf { CompletionTempo() }
val LocalYantraHaptics = staticCompositionLocalOf<YantraHaptics?> { null }

/**
 * How long the ink strike takes to draw, for callers that overlay [InkStrike] on a row title. The
 * timing table itself stays private: it is one choreography, and a caller that could retune a
 * single leg of it would put the strike out of step with the bindu it lands beside.
 */
const val INK_STRIKE_MS = Timing.STRIKE_MS

/** Fix 3: shared tempo tracker. Hoist one instance per task list. */
class CompletionTempo {
    private var lastCompletionAt = 0L
    fun registerAndCheckBulk(): Boolean {
        val now = System.currentTimeMillis()
        val bulk = now - lastCompletionAt < Timing.BULK_WINDOW_MS
        lastCompletionAt = now
        return bulk
    }
}

/** Fix 2: Compose animations ignore the system animator scale, so read it
 *  ourselves. Zero means the user removed animations — crossfade only. */
fun isReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f

// ---------------------------------------------------------------------------
// Haptics: LOW_TICK on hold/press-arm, THUD on bindu landing.
// Falls back gracefully below API 30 / unsupported primitives.
// ---------------------------------------------------------------------------

/**
 * Haptics are decoration with a job, never a dependency: every call is guarded so a device that
 * refuses to vibrate — no motor, a restricted profile, a permission stripped by an OEM — loses the
 * feel and keeps the app. This was not hypothetical; the first run without the VIBRATE permission
 * took the process down from inside the hold windup.
 */
class YantraHaptics(context: Context, var enabled: Boolean = true) {
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    private fun primitivesOk(vararg p: Int): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                vibrator?.areAllPrimitivesSupported(*p) == true

    /** Swallows anything the vibrator service throws — see the note on the class. */
    private fun buzz(effect: () -> VibrationEffect) {
        val v = vibrator ?: return
        runCatching { v.vibrate(effect()) }
    }

    fun tick() {
        if (!enabled) return
        if (primitivesOk(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
            buzz {
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.6f
                    ).compose()
            }
        } else {
            buzz { VibrationEffect.createOneShot(10, 80) }
        }
    }

    fun thud() {
        if (!enabled) return
        if (primitivesOk(VibrationEffect.Composition.PRIMITIVE_THUD)) {
            buzz {
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_THUD, 0.8f
                    ).compose()
            }
        } else {
            buzz { VibrationEffect.createOneShot(30, 160) }
        }
    }
}

/** Wobbly ink strike across the title. Jitter is seeded by taskId so a
 *  given task's strike is stable across recompositions — the mark is yours. */
fun inkStrikePath(taskId: String, width: Float, midY: Float): Path {
    val rnd = Random(taskId.hashCode())
    fun jy() = midY + (rnd.nextFloat() - 0.5f) * 5f
    val p = Path()
    p.moveTo(0f, jy())
    var x = 0f
    val segments = 3 + rnd.nextInt(2)
    val step = width / segments
    repeat(segments) {
        val cx = x + step / 2f + (rnd.nextFloat() - 0.5f) * step * 0.2f
        x += step
        p.quadraticBezierTo(cx, jy(), x.coerceAtMost(width), jy())
    }
    return p
}

// ---------------------------------------------------------------------------
// The checkbox
// ---------------------------------------------------------------------------

@Composable
fun YantraCheckbox(
    state: TaskState,
    taskId: String,
    onComplete: () -> Unit,
    onUndo: () -> Unit,
    tempo: CompletionTempo,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    darkTheme: Boolean = false,
    haptics: YantraHaptics? = null,
    /**
     * Priority ink for the bhupura frame — crimson or amber, and only on a task list.
     *
     * The colour law puts priority on the frame and nowhere else: it is the world's claim on the
     * task, so it draws the enclosure, never the marks inside it. Null keeps the frame neutral,
     * which is what the focus view always passes (a crimson enclosure to stare at for 25 minutes
     * is ambient alarm) and what a done task gets, since a finished task has no urgency left.
     */
    frameTint: Color? = null,
) {
    val context = LocalContext.current
    val reduced = remember { isReducedMotion(context) }
    val coral = YantraInk.coral(darkTheme)
    val neutral = frameTint ?: YantraInk.neutral(darkTheme)
    val scope = rememberCoroutineScope()

    // Layer progress values. 1f = fully present.
    val frame = remember { Animatable(if (state == TaskState.DONE) 0f else 1f) }
    val ringDraw = remember {
        Animatable(if (state == TaskState.IN_PROGRESS) 1f else 0f)
    }
    val ringScale = remember { Animatable(1f) }
    val ringFillAlpha = remember {
        Animatable(if (state == TaskState.IN_PROGRESS) 0.18f else 0f)
    }
    val bindu = remember { Animatable(if (state == TaskState.DONE) 1f else 0f) }
    val squash = remember { Animatable(1f) }

    // React to state transitions with the right choreography.
    LaunchedEffect(state) {
        when (state) {
            TaskState.OPEN -> coroutineScope {
                launch { frame.animateTo(1f, tween(if (reduced) Timing.REDUCED_MOTION_MS else 250)) }
                launch { ringDraw.animateTo(0f, tween(200)) }
                launch { ringFillAlpha.animateTo(0f, tween(150)) }
                launch { ringScale.snapTo(1f) }
                launch { bindu.animateTo(0f, tween(150)) }
            }

            TaskState.IN_PROGRESS -> coroutineScope {
                haptics?.tick()
                if (reduced) {
                    launch { ringDraw.snapTo(1f) }
                    launch { ringFillAlpha.animateTo(0.18f, tween(Timing.REDUCED_MOTION_MS)) }
                } else {
                    launch { ringDraw.animateTo(1f, tween(320)) }
                    // Fix 1: static fill. The ring drawing in IS the state
                    // announcement; after that, nothing moves at rest.
                    launch { ringFillAlpha.animateTo(0.18f, tween(200)) }
                }
            }

            TaskState.DONE -> coroutineScope {
                val bulk = tempo.registerAndCheckBulk()
                when {
                    reduced -> {
                        // Fix 2: crossfade only. Haptics carry the feel.
                        launch { frame.animateTo(0f, tween(Timing.REDUCED_MOTION_MS)) }
                        launch { ringDraw.animateTo(0f, tween(Timing.REDUCED_MOTION_MS)) }
                        launch { ringFillAlpha.animateTo(0f, tween(Timing.REDUCED_MOTION_MS)) }
                        launch { bindu.animateTo(1f, tween(Timing.REDUCED_MOTION_MS)) }
                        haptics?.thud()
                    }
                    bulk -> {
                        // Fix 3: short form — quick pen flick down the list.
                        launch { frame.animateTo(0f, tween(120)) }
                        launch { ringDraw.animateTo(0f, tween(120)) }
                        launch { ringFillAlpha.animateTo(0f, tween(100)) }
                        launch {
                            delay(60)
                            haptics?.thud()
                            bindu.animateTo(
                                1f,
                                spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)
                            )
                        }
                    }
                    else -> {
                        // Full choreography, ~450ms, fully interruptible.
                        launch {
                            delay(Timing.FRAME_UNDRAW_DELAY_MS)
                            frame.animateTo(0f, tween(Timing.FRAME_UNDRAW_MS))
                        }
                        launch {
                            ringDraw.animateTo(1f, tween(Timing.RING_TRACE_MS))
                            launch { ringScale.animateTo(0.62f, tween(Timing.RING_COLLAPSE_MS)) }
                            launch { ringFillAlpha.animateTo(0f, tween(Timing.RING_COLLAPSE_MS)) }
                            ringDraw.animateTo(0f, tween(Timing.RING_COLLAPSE_MS))
                            ringScale.snapTo(1f)
                        }
                        launch {
                            delay(Timing.BINDU_DELAY_MS)
                            haptics?.thud()
                            bindu.animateTo(
                                1f,
                                spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)
                            )
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier
            .size(size)
            .pointerInput(state) {
                // Tap only. The glyph used to also take a long-press (with a windup that drew the
                // ring during the hold) to mark a task in progress; that gesture now lives as a
                // swipe on the row, so this target has exactly one meaning again — press it and the
                // task is finished.
                detectTapGestures(
                    onPress = {
                        scope.launch { squash.animateTo(0.88f, tween(100)) }
                        tryAwaitRelease()
                        scope.launch { squash.animateTo(1f, spring(dampingRatio = 0.6f)) }
                    },
                    onTap = {
                        when (state) {
                            TaskState.DONE -> onUndo()
                            else -> onComplete()
                        }
                    },
                    // Deliberately consumed and ignored. The glyph sits inside a block that takes
                    // long-press-to-reorder over its whole area, so leaving this unhandled let a
                    // slow press on the checkbox pick the row up and drop it somewhere else. Long
                    // press means nothing here — and saying so explicitly is what keeps it from
                    // meaning something else by accident.
                    onLongPress = {},
                )
            }
    ) {
        Canvas(Modifier.size(size)) {
            val s = this.size.minDimension
            scale(squash.value) {
                // Layer 1: bhupura frame (neutral; un-draws on completion)
                drawPartialPath(
                    bhupuraPath(s), frame.value, neutral, 1.6.dp.toPx()
                )
                // Layer 2: circle — in-progress fill + trace/collapse
                if (ringDraw.value > 0f) {
                    scale(ringScale.value) {
                        val r = s * 7.5f / 28f
                        if (ringFillAlpha.value > 0f) {
                            drawCircle(
                                coral.copy(alpha = ringFillAlpha.value),
                                radius = r, center = center
                            )
                        }
                        val circle = Path().apply {
                            addOval(
                                androidx.compose.ui.geometry.Rect(
                                    center - Offset(r, r), center + Offset(r, r)
                                )
                            )
                        }
                        drawPartialPath(circle, ringDraw.value, coral, 1.6.dp.toPx())
                    }
                }
                // Layer 3: bindu
                if (bindu.value > 0f) {
                    drawCircle(
                        coral,
                        radius = s * 3.6f / 28f * bindu.value,
                        center = center
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Ink strike overlay for the row title. Drive `progress` 0→1 alongside
// the DONE transition (delay STRIKE_DELAY_MS, tween STRIKE_MS; snap in
// reduced motion / tween 120ms in bulk mode). Reverse for undo.
// ---------------------------------------------------------------------------

@Composable
fun InkStrike(
    taskId: String,
    progress: Float,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false,
    strokeWidth: Dp = 2.2.dp,
) {
    val coral = YantraInk.coral(darkTheme)
    Canvas(modifier) {
        if (progress <= 0f) return@Canvas
        // 44% height: through the x-height, like a real pen strike.
        val path = inkStrikePath(taskId, size.width, size.height * 0.44f)
        val pm = PathMeasure().apply { setPath(path, false) }
        val seg = Path()
        pm.getSegment(0f, pm.length * progress.coerceIn(0f, 1f), seg, true)
        drawPath(
            seg, coral,
            style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round)
        )
    }
}

// ---------------------------------------------------------------------------
// Linger contract (list-level, for reference):
//
//   1. On DONE, keep the row in place for LINGER_MS = 1800 before letting
//      animateItemPlacement collapse it out (or keep struck rows until the
//      user leaves the list — flag it, both are defensible).
//   2. During the linger, tap = onUndo(): reverse strike + bindu, redraw
//      frame. The linger IS the undo window; no snackbar needed.
//   3. Departure animation: fade + 12dp slide, 250ms, never a big travel
//      (reduced motion: fade only).
// ---------------------------------------------------------------------------
