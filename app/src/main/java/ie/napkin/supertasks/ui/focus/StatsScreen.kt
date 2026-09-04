package ie.napkin.supertasks.ui.focus

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import ie.napkin.supertasks.data.db.counts
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.durationLabel
import ie.napkin.supertasks.ui.container
import ie.napkin.supertasks.ui.theme.Yantra
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import ie.napkin.supertasks.ui.theme.YantraDisplay
import ie.napkin.supertasks.data.format.Links

data class DayStat(val date: LocalDate, val completed: Int, val totalSecs: Int)
data class TaskStat(val nodeId: String, val title: String, val completed: Int, val totalSecs: Int)
data class Stats(
    val todaySecs: Int = 0,
    val todayCount: Int = 0,
    val weekSecs: Int = 0,
    val weekCount: Int = 0,
    val days: List<DayStat> = emptyList(),
    val topTasks: List<TaskStat> = emptyList(),
)

class StatsViewModel(private val container: AppContainer) : ViewModel() {

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

        val topTasks = counted
            .groupBy { it.nodeId }
            .map { (nodeId, list) ->
                val title = Links.plain(container.nodes.byId(nodeId)?.title.orEmpty()).ifBlank { "Untitled task" }
                TaskStat(nodeId, title, list.size, list.sumOf { it.actualSecs ?: 0 })
            }
            .sortedByDescending { it.totalSecs }
            .take(8)

        val weekCounted = counted.filter { dayOf(it) >= weekStart }
        return Stats(
            todaySecs = todaySessions.sumOf { it.actualSecs ?: 0 },
            todayCount = todaySessions.size,
            weekSecs = weekCounted.sumOf { it.actualSecs ?: 0 },
            weekCount = weekCounted.size,
            days = days,
            topTasks = topTasks,
        )
    }
}

@Composable
fun StatsScreen(nav: NavHostController) {
    val vm: StatsViewModel = viewModel { StatsViewModel(container()) }
    val stats by vm.stats.collectAsStateWithLifecycle()
    val y = Yantra.colors

    Column(
        Modifier
            .fillMaxSize()
            .background(y.page)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavCircle(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Back",
                onClick = { nav.popBackStack() },
                iconSize = 22.dp,
            )
            Text("Focus stats", fontFamily = YantraDisplay, fontSize = 24.sp, fontWeight = FontWeight.W700, letterSpacing = (-0.4).sp, color = y.textPrimary)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "totals") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                    StatCard("Today", stats.todayCount, durationLabel(stats.todaySecs) + " focused", accent = true, modifier = Modifier.weight(1f))
                    StatCard("This week", stats.weekCount, durationLabel(stats.weekSecs), accent = false, modifier = Modifier.weight(1f))
                }
            }
            item(key = "days-label") {
                SectionLabel("Last 7 days", modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
            }
            item(key = "days") { DayBars(stats.days) }
            if (stats.topTasks.isNotEmpty()) {
                item(key = "top-header") {
                    SectionLabel("Most focused", modifier = Modifier.padding(top = 26.dp, bottom = 6.dp))
                }
                itemsIndexed(stats.topTasks) { index, t ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.W700,
                            fontSize = 13.sp,
                            color = if (index == 0) y.accentEyebrow else y.textDim,
                            modifier = Modifier.width(18.dp),
                        )
                        Text(
                            t.title,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.W600,
                            color = y.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${t.completed}", fontSize = 12.5.sp, color = y.textMuted)
                            Icon(Icons.Default.Timer, contentDescription = null, tint = y.textMuted, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StatCard(title: String, count: Int, sub: String, accent: Boolean, modifier: Modifier = Modifier) {
    val y = Yantra.colors
    Column(
        modifier
            .background(y.cardBg, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        SectionLabel(title, color = y.textMuted)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 10.dp)) {
            Text(
                "$count",
                fontFamily = YantraDisplay,
                fontSize = 38.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = (-0.5).sp,
                color = if (accent) y.accentText else y.textPrimary,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.Timer,
                contentDescription = null,
                tint = if (accent) y.accentText else y.textPrimary,
                modifier = Modifier.size(15.dp).padding(bottom = 4.dp),
            )
        }
        Text(sub, fontSize = 12.5.sp, color = y.textMuted, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun DayBars(days: List<DayStat>) {
    val y = Yantra.colors
    val barBg = y.tileWarm
    val max = (days.maxOfOrNull { it.totalSecs } ?: 0).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(140.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEachIndexed { i, day ->
            val isToday = i == days.lastIndex
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                val frac = day.totalSecs.toFloat() / max
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((104 * frac).dp.coerceAtLeast(if (day.totalSecs > 0) 8.dp else 6.dp))
                        .background(if (isToday && day.totalSecs > 0) y.accent else barBg, RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    day.date.dayOfWeek.name.first().toString(),
                    fontSize = 10.5.sp,
                    fontWeight = if (isToday) FontWeight.W700 else FontWeight.W400,
                    color = if (isToday) y.accentEyebrow else y.textDim,
                )
            }
        }
    }
}
