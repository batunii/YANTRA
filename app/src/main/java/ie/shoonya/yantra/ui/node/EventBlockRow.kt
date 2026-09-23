package ie.shoonya.yantra.ui.node

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.shoonya.yantra.data.db.EventWithTitle
import ie.shoonya.yantra.data.label.LabelPalette
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraMono
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import ie.shoonya.yantra.ui.theme.YantraType
import ie.shoonya.yantra.ui.theme.YantraRadius
import ie.shoonya.yantra.ui.components.spine
import ie.shoonya.yantra.ui.components.SPINE_WIDTH

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/**
 * An event, on the page it is written on — CALENDAR_PLAN.md §18.
 *
 * It used to render through the ordinary text row, which shows a line's *title* and nothing else. An
 * event's title is the least of it: the time is the thing, and a **sitting has no title at all** by
 * design, so it came out as a blank editable row. A list holding two events looked empty, and the
 * one row you could see was one you could accidentally type into.
 *
 * So it is read-only here and says what it is. The line remains the record — this draws it, and the
 * chevron opens what the chevron has always opened.
 */
@Composable
internal fun EventBlockRow(
    row: EventWithTitle,
    onOpen: () -> Unit,
) {
    val y = Yantra.colors
    val e = row.event
    val sitting = e.forNodeId != null
    val start = runCatching { LocalDateTime.parse(e.startLocal) }.getOrNull()
    val end = runCatching { LocalDateTime.parse(e.endLocal) }.getOrNull()
    // The same two colours a block on the day wears, and for the same reasons — the fill is what
    // this thing is, the spine is whose repository it is in. A line on a page and a block on a day
    // are one object seen from two sides; they had better not disagree about colour.
    val tint = LabelPalette.byName(e.color)
        ?.let { Color(LabelPalette.display(it.light, y.isDark)) }
    val workspaceInk = LabelPalette.byName(ie.shoonya.yantra.ui.appContainer().workspaceColours()[e.workspaceId])
        ?.let { Color(LabelPalette.display(it.light, y.isDark)) }
    // Compared against this device's *calendars*, not against the GitHub login it pushes with.
    // The line carries a calendar address, so measuring it against a login would make every event
    // on the device read as somebody else's.
    val mine = ie.shoonya.yantra.ui.appContainer().myCalendarAccounts()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(YantraRadius.control))
            .background(tint?.copy(alpha = 0.16f) ?: y.cardBg)
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same spine the calendar draws and the player draws — see Modifier.spine. On a page
        // every row is from one repository, so this is usually one colour down the whole document:
        // that is the point. It is the same mark meaning the same thing, and the day this page is
        // read beside a list from another repo it is already saying which.
        Box(Modifier.width(SPINE_WIDTH).height(30.dp).spine(workspaceInk ?: tint ?: y.accent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // **An event says three things: when, what day, and whose calendar.** One beside the
            // title, two under it — the same shape on every event, so a row is read the same way
            // wherever it appears rather than rearranging itself per surface.
            //
            // The clock goes beside the title because it is the shortest and the most glanced at,
            // and because a task row already puts its date there — an event sitting among tasks
            // should not invent a second shape. The day and the calendar go underneath, where
            // there is room for a name.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // A sitting borrows its task's words, exactly as it does on the calendar.
                    // Failing that, it is still a piece of time and says so rather than nothing.
                    row.displayTitle?.takeIf { it.isNotBlank() }
                        ?: if (sitting) "Time set aside" else "Event",
                    fontSize = YantraType.body,
                    fontWeight = FontWeight.W600,
                    color = y.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    clockWords(start, end, e.allDay),
                    fontFamily = YantraMono,
                    fontSize = YantraType.dense,
                    color = y.textMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(2.dp))
            // The day, and the calendar it is on. Two things, one line.
            //
            // The calendar is named as the account names it — `shrey@napkin.ie` — and not dressed
            // up as a sentence. "from saieeshward's calendar" was three words of grammar around one
            // word of information, and it pushed the row to three lines and still ellipsised on the
            // part that mattered. An address is shorter than a sentence about an address, and it is
            // also the thing you would recognise.
            //
            // Said for **every** calendar, including your own. There are three states, not two —
            // yours, somebody else's, and one nobody claimed, which is an event typed by hand or
            // written before events carried a calendar. Leaving yours silent made it identical to
            // the unclaimed one.
            //
            // The accent is on the calendar alone, not on the whole line. Colouring the Text
            // coloured the day with it, which made the row look like the *date* was the unusual
            // thing — and a date is the one part of an event nobody needs alerting to. One string
            // so it still ellipsises as one, two spans so only the part that distinguishes this
            // row from its neighbour is lit.
            val day = dayWords(start, sitting)
            Text(
                buildAnnotatedString {
                    day?.let { withStyle(SpanStyle(color = y.textMuted)) { append(it) } }
                    e.author?.let { who ->
                        if (day != null) withStyle(SpanStyle(color = y.textMuted)) { append("  ·  ") }
                        withStyle(
                            SpanStyle(color = if (who in mine) y.textMuted else y.accent),
                        ) { append(who) }
                    }
                },
                fontFamily = YantraMono,
                fontSize = YantraType.dense,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(22.dp).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("›", fontSize = YantraType.row, color = y.textDim)
        }
    }
}

/** The clock alone — what sits beside a title. */
internal fun clockWords(start: LocalDateTime?, end: LocalDateTime?, allDay: Boolean): String {
    if (start == null) return "—"
    if (allDay) return "all day"
    val head = start.format(CLOCK)
    return when {
        end == null || end == start -> head
        end.toLocalDate() == start.toLocalDate() -> "$head–${end.format(CLOCK)}"
        // Across midnight the end clock alone would read as earlier than the start.
        else -> "$head →"
    }
}

/** The day alone — what sits under the title, beside whose calendar it is. */
internal fun dayWords(start: LocalDateTime?, sitting: Boolean): String? {
    if (start == null) return null
    return (if (sitting) "for " else "") + start.format(DAY)
}

/** When it is, in as few words as say it. */
internal fun whenWords(
    start: LocalDateTime?,
    end: LocalDateTime?,
    allDay: Boolean,
    sitting: Boolean,
): String {
    if (start == null) return "Sometime"
    val prefix = if (sitting) "for " else ""
    if (allDay) return prefix + start.format(DAY) + " · all day"
    val head = "${start.format(DAY)} · ${start.format(CLOCK)}"
    // A moment has no tail, and an end on the same day needs only its clock.
    return prefix + when {
        end == null || end == start -> head
        end.toLocalDate() == start.toLocalDate() -> "$head–${end.format(CLOCK)}"
        else -> "$head → ${end.format(DAY)} ${end.format(CLOCK)}"
    }
}


/**
 * When this page's event is, shown in the band — CALENDAR_PLAN.md §18.
 *
 * A document with a title and no date is a note that used to be a meeting. This sits where a task's
 * chips sit, and says the one thing about an event that its title cannot.
 */
@Composable
internal fun EventWhenChip(row: EventWithTitle) {
    val y = Yantra.colors
    val e = row.event
    val start = runCatching { LocalDateTime.parse(e.startLocal) }.getOrNull()
    val end = runCatching { LocalDateTime.parse(e.endLocal) }.getOrNull()
    Row(
        Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(YantraRadius.block))
            .background(y.accentFill)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            whenWords(start, end, e.allDay, e.forNodeId != null),
            fontFamily = YantraMono,
            fontSize = YantraType.caption,
            fontWeight = FontWeight.W700,
            color = y.accentText,
        )
    }
}
