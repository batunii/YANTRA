package ie.shoonya.yantra.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.Visibility
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.visibility
import ie.shoonya.yantra.App
import ie.shoonya.yantra.AppContainer
import ie.shoonya.yantra.MainActivity
import ie.shoonya.yantra.R
import ie.shoonya.yantra.data.db.BuiltIns
import ie.shoonya.yantra.data.device.CalendarChoice
import ie.shoonya.yantra.data.device.DeviceCalendarSource
import ie.shoonya.yantra.data.format.Links
import ie.shoonya.yantra.data.format.Markdown
import ie.shoonya.yantra.data.label.LabelPalette
import ie.shoonya.yantra.ui.calendar.CalendarBucketer
import ie.shoonya.yantra.ui.theme.YantraColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * What a placed calendar widget remembers: which view it is showing and where it is looking.
 *
 * In Glance state rather than [WidgetPrefs] because a live render session only reacts to state
 * changes — a callback that wrote a preference would be a tap that appeared to do nothing until
 * something else happened to re-render.
 */
object CalendarWidgetKeys {
    val VIEW = stringPreferencesKey("calView")
    val ANCHOR = stringPreferencesKey("calAnchor")

    /**
     * The day the anchor was written on, which is what stops a widget stranding itself.
     *
     * Paging back to March and putting the phone down should not mean a home screen still showing
     * March in June. An anchor is only honoured on the day it was set; after that the widget is
     * back on today, which is where a calendar on a wall is every morning.
     */
    val ANCHOR_SET = stringPreferencesKey("calAnchorSet")

    /** How much of the wallpaper the pane keeps out, 50..100 — shared with the settings screen. */
    val OPACITY = intPreferencesKey("calOpacity")

    fun view(state: Preferences): CalendarWidgetView = CalendarWidgetView.of(state[VIEW])

    fun anchor(state: Preferences, today: LocalDate): LocalDate {
        val setOn = state[ANCHOR_SET]?.let { date(it) }
        if (setOn != today) return today
        return state[ANCHOR]?.let { date(it) } ?: today
    }

    private fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()
}

/** Defaults for the settings in [CalendarWidgetKeys], shared with the settings screen. */
object CalendarWidgetDefaults {
    const val OPACITY = 94
    const val MIN_OPACITY = 50
}

/**
 * The calendar, on a home screen — a month, three days, or one.
 *
 * **It is a printed page, not a control panel.** Nothing is drawn on it except words, one 3dp rule
 * down the margin of each line, one 3dp rule under each date in the month, and the pane they sit
 * on. There are no chips, no filled blocks, no pills, no seams and no coloured grounds: Glance
 * collapses `Medium` and `Bold` into a single visual weight, so a surface that tries to build
 * hierarchy out of boxes ends up with several loud things and no quiet ones. Here the hierarchy is
 * size, ink and air, and **`FontWeight.Bold` appears exactly once in any composition** — the date.
 *
 * Two facts make it readable at arm's length, and they are the only two:
 *
 *  - **one left edge.** Times are right-aligned into a fixed gutter, so every title in the widget
 *    begins at the same x whatever the hour is. A ragged left edge is what turns a list of times
 *    into something you have to read rather than something you can scan.
 *  - **one accent.** The user's ink appears at most twice at once: on the word that names today,
 *    and on the time of the next thing that has not happened yet. Nothing else is saturated.
 *
 * It draws everything the calendar screen draws — events written here, sittings booked against a
 * task, tasks that are due, and the phone's own calendars when they have been allowed.
 */
class YantraCalendarWidget : GlanceAppWidget() {

    /**
     * Three layouts, and each answers a different question: what's next, what's today, what's this
     * week or month.
     *
     * Responsive rather than Exact because every bucket is a complete duplicate view tree inside
     * one ~1MB Binder transaction — so the 42-cell month grid is composed **once**, in the largest
     * bucket only, and the two smaller trees never carry it.
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 110.dp),
            DpSize(250.dp, 200.dp),
            DpSize(320.dp, 300.dp),
            // A fourth, because a 4x5 is a common shape and `LocalSize` under Responsive reports
            // the *declared* bucket: without this, a widget 566dp tall was budgeting rows for one
            // 300dp tall and leaving half its pane empty under the last line.
            DpSize(320.dp, 440.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as App).container
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val state = currentState<Preferences>()
            val today = LocalDate.now()
            val m = CalendarMetrics.forSize(LocalSize.current)
            // The **effective** view, never the stored one. A stored month on a 2x2 has nowhere to
            // be drawn, and a masthead reading "September" over one day's events would be the
            // widget lying about what is underneath it.
            val view = m.allow(CalendarWidgetKeys.view(state))
            val anchor = CalendarWidgetKeys.anchor(state, today)
            val ctx = LocalContext.current
            val flow = remember(view, anchor, m.rows, m.compact) {
                dataFlow(ctx, container, view, anchor, m.rows, m.compact)
            }
            val locale = Locale.getDefault()
            val data by flow.collectAsState(
                CalendarWidgetData(
                    heading = CalendarWidgetShape.heading(view, anchor, today, locale, m.compact),
                    eyebrow = CalendarWidgetShape.eyebrow(view, anchor, today, locale),
                    cells = emptyList(),
                    columns = emptyList(),
                    nextUp = null,
                    ready = false,
                )
            )
            val custom = yantraGlanceColors(context)
            val content = @Composable {
                Folio(data, view, anchor, today, m, yantraStatusColors(context), widgetId)
            }
            if (custom != null) GlanceTheme(colors = custom) { content() } else GlanceTheme { content() }
        }
    }

    /**
     * The same four sources the calendar screen combines, asked for one window.
     *
     * Deliberately the same [CalendarBucketer] call, not a simpler one written for a widget: the
     * bucketing is where multi-day events, exclusive all-day ends and midnight-due tasks are got
     * right, and a widget that re-derived any of that would disagree with the screen on exactly the
     * days it is hardest to notice.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun dataFlow(
        context: Context,
        container: AppContainer,
        view: CalendarWidgetView,
        anchor: LocalDate,
        rows: Int,
        compact: Boolean,
    ): Flow<CalendarWidgetData> = flow {
        val app = context.applicationContext
        val zone = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        val (from, toExclusive) = CalendarWidgetShape.window(view, anchor)
        val fromUtc = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toUtc = toExclusive.atStartOfDay(zone).toInstant().toEpochMilli()

        val dueDefId = container.properties.builtInDefsOnce()
            .firstOrNull { it.name.equals(BuiltIns.DUE_NAME, ignoreCase = true) }?.id

        val events = container.db.eventDao().inRange(fromUtc, toUtc)
        val tasks = dueDefId
            ?.let { container.db.propertyDao().observeDueInRange(it, fromUtc, toUtc) }
            ?: flowOf(emptyList())

        // The phone's own calendars, re-read whenever the provider says something changed. The
        // permission is checked at the point of asking rather than remembered — it can be revoked
        // while a widget sits on a home screen, and the honest answer then is an empty overlay.
        val device = DeviceCalendarSource(app)
        val choice = CalendarChoice(app)
        val theirs = device.changes().map { device.instances(fromUtc, toUtc, choice.effective(device)) }

        // Task id to its list's colour, so a sitting is the same colour here as on the day view.
        val origins = container.db.nodeDao().taskOrigins().map { rowsIn ->
            rowsIn.mapNotNull { r -> LabelPalette.byName(r.listColor)?.let { r.id to it.light } }.toMap()
        }

        val workspaceTints = container.workspaceColours()
            .mapNotNull { (id, name) -> LabelPalette.byName(name)?.let { id to it.light } }
            .toMap()

        emitAll(
            combine(events, tasks, theirs, origins) { e, t, d, listTints ->
                val days = CalendarBucketer.bucket(
                    workspaceTints = workspaceTints,
                    listTints = listTints,
                    events = e,
                    sittingOf = e.mapNotNull { row ->
                        row.event.forNodeId?.let { row.event.nodeId to it }
                    }.toMap(),
                    tasks = t,
                    titles = e.mapNotNull { row ->
                        row.displayTitle?.let { row.event.nodeId to it }
                    }.toMap(),
                    device = d,
                    from = from,
                    toExclusive = toExclusive,
                    zone = zone,
                )
                val today = LocalDate.now()
                // The columns are only ever the days on screen; the rest of the window was read so
                // an empty day can say when the quiet ends.
                val shown = CalendarWidgetShape.columns(
                    view, anchor, today, days, locale, rows.coerceAtLeast(1),
                )
                CalendarWidgetData(
                    heading = CalendarWidgetShape.heading(view, anchor, today, locale, compact),
                    eyebrow = CalendarWidgetShape.eyebrow(view, anchor, today, locale),
                    cells = if (view == CalendarWidgetView.MONTH) {
                        CalendarWidgetShape.cells(anchor, today, days)
                    } else emptyList(),
                    columns = shown,
                    nextUp = if (view == CalendarWidgetView.MONTH) null
                    else CalendarWidgetShape.nextUp(days, shown.lastOrNull()?.date ?: anchor, locale),
                    ready = true,
                )
            }
        )
    }
}

/** Receiver for [YantraCalendarWidget]. Renaming it unbinds every widget already placed. */
class CalendarWidgetReceiver : androidx.glance.appwidget.GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = YantraCalendarWidget()
}

// ---- how big the parts are ----

/** The three lengths a density mark comes in — one shape at three widths, never three marks. */
private val MARK = listOf(6.dp, 10.dp, 14.dp)

/**
 * The type scale and the room each part gets.
 *
 * Two buckets of type, three gates, and nothing else is measured. The ratios are **26 / 15 / 12** —
 * 1.73 then 1.25 — so the date has real authority. The scale this replaced ran 15 / 13, which is
 * 1.15, and at arm's length 1.15 is one size.
 *
 * Nothing prints below 11sp in any state at any size. A widget is read across a room, on somebody's
 * photograph, not on paper at a desk.
 */
private data class CalendarMetrics(
    val hero: TextUnit,
    val cell: TextUnit,
    val title: TextUnit,
    val label: TextUnit,
    val pad: Dp,
    val padV: Dp,
    val air: Dp,
    val rowGap: Dp,
    val line: Dp,
    val key: Dp,
    val glyph: Dp,
    val gutter: Dp,
    val gutterGap: Dp,
    val weekdayH: Dp,
    val subheadH: Dp,
    val dayAir: Dp,
    val compact: Boolean,
    /** Whether the paging keys have anywhere to live. */
    val paging: Boolean,
    /** Whether this size can draw more than one view, and so whether a view key means anything. */
    val bigViews: Boolean,
    /** How many agenda lines the body holds, from the real row pitch rather than from a guess. */
    val rows: Int,
    /** What is left to the body, for the month grid's font-scale guard. */
    val bodyH: Dp,
) {
    /**
     * The view this size can actually draw.
     *
     * A control that cannot change anything is not parked somewhere because there was room, and a
     * masthead never names something the body is not showing.
     */
    fun allow(stored: CalendarWidgetView): CalendarWidgetView =
        if (bigViews) stored else CalendarWidgetView.DAY

    /** The next view worth cycling to, skipping any this size cannot draw. */
    fun next(from: CalendarWidgetView): CalendarWidgetView = when {
        !bigViews -> CalendarWidgetView.DAY
        from == CalendarWidgetView.DAY -> CalendarWidgetView.THREE_DAY
        from == CalendarWidgetView.THREE_DAY -> CalendarWidgetView.MONTH
        else -> CalendarWidgetView.DAY
    }

    companion object {
        /** The same number the list widget changes gear at, so two Yantra panes side by side agree. */
        private val COMPACT = 150.dp
        private val PAGING_MIN_W = 220.dp
        private val VIEW_MIN_W = 300.dp
        private val VIEW_MIN_H = 260.dp

        /** Nine rows and an overflow line is ten children, where a host starts to clip. */
        private const val MAX_ROWS = 8

        fun forSize(size: DpSize): CalendarMetrics {
            val compact = size.height < COMPACT
            val pad = if (compact) 11.dp else 15.dp
            val padV = pad - 3.dp
            val hero = if (compact) 18.sp else 26.sp
            val line = if (compact) 17.dp else 20.dp
            val rowGap = if (compact) 7.dp else 10.dp
            val air = if (compact) 10.dp else 14.dp
            val key = if (compact) 28.dp else 34.dp
            // The masthead is a key row and a hero line, and its height is arithmetic rather than a
            // constant, so the body below it is never guessed at.
            val heroLine = (hero.value * 1.30f).dp
            val bodyH = (size.height - 2.dp - padV * 2 - key - 2.dp - heroLine - air)
                .coerceAtLeast(0.dp)
            val pitch = line + rowGap
            return CalendarMetrics(
                hero = hero,
                cell = if (compact) 12.sp else 14.sp,
                title = if (compact) 13.sp else 15.sp,
                label = if (compact) 11.sp else 12.sp,
                pad = pad,
                padV = padV,
                air = air,
                rowGap = rowGap,
                line = line,
                key = key,
                glyph = if (compact) 16.dp else 20.dp,
                gutter = if (compact) 40.dp else 46.dp,
                gutterGap = if (compact) 8.dp else 10.dp,
                weekdayH = 15.dp,
                subheadH = 16.dp,
                dayAir = 10.dp,
                compact = compact,
                paging = size.width >= PAGING_MIN_W,
                bigViews = size.width >= VIEW_MIN_W && size.height >= VIEW_MIN_H,
                rows = (bodyH / pitch).toInt().coerceIn(1, MAX_ROWS),
                bodyH = bodyH,
            )
        }
    }
}

// ---- the page ----

/**
 * The pane: the family's recipe, bone for bone.
 *
 * A 1dp ring of `onSurface` at 10% as the outer box's background — Glance has no border modifier —
 * and the scrim inside it. The scrim is `surface`, never `page`: on a dark theme `page` is the
 * launcher's own black, and a pane painted in it is not a pane at all. That is exactly how the
 * first version of this widget ended up with its rows floating on somebody's wallpaper.
 */
@Composable
private fun Folio(
    data: CalendarWidgetData,
    view: CalendarWidgetView,
    anchor: LocalDate,
    today: LocalDate,
    m: CalendarMetrics,
    status: YantraColors,
    widgetId: Int,
) {
    val context = LocalContext.current
    val opacity = (currentState<Preferences>()[CalendarWidgetKeys.OPACITY]
        ?: CalendarWidgetDefaults.OPACITY)
        .coerceIn(CalendarWidgetDefaults.MIN_OPACITY, 100)
    val scrim = GlanceTheme.colors.surface.getColor(context).copy(alpha = opacity / 100f)
    val edge = GlanceTheme.colors.onSurface.getColor(context).copy(alpha = 0.10f)
    Box(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ColorProvider(edge))
            .cornerRadius(R.dimen.widget_radius)
            .padding(1.dp)
    ) {
        Column(
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(scrim))
                .cornerRadius(R.dimen.widget_radius)
                .padding(horizontal = m.pad, vertical = m.padV)
        ) {
            Masthead(data, view, anchor, today, m, widgetId)
            Box(GlanceModifier.fillMaxSize().padding(top = m.air)) {
                when (view) {
                    CalendarWidgetView.MONTH -> MonthGrid(data, m)
                    CalendarWidgetView.THREE_DAY -> Programme(data, anchor, today, m, status)
                    CalendarWidgetView.DAY -> DayPage(data, anchor, today, m, status)
                }
            }
        }
    }
}

/**
 * Two lines, the same two in every view: the container, then the position inside it.
 *
 * Two Texts on two lines rather than a date marker beside a number, because Glance has no baseline
 * alignment and sibling Texts at different sizes on one line visibly drift. It is also the only
 * thing a reader has to learn, so switching view must never re-design it.
 */
@Composable
private fun ColumnScope.Masthead(
    data: CalendarWidgetData,
    view: CalendarWidgetView,
    anchor: LocalDate,
    today: LocalDate,
    m: CalendarMetrics,
    widgetId: Int,
) {
    val context = LocalContext.current
    Row(
        GlanceModifier.fillMaxWidth().height(m.key),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            data.eyebrow,
            style = TextStyle(
                // Today is marked one way in this whole widget: the accent on the word that names
                // it. Never a fill, never a pill, never a ground.
                color = if (view == CalendarWidgetView.DAY && anchor == today) GlanceTheme.colors.primary
                else GlanceTheme.colors.onSurfaceVariant,
                fontSize = m.label,
                fontWeight = FontWeight.Normal,
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        // Fixed composition per size: the same keys in the same places in every state. The `today`
        // key this replaced was drawn only when you were off today, so the paging keys jumped 24dp
        // sideways under the thumb the moment you paged.
        if (m.paging) {
            Key(R.drawable.ic_widget_prev, "Previous", m, first = true) {
                actionRunCallback<CalendarStepAction>(
                    actionParametersOf(CalendarStepAction.DELTA to -1)
                )
            }
            Key(R.drawable.ic_widget_next, "Next", m) {
                actionRunCallback<CalendarStepAction>(
                    actionParametersOf(CalendarStepAction.DELTA to 1)
                )
            }
        }
        if (m.bigViews) {
            Key(
                when (view) {
                    CalendarWidgetView.DAY -> R.drawable.ic_widget_view_day
                    CalendarWidgetView.THREE_DAY -> R.drawable.ic_widget_view_days
                    CalendarWidgetView.MONTH -> R.drawable.ic_widget_view_month
                },
                "Change view",
                m,
                // It carries state, so it is drawn in the ink the body is drawn in.
                state = true,
            ) {
                actionRunCallback<CalendarSetViewAction>(
                    actionParametersOf(CalendarSetViewAction.VIEW to m.next(view).store)
                )
            }
        }
        Key(R.drawable.ic_widget_more, "Widget settings", m) {
            actionStartActivity(settingsIntent(context, widgetId))
        }
    }
    Text(
        data.heading,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = m.hero,
            // The one Bold on the surface. Glance has two visual weights; the ramp is spent here.
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 1,
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            // The date is the way back: into the app on the day it names, or — once you have paged
            // away — to today. That is the whole reason there is no separate today key.
            .clickable(
                if (anchor == today) actionStartActivity(openCalendarIntent(context, anchor))
                else actionRunCallback<CalendarTodayAction>()
            ),
    )
}

/**
 * A header key: a drawn mark, never a typed chevron, and never on a ground.
 *
 * Four naked marks on the quietest line of a typographic page. A fill behind each — the list
 * widget's recipe — would turn the folio into a toolbar, so this is a deliberate and uniform
 * departure applied to all of them rather than to none.
 */
@Composable
private fun RowScope.Key(
    res: Int,
    description: String,
    m: CalendarMetrics,
    first: Boolean = false,
    state: Boolean = false,
    action: () -> Action,
) {
    Box(
        // The 2dp gap is inside the key's own size, not added to it — see [AgendaLine] for what
        // happens when that is forgotten. It is deliberate here: the touch target stays `m.key`
        // and only the drawn glyph is inset, so four keys sit on a 34dp pitch with air between.
        GlanceModifier
            .size(m.key)
            .padding(start = if (first) 0.dp else 2.dp)
            .cornerRadius(R.dimen.widget_inner_radius)
            .clickable(action()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(res),
            contentDescription = description,
            colorFilter = ColorFilter.tint(
                if (state) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant
            ),
            modifier = GlanceModifier.size(m.glyph),
        )
    }
}

// ---- the day ----

@Composable
private fun DayPage(
    data: CalendarWidgetData,
    anchor: LocalDate,
    today: LocalDate,
    m: CalendarMetrics,
    status: YantraColors,
) {
    val column = data.columns.firstOrNull()
    Column(GlanceModifier.fillMaxSize()) {
        when {
            !data.ready -> Waiting(m)
            column == null || column.rows.isEmpty() -> Quiet(data.nextUp, anchor, m)
            else -> Agenda(column, today, m, status, m.rows)
        }
    }
}

/**
 * Three days as one page, never as three columns.
 *
 * A third of a 300dp pane is ninety pixels, which cannot hold an event title — the column version
 * paid for that with two full-height rules and the words "Nothing on" printed three times. Stacked,
 * every title gets the full width, the page keeps one left edge, and a day with nothing on it costs
 * one quiet line instead of a column.
 */
@Composable
private fun Programme(
    data: CalendarWidgetData,
    anchor: LocalDate,
    today: LocalDate,
    m: CalendarMetrics,
    status: YantraColors,
) {
    val days = data.columns
    if (!data.ready || days.isEmpty()) {
        Column(GlanceModifier.fillMaxSize()) { Waiting(m) }
        return
    }
    // When the whole span is empty the subheads are dropped and the page says it once: three
    // repetitions of the same non-information is not three facts.
    if (days.all { it.rows.isEmpty() }) {
        Column(GlanceModifier.fillMaxSize()) { Quiet(data.nextUp, anchor, m) }
        return
    }
    // Every day takes one slot first, so every day is visible even when it is empty; what is left
    // is dealt round-robin in date order until it runs out or every day has what it needs.
    val share = IntArray(days.size) { 1 }
    var left = (m.rows - days.size).coerceAtLeast(0)
    while (left > 0) {
        var dealt = false
        days.forEachIndexed { i, d ->
            if (left > 0 && share[i] < minOf(d.rows.size, 6)) {
                share[i]++
                left--
                dealt = true
            }
        }
        if (!dealt) break
    }
    Column(GlanceModifier.fillMaxSize()) {
        days.forEachIndexed { index, day ->
            Column(
                GlanceModifier
                    .fillMaxWidth()
                    .padding(top = if (index == 0) 0.dp else m.dayAir)
            ) {
                Subhead(day, m)
                if (day.rows.isEmpty()) {
                    Line("Nothing on", m, GlanceTheme.colors.outline, m.label, day.date)
                } else {
                    Agenda(day, today, m, status, share[index])
                }
            }
        }
    }
}

/** A day's name inside the programme — the masthead's voice, one size down. */
@Composable
private fun ColumnScope.Subhead(day: WidgetDayColumn, m: CalendarMetrics) {
    val context = LocalContext.current
    Text(
        "${day.weekday} ${day.dayNumber}",
        style = TextStyle(
            color = if (day.isToday) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
            fontSize = m.label,
            fontWeight = FontWeight.Normal,
        ),
        maxLines = 1,
        modifier = GlanceModifier
            .fillMaxWidth()
            // No fixed height: a subhead is one line of type and knows how tall that is. Pinning
            // it and then padding inside the pin is what clipped the rows below.
            .padding(bottom = 2.dp)
            .clickable(actionStartActivity(openCalendarIntent(context, day.date))),
    )
}

/** One day's lines, and an honest count of what would not fit. */
@Composable
private fun ColumnScope.Agenda(
    day: WidgetDayColumn,
    today: LocalDate,
    m: CalendarMetrics,
    status: YantraColors,
    limit: Int,
) {
    val all = day.rows
    val room = limit.coerceAtLeast(1)
    val overflows = all.size + day.more > room
    val shown = if (overflows) all.take((room - 1).coerceAtLeast(1)) else all.take(room)
    val now = if (day.date == today) LocalTime.now() else null
    // The one live mark on the surface: the first thing today that has not started yet. It costs
    // no ink, no space and no element — only the colour of a time that was being printed anyway.
    val nowMin = now?.let { it.hour * 60 + it.minute }
    val nextIndex = nowMin?.let { at ->
        shown.indexOfFirst { r -> (r.startMin ?: -1) >= at }.takeIf { it >= 0 }
    } ?: -1
    shown.forEachIndexed { i, row -> AgendaLine(row, day.date, m, status, next = i == nextIndex) }
    val hidden = all.size - shown.size + day.more
    if (hidden > 0) {
        val after = all.getOrNull(shown.size)?.time
        Line(
            if (after != null) "+$hidden after $after" else "+$hidden more",
            m,
            GlanceTheme.colors.onSurfaceVariant,
            m.label,
            day.date,
        )
    }
}

/**
 * One thing, on one line.
 *
 * Three children and one rule: the margin rule, the gutter, the title. The title is the only
 * weighted child, so a forty-character title truncates where the gutter leaves off and **the left
 * edge never moves, in any view, in any state** — which is the whole reason the times are
 * right-aligned into a fixed column instead of printed in front of each name.
 *
 * One line, not two. The app's two-line row is for task rows, whose second line carries meta; here
 * the meta is a time and the time is already beside the title, so a second line would carry
 * nothing — and one line is what lets a 4x4 show six things whole rather than three truncated.
 */
@Composable
private fun ColumnScope.AgendaLine(
    row: WidgetAgendaRow,
    date: LocalDate,
    m: CalendarMetrics,
    status: YantraColors,
    next: Boolean,
) {
    val context = LocalContext.current
    // The margin rule is the only place identity lives, and it is drawn on every row — an
    // untinted row wears frame ink, so the column always resolves as structure rather than as
    // damage. The accent is never a row's identity: coral is the effort layer, and a generic
    // 14:00 meeting wearing it would destroy the one hue the palette depends on.
    val rule: ColorProvider = when {
        row.tint != null -> ColorProvider(Color(LabelPalette.display(row.tint, status.isDark)))
        row.deviceColor != null ->
            LabelPalette.nearest(row.deviceColor, status.isDark)
                ?.let { ColorProvider(Color(it)) } ?: GlanceTheme.colors.outline
        else -> GlanceTheme.colors.outline
    }
    val open = row.nodeId
        ?.let { openNodeIntent(context, it) }
        ?: openCalendarIntent(context, date)
    Row(
        // **The height is the pitch, not the line.** Padding in Glance — as in Compose — is taken
        // *inside* a fixed height, not added to it: `height(20).padding(top = 10)` leaves ten
        // pixels for a fifteen-point word, and every row on the surface was drawn with its bottom
        // half cut off. The row is the full pitch and gives the gap back as padding, which is also
        // the number the row budget is computed from, so the two can no longer disagree.
        GlanceModifier
            .fillMaxWidth()
            .height(m.line + m.rowGap)
            .padding(top = m.rowGap)
            .clickable(actionStartActivity(open)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            GlanceModifier
                .width(3.dp)
                .height(m.line - 4.dp)
                .cornerRadius(2.dp)
                .background(rule)
        ) {}
        Box(
            // The width is the column **plus** its two gaps, for the same reason the row's height
            // is the pitch: padding is taken out of a fixed width, not added to it. At `width(46)`
            // with 19dp of padding inside it, the gutter had 27dp to print `14:00` in and printed
            // `7:0…` instead.
            GlanceModifier
                .width(9.dp + m.gutter + m.gutterGap)
                .padding(start = 9.dp, end = m.gutterGap),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                row.time ?: if (row.allDay) "all day" else "due",
                style = TextStyle(
                    color = when {
                        row.done -> GlanceTheme.colors.outline
                        // The only crimson on the surface, and it is one word wide.
                        row.isTask && row.time == null && date < LocalDate.now() ->
                            ColorProvider(status.overdue)
                        next -> GlanceTheme.colors.primary
                        else -> GlanceTheme.colors.onSurfaceVariant
                    },
                    fontSize = m.label,
                    fontWeight = FontWeight.Normal,
                ),
                maxLines = 1,
            )
        }
        Text(
            plain(row.title),
            style = TextStyle(
                color = if (row.done) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                fontSize = m.title,
                fontWeight = FontWeight.Normal,
                textDecoration = if (row.done) TextDecoration.LineThrough else TextDecoration.None,
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
    }
}

/**
 * The empty day, which is the common state and is designed as one.
 *
 * Two lines hanging at the title indent. The second is the one worth its ink: an empty Friday that
 * also says what Tuesday holds has answered the question a person was actually asking, which is
 * when the quiet ends.
 */
@Composable
private fun ColumnScope.Quiet(next: WidgetNextUp?, date: LocalDate, m: CalendarMetrics) {
    if (m.compact) {
        // A 2x2 body is about one line tall; two would clip.
        Line(
            if (next != null) "Nothing on · next ${next.label}" else "Nothing on.",
            m, GlanceTheme.colors.onSurfaceVariant, m.title, date,
        )
        return
    }
    Line("Nothing on.", m, GlanceTheme.colors.onSurfaceVariant, m.title, date)
    Line(
        if (next != null) "Next · ${next.label}, ${plain(next.title)}"
        else "Nothing in the next two weeks.",
        m, GlanceTheme.colors.outline, m.label, date, top = 6.dp,
    )
}

/** The first frame, before the first read lands. Never "Nothing on" — a calendar may not guess. */
@Composable
private fun ColumnScope.Waiting(m: CalendarMetrics) {
    Line("…", m, GlanceTheme.colors.outline, m.title, null)
}

/** A line in the title column: no rule, no gutter, the same left edge as everything else. */
@Composable
private fun ColumnScope.Line(
    text: String,
    m: CalendarMetrics,
    ink: ColorProvider,
    size: TextUnit,
    date: LocalDate?,
    top: Dp = 0.dp,
) {
    val context = LocalContext.current
    Text(
        text,
        style = TextStyle(color = ink, fontSize = size, fontWeight = FontWeight.Normal),
        maxLines = 1,
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(start = 3.dp + 9.dp + m.gutter + m.gutterGap, top = top)
            .then(
                if (date == null) GlanceModifier
                else GlanceModifier.clickable(actionStartActivity(openCalendarIntent(context, date)))
            ),
    )
}

// ---- the month ----

/**
 * The grid, and nothing under it.
 *
 * A month with nothing on it is a grid with no marks, which is the correct and complete answer —
 * and the agenda that used to sit beneath it never fitted anyway: a 4x4 body is 190dp and the grid
 * plus its weekday letters want 183 of them.
 *
 * Six weighted rows, so the grid fills whatever the launcher actually gave the widget. Under
 * Responsive, `LocalSize` is the *declared bucket*, so any computed cell height is wrong the moment
 * somebody stretches a 4x4 into a 5x5.
 */
@Composable
private fun MonthGrid(data: CalendarWidgetData, m: CalendarMetrics) {
    val locale = Locale.getDefault()
    val scale = LocalContext.current.resources.configuration.fontScale
    // A font-scale guard, not a size guard: weighted rows removed the size case. At a large system
    // font the marks go invisible and the grid degrades to numerals, which is a decision taken here
    // rather than a clip nobody chose.
    val estCell = (m.bodyH - m.weekdayH) / 6
    val marksFit = estCell >= (m.cell.value * 1.30f * scale).dp + 6.dp + 2.dp
    Column(GlanceModifier.fillMaxSize()) {
        Row(GlanceModifier.fillMaxWidth().height(m.weekdayH)) {
            // Monday-first, matching monthGrid, and NARROW rather than take(1) — the latter gives
            // an ambiguous T/T and S/S, and splits a surrogate pair outside Latin locales.
            java.time.DayOfWeek.entries.forEach { d ->
                Text(
                    d.getDisplayName(JavaTextStyle.NARROW, locale),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = m.label,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
        val weeks = if (data.cells.size == 42) data.cells.chunked(7) else List(6) { emptyList() }
        Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
            weeks.forEach { week ->
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    if (week.isEmpty()) Box(GlanceModifier.defaultWeight()) {}
                    else week.forEach { DayCell(it, m, marksFit) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DayCell(cell: WidgetDayCell, m: CalendarMetrics, marksFit: Boolean) {
    val context = LocalContext.current
    val ink = when {
        // Today is marked one way in this whole widget, and this is it: the numeral in the accent.
        // Not bold, not filled, not boxed.
        cell.isToday -> GlanceTheme.colors.primary
        !cell.inMonth -> GlanceTheme.colors.outline
        else -> GlanceTheme.colors.onSurface
    }
    Box(
        GlanceModifier
            .defaultWeight()
            .fillMaxHeight()
            .cornerRadius(R.dimen.widget_inner_radius)
            .clickable(actionStartActivity(openCalendarIntent(context, cell.date))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                cell.date.dayOfMonth.toString(),
                style = TextStyle(color = ink, fontSize = m.cell, fontWeight = FontWeight.Normal),
                maxLines = 1,
                // The gap belongs to the numeral, not to the mark. Padding on the mark would sit
                // *inside* its 3dp height and its background paints the whole view, so the rule
                // would have kept its size and lost its distance — a dash tucked under the number
                // rather than a rule set below it.
                modifier = GlanceModifier.padding(bottom = 3.dp),
            )
            // One mark per cell at three lengths, not three marks. Seven separate 1dp slivers came
            // to a hundred and twenty-six hairlines on a busy month: a grey smear, neither
            // countable nor invisible. This is one shape on one baseline, in the numeral's own ink,
            // so today's rule is the accent and a neighbouring month's is outline.
            //
            // Invisible rather than absent, so a quiet month's numerals sit at exactly the optical
            // height a busy month's do, and the grid does not reflow when the first read lands.
            Box(
                GlanceModifier
                    .width(MARK[if (cell.count >= 5) 2 else if (cell.count >= 3) 1 else 0])
                    .height(3.dp)
                    .cornerRadius(2.dp)
                    .background(ink)
                    .visibility(
                        if (cell.count == 0 || !marksFit) Visibility.Invisible else Visibility.Visible
                    )
            ) {}
        }
    }
}

// ---- words, and the way back into the app ----

/**
 * A title renders as literally its characters.
 *
 * The file format is markdown and a title can carry link syntax; a widget has no emphasis to give
 * it and would otherwise print the punctuation.
 */
private fun plain(title: String): String =
    Links.plain(Markdown.plain(title)).trim().ifBlank { "Untitled" }

private fun openCalendarIntent(context: Context, date: LocalDate): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(WidgetIntents.EXTRA_OPEN_CALENDAR, date.toString())
        // A distinct data URI per day, or the launcher merges every day's PendingIntent into
        // whichever one it saw first and the whole grid opens on the same date.
        data = Uri.parse("yantra://calendar/$date")
    }

private fun openNodeIntent(context: Context, nodeId: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(WidgetIntents.EXTRA_OPEN_NODE, nodeId)
        data = Uri.parse("yantra://node/$nodeId")
    }

private fun settingsIntent(context: Context, widgetId: Int): Intent =
    Intent(context, WidgetSettingsActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra(WidgetSettingsActivity.EXTRA_WIDGET_ID, widgetId)
        putExtra(WidgetSettingsActivity.EXTRA_IS_CALENDAR, true)
        data = Uri.parse("yantra://widgetsettings/$widgetId")
    }
