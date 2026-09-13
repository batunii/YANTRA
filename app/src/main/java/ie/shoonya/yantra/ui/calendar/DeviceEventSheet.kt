package ie.shoonya.yantra.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraMono
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Somebody else's event, and the two things you can do about it — CALENDAR_PLAN.md §5, §18.
 *
 * **A tap used to leave the app.** It handed the occurrence straight to the calendar that owns it,
 * which is one of the two right answers and a poor way to offer it: a single tap that throws you
 * into another application is not a choice, and there was no way at all to act on the thing from
 * here. You would tap your Tuesday meeting meaning to write a note about it and find yourself in
 * Google Calendar.
 *
 * So it opens here, read-only, and says plainly that it is not yours. Nothing on this sheet can
 * change their event, and that is not a restriction this code imposes — the app holds no permission
 * to write one, so there is nothing to offer.
 *
 * What it *can* offer is a page of your own about the meeting — CALENDAR_PLAN.md §19. A note, not a
 * copy: the day keeps one block for one meeting, drawn at their hours, and your notes follow it when
 * they move it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceEventSheet(
    item: DayItem.Device,
    onOpenInCalendar: () -> Unit,
    onNotes: () -> Unit,
    onDismiss: () -> Unit,
) {
    val y = Yantra.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = y.page,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Its own calendar's colour, unmapped, so it is recognisably the same entry you
                // would see in the app it came from.
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(item.color?.let { Color(it) } ?: y.textDim),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "FROM YOUR PHONE'S CALENDAR",
                    fontFamily = YantraMono,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 1.2.sp,
                    color = y.textMuted,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.title,
                fontSize = 19.sp,
                fontWeight = FontWeight.W700,
                color = y.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(item.start.format(DAY))
                    if (item.allDay) append(" · all day")
                    else {
                        append(" · ").append(item.start.format(CLOCK))
                        if (item.end != item.start) append("–").append(item.end.format(CLOCK))
                    }
                },
                fontFamily = YantraMono,
                fontSize = 12.sp,
                color = y.textMuted,
            )
            item.location?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, fontSize = 12.sp, color = y.textMuted)
            }

            Spacer(Modifier.height(16.dp))
            // Notes *about* their meeting, never a copy of it. There is one meeting and one block
            // for it; what this adds is a page of yours hanging off it.
            if (item.uid != null) {
                SheetAction(
                    title = if (item.noteId != null) "Open my notes" else "Take notes on this",
                    subtitle = if (item.noteId != null) {
                        "The page you have been writing on this meeting."
                    } else {
                        "A page of your own about this meeting. It stays attached to theirs — if " +
                            "they move it, your notes move with it."
                    },
                    onClick = onNotes,
                )
                Spacer(Modifier.height(8.dp))
            }
            SheetAction(
                title = "Open in the calendar app",
                subtitle = "Where it lives, and the only place it can be changed.",
                onClick = onOpenInCalendar,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SheetAction(title: String, subtitle: String, onClick: () -> Unit) {
    val y = Yantra.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(y.cardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.W600, color = y.textPrimary)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, fontSize = 11.5.sp, color = y.textMuted, lineHeight = 16.sp)
    }
}
