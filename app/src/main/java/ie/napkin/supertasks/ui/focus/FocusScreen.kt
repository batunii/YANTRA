package ie.napkin.supertasks.ui.focus

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import ie.napkin.supertasks.AppContainer
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.FocusSessionEntity
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.durationLabel
import ie.napkin.supertasks.ui.container
import ie.napkin.supertasks.ui.theme.MonoLarge
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.LocalYantraHaptics
import ie.napkin.supertasks.ui.components.isReducedMotion
import androidx.compose.ui.platform.LocalContext
import ie.napkin.supertasks.data.db.FocusOutcome
import ie.napkin.supertasks.ui.components.ButtonTone
import ie.napkin.supertasks.ui.components.ConfirmDialog
import androidx.compose.ui.text.input.KeyboardType
import ie.napkin.supertasks.ui.components.YantraField
import ie.napkin.supertasks.ui.components.YantraButton
import ie.napkin.supertasks.ui.theme.Yantra
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ie.napkin.supertasks.ui.components.YantraMark
import ie.napkin.supertasks.ui.theme.YantraDisplay
import ie.napkin.supertasks.ui.theme.YantraMono
import ie.napkin.supertasks.domain.FocusTimer
import androidx.compose.ui.graphics.vector.ImageVector
import ie.napkin.supertasks.data.format.Links

class FocusViewModel(
    container: AppContainer,
    val requestedNodeId: String?,
) : ViewModel() {
    val timer = container.timer
    private val nodes = container.nodes

    val requestedNode: StateFlow<NodeEntity?> =
        (requestedNodeId?.let { nodes.observe(it) } ?: flowOf(null))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun sessionsFor(nodeId: String) = timerSessions(nodeId)
    private val focus = container.focus
    private fun timerSessions(nodeId: String) = focus.forNode(nodeId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(nav: NavHostController, nodeIdArg: String?) {
    val vm: FocusViewModel = viewModel(key = "focus-${nodeIdArg ?: "current"}") {
        FocusViewModel(container(), nodeIdArg)
    }
    val timerState by vm.timer.state.collectAsStateWithLifecycle()
    val requestedNode by vm.requestedNode.collectAsStateWithLifecycle()
    val y = Yantra.colors

    val historyNodeId = timerState?.nodeId ?: nodeIdArg
    val sessions by remember(historyNodeId) {
        historyNodeId?.let { vm.sessionsFor(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    // Completed sessions only: the log is appended at session close, so a trikona and its day's
    // first ring always arrive together and an abandoned session leaves no trace.
    val dayCounts = remember(sessions) {
        groupSessionsByDay(
            // Every session that happened, not only the ones that ran their course — the point of
            // the ledger is time given, and an interrupted hour is still an hour.
            sessions.filter { it.actualSecs != null }
                .map { java.time.Instant.ofEpochMilli(it.startedAt) }
        )
    }
    val active = timerState

    Box(
        Modifier
            .fillMaxSize()
            .background(y.page),
    ) {
        // No watermark here, and no glow either. The glyph in the middle of this screen already IS
        // the bhupura, at the size the screen is built around: a second faint one behind it read as
        // a misprint, two copies of the same square not quite lining up. The radial accent glow that
        // used to sit here is gone for the related reason — the motion law's sibling is that nothing
        // radiates at rest, and a lit halo is texture the strata would have to compete with.
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            NavCircle(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Back",
                onClick = { nav.popBackStack() },
                iconSize = 22.dp,
            )

            when {
                active != null && active.isFinished -> DoneContent(
                    state = active,
                    onStartAnother = vm.timer::dismissFinished,
                    onDone = { vm.timer.dismissFinished(); nav.popBackStack() },
                )
                active != null -> ActiveTimer(
                    state = active,
                    dayCounts = dayCounts,
                    onPause = vm.timer::pause,
                    onResume = vm.timer::resume,
                    onComplete = vm.timer::finish,
                    onAbandon = vm.timer::abandon,
                )
                requestedNode != null -> IdleScroll(sessions) {
                    TimerSetup(
                        node = requestedNode!!,
                        onStart = { secs -> vm.timer.start(requestedNode!!.id, requestedNode!!.title.orEmpty(), secs) },
                    )
                }
                else -> IdleScroll(sessions) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 44.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        YantraMark(
                            Modifier.size(60.dp),
                            tint = y.checkOutline,
                            checkTint = y.accent.copy(alpha = 0.55f),
                        )
                        Text(
                            "Nothing in focus.\nStart a session from any task.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = y.textMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IdleScroll(sessions: List<FocusSessionEntity>, top: @Composable () -> Unit) {
    val y = Yantra.colors
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        top()
        if (sessions.isNotEmpty()) {
            SectionLabel("History", modifier = Modifier.padding(start = 30.dp, top = 28.dp, bottom = 6.dp))
            Column(Modifier.padding(horizontal = 26.dp)) {
                sessions.forEach { s -> SessionRow(s) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One duration option. Its own function now that there are four of them and one is a door. */
@Composable
private fun DurationChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val y = Yantra.colors
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) y.accent.copy(alpha = 0.16f) else y.cardBg)
            .then(if (selected) Modifier.border(1.5.dp, y.accent, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.W800 else FontWeight.W700,
            color = if (selected) y.accentText else y.textSecondary,
        )
    }
}

private val PRESETS = listOf(15, 25, 50)

@Composable
private fun TimerSetup(node: NodeEntity, onStart: (Int) -> Unit) {
    val y = Yantra.colors
    var minutes by remember { mutableIntStateOf(25) }
    var picking by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 30.dp)) {
        Spacer(Modifier.height(16.dp))
        SectionLabel("Focus on")
        Text(
            Links.plain(node.title.orEmpty()).ifBlank { "Untitled task" },
            fontFamily = YantraDisplay,
            fontSize = 32.sp,
            lineHeight = 39.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.4).sp,
            color = y.textPrimary,
            modifier = Modifier.padding(top = 10.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(28.dp))
        // Two instruments, and the screen has to make the difference legible rather than hide one
        // behind the other: a promise you are making, or a clock you are simply starting.
        SectionLabel("How long")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(15, 25, 50).forEach { m ->
                DurationChip(
                    label = "$m",
                    selected = minutes == m,
                    modifier = Modifier.weight(1f),
                    onClick = { minutes = m },
                )
            }
            // Three presets were three opinions about how long you should sit, and "exactly this
            // long" was the whole point of the committed mode. Ninety minutes is a real answer.
            DurationChip(
                label = if (minutes in PRESETS) "···" else "$minutes",
                selected = minutes !in PRESETS,
                modifier = Modifier.weight(1f),
                onClick = { picking = true },
            )
        }
        if (picking) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                YantraField(
                    value = custom,
                    onValue = { typed -> custom = typed.filter { it.isDigit() }.take(3) },
                    placeholder = "minutes",
                    keyboard = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
                YantraButton(
                    label = "Set",
                    tone = ButtonTone.Quiet,
                    enabled = (custom.toIntOrNull() ?: 0) > 0,
                    onClick = {
                        custom.toIntOrNull()?.takeIf { it > 0 }?.let { minutes = it }
                        picking = false
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Anything up to a few hours. 90 for a long stretch, 5 for a nudge.",
                color = y.textDim,
                fontSize = 11.5.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
        YantraButton(
            label = "Focus for $minutes min",
            modifier = Modifier.fillMaxWidth(),
            tone = ButtonTone.Soft,
            onClick = { onStart(minutes * 60) },
        )
        Spacer(Modifier.height(10.dp))
        // The stopwatch. Quieter than the commitment, because committing to a length is the more
        // useful habit — but a first-class way to start, not a fallback.
        YantraButton(
            label = "Just start the clock",
            modifier = Modifier.fillMaxWidth(),
            tone = ButtonTone.Quiet,
            onClick = { onStart(0) },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "A set length is a promise to yourself; the clock just records what you gave. Both go " +
                "into the same history.",
            color = y.textDim,
            fontSize = 11.5.sp,
        )
    }
}

@Composable
private fun ActiveTimer(
    state: FocusTimer.State,
    dayCounts: List<Int>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onComplete: () -> Unit,
    onAbandon: () -> Unit,
) {
    val y = Yantra.colors
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        SectionLabel("Focusing", color = y.accentEyebrow)
        Text(
            state.nodeTitle.ifBlank { "Untitled task" },
            fontSize = 16.sp,
            fontWeight = FontWeight.W700,
            color = y.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 30.dp, end = 30.dp, top = 8.dp),
        )
        // The focus glyph. Everything it draws is a pure function of the session log: a trikona
        // opens each day, then one ring per session that day, reading outward from the centre. The
        // live session sweeps the track — the one thing in this app allowed to move at rest, one
        // revolution per session.
        //
        // The frame stays neutral here even for a high-priority task. Twenty-five minutes staring
        // at a crimson enclosure is ambient alarm; priority survives as the label above.
        // An open session has no destination, so there is no fraction of it to be at. The ring
        // fills over an hour purely so the screen is not frozen — it marks passing time, not
        // progress toward anything, because there is nothing to be progressing toward.
        val elapsed = if (state.isOpen) (state.elapsedSecs / 3600f)
        else if (state.plannedSecs == 0) 0f
        else (state.plannedSecs - state.remainingSecs).toFloat() / state.plannedSecs
        val progress by animateFloatAsState(elapsed.coerceIn(0f, 1f), tween(400), label = "sessionArc")
        val haptics = LocalYantraHaptics.current
        val context = LocalContext.current
        val reduced = remember { isReducedMotion(context) }
        YantraFocusGlyph(
            dayCounts = dayCounts,
            sessionProgress = progress,
            onBreak = false,
            darkTheme = y.isDark,
            reducedMotion = reduced,
            // The reward is the deposit, not a bell: the thud lands when the ring parks.
            onDeposit = { haptics?.thud() },
            modifier = Modifier.padding(top = 22.dp).size(272.dp),
        )
        // Digits go BELOW the glyph, never over the ring band — a numeral across the strata fights
        // the marks it is supposed to be summarising.
        // A countdown shows what is left; a stopwatch shows what has been given. Reading a
        // stopwatch's "remaining" would show 0:00 for the whole session.
        val shown = if (state.isOpen) state.elapsedSecs else state.remainingSecs
        Text(
            "%d:%02d".format(shown / 60, shown % 60),
            style = MonoLarge,
            color = y.textPrimary,
            modifier = Modifier.padding(top = 26.dp),
        )
        Text(
            buildString {
                append(if (state.isRunning) "focus" else "paused")
                val done = dayCounts.sum()
                if (done > 0) {
                    append(" · session ")
                    append(done + 1)
                    append(" · day ")
                    append(dayCounts.size)
                }
            },
            fontFamily = YantraMono,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            color = y.textDim,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.weight(1f))
        // Pause / resume — outlined-tint circle.
        Box(
            Modifier
                .size(76.dp)
                .background(y.accentFill, CircleShape)
                .border(1.dp, y.accentBorder, CircleShape)
                .clickable { if (state.isRunning) onPause() else onResume() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isRunning) "Pause" else "Resume",
                tint = y.accent,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(16.dp))

        // Asked at the moment of stopping rather than hovering over the whole session. A permanent
        // caption is noise for the ninety-nine per cent of a session that is long enough, and it
        // nags while you are working; the question only matters when you are about to act on it.
        var confirming by remember { mutableStateOf<(() -> Unit)?>(null) }
        fun stopping(end: () -> Unit): () -> Unit = {
            if (FocusOutcome.wouldBeKept(state.elapsedSecs, state.plannedSecs)) end()
            else confirming = end
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            YantraButton("Finish", stopping(onComplete), tone = ButtonTone.Soft, icon = Icons.Default.Timer)
            YantraButton("Drop", stopping(onAbandon), tone = ButtonTone.Quiet)
        }

        confirming?.let { end ->
            ConfirmDialog(
                title = "Too short to record",
                body = "Nothing has been focused on for long enough to keep. Stopping now ends the " +
                    "session without adding it to your history.",
                confirmLabel = "End anyway",
                // Not "Cancel": backing out here continues the session rather than cancelling it,
                // and the wrong word on the safe option is how people press the wrong one.
                dismissLabel = "Keep going",
                onDismiss = { confirming = null },
                onConfirm = {
                    confirming = null
                    end()
                },
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun DoneContent(
    state: FocusTimer.State,
    onStartAnother: () -> Unit,
    onDone: () -> Unit,
) {
    val y = Yantra.colors
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(74.dp)
                .background(y.accent.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .background(y.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = y.onAccent, modifier = Modifier.size(26.dp))
            }
        }
        Text(
            "Session complete",
            fontFamily = YantraDisplay,
            fontSize = 24.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.4).sp,
            color = y.textPrimary,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            buildString {
                val task = state.nodeTitle.ifBlank { "your task" }
                if (state.isOpen) append("${(state.actualOrElapsed()) / 60} min on $task")
                else append("${state.plannedSecs / 60} min on $task")
            },
            fontSize = 13.5.sp,
            color = y.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .background(y.secondaryButton, RoundedCornerShape(12.dp))
                    .clickable(onClick = onStartAnother)
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Start another", fontSize = 14.sp, fontWeight = FontWeight.W600, color = y.textSecondary) }
            YantraButton(
                label = "Done",
                modifier = Modifier.weight(1f),
                tone = ButtonTone.Soft,
                onClick = onDone,
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

private val sessionTimeFmt = DateTimeFormatter.ofPattern("MMM d, HH:mm")

@Composable
private fun SessionRow(s: FocusSessionEntity) {
    val y = Yantra.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A filled mark for a promise kept; an open one for time given without one. Both are time.
        if (ie.napkin.supertasks.data.db.FocusOutcome.keptItsPromise(s.outcome, s.plannedSecs)) {
            Icon(Icons.Default.Timer, contentDescription = "Ran its course", tint = y.accent, modifier = Modifier.size(16.dp))
        } else {
            Text("◌", fontSize = 16.sp, color = y.textDim)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                Instant.ofEpochMilli(s.startedAt).atZone(ZoneId.systemDefault()).format(sessionTimeFmt),
                style = MaterialTheme.typography.bodyMedium,
                color = y.textPrimary,
            )
            Text(
                when {
                    s.endedAt == null -> "In progress"
                    else -> {
                        val how = when (s.outcome) {
                            ie.napkin.supertasks.data.db.FocusOutcome.RAN_OUT -> "Ran its course"
                            ie.napkin.supertasks.data.db.FocusOutcome.STOPPED ->
                                if (s.plannedSecs > 0) "Stopped early" else "Stopped"
                            ie.napkin.supertasks.data.db.FocusOutcome.LOST -> "Ended by itself"
                            else -> "Interrupted"
                        }
                        "$how · ${durationLabel(s.actualSecs ?: 0)}"
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = y.textMuted,
            )
        }
        Text("${s.plannedSecs / 60}m planned", fontSize = 11.sp, fontWeight = FontWeight.W600, color = y.textDim)
    }
}
