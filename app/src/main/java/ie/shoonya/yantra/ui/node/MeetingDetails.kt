package ie.shoonya.yantra.ui.node

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.shoonya.yantra.data.device.DeviceEventDetails
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraMono

/**
 * What the owning calendar knows about this meeting — CALENDAR_PLAN.md §22.
 *
 * **Read, not copied.** None of this is in the file: the place, the guests and the description live
 * in the calendar that owns them, and are drawn here on the way past. So there is one meeting, one
 * task, and one page that answers both "what is this" and "what did I write about it" — and nothing
 * that can drift out of agreement with the calendar, because nothing was duplicated.
 *
 * Gone entirely without the permission, which is the same rule the overlay has always followed: an
 * addition to your own work, never a precondition for it.
 */
@Composable
internal fun MeetingDetails(details: DeviceEventDetails) {
    val y = Yantra.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(y.cardBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            // Named as theirs, because nothing here can be edited and a panel that looked like the
            // page would invite the attempt.
            listOfNotNull("FROM", details.calendarName?.uppercase()).joinToString(" ")
                .ifBlank { "FROM YOUR CALENDAR" },
            fontFamily = YantraMono,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = 1.2.sp,
            color = y.textMuted,
        )
        details.location?.let {
            Spacer(Modifier.height(8.dp))
            Field("Where", it)
        }
        details.organiser?.let {
            Spacer(Modifier.height(6.dp))
            Field("Organiser", it)
        }
        if (details.guests.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            // Capped, because a company-wide invite has three hundred and the page is for writing.
            Field(
                "Guests",
                details.guests.take(6).joinToString(", ") +
                    if (details.guests.size > 6) " +${details.guests.size - 6} more" else "",
            )
        }
        details.description?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it.trim(),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = y.textSecondary,
                maxLines = 12,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        // The way back to the app that owns it. It used to be a choice you made *instead* of
        // opening the page, which meant a tap could throw you into another application; here it is
        // one row on the page you were going to anyway.
        Text(
            "Open in the calendar app  \u203a",
            fontSize = 12.sp,
            fontWeight = FontWeight.W700,
            color = y.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    runCatching {
                        context.startActivity(
                            ie.shoonya.yantra.data.device.DeviceCalendarSource(context)
                                .viewIntent(details.eventId, details.beginUtc, details.endUtc),
                        )
                    }
                }
                .padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    val y = Yantra.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Text(
            label,
            fontFamily = YantraMono,
            fontSize = 10.sp,
            color = y.textDim,
            modifier = Modifier.width(68.dp),
        )
        Text(value, fontSize = 12.5.sp, color = y.textPrimary, modifier = Modifier.weight(1f))
    }
}
