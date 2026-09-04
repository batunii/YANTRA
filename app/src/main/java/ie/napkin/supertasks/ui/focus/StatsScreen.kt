package ie.napkin.supertasks.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import ie.napkin.supertasks.AppContainer
import ie.napkin.supertasks.data.db.FocusSessionEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.db.counts
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.PageHeader
import ie.napkin.supertasks.ui.components.bhupuraPath
import ie.napkin.supertasks.ui.components.YantraInk
import ie.napkin.supertasks.ui.components.SelectChip
import ie.napkin.supertasks.ui.components.SwitchHereDialog
import ie.napkin.supertasks.ui.components.isReducedMotion
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.durationLabel
import ie.napkin.supertasks.ui.Routes
import ie.napkin.supertasks.ui.container
import ie.napkin.supertasks.domain.FocusTimer
import ie.napkin.supertasks.domain.TimingRequest
import ie.napkin.supertasks.ui.theme.Yantra
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import ie.napkin.supertasks.ui.theme.YantraDisplay
import ie.napkin.supertasks.ui.theme.YantraMono
import ie.napkin.supertasks.data.format.Links

data class DayStat(val date: LocalDate, val completed: Int, val totalSecs: Int)

/** One row of a breakdown — a task, a list or a workspace, with what was given to it. */
data class GroupStat(
    val key: String,
    val title: String,
    val sessions: Int,
    val totalSecs: Int,
)

/**
 * The same time, cut three ways.
 *
 * All three are built from one pass over the same sessions, so they add up to each other: a task's
 * hours are inside its list's, and a list's are inside its workspace's. That only holds because a
 * session is attributed to exactly one of each — see [StatsViewModel.build].
 */
data class Breakdown(
    val tasks: List<GroupStat> = emptyList(),
    val lists: List<GroupStat> = emptyList(),
    val workspaces: List<GroupStat> = emptyList(),
) {
    fun of(cut: Cut): List<GroupStat> = when (cut) {
        Cut.TASKS -> tasks
        Cut.LISTS -> lists
        Cut.WORKSPACES -> workspaces
    }
}

/** Which way the breakdown is cut. The label is the chip's. */
enum class Cut(val label: String) { TASKS("Tasks"), LISTS("Lists"), WORKSPACES("Workspaces") }

/** How far back the breakdown reaches. */
enum class Span(val label: String) { WEEK("Week"), ALL_TIME("All time") }

data class Stats(
    val todaySecs: Int = 0,
    val todayCount: Int = 0,
    val weekSecs: Int = 0,
    val weekCount: Int = 0,
    val days: List<DayStat> = emptyList(),
    val week: Breakdown = Breakdown(),
    val allTime: Breakdown = Breakdown(),
) {
    fun breakdown(span: Span): Breakdown = when (span) {
        Span.WEEK -> week
        Span.ALL_TIME -> allTime
    }
}

/**
 * The two facts the diagram at the top of this page draws: how much you did today, and how much of
 * the week you showed up for.
 *
 * Derived from [Stats] rather than queried again, and that is the whole point of it being a data
 * class with no logic of its own. The window (seven days including today) and the rule for which
 * sessions count (see `counts`) are already settled once in [StatsViewModel.build], and the bars
 * and the cards on this same page read the same answers. A second query here could drift from the
 * numbers printed inches below the marks, and nothing on screen would reveal which one was lying.
 */
data class WeekReview(
    /** Days in the window with anything on them — one trikona each. */
    val activeDays: Int = 0,
    /** Sessions today — one ring each. */
    val todayCount: Int = 0,
) {
    val isEmpty: Boolean get() = activeDays == 0

    /**
     * The review in the shape [strataMarks] reads: one trikona per active day, then today's rings
     * after the last of them. A day with no rings of its own is a zero here, which is what puts
     * the trikonas next to each other.
     *
     * **Not the week's full per-day counts**, which is the other reading of a weekly review and was
     * built first. The strata band is 4.4 units of a 28-unit design space, so fifteen rings — an
     * ordinary week once every task's sessions are pooled — sit 0.31u apart against a 0.16u stroke
     * and read as one moiré disc. A mark you cannot count is not a mark, and counting them is the
     * glyph's whole claim.
     *
     * The cost is accepted rather than hidden: several trikonas in a row open nothing, where the
     * grammar says a trikona opens a day, and the week's earlier sessions are not drawn. What is
     * bought is that both numbers stay legible — today's count is bounded by a day, the trikonas by
     * seven — which is what a diagram at the top of a stats page is for. The per-day detail it
     * gives up is exactly what the bars below it already show.
     */
    val strataDayCounts: List<Int> get() =
        if (activeDays == 0) emptyList() else List(activeDays - 1) { 0 } + todayCount
}

/** [WeekReview] read straight off the page's own numbers. */
fun weekReview(stats: Stats): WeekReview = WeekReview(
    activeDays = stats.days.count { it.completed > 0 },
    todayCount = stats.todayCount,
)

class StatsViewModel(private val container: AppContainer) : ViewModel() {

    /** The live session, so the yantra on this page can sweep it. */
    val timer = container.timer

    /**
     * The play key on a breakdown row.
     *
     * The same one button the now player has, and deliberately the same request object: starting a
     * clock on a task while another is running is a decision someone has to make, and it is made in
     * one place so that every surface asks it the same way.
     */
    val timing = TimingRequest(container.running)

    fun startFocus(nodeId: String, title: String) {
        viewModelScope.launch { timing.toggle(nodeId, title) }
    }

    /** History screens = plain queries over focus_session (per-task, per-day, totals). */
    val stats: StateFlow<Stats> = container.focus.all()
        .map { sessions -> build(sessions) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Stats())

    private suspend fun build(sessions: List<FocusSessionEntity>): Stats {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        fun dayOf(s: FocusSessionEntity): LocalDate =
            Instant.ofEpochMilli(s.startedAt).atZone(zone).toLocalDate()

        // The sessions that count — see `counts`, which is the one place that rule lives.
        //
        // This used to filter on `endedAt` and then on `actualSecs`, which caught the in-flight
        // session and missed the mis-tap: a day of four accidental taps read as four sessions here,
        // and the seconds they logged went into the totals beside them. Interruptions still count,
        // which is the older half of the same rule — the ledger measures time given, not whether
        // the session behaved.
        val counted = sessions.filter { it.counts }
        val todaySessions = counted.filter { dayOf(it) == today }
        val weekStart = today.minusDays(6)

        val days = (0..6).map { offset ->
            val date = today.minusDays((6 - offset).toLong())
            val daySessions = counted.filter { dayOf(it) == date }
            DayStat(date, daySessions.size, daySessions.sumOf { it.actualSecs ?: 0 })
        }

        val weekCounted = counted.filter { dayOf(it) >= weekStart }

        // Every distinct task that was focused, resolved once. A session names a node; the row a
        // breakdown wants is that node's title, the list the work sits in, and the repository it
        // came from. Two windows are cut from the same sessions, so resolving per window would ask
        // the same questions twice for no new answer.
        val ids = counted.map { it.nodeId }.distinct()
        val taskOf = HashMap<String, Pair<String, String>>()
        val listOf = HashMap<String, Pair<String, String>?>()
        ids.forEach { id ->
            val node = container.nodes.byId(id)
            taskOf[id] = id to Links.plain(node?.title.orEmpty()).ifBlank { "Untitled task" }
            // The *nearest* list above the task, not the top of the chain. A subtask three deep
            // still belongs to the list its ancestor sits on, and that is the list you would say it
            // was work on. `ancestors` reads root → parent, so the nearest one is the last match.
            listOf[id] = container.nodes.ancestors(id)
                .lastOrNull { it.type == NodeType.LIST }
                ?.let { it.id to Links.plain(it.title.orEmpty()).ifBlank { "Untitled list" } }
        }
        // Names live in the registry rather than on the store, and Personal is in there too — it is
        // the entry with the empty id, which is also what a session written before workspaces
        // existed carries.
        val wsNames = container.registry.entries().associate { it.id to it.name }

        fun cuts(rows: List<FocusSessionEntity>) = Breakdown(
            tasks = rollup(rows) { taskOf[it.nodeId] },
            lists = rollup(rows) { listOf[it.nodeId] },
            // A task with no list above it is not counted into any list, deliberately: inventing a
            // bucket for it would make the lists add up to more than the workspace they are in.
            workspaces = rollup(rows) { it.workspaceId to (wsNames[it.workspaceId] ?: "Workspace") },
        )

        return Stats(
            todaySecs = todaySessions.sumOf { it.actualSecs ?: 0 },
            todayCount = todaySessions.size,
            weekSecs = weekCounted.sumOf { it.actualSecs ?: 0 },
            weekCount = weekCounted.size,
            days = days,
            week = cuts(weekCounted),
            allTime = cuts(counted),
        )
    }
}

/**
 * Sessions grouped by whatever [key] names them, ranked by time given.
 *
 * Ranked by seconds rather than by session count, because a count stopped measuring anything the
 * moment an open stopwatch existed — three four-minute sessions would outrank one deliberate
 * ninety-minute block. The count still rides along, since it is the other half of the same story.
 *
 * A null key drops the row rather than bucketing it under "other". A task with no list above it
 * belongs to no list, and inventing somewhere to put it would make the lists sum to more than the
 * workspace containing them.
 */
private fun rollup(
    rows: List<FocusSessionEntity>,
    key: (FocusSessionEntity) -> Pair<String, String>?,
): List<GroupStat> =
    rows.mapNotNull { s -> key(s)?.let { it to s } }
        .groupBy({ it.first }, { it.second })
        .map { (id, list) ->
            GroupStat(id.first, id.second, list.size, list.sumOf { it.actualSecs ?: 0 })
        }
        .sortedByDescending { it.totalSecs }
        .take(8)

@Composable
fun StatsScreen(nav: NavHostController) {
    val vm: StatsViewModel = viewModel { StatsViewModel(container()) }
    val stats by vm.stats.collectAsStateWithLifecycle()
    val live by vm.timer.state.collectAsStateWithLifecycle()
    val y = Yantra.colors
    val review = weekReview(stats)
    var cut by rememberSaveable { mutableStateOf(Cut.TASKS) }
    var span by rememberSaveable { mutableStateOf(Span.WEEK) }

    // Starting a clock here can find one already running elsewhere, and that is a question rather
    // than something to do quietly — the ledger would otherwise gain an interruption nobody chose.
    val occupied by vm.timing.occupied.collectAsStateWithLifecycle()
    occupied?.let {
        SwitchHereDialog(
            runningTitle = it.byTitle,
            onConfirm = { vm.timing.confirm() },
            onDismiss = { vm.timing.dismiss() },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(y.page)
            .statusBarsPadding(),
    ) {
        // Folds once the yantra has gone by, so the screen keeps its name without keeping the band.
        val listState = rememberLazyListState()
        val collapsed by remember {
            derivedStateOf { listState.firstVisibleItemIndex > 0 }
        }
        PageHeader("Focus stats", onBack = { nav.popBackStack() }, collapsed = collapsed)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "week-yantra") {
                WeekReviewPanel(
                    week = review,
                    live = live,
                    // Only a running session is a destination. Idle, the focus screen says nothing
                    // this page has not already said better, so the mark is not made to look like
                    // a door that opens onto a dead end.
                    onOpenLive = if (live != null) ({ nav.navigate(Routes.FOCUS_CURRENT) }) else null,
                )
            }
            item(key = "strip") { StatStrip(stats, review) }
            item(key = "breakdown-header") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("Breakdown", modifier = Modifier.weight(1f))
                    // The window toggle rides the section label rather than taking a row of its
                    // own. It is a two-state question about the rows below, and this page is being
                    // cut for space — a second strip of chips under the first would spend more of
                    // it than the two cards this replaced.
                    Span.entries.forEachIndexed { i, option ->
                        if (i > 0) {
                            Text(
                                "·",
                                style = MaterialTheme.typography.labelSmall,
                                color = y.textDim,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (span == option) FontWeight.W700 else FontWeight.W500,
                            color = if (span == option) y.accentText else y.textMuted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { span = option }
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            item(key = "cuts") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Cut.entries.forEach { option ->
                        SelectChip(
                            label = option.label,
                            selected = cut == option,
                            // Stretched to a third each: a segmented control, not three chips that
                            // happen to be next to each other.
                            stretch = true,
                            modifier = Modifier.weight(1f),
                            onClick = { cut = option },
                        )
                    }
                }
            }
            val rows = stats.breakdown(span).of(cut)
            if (rows.isEmpty()) {
                item(key = "breakdown-empty") {
                    Text(
                        when (span) {
                            Span.WEEK -> "No focus in the last 7 days."
                            Span.ALL_TIME -> "No focus recorded yet."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = y.textMuted,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                }
            } else {
                // Against the leader, not against the total. A share of the whole makes every mark
                // nearly empty the moment the work is spread over a dozen tasks — which is the
                // ordinary case and the one the column most needs to be readable in. The bars above
                // are normalised the same way, so the two read alike.
                val top = rows.first().totalSecs.coerceAtLeast(1)
                items(rows, key = { "${cut.name}-${it.key}" }) { row ->
                    BreakdownRow(
                        row = row,
                        fraction = row.totalSecs.toFloat() / top,
                        // Only a task is somewhere you can go and something you can start. A list
                        // and a workspace are sums over many tasks — there is no clock to put on
                        // them, and no one page that is theirs.
                        onOpen = if (cut == Cut.TASKS) ({ nav.navigate(Routes.focus(row.key)) }) else null,
                        onPlay = if (cut == Cut.TASKS) ({ vm.startFocus(row.key, row.title) }) else null,
                        running = live?.nodeId == row.key,
                    )
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * The week, drawn — the diagram this page opens with.
 *
 * The same glyph the live session sits inside, reading the same way, with no arc on it because
 * nothing is running: a trikona for each day you focused, then today's sessions as rings outside
 * them. [WeekReview.strataDayCounts] says what it leaves out and why.
 *
 * Only one number is written underneath, and only the one nothing else on the page says. Today's
 * count is already the card directly below this, and printing it twice a thumb's width apart would
 * make the diagram look like a caption for the cards rather than the thing they are itemising.
 *
 * Set in the text voice, not mono: mono is the instrument voice and reads as a number being
 * *watched* — the countdown on the focus screen is; a week you have already lived is reported.
 */
@Composable
private fun WeekReviewPanel(
    week: WeekReview,
    live: FocusTimer.State?,
    onOpenLive: (() -> Unit)?,
) {
    val y = Yantra.colors
    val context = LocalContext.current
    val reduced = remember { isReducedMotion(context) }

    // The one thing in this app allowed to move at rest, and only while a session is actually
    // running. An open session has nowhere to arrive, so its arc fills over an hour purely to mark
    // passing time — the same reading the active timer uses, because it is the same session.
    val elapsed = live?.let {
        if (it.isOpen) it.elapsedSecs / 3600f
        else if (it.plannedSecs == 0) 0f
        else (it.plannedSecs - it.remainingSecs).toFloat() / it.plannedSecs
    }
    val progress by animateFloatAsState(
        (elapsed ?: 0f).coerceIn(0f, 1f), tween(400), label = "sessionArc",
    )
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        YantraFocusGlyph(
            dayCounts = week.strataDayCounts,
            // Null when nothing runs: no arc, and the marks are the whole picture.
            sessionProgress = if (live != null) progress else null,
            onBreak = false,
            darkTheme = y.isDark,
            reducedMotion = reduced,
            // No haptic: a thud is the reward for depositing a session, and nothing is deposited by
            // opening a screen.
            modifier = Modifier
                .size(208.dp)
                .then(
                    if (onOpenLive == null) Modifier
                    else Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        // No ripple. The mark is a drawing, and a rectangle of ink flashing behind
                        // a gated square reads as a second, wrong shape.
                        indication = null,
                        onClickLabel = "Open the running session",
                        onClick = onOpenLive,
                    )
                ),
        )
        if (live != null) {
            // The session, named, directly under the arc that is drawing it. Mono here and nowhere
            // else on this page: this is the one number being watched rather than reported.
            Text(
                "%d:%02d".format(live.elapsedSecs / 60, live.elapsedSecs % 60),
                fontFamily = YantraMono,
                fontSize = 15.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 1.sp,
                color = y.accentText,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                Links.plain(live.nodeTitle).ifBlank { "Untitled task" },
                style = MaterialTheme.typography.bodySmall,
                color = y.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        } else {
            Text(
                if (week.isEmpty) "No focus in the last 7 days"
                else "Focused on ${week.activeDays} of the last 7 days",
                style = MaterialTheme.typography.bodySmall,
                color = y.textMuted,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

/**
 * The four numbers, in one row and no boxes.
 *
 * This replaced two cards that between them spent about a third of the screen on two figures. A
 * card earns its background by being a thing you act on; these are read and nothing else, so they
 * get a label, a numeral and the width they need. Four now fit in less room than two did.
 *
 * Two of them are also drawn above — the rings are Today and the trikonas are Rhythm — which is
 * not a duplication to remove. The glyph is read at a glance and answers "how did the week go";
 * the numerals answer "exactly how much", and a diagram that has to be counted precisely is a
 * diagram doing a table's job.
 */
@Composable
private fun StatStrip(stats: Stats, review: WeekReview) {
    Row(
        Modifier.fillMaxWidth().padding(top = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatCell("Today", "${stats.todayCount}", "sessions", accent = true, modifier = Modifier.weight(1f))
        // "Consistency" said in a word what "4 of 7" says in three characters, and cost more room
        // than the number it labelled. Rhythm is the shorter word for the same question: did you
        // keep showing up.
        StatCell("Rhythm", "${review.activeDays}/7", "days", modifier = Modifier.weight(1f))
        StatCell("Week", "${stats.weekCount}", durationLabel(stats.weekSecs), modifier = Modifier.weight(1f))
        // Per day *worked*, not per day of the week. Dividing by seven answers a question nobody
        // asks — it drags a good three-day week below a thin seven-day one — where this says how
        // deep a working day goes, which is the one thing the other three cannot be read for.
        StatCell(
            "Per day",
            if (review.activeDays == 0) "—" else "%.1f".format(stats.weekCount.toFloat() / review.activeDays),
            "a day on",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val y = Yantra.colors
    Column(modifier) {
        SectionLabel(label, color = y.textMuted)
        Text(
            value,
            fontFamily = YantraDisplay,
            fontSize = 25.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.5).sp,
            color = if (accent) y.accentText else y.textPrimary,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            sub,
            fontSize = 10.5.sp,
            color = y.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/**
 * One line of a breakdown: how much of the leader's time this task, list or workspace took.
 *
 * The share is drawn as the bhupura rather than as a bar, which is the point of it. The mark is one
 * path redrawn at every size — the checkbox, the focus glyph, the launcher icon are all this same
 * outline — so a quantity in this app can be shown filling the app's own shape instead of importing
 * a rectangle that belongs to no part of the language.
 */
@Composable
private fun BreakdownRow(
    row: GroupStat,
    fraction: Float,
    onOpen: (() -> Unit)?,
    onPlay: (() -> Unit)?,
    running: Boolean,
) {
    val y = Yantra.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onOpen == null) Modifier else Modifier.clickable(onClick = onOpen))
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShareMark(fraction, Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                row.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.W600,
                color = y.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${durationLabel(row.totalSecs)} · ${row.sessions} session${if (row.sessions == 1) "" else "s"}",
                fontSize = 11.5.sp,
                color = y.textDim,
                maxLines = 1,
            )
        }
        if (onPlay != null) {
            // Its own target, and a real one: 40dp of tappable circle inside a row that also opens.
            // A play key you have to aim at is a play key that starts the wrong task.
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (running) y.accentFill else Color.Transparent)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (running) "Stop the clock on ${row.title}" else "Start the clock on ${row.title}",
                    tint = if (running) y.accent else y.textMuted,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

/**
 * The bhupura, filled from the left to [fraction].
 *
 * The frame is always drawn whole and the fill clipped inside it, so every row shows the same mark
 * at the same size and only the ink inside it differs — a gate that lost its corners at 12% would
 * read as a different symbol rather than as a smaller quantity.
 *
 * Neutral outline and accent fill, which is the colour law doing its ordinary job: the frame is
 * structure, and what is filled in is your own effort. Nothing here is a gauge of the bindu, which
 * this deliberately does not draw — the bindu is the centre, not a measure, and a mark this small
 * would turn it into one.
 */
@Composable
private fun ShareMark(fraction: Float, modifier: Modifier = Modifier) {
    val y = Yantra.colors
    val neutral = YantraInk.neutral(y.isDark)
    val accent = y.accent
    Canvas(modifier) {
        val s = size.minDimension
        val path = bhupuraPath(s)
        drawPath(path, color = neutral, alpha = 0.45f, style = Stroke(width = s * 0.055f))
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            clipRect(right = s * f) { drawPath(path, color = accent, alpha = 0.9f) }
        }
    }
}
