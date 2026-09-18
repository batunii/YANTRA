package ie.shoonya.yantra.ui.node

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.shoonya.yantra.data.db.EventWithTitle
import ie.shoonya.yantra.data.device.DeviceCalendarSource
import ie.shoonya.yantra.data.device.DeviceEventDetails
import ie.shoonya.yantra.data.device.MeetingText
import ie.shoonya.yantra.data.label.LabelPalette
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraMono
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import ie.shoonya.yantra.ui.theme.YantraType
import ie.shoonya.yantra.ui.theme.YantraRadius

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/**
 * An event's own header — CALENDAR_PLAN.md §23.
 *
 * **Driven by the file, enriched by the calendar.** The when, the place and the colour come from the
 * event's own row, so this is never empty: an event you made yourself has a header, and so does one
 * about somebody else's meeting when the permission is off or the meeting has gone. The guests, the
 * organiser, the description and the way back to the owning app come from the provider when it can
 * be read, and are simply absent when it cannot.
 *
 * That ordering is the fix for a page that came up blank. It used to be the other way round — the
 * whole header was the provider's answer — so a single failed lookup left a title and nothing else,
 * with no way to tell an empty page from a broken one.
 *
 * Columnar, and deliberately: a label column and a value column read as a record of a thing, where
 * the same facts run together as sentences. It is the header of the page rather than content in it,
 * because none of it is yours to edit.
 */
@Composable
internal fun MeetingHeader(
    row: EventWithTitle,
    details: DeviceEventDetails?,
    /**
     * Opens this event for editing — CALENDAR_PLAN.md §28. Null where it is not yours to edit.
     *
     * The asymmetry is the point of the distinction. Somebody else's meeting is read-only and has
     * to look it: this app holds no permission to write their calendar, so a control offering to
     * change the time would be a promise it cannot keep. Your own event is a node like any other,
     * and until now had no way to be changed from the one screen you open it on.
     */
    onEdit: (() -> Unit)? = null,
) {
    val y = Yantra.colors
    val context = LocalContext.current
    val e = row.event
    val start = runCatching { LocalDateTime.parse(e.startLocal) }.getOrNull()
    val end = runCatching { LocalDateTime.parse(e.endLocal) }.getOrNull()
    // Compact by default, and bigger when you ask — CALENDAR_PLAN.md §27. The when, the place and
    // the way in are what a header is for; the guest list and the invitation's small print are what
    // you look up once. Folded, the page under it is still a page rather than a wall of somebody
    // else's text.
    var expanded by remember(e.nodeId) { mutableStateOf(false) }
    val tint = e.color
        ?.let { name -> LabelPalette.swatches.firstOrNull { it.name.equals(name, true) }?.light }
        ?.let { Color(LabelPalette.display(it, y.isDark)) }
        ?: y.accent

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(y.cardBg, RoundedCornerShape(YantraRadius.card))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
            Spacer(Modifier.width(8.dp))
            Text(
                // Whose it is, said first. A header that looked like yours would invite an edit
                // that cannot happen — this app holds no permission to write their calendar.
                if (row.nodeExtUid != null) {
                    details?.calendarName?.uppercase()?.let { "FROM $it" } ?: "FROM YOUR CALENDAR"
                } else {
                    "EVENT"
                },
                fontFamily = YantraMono,
                fontSize = YantraType.section,
                fontWeight = FontWeight.W700,
                letterSpacing = 1.2.sp,
                color = y.textMuted,
            )
        }

        Spacer(Modifier.height(10.dp))
        // The when, given the room it deserves: it is the one fact an event has that a note has not.
        Text(
            start?.format(DAY) ?: "Sometime",
            fontSize = YantraType.meta,
            fontWeight = FontWeight.W600,
            color = y.textSecondary,
        )
        Spacer(Modifier.height(1.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                when {
                    start == null -> "—"
                    e.allDay -> "All day"
                    end == null || end == start -> start.format(CLOCK)
                    else -> "${start.format(CLOCK)}–${end.format(CLOCK)}"
                },
                fontFamily = YantraMono,
                fontSize = YantraType.title,
                fontWeight = FontWeight.W700,
                color = y.textPrimary,
            )
            if (start != null && end != null && !e.allDay && end != start) {
                Spacer(Modifier.width(10.dp))
                Text(
                    lengthWords(Duration.between(start, end)),
                    fontSize = YantraType.caption,
                    color = y.textDim,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }

        // The join link, first and biggest — CALENDAR_PLAN.md §24. On a meeting that has one it is
        // the thing you came for, and every calendar worth using puts it where your thumb already
        // is rather than three lines into a description.
        val conference = MeetingText.conferenceIn(details?.location ?: e.location, details?.description)
        conference?.let { call ->
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(YantraRadius.control))
                    .background(y.accentFill)
                    .clickable { open(context, call.url) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Join ${call.name}",
                    fontSize = YantraType.label,
                    fontWeight = FontWeight.W700,
                    color = y.accentText,
                    modifier = Modifier.weight(1f),
                )
                Text("\u203a", fontSize = YantraType.card, color = y.accentText)
            }
        }

        val where = (details?.location ?: e.location)
            // A location that is only the video link says nothing the button above has not.
            ?.takeIf { it.trim() != conference?.url }
        val guests = details?.guests.orEmpty()
        // Where it is stays folded-in: it is half of what a header is for.
        where?.let {
            Spacer(Modifier.height(12.dp))
            Field("Where", it)
        }
        if (expanded) {
            details?.organiser?.let { Field("Organiser", it) }
            if (guests.isNotEmpty()) {
                // Capped even when expanded: a company-wide invite runs to three hundred, and the
                // page is for writing.
                Field(
                    "Guests",
                    guests.take(12).joinToString(", ") +
                        if (guests.size > 12) "  +${guests.size - 12} more" else "",
                )
            }
        }

        // Unwrapped, because Google's web client writes HTML and a description drawn raw is a wall
        // of `<br>`s. See MeetingText.readable.
        val description = MeetingText.readable(details?.description)
        if (expanded && description.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                description,
                fontSize = YantraType.meta,
                lineHeight = 18.sp,
                color = y.textSecondary,
            )
            // Every other link in it, as things you can press. An invitation routinely carries the
            // agenda, the deck and a dial-in page, and a URL you have to select and copy is a URL
            // nobody follows from a phone.
            val others = MeetingText.linksIn(description).filter { it != conference?.url }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                others.take(4).forEach { url ->
                    Text(
                        shortUrl(url),
                        fontSize = YantraType.section,
                        color = y.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(YantraRadius.block))
                            .clickable { open(context, url) }
                            .padding(vertical = 3.dp),
                    )
                }
            }
        }

        // The header's own controls: how much of itself to show, and — on your own event — the way
        // to change it. "More details" is hidden entirely when there is nothing more behind it, so
        // it is never a button that does nothing.
        val more = details != null &&
            (details.organiser != null || guests.isNotEmpty() || description.isNotBlank())
        if (more || onEdit != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (more) {
                    Text(
                        if (expanded) "Less  ‹" else "More details  ›",
                        fontSize = YantraType.section,
                        fontWeight = FontWeight.W700,
                        color = y.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(YantraRadius.block))
                            .clickable { expanded = !expanded }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                }
                onEdit?.let { edit ->
                    if (more) Spacer(Modifier.width(14.dp))
                    Text(
                        "Edit",
                        fontSize = YantraType.section,
                        fontWeight = FontWeight.W700,
                        color = y.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(YantraRadius.block))
                            .clickable(onClick = edit)
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                }
            }
        }

        if (expanded) details?.let { d ->
            Spacer(Modifier.height(8.dp))
            // One row on the page you were going to anyway, rather than a choice made instead of
            // opening it. A tap on a meeting should not be able to throw you into another app.
            Text(
                "Open in the calendar app  ›",
                fontSize = YantraType.section,
                fontWeight = FontWeight.W700,
                color = y.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(YantraRadius.block))
                    .clickable {
                        runCatching {
                            context.startActivity(
                                DeviceCalendarSource(context).viewIntent(d.eventId, d.beginUtc, d.endUtc),
                            )
                        }
                    }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * The same event, reduced to what survives a fold — CALENDAR_PLAN.md §28.
 *
 * The band folds when the keyboard comes up, because a header is space the page does not get and
 * with the keyboard up there is very little to go round. For a task folding to nothing is right.
 * For a meeting it is not: the details are what you are writing *against*, and taking them away the
 * moment you start writing is the whole complaint this answers.
 *
 * So an event folds to this instead of to nothing — when it is, and the way in. Both fit on one
 * line, and neither is something you should have to unfold the page to see.
 */
@Composable
internal fun MeetingStrip(row: EventWithTitle, details: DeviceEventDetails?) {
    val y = Yantra.colors
    val context = LocalContext.current
    val e = row.event
    val start = runCatching { LocalDateTime.parse(e.startLocal) }.getOrNull()
    val end = runCatching { LocalDateTime.parse(e.endLocal) }.getOrNull()
    val conference = MeetingText.conferenceIn(details?.location ?: e.location, details?.description)

    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                start == null -> "—"
                e.allDay -> "All day"
                end == null || end == start -> start.format(CLOCK)
                else -> "${start.format(CLOCK)}–${end.format(CLOCK)}"
            },
            fontFamily = YantraMono,
            fontSize = YantraType.meta,
            fontWeight = FontWeight.W700,
            color = y.textSecondary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            start?.format(DAY) ?: "",
            fontSize = YantraType.caption,
            color = y.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        conference?.let { call ->
            Spacer(Modifier.width(8.dp))
            Text(
                "Join \u203a",
                fontSize = YantraType.caption,
                fontWeight = FontWeight.W700,
                color = y.accentText,
                modifier = Modifier
                    .clip(RoundedCornerShape(YantraRadius.block))
                    .background(y.accentFill)
                    .clickable { open(context, call.url) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    val y = Yantra.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            label.uppercase(),
            fontFamily = YantraMono,
            fontSize = YantraType.dense,
            letterSpacing = 0.8.sp,
            color = y.textDim,
            modifier = Modifier.width(74.dp).padding(top = 2.dp),
        )
        Text(value, fontSize = YantraType.meta, lineHeight = 17.sp, color = y.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** Hands a link to whatever opens it. A calendar page cannot assume a browser is there. */
private fun open(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** A link as something readable: the host, and enough of the path to tell two apart. */
private fun shortUrl(url: String): String {
    val host = url.substringAfter("://", url).substringBefore('/')
    val path = url.substringAfter("://", url).substringAfter('/', "")
    return if (path.isEmpty()) host else "$host/${path.take(24)}${if (path.length > 24) "…" else ""}"
}

/** "1 hour", "45 min" — how long, in the words somebody would use. */
private fun lengthWords(d: Duration): String {
    val minutes = d.toMinutes()
    return when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> if (minutes == 60L) "1 hour" else "${minutes / 60} hours"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}
