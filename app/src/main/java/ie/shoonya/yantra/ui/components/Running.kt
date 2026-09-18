package ie.shoonya.yantra.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ie.shoonya.yantra.domain.RunningTask
import ie.shoonya.yantra.domain.sessionClock
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraDisplay
import ie.shoonya.yantra.ui.theme.YantraMono
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.absoluteValue
import ie.shoonya.yantra.ui.theme.YantraType
import androidx.compose.ui.draw.drawBehind
import ie.shoonya.yantra.ui.theme.YantraRadius
import ie.shoonya.yantra.ui.theme.YantraText
import androidx.compose.foundation.layout.Arrangement
import ie.shoonya.yantra.data.label.LabelPalette

/**
 * The task you are on, for the few places that draw it.
 *
 * A composition local rather than a parameter threaded through every screen, because this is
 * deliberately the one thing that looks the same everywhere — a row on a smart list, a line on a
 * page, and the bar itself all read the same state, and none of them should be able to be given a
 * different one by whoever wired the screen up.
 *
 * It carries the flow rather than the value so that a per-second tick recomposes only what is
 * actually showing a clock. Handing down the unwrapped state would put every list row in the app on
 * a one-second recomposition loop to render nothing.
 */
val LocalNow = staticCompositionLocalOf<StateFlow<List<RunningTask.Now>>> { MutableStateFlow(emptyList()) }

/** The running row's clock. See [sessionClock] — the same reading the notification shows. */
fun elapsedLabel(seconds: Int): String = sessionClock(seconds)

/**
 * Which tasks are on the go.
 *
 * Changes only when the set changes — never on the tick. That distinction is the whole reason this
 * exists separately from [ElapsedSlot]: every task row in the app asks this question, and a value
 * that changed every second would put the entire list on a one-second recomposition loop to answer
 * "still not me".
 *
 * The `flow.value` read below is the seed for `collectAsStateWithLifecycle`, not a substitute for
 * collecting it — the collector underneath is what makes this recompose. Reading it is what stops
 * the first frame claiming nothing is running while the subscription is still being set up, which
 * on a cold start into a live session is a visible flicker of the wrong answer.
 */
@Composable
@android.annotation.SuppressLint("StateFlowValueCalledInComposition")
fun startedTaskIds(): Set<String> {
    val flow = LocalNow.current
    val ids by remember(flow) {
        flow.map { list -> list.map { it.nodeId }.toSet() }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = flow.value.map { it.nodeId }.toSet())
    return ids
}

/**
 * Which task has a clock actually running on it, or null.
 *
 * Distinct from [startedTaskIds], and the distinction matters in the trailing slot: a task you have
 * picked up but not timed has nothing to put there, so its schedule chip must stay. Stable across
 * the tick, for the same reason.
 */
@Composable
@android.annotation.SuppressLint("StateFlowValueCalledInComposition")
fun timingTaskId(): String? {
    val flow = LocalNow.current
    val id by remember(flow) {
        flow.map { list -> list.firstOrNull { it.hasSession }?.nodeId }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = flow.value.firstOrNull { it.hasSession }?.nodeId)
    return id
}

/**
 * The trailing slot on the running row: elapsed time, in the accent.
 *
 * Only while a session is actually running. A task you have merely said you are on has no elapsed
 * time to report — the ring and the wash already say what is true about it, and a clock reading
 * 0:00 would claim a measurement nobody started. This is also what a device that only received the
 * flag through sync shows: the claim travelled, the stopwatch did not.
 */
@Composable
fun ElapsedSlot(nodeId: String, modifier: Modifier = Modifier) {
    val y = Yantra.colors
    val now by LocalNow.current.collectAsStateWithLifecycle()
    val elapsed = now.firstOrNull { it.nodeId == nodeId }?.elapsedSecs ?: return
    Text(
        elapsedLabel(elapsed),
        fontFamily = YantraMono,
        fontSize = YantraType.caption,
        fontWeight = FontWeight.W700,
        color = y.accent,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * The glyph's running state, drawn once and not animated: the neutral bhupura with the ring closed
 * inside it.
 *
 * [YantraCheckbox] is the interactive one, and it carries a swipe, three animatables and a
 * completion choreography. None of that belongs on a bar that is only reporting; this is the same
 * two layers of the design language with nothing behind them.
 */
@Composable
fun RunningGlyph(
    /** The gate. Neutral, like every other frame in the app — structure is not a hue. */
    frameTint: Color,
    /** The ring inside it. The accent, because being on something is your own effort. */
    ringTint: Color,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        // Proportional, not the checkbox's flat 1.6dp. That figure is tuned against a 30dp row
        // glyph; carried onto a 22dp one it is half again as heavy relative to the shape, and the
        // ring thickens into the frame until the two read as one blob rather than a mark inside a
        // gate. Scaling it keeps the drawing the same drawing at any size.
        val stroke = Stroke(s * 1.6f / 30f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(bhupuraPath(s), frameTint, style = stroke)
        val r = s * 7.5f / 28f
        drawPath(
            Path().apply { addOval(Rect(center - Offset(r, r), center + Offset(r, r))) },
            ringTint,
            style = stroke,
        )
    }
}

/**
 * The player: a thin bar at the very bottom holding whatever you are on.
 *
 * Shaped as the header's reflection — full bleed, rounded at the top where the band is rounded at
 * the bottom — so the screen is a sheet of paper held between two folds. Much thinner than the
 * header, because the header names where you are and this only reports what is running.
 *
 * **Two targets, two meanings, and the split is the point.** The button starts an *open* stopwatch
 * right here, because "start counting" is a control you press in passing and it promises nothing
 * about how long. The body opens the focus screen, where you commit to a length. Those are the two
 * instruments [ie.shoonya.yantra.domain.FocusTimer] already has; the player is just the first one
 * finally getting a control of its own instead of a three-step trip through a screen.
 *
 * **Only the running task wears the accent.** The colour law gives it to effort, and a task you have
 * merely picked up is not that yet — paint the whole bar coral and the one task actually counting
 * has nothing left to distinguish it.
 */
@Composable
fun NowPlayer(
    stack: List<RunningTask.Now>,
    onOpen: (RunningTask.Now) -> Unit,
    onToggleClock: (RunningTask.Now) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (stack.isEmpty()) return
    val y = Yantra.colors
    val density = LocalDensity.current

    /**
     * The deck holds everything you have picked up.
     *
     * CALENDAR_UI.md §4 proposed ranks one and two only, on the grounds that rank three onwards is
     * a list of claims you made and never cleared. That is true of the *list* and false of the
     * *bar*: three tasks in progress and two on the deck means the third has no representation
     * anywhere on the screen you are looking at, and a task you cannot see is a task you will not
     * clear. Overruled deliberately — the count is the thing worth knowing, and the swipe is
     * cheap.
     *
     * `RunningTask.stack` still ranks it: the timed card, then whatever is scheduled now, then
     * everything else newest-first. That ordering is what makes a long deck usable — the front of
     * it is always about this moment.
     */
    val dealt = stack

    /**
     * Which task is showing, held **by id rather than by position**.
     *
     * The list re-sorts whenever a clock starts or stops — the timed task is dealt to the front —
     * so an index is a pointer into a list that moves underneath it. Held positionally, pressing
     * play reordered the stack and slot 2 quietly became a different task: the button then acted on
     * whatever had slid under it, which is how a press meant to start one task stopped another.
     * An id cannot drift. If the task leaves the stack entirely, the front of it is the honest
     * fallback.
     */
    var selected by remember { mutableStateOf<String?>(null) }
    val index = dealt.indexOfFirst { it.nodeId == selected }.takeIf { it >= 0 } ?: 0
    val current = dealt[index]
    val live = current.hasSession

    // Live drag offset, read only inside graphicsLayer — a draw-phase read, so swiping the player
    // relayouts nothing and recomposes nothing.
    var dragX by remember { mutableFloatStateOf(0f) }
    var barW by remember { mutableIntStateOf(0) }
    val commit = with(density) { 56.dp.toPx() }

    val dragState = rememberDraggableState { delta ->
        dragX = (dragX + delta).coerceIn(-commit * 1.8f, commit * 1.8f)
    }
    // Full strength, for the eyebrow. Everything else on a running bar steps back.
    val accentInk = y.accent
    // The spine is the workspace, here and everywhere it appears. One device, one meaning.
    //
    // It carried the list before, which put two questions on one 3dp rule: on a widget row the same
    // idiom meant "which repository" and here it meant "which list", and with five swatches there
    // is no telling those apart. The workspace wins it because there are two or three of them and
    // dozens of lists, and because it is the one fact with no room for its word on a widget row.
    //
    // **Nothing at all while only one workspace is open**, rather than a neutral rule.
    //
    // It drew in frame ink, which is what "no colour chosen" looks like on a list mark — and on a
    // near-black bar frame ink is #B4B2A9, so the brightest thing on the player was a stripe with
    // nothing to say. Neutral is not quiet here; it is just a different loud.
    //
    // It is also the rule the spine already rests on, followed one step further: a mark that always
    // means the same thing means nothing, so when there is no repository to name there is no mark.
    // The bar keeps its wash, its glyph and its eyebrow, all of which are saying something.
    val spineInk = LabelPalette.byName(current.workspaceColour)
        ?.let { Color(LabelPalette.display(it.light, y.isDark)) }
    // The list is a word in the eyebrow, wearing its own colour. A hue is a glance and a word is
    // the fact; neither has to carry the other, which is what makes a repeated hue a coincidence
    // rather than an ambiguity.
    // Held back to 72%. A palette swatch is mixed to one lightness across every hue so no colour
    // out-shouts another *at full strength* — which is right for a label you are meant to find, and
    // too much for a line that is only telling you where you already are. The hue survives the
    // knock-down; the shout does not.
    val listInk = (
        LabelPalette.byName(current.listColour)
            ?.let { Color(LabelPalette.display(it.light, y.isDark)) }
            ?: y.textMuted
        ).copy(alpha = 0.72f)
    val shape = RoundedCornerShape(topStart = YantraRadius.sheet, topEnd = YantraRadius.sheet)

    Row(
        modifier
            .fillMaxWidth()
            .onSizeChanged { barW = it.width }
            // Running is a wash and a spine, not a flood — CALENDAR_UI.md §4.
            //
            // The bar used to fill solid with the accent, which made every word on it a reversed
            // colour and the whole surface the loudest thing on the screen. CALENDAR_PLAN.md §16
            // already made the opposite call for a coloured block: replace the spine, tint the
            // wash, do not flood the fill. A bar is a block that happens to be at the bottom.
            .background(if (live) y.accentFill else y.band, shape)
            // The spine is identity; the wash is state.
            //
            // It used to appear only while running, which made it a fourth way of saying something
            // the wash, the glyph's ring and the eyebrow already said. Now it says the one thing
            // this bar could not otherwise say: which repository the task you are on came from.
            // The list is already a word in the eyebrow, in the list's own colour, so neither fact
            // is read out of a hue alone.
            //
            // Always drawn, so the mark has exactly one meaning with no case to remember. With a
            // single workspace open it is frame ink, which is what "nothing to tell apart" looks
            // like everywhere else in the app.
            //
            // Inset, because this surface has rounded top corners and a block does not.
            .then(if (spineInk == null) Modifier else Modifier.spine(spineInk, inset = 10.dp))
            .then(
                if (dealt.size < 2) Modifier else Modifier.draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        // A flick counts even if it did not travel far — waiting for the full
                        // distance makes a bar feel stuck to anyone who flicks rather than drags.
                        val fwd = dragX <= -commit || velocity <= -700f
                        val back = dragX >= commit || velocity >= 700f
                        val out = (barW.takeIf { it > 0 } ?: 1000).toFloat()
                        if (fwd || back) {
                            // Out the way it was going, then the next one in from the other side.
                            // Two halves of one movement rather than a jump: the bar never shows a
                            // card arriving at a position it did not travel to.
                            animate(
                                dragX, if (fwd) -out else out, initialVelocity = velocity,
                                animationSpec = tween(130, easing = LinearOutSlowInEasing),
                            ) { v, _ -> dragX = v }
                            val next = ((index + if (fwd) 1 else -1) % dealt.size + dealt.size) % dealt.size
                            selected = dealt[next].nodeId
                            dragX = if (fwd) out else -out
                            animate(
                                dragX, 0f,
                                animationSpec = tween(170, easing = FastOutSlowInEasing),
                            ) { v, _ -> dragX = v }
                        } else {
                            animate(
                                dragX, 0f, initialVelocity = velocity,
                                animationSpec = spring(dampingRatio = 0.8f, stiffness = 900f),
                            ) { v, _ -> dragX = v }
                        }
                    },
                )
            )
            .padding(start = 18.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .graphicsLayer {
                    translationX = dragX
                    alpha = 1f - (dragX.absoluteValue / (commit * 2.4f)).coerceIn(0f, 0.85f)
                }
                .clickable(onClick = { onOpen(current) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Neutral frame, accent ring — always, now.
            //
            // These used to switch to `onAccent` while running, which is the ink meant for text
            // *on* solid coral. That was right while the bar flooded and wrong the moment it
            // stopped: a light ink on a dark wash left the bhupura all but invisible, which is
            // exactly what a reader reported. RunningGlyph's own doc already said what the tints
            // are — the frame is neutral because structure is not a hue, the ring is the accent
            // because being on something is your own effort — and with no flood there is nothing
            // to make an exception for.
            RunningGlyph(
                frameTint = y.checkOutline,
                ringTint = y.accent,
                size = 20.dp,
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    // One line in a bar: the markers have nothing to become here either.
                    inlinePlain(current.title).ifBlank { "Untitled" },
                    // The row-title spec — CALENDAR_UI.md §4. It was the Display face at 13.5sp:
                    // the smallest text in the app wearing its largest voice. A bar is a row.
                    fontFamily = YantraText,
                    fontSize = YantraType.row,
                    fontWeight = FontWeight.W500,
                    color = y.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The eyebrow: which list, then what is happening on it.
                //
                // The list leads, because it is the fact the bar could not otherwise give you — a
                // title alone does not say whether "Draft the deck" is work or the side project,
                // and the spine beside it answers a different question (the repository). It wears
                // its list's colour so the same hue you see on Home's marks is on the bar.
                //
                // **The name is quiet and the state is not.** Space Mono ships two weights, so
                // the list name went out at the same 12sp bold as the state word — four loud things
                // at once (size, weight, uppercase tracking, a saturated hue) under a 15sp title
                // that is none of them. It read as a second title. It is the bottom of the scale
                // now, at the regular weight, tracked like MonoBreadcrumb and held to 72% ink —
                // the app's quietest voice, which is the right one for a line that tells you where
                // you already are rather than asking you to go anywhere.
                //
                // The state keeps the bold, deliberately: on one line the list is the standing fact
                // and RUNNING · 3:45 is the news, and that is a hierarchy rather than an
                // inconsistency.
                //
                // **Only what the line cannot say without a word.**
                //
                // It said "ON THE GO" when idle and "RUNNING · 3:45" when counting, and both were
                // labels on something already said. The first went because a deck of five rings
                // takes a third of this line and the glyph's ring and the ▶ key both report a task
                // taken up and not counting. And once *no word* means "not running", the word
                // RUNNING is redundant in the same way: a clock that is ticking, in the accent,
                // beside a ⏸, is the state. The number is the news; RUNNING was a caption on it.
                //
                // IT IS TIME keeps its words, because it is the one state with no numeral to carry
                // it: the hour has come and nothing is counting. The bar says so; the file says
                // nothing, which is the whole arrangement — see RunningTask.stack.
                val state = when {
                    current.elapsedSecs != null -> elapsedLabel(current.elapsedSecs)
                    current.scheduled -> "IT IS TIME"
                    // Only when the name is missing — a task whose list could not be resolved would
                    // otherwise have a blank eyebrow and look broken.
                    else -> "ON THE GO".takeIf { current.listName.isNullOrBlank() }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    current.listName?.takeIf { it.isNotBlank() }?.let { list ->
                        Text(
                            list.uppercase(),
                            fontFamily = YantraMono,
                            // The bottom of the scale — the size the app gives dense data, which is
                            // what this is. It sat one step up and still read as an announcement.
                            fontSize = YantraType.dense,
                            fontWeight = FontWeight.W400,
                            letterSpacing = 1.sp,
                            color = listInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Yields first. The clock and the rings are fixed-width facts; a list
                            // name is the only thing here that can be shortened and still be read.
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (state != null) {
                            Text(
                                "  ·  ",
                                fontFamily = YantraMono,
                                fontSize = YantraType.caption,
                                color = y.textDim,
                            )
                        }
                    }
                    if (state != null) {
                        Text(
                            state,
                            fontFamily = YantraMono,
                            fontSize = YantraType.caption,
                            fontWeight = FontWeight.W700,
                            letterSpacing = 1.2.sp,
                            color = if (live) accentInk else y.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                DeckRings(dealt = dealt, index = index)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        TransportKey(live = live, onClick = { onToggleClock(current) })
    }
}

/**
 * The deck counter — ICONS.md §6, CALENDAR_UI.md §4.
 *
 * One ring per card you can reach by swiping, which is what a position indicator is for. Three
 * readings out of one shape: rest weight is on the go and not where you are, full weight is the
 * card you are looking at, and [YantraMark.RingLive] in the accent is the one the clock is on.
 * Everything past the deck is counted in the eyebrow instead — a ring for a card you cannot swipe
 * to would be a position indicator pointing at nowhere.
 *
 * **Beside the eyebrow, not beside the transport key.** It was on the right at first, which reads
 * fine at two rings and has nowhere to go at four: the key is fixed to the edge and the rings would
 * have had to grow into the title. Here they grow into a line that is already short, and they sit
 * with the state they qualify — "ON THE GO ○ ●" is one statement.
 *
 * The bindu was rejected for this. A row of dots with one filled is a gauge, and the bindu is the
 * centre and never a gauge; it is also the done state of the task glyph, so a bare filled dot among
 * rings would read as "finished" on the one card that is running.
 */
@Composable
private fun DeckRings(dealt: List<RunningTask.Now>, index: Int) {
    if (dealt.size < 2) return
    val y = Yantra.colors
    val live = dealt.indexOfFirst { it.hasSession }
    Spacer(Modifier.width(8.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dealt.size <= RINGS_MAX) {
            dealt.forEachIndexed { i, card ->
                YantraIcon(
                    if (card.hasSession) YantraMark.RingLive else YantraMark.Ring,
                    size = YantraIcons.Small,
                    tint = when {
                        card.hasSession -> y.accent
                        i == index -> y.textPrimary
                        // Lifted rather than thinned. ICONS.md §8 flags 40% as possibly under the
                        // 3:1 hairline rule on OLED and says to raise the alpha rather than thicken
                        // the stroke — a heavier rest ring would stop being the same mark.
                        else -> y.textPrimary.copy(alpha = 0.55f)
                    },
                    contentDescription = null,
                )
            }
        } else {
            // Past the point where a row of rings can be read at a glance, one ring and a numeral
            // — ICONS.md §6. A diagram you have to count is doing a table's job, and eight rings
            // is counting.
            YantraIcon(
                if (live >= 0) YantraMark.RingLive else YantraMark.Ring,
                size = YantraIcons.Small,
                tint = if (live >= 0) y.accent else y.textPrimary,
                contentDescription = null,
            )
            Text(
                "${index + 1}/${dealt.size}",
                fontFamily = YantraMono,
                fontSize = YantraType.section,
                fontWeight = FontWeight.W700,
                color = y.textMuted,
            )
        }
    }
}

/**
 * How many rings before the deck is counted instead of drawn.
 *
 * Five, which is where ICONS.md §6 puts it and which comfortably clears the four a reader asked to
 * see before a number takes over. Below this you read the row; above it you would be counting, and
 * counting is what the numeral is for.
 */
private const val RINGS_MAX = 5

/**
 * The one control: play, or stop.
 *
 * A filled triangle and a filled square, drawn rather than iconised — they are two of the most
 * recognisable shapes there are, and the icon set's versions arrive with their own padding and
 * optical centre that would not agree with a 20dp glyph sitting beside them.
 */
@Composable
private fun TransportKey(live: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(y.accentFill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // From the set — CALENDAR_UI.md §4.
        //
        // The comment that used to be here justified drawing the triangle and the square by hand:
        // Material's versions arrive with their own padding and optical centre, which would not
        // agree with a 20dp glyph beside them. True of Material, and no longer true of anything —
        // once every mark is one 28-unit space at one stroke, "it will not sit right next to the
        // others" is the argument *for* using the set.
        //
        // Both are filled, which is the second and last fill exception; see YantraIcons' header.
        YantraIcon(
            if (live) YantraMark.Stop else YantraMark.Play,
            size = YantraIcons.Medium,
            tint = y.accent,
            contentDescription = if (live) "Stop the clock" else "Start the clock",
        )
    }
}

/**
 * The bottom of a screen that can capture: the field, and under it the player when something is on
 * the go.
 *
 * **Capture is always open.** It used to hide behind a key whenever anything was running, which made
 * the bottom of the screen mean two different things depending on state, and put a tap in front of
 * the highest-frequency action in the app for no reason but that something else wanted the space.
 * Writing something down should never cost a mode.
 *
 * The player sits *below* the field, at the very edge — it is the outermost thing, the reflection of
 * the header at the other end of the sheet, and the field stays where the thumb already expects it.
 *
 * **Both stand down while the keyboard is up.** A bar over the line you are typing is worse than no
 * bar: what is running is a thing you can check in a moment, and what you are writing is a thing you
 * lose. A running session stays visible on the lock screen and in the widget meanwhile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BottomBar(
    onOpenNow: (RunningTask.Now) -> Unit,
    onToggleClock: (RunningTask.Now) -> Unit,
    modifier: Modifier = Modifier,
    /** False where the screen has no business showing it — a task's own page. */
    showNow: Boolean = true,
    capture: @Composable (bottomPadding: Dp) -> Unit,
) {
    val stack by LocalNow.current.collectAsStateWithLifecycle()
    val shown = if (showNow && !WindowInsets.isImeVisible) stack else emptyList()
    // The player's key is the other way a session begins, and every screen that shows a player
    // shows this one — so asking here covers Home, the smart lists, a task's page and the stats
    // screen at once, rather than four call sites that would each have to remember.
    //
    // A no-op once granted, and once denied: the launcher only fires when the permission is
    // actually missing, so pressing play repeatedly does not re-ask.
    val askNotifications = rememberNotificationPermissionRequest()
    Column(modifier.fillMaxWidth()) {
        // The field keeps its own breathing room at the screen edge, and gives most of it back when
        // the player is underneath to catch it.
        capture(if (shown.isEmpty()) 22.dp else 10.dp)
        if (shown.isNotEmpty()) {
            NowPlayer(
                stack = shown,
                onOpen = onOpenNow,
                onToggleClock = { now -> askNotifications(); onToggleClock(now) },
            )
        }
    }
}

/**
 * The offer made when you start a focus while one is already running.
 *
 * This is the one exclusivity the app has left. Several tasks can be on the go at once — that is
 * what the deck is for — but a focus session measures attention, and there is one of that. So a
 * second start has to take the clock from the first, which closes that session as interrupted in a
 * ledger someone will read later. Doing it silently is how a day's record ends up holding sessions
 * the person does not remember ending.
 *
 * Nothing is offered here except switching. A second clock is not on the table, which is the point.
 */
@Composable
fun SwitchHereDialog(
    runningTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val y = Yantra.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("A focus is already running") },
        text = {
            Text(
                "“${runningTitle.ifBlank { "Untitled" }}” has the clock. Starting this one stops " +
                    "that session — the time it has already taken still counts.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    "SWITCH HERE",
                    fontFamily = YantraMono,
                    fontSize = YantraType.section,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 1.4.sp,
                    color = y.accent,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Leave it running") } },
    )
}
