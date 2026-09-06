package ie.napkin.supertasks.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import ie.napkin.supertasks.App
import ie.napkin.supertasks.MainActivity
import ie.napkin.supertasks.R
import ie.napkin.supertasks.domain.FocusTimer
import ie.napkin.supertasks.widget.actions.FocusAction
import ie.napkin.supertasks.domain.sessionClock
import ie.napkin.supertasks.data.format.Markdown

/**
 * Focus-timer widget. The running clock is a RemoteViews Chronometer embedded via
 * [AndroidRemoteViews]: the launcher process renders the ticks, so the widget stays live with zero
 * updates from us — even across force-stop. We re-render only on state transitions (AppContainer
 * collector) and let a WorkManager job finalize a session whose end passes while the process is
 * dead.
 *
 * **It counts the direction the session runs.** [FocusTimer] holds two instruments, and this widget
 * used to draw only one of them: it always asked for a countdown, from `remainingSecs`, which for an
 * open stopwatch is *zero* — so pressing play on the bar and looking at the home screen showed a
 * clock counting down through zero into negative time. A committed session counts down to its
 * promise; an open one counts up from its start. Same as the notification, and for the same reason.
 */
class FocusWidget : GlanceAppWidget() {

    // Not Single: with Single every layout decision is made against the *minimum* size from the
    // provider info, so a widget dragged out to full width was still typeset for a strip. Two
    // layouts are worth having, so Responsive rather than Exact — see WidgetMetrics in ListWidget
    // for the same argument at more length.
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 70.dp), DpSize(250.dp, 130.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as App).container
        container.timer.restoreIfNeeded()
        val initialLast = container.focus.lastSession()
            ?.let { it.nodeId to (container.focus.nodeTitle(it.nodeId) ?: "") }
        provideContent {
            // Observed, not snapshotted: a live Glance session ignores update() re-renders, so
            // pause/dismiss taps must recompose via the StateFlow (also closes the start race).
            val state by container.timer.state.collectAsState()
            val last by produceState(initialValue = initialLast, state) {
                if (state == null) {
                    value = container.focus.lastSession()
                        ?.let { it.nodeId to (container.focus.nodeTitle(it.nodeId) ?: "") }
                }
            }
            val custom = yantraGlanceColors(context)
            if (custom != null) GlanceTheme(colors = custom) { FocusContent(state, if (state == null) last else null) }
            else GlanceTheme { FocusContent(state, if (state == null) last else null) }
        }
    }
}

/**
 * Deliberately still named Pomodoro, and the only thing in the app that is.
 *
 * A launcher stores the `ComponentName` of the provider each placed widget belongs to. Renaming this
 * class changes that name, and every widget already on a home screen stops resolving — it does not
 * move, it breaks, and it has to be placed again. The class name is invisible to the user; the
 * widget disappearing is not. So this one keeps the old name, and the manifest entry with it.
 */
class PomodoroWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FocusWidget()
}

/**
 * Type and rhythm for one focus-widget size, on the same argument as the list widget's: a home
 * screen is read at arm's length and in passing, so the clock is the one thing that has to be big.
 */
private data class FocusMetrics(
    val header: TextUnit,
    val title: TextUnit,
    val clock: Float,
    val chip: TextUnit,
    val clockHeight: Dp,
    val pad: Dp,
    /** Side of a transport key. Big enough to hit at arm's length without looking. */
    val key: Dp,
    /** Side of the session mark beside the clock. */
    val mark: Dp,
    val compact: Boolean,
) {
    companion object {
        /** Below this a widget is a strip: room for the clock and the buttons, and nothing else. */
        private val COMPACT = 120.dp

        fun forSize(size: DpSize): FocusMetrics =
            if (size.height < COMPACT) FocusMetrics(
                header = 10.sp, title = 12.sp, clock = 22f, chip = 12.sp,
                clockHeight = 30.dp, pad = 11.dp, key = 38.dp, mark = 24.dp, compact = true,
            ) else FocusMetrics(
                header = 11.sp, title = 15.sp, clock = 34f, chip = 13.sp,
                clockHeight = 44.dp, pad = 15.dp, key = 46.dp, mark = 34.dp, compact = false,
            )
    }
}

@Composable
private fun FocusContent(state: FocusTimer.State?, last: Pair<String, String>?) {
    val context = LocalContext.current
    val m = FocusMetrics.forSize(LocalSize.current)
    val openFocus = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(WidgetIntents.EXTRA_OPEN_FOCUS, true)
        data = Uri.parse("yantra://focus")
    }
    // A 1dp ring drawn as a background behind an inset surface — Glance has no border. Same
    // construction as the list widget, so the two read as one family on a home screen.
    val edge = GlanceTheme.colors.onSurface.getColor(context).copy(alpha = 0.10f)
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(edge)
            .cornerRadius(R.dimen.widget_radius)
            .padding(1.dp),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .cornerRadius(R.dimen.widget_radius)
                .padding(m.pad)
                .clickable(actionStartActivity(openFocus)),
        ) {
            when {
                state == null -> IdleContent(last, m)
                // Spent as well as finished: a committed session whose moment has passed is over
                // whether or not anything has closed it yet, and drawing it as live hands the
                // launcher a countdown that will run straight through zero.
                state.isFinished || state.isSpent -> FinishedContent(state, m)
                else -> SessionContent(state, m)
            }
        }
    }
}

/**
 * The session's mark, beside its clock.
 *
 * The widget had the bhupura only as the shape under its buttons, which is the mark doing a
 * container's job — present but not *saying* anything. Here it is the mark itself, and it carries
 * the one thing the clock cannot: the bindu is solid while the session runs and hollow while it is
 * paused, the same distinction the task glyph draws and the notification repeats. So the three
 * surfaces a session appears on now show the same session in the same hand.
 */
@Composable
private fun SessionMarkImage(state: FocusTimer.State, m: FocusMetrics) {
    Image(
        provider = ImageProvider(
            if (state.isRunning) R.drawable.ic_widget_mark else R.drawable.ic_widget_mark_paused
        ),
        contentDescription = null,
        colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
        modifier = GlanceModifier.size(m.mark),
    )
}

@Composable
private fun HeaderText(text: String, m: FocusMetrics, urgent: Boolean = false) {
    Text(
        text,
        style = TextStyle(
            color = if (urgent) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
            fontSize = m.header,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 1,
    )
}

@Composable
private fun TitleText(text: String, m: FocusMetrics) {
    Text(
        // Emphasis markers out: a widget draws one weight and would otherwise print the asterisks.
        Markdown.plain(text).ifBlank { "Focus" },
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = m.title, fontWeight = FontWeight.Medium),
        maxLines = 1,
    )
}

/**
 * A transport key: a glyph on a shape.
 *
 * These were words on a tinted rectangle, which is what a widget looked like several Android
 * releases ago. A control on a modern home screen is a *shape* with a mark in it — and rather than
 * borrow Material's expressive vocabulary of clovers and cookies, this uses the shape the app
 * already owns. [primary] gets the filled bhupura, because there is only ever one obvious next
 * action; the rest get the same shape at a tenth of the ink, so the row reads as one family with
 * one thing lit.
 *
 * The glyph is a drawable rather than drawn: Glance has no Canvas, so the widget's marks live in
 * the same vectors the notification's transport uses. Identical geometry, one place to change.
 */
@Composable
private fun TransportKey(
    icon: Int,
    label: String,
    command: String,
    m: FocusMetrics,
    primary: Boolean = false,
) {
    val context = LocalContext.current
    val ink = GlanceTheme.colors.primary.getColor(context)
    // The shape is a drawn layer rather than a tinted background. Glance renders a background
    // ImageProvider through RemoteViews as a background *drawable*, and a colour filter does not
    // reliably reach one — so the shape either arrived untinted or not at all depending on the
    // host. Two stacked Images always work, and cost one view.
    Box(
        modifier = GlanceModifier
            .size(m.key)
            .clickable(
                actionRunCallback<FocusAction>(actionParametersOf(FocusAction.Command to command))
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_bhupura_solid),
            contentDescription = null,
            colorFilter = ColorFilter.tint(
                if (primary) GlanceTheme.colors.primary
                else ColorProvider(ink.copy(alpha = 0.16f))
            ),
            modifier = GlanceModifier.size(m.key),
        )
        Image(
            provider = ImageProvider(icon),
            contentDescription = label,
            colorFilter = ColorFilter.tint(
                if (primary) GlanceTheme.colors.onPrimary else GlanceTheme.colors.primary
            ),
            modifier = GlanceModifier.size(m.key - 20.dp),
        )
    }
}

/** A worded action, for the idle state where there is no transport to key — only an invitation. */
@Composable
private fun ActionChip(label: String, command: String, m: FocusMetrics, filled: Boolean = false) {
    val context = LocalContext.current
    val bg = if (filled) GlanceTheme.colors.primary.getColor(context)
    else GlanceTheme.colors.primary.getColor(context).copy(alpha = 0.15f)
    Box(
        modifier = GlanceModifier
            .background(bg)
            .cornerRadius(R.dimen.widget_inner_radius)
            .clickable(
                actionRunCallback<FocusAction>(actionParametersOf(FocusAction.Command to command))
            )
            .padding(horizontal = if (m.compact) 12.dp else 15.dp, vertical = if (m.compact) 6.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                color = if (filled) GlanceTheme.colors.onPrimary else GlanceTheme.colors.primary,
                fontSize = m.chip,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun IdleContent(last: Pair<String, String>?, m: FocusMetrics) {
    HeaderText("FOCUS", m)
    Spacer(GlanceModifier.height(4.dp))
    TitleText(last?.second ?: "Pick a task in the app", m)
    if (last != null) {
        Spacer(GlanceModifier.height(if (m.compact) 6.dp else 10.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            ActionChip("Start 25m", FocusAction.START_LAST, m, filled = true)
        }
    }
}

/**
 * A live session, running or paused.
 *
 * One composable for both, because they differ in three details and not in shape — and when they
 * were two, the paused branch quietly grew its own idea of what an open session's clock says
 * (`remainingSecs`, which for a stopwatch is zero).
 */
@Composable
private fun SessionContent(state: FocusTimer.State, m: FocusMetrics) {
    val context = LocalContext.current
    HeaderText(if (state.isRunning) "FOCUSING" else "PAUSED", m, urgent = !state.isRunning)
    Spacer(GlanceModifier.height(2.dp))
    TitleText(state.nodeTitle, m)
    Spacer(GlanceModifier.height(if (m.compact) 2.dp else 6.dp))

    // One box, one face, whichever way the session is going. Running was a mono Chronometer and
    // paused a Glance Text — which meant the reading changed typeface *and* jumped position every
    // time the transport was pressed, because Glance's TextStyle carries no fontFamily and quietly
    // drops the mono. Both faces now live in widget_focus_clock.xml at a fixed height.
    val clockInk = GlanceTheme.colors.onSurface.getColor(context).toArgb()
    val rv = RemoteViews(context.packageName, R.layout.widget_focus_clock).apply {
        setTextColor(R.id.focus_clock_running, clockInk)
        setTextColor(R.id.focus_clock_frozen, clockInk)
        setTextViewTextSize(R.id.focus_clock_running, TypedValue.COMPLEX_UNIT_SP, m.clock)
        setTextViewTextSize(R.id.focus_clock_frozen, TypedValue.COMPLEX_UNIT_SP, m.clock)
        // Left, not centred: this one sits beside a mark in a panel, and a clock that drifts from
        // the title above it reads as a different column.
        setInt(R.id.focus_clock_running, "setGravity", android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START)
        setInt(R.id.focus_clock_frozen, "setGravity", android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START)
        if (state.isRunning) {
            setViewVisibility(R.id.focus_clock_running, android.view.View.VISIBLE)
            setViewVisibility(R.id.focus_clock_frozen, android.view.View.GONE)
            setChronometerCountDown(R.id.focus_clock_running, !state.isOpen)
            setChronometer(
                R.id.focus_clock_running,
                if (state.isOpen) SystemClock.elapsedRealtime() - state.elapsedSecs * 1000L
                else SystemClock.elapsedRealtime() + state.remainingSecs * 1000L,
                null,
                true,
            )
        } else {
            setViewVisibility(R.id.focus_clock_running, android.view.View.GONE)
            setViewVisibility(R.id.focus_clock_frozen, android.view.View.VISIBLE)
            setTextViewText(
                R.id.focus_clock_frozen,
                sessionClock(if (state.isOpen) state.elapsedSecs else state.remainingSecs),
            )
        }
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionMarkImage(state, m)
        Spacer(GlanceModifier.width(10.dp))
        AndroidRemoteViews(rv, modifier = GlanceModifier.defaultWeight().height(m.clockHeight))
    }

    // How much of the promise is left, for the instrument that made one. An open session has no
    // denominator, so it gets no bar rather than a bar that means nothing.
    if (!state.isOpen && !m.compact && state.plannedSecs > 0) {
        Spacer(GlanceModifier.height(6.dp))
        LinearProgressIndicator(
            progress = (state.elapsedSecs.toFloat() / state.plannedSecs).coerceIn(0f, 1f),
            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
            color = GlanceTheme.colors.primary,
            backgroundColor = ColorProvider(
                GlanceTheme.colors.onSurface.getColor(context).copy(alpha = 0.12f)
            ),
        )
    }

    Spacer(GlanceModifier.height(if (m.compact) 6.dp else 10.dp))
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        if (state.isRunning) {
            TransportKey(R.drawable.ic_widget_pause, "Pause", FocusAction.PAUSE, m)
        } else {
            // Resume leads when the clock is stopped: it is the only thing you are likely to
            // have come to the home screen to do.
            TransportKey(R.drawable.ic_widget_play, "Resume", FocusAction.RESUME, m, primary = true)
        }
        Spacer(GlanceModifier.width(10.dp))
        TransportKey(R.drawable.ic_widget_stop, "Stop", FocusAction.STOP, m)
    }
}

@Composable
private fun FinishedContent(state: FocusTimer.State, m: FocusMetrics) {
    HeaderText("DONE", m)
    Spacer(GlanceModifier.height(2.dp))
    TitleText(state.nodeTitle, m)
    Spacer(GlanceModifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Filled, because a landed bindu is what completion looks like everywhere else in the app.
        Image(
            provider = ImageProvider(R.drawable.ic_widget_mark),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            modifier = GlanceModifier.size(m.mark),
        )
        Spacer(GlanceModifier.width(10.dp))
        Text(
            // What you gave, rather than a tally of sessions: the number the ledger just recorded
            // is more use than the fact that it recorded one.
            sessionClock(state.elapsedSecs),
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = m.clock.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
    Spacer(GlanceModifier.height(if (m.compact) 6.dp else 10.dp))
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        ActionChip("Dismiss", FocusAction.DISMISS, m, filled = true)
    }
}

