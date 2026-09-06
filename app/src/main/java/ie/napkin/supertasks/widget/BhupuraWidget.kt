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
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
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
 * The mark itself, on the home screen.
 *
 * Every other widget here is a panel with the bhupura *in* it — a rounded rectangle like every other
 * tile in the grid, carrying the app's shape as a mark or a button. This one is the shape: no card,
 * no scrim, no corners borrowed from the launcher. The wallpaper shows through everywhere the
 * enclosure is not, so what sits on the home screen is the yantra rather than a box with a yantra
 * printed on it.
 *
 * That is the whole reason it exists, and it is why this is a *new* widget rather than a change to
 * the focus one. A panel and a mark want opposite things — a panel wants edges to hold a list
 * against, a mark wants none — and the focus widget has buttons, a title and a progress bar that
 * need somewhere square to live. Making it bhupura-shaped would have cost all of that. So both
 * exist, and the choice is the user's.
 *
 * **Three layers, one shape.** A fill in the surface colour gives the mark a ground of its own, so
 * the enclosure is not left as a hairline for a busy wallpaper to eat; the enclosure is drawn over
 * it in the frame's ink; and effort's colour is spent on exactly one thing, the key. Solid accent
 * everywhere was a coral slab that stopped reading as a shape — an enclosure only looks like one
 * when there is something to enclose — and the key being the only filled, coloured thing inside is
 * what makes it look pressable rather than like one more piece of the drawing.
 *
 * **One key, in the bindu's place.** The mark's centre is the point the figure is drawn around, and
 * in a widget about starting and stopping there is exactly one thing that belongs there. Its command
 * follows the state — pause what runs, resume what is paused, start again on the last task when
 * nothing does — so the key is never ambiguous and never needs a second one beside it.
 *
 * The clock sits above it and the task's name below, each positioned against the interior rather
 * than stacked with the key, so that neither can push the bindu off the centre it is defined by.
 */
class BhupuraWidget : GlanceAppWidget() {

    // Exact, unusually: the mark is drawn to fill whatever square the user drags out, and the type
    // inside has to be sized against the real dimension rather than snapped to one of two presets.
    // Cheap here because there is no data flow behind it — only the timer state it already holds.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as App).container
        container.timer.restoreIfNeeded()
        // What the key would start, when nothing is running. Same source the focus widget uses.
        val initialLast = container.focus.lastSession()
            ?.let { container.focus.nodeTitle(it.nodeId) }
        provideContent {
            val state by container.timer.state.collectAsState()
            val last by produceState(initialValue = initialLast, state) {
                if (state == null) {
                    value = container.focus.lastSession()
                        ?.let { container.focus.nodeTitle(it.nodeId) }
                }
            }
            val custom = yantraGlanceColors(context)
            if (custom != null) GlanceTheme(colors = custom) { BhupuraContent(state, last) }
            else GlanceTheme { BhupuraContent(state, last) }
        }
    }
}

class BhupuraWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BhupuraWidget()
}

@Composable
private fun BhupuraContent(state: FocusTimer.State?, lastTitle: String?) {
    val context = LocalContext.current
    val side = minOf(LocalSize.current.width, LocalSize.current.height)
    // Spent counts as over. A refresh can always arrive late — the process may be dead when the
    // promise comes due — and until it lands this is the only thing standing between the widget
    // and a countdown ticking into negative time.
    val live = state?.takeIf { !it.isFinished && !it.isSpent }

    val openFocus = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(WidgetIntents.EXTRA_OPEN_FOCUS, true)
        data = Uri.parse("yantra://focus")
    }

    // **The ground is the mark.**
    //
    // A widget's host view is always a rectangle — the launcher lays it out in a rectangular cell
    // and there is no way to give it a shaped outline. What *can* be shaped is what gets painted
    // into it: leave the root transparent, declare no appWidgetBackground, and draw nothing but a
    // filled bhupura, and the tile reads as the mark with wallpaper on every side of it.
    //
    // This is the resolution of the two things asked for at once. The outline needed a ground
    // because a hairline is what a busy photograph eats first; the widget needed to not be a
    // square. So the ground is bhupura-shaped rather than rectangular: the fill in the surface
    // colour, the enclosure drawn over it in the frame's ink, and effort's colour reserved for the
    // one thing you press.
    Box(
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(openFocus)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_bhupura_solid),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.surface),
            contentScale = ContentScale.Fit,
            modifier = GlanceModifier.fillMaxSize(),
        )
        Image(
            provider = ImageProvider(R.drawable.ic_widget_bhupura_outline),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
            contentScale = ContentScale.Fit,
            modifier = GlanceModifier.fillMaxSize(),
        )

        // Interior only: the gates and corners take the outer fifth on every side.
        //
        // Three layers rather than a column, so the key sits at the mark's true centre no matter
        // what is above or below it. Stacked, it would have been pushed off-centre by whichever of
        // the clock and the name happened to be present — and the bindu is the point the whole
        // figure is drawn around, so it is the one thing that must not drift.
        Box(
            modifier = GlanceModifier.size(side * 0.56f),
            contentAlignment = Alignment.Center,
        ) {
            if (live != null) {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) { Clock(live, side) }
            }
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { TransportBindu(live, side) }
            // Below the key: what the clock is *for*. Suppressed on a strip too small to hold a
            // legible line, where a two-character stub of a task name is worse than no name.
            // Markers out, for the same reason the focus widget takes them out.
            val title = (live?.nodeTitle?.takeIf { it.isNotBlank() } ?: lastTitle)
                ?.let { Markdown.plain(it) }
            if (!title.isNullOrBlank() && side > 120.dp) {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter,
                ) { TaskName(title, side) }
            }
        }
    }
}

/**
 * What the session is on.
 *
 * In the frame's ink rather than effort's, and quieter than both the clock and the key: the name
 * answers "which task" for someone who already knows a session is running, and it must not compete
 * with the number that changes or the control that acts. One line, always — a task title wrapped
 * across a bhupura's narrowing interior is unreadable at any size.
 */
@Composable
private fun TaskName(title: String, side: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    Text(
        title,
        style = TextStyle(
            color = ColorProvider(
                GlanceTheme.colors.onSurface.getColor(context).copy(alpha = 0.65f)
            ),
            fontSize = (side.value * 0.062f).coerceIn(9f, 13f).sp,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
    )
}

/**
 * The session's reading, above the key.
 *
 * Always the same box and always the same face, whichever way the session is going — see
 * `widget_focus_clock.xml`. Effort's colour, because the number *is* the session.
 */
@Composable
private fun Clock(state: FocusTimer.State, side: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val ink = GlanceTheme.colors.primary.getColor(context).toArgb()
    val clockSp = (side.value * 0.125f).coerceIn(11f, 30f)

    val rv = RemoteViews(context.packageName, R.layout.widget_focus_clock).apply {
        setTextColor(R.id.focus_clock_running, ink)
        setTextColor(R.id.focus_clock_frozen, ink)
        setTextViewTextSize(R.id.focus_clock_running, TypedValue.COMPLEX_UNIT_SP, clockSp)
        setTextViewTextSize(R.id.focus_clock_frozen, TypedValue.COMPLEX_UNIT_SP, clockSp)
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
            // No chronometer can be frozen, only replaced — the same bargain the notification
            // strikes, and the reason both faces live in one layout.
            setViewVisibility(R.id.focus_clock_running, android.view.View.GONE)
            setViewVisibility(R.id.focus_clock_frozen, android.view.View.VISIBLE)
            setTextViewText(
                R.id.focus_clock_frozen,
                sessionClock(if (state.isOpen) state.elapsedSecs else state.remainingSecs),
            )
        }
    }
    AndroidRemoteViews(
        rv,
        // Fixed, so pausing cannot move the key underneath it.
        modifier = GlanceModifier.fillMaxWidth().height((clockSp * 1.45f).dp),
    )
}

/**
 * The one key, where the bindu goes.
 *
 * The mark's centre is the point the figure is drawn around, and in a widget about starting and
 * stopping there is exactly one thing that belongs there. Solid accent with the glyph in paper —
 * the only filled thing inside the outline, which is what makes it read as the thing to press
 * rather than one more piece of the drawing.
 *
 * Its command follows the state, so the key is never ambiguous: pause what is running, resume what
 * is paused, and with nothing running at all, start again on the last thing you worked on.
 */
@Composable
private fun TransportBindu(state: FocusTimer.State?, side: androidx.compose.ui.unit.Dp) {
    val running = state?.isRunning == true
    val command = when {
        running -> FocusAction.PAUSE
        state != null -> FocusAction.RESUME
        else -> FocusAction.START_LAST
    }
    val icon = if (running) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
    val label = when {
        running -> "Pause"
        state != null -> "Resume"
        else -> "Start focus"
    }
    // Bigger when it is alone: with no clock above it the key is the whole content, and a small
    // dot in a large enclosure reads as an unfinished drawing rather than a control.
    val key = (side * (if (state == null) 0.26f else 0.20f)).coerceAtLeast(30.dp)

    Box(
        modifier = GlanceModifier
            .size(key)
            .clickable(
                actionRunCallback<FocusAction>(actionParametersOf(FocusAction.Command to command))
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_bhupura_solid),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            modifier = GlanceModifier.size(key),
        )
        Image(
            provider = ImageProvider(icon),
            contentDescription = label,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
            modifier = GlanceModifier.size(key * 0.48f),
        )
    }
}

