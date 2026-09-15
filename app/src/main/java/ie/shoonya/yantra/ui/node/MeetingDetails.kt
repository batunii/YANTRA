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
import ie.shoonya.yantra.data.label.LabelPalette
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraMono
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
internal fun MeetingHeader(row: EventWithTitle, details: DeviceEventDetails?) {
    val y = Yantra.colors
    val context = LocalContext.current
    val e = row.event
    val start = runCatching { LocalDateTime.parse(e.startLocal) }.getOrNull()
    val end = runCatching { LocalDateTime.parse(e.endLocal) }.getOrNull()
    val tint = e.color
        ?.let { name -> LabelPalette.swatches.firstOrNull { it.name.equals(name, true) }?.light }
        ?.let { Color(LabelPalette.display(it, y.isDark)) }
        ?: y.accent

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(y.cardBg, RoundedCornerShape(14.dp))
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
                fontSize = 8.5.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 1.2.sp,
                color = y.textMuted,
            )
        }

        Spacer(Modifier.height(10.dp))
        // The when, given the room it deserves: it is the one fact an event has that a note has not.
        Text(
            start?.format(DAY) ?: "Sometime",
            fontSize = 13.sp,
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
                fontSize = 21.sp,
                fontWeight = FontWeight.W700,
                color = y.textPrimary,
            )
            if (start != null && end != null && !e.allDay && end != start) {
                Spacer(Modifier.width(10.dp))
                Text(
                    lengthWords(Duration.between(start, end)),
                    fontSize = 11.5.sp,
                    color = y.textDim,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }

        val where = details?.location ?: e.location
        val guests = details?.guests.orEmpty()
        if (where != null || guests.isNotEmpty() || details?.organiser != null) {
            Spacer(Modifier.height(12.dp))
            where?.let { Field("Where", it) }
            details?.organiser?.let { Field("Organiser", it) }
            if (guests.isNotEmpty()) {
                // Capped: a company-wide invite runs to three hundred, and the page is for writing.
                Field(
                    "Guests",
                    guests.take(5).joinToString(", ") +
                        if (guests.size > 5) "  +${guests.size - 5} more" else "",
                )
            }
        }

        details?.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                it.trim(),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = y.textSecondary,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }

        details?.let { d ->
            Spacer(Modifier.height(10.dp))
            // One row on the page you were going to anyway, rather than a choice made instead of
            // opening it. A tap on a meeting should not be able to throw you into another app.
            Text(
                "Open in the calendar app  ›",
                fontSize = 12.sp,
                fontWeight = FontWeight.W700,
                color = y.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
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
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
            color = y.textDim,
            modifier = Modifier.width(74.dp).padding(top = 2.dp),
        )
        Text(value, fontSize = 12.5.sp, lineHeight = 17.sp, color = y.textPrimary, modifier = Modifier.weight(1f))
    }
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
