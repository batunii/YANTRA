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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.shoonya.yantra.data.format.EventRef
import ie.shoonya.yantra.data.format.EventTime
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraMono
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** Durations offered by name, because "an hour" is what people mean rather than 60. */
private val LENGTHS = listOf(
    "15 min" to Duration.ofMinutes(15),
    "30 min" to Duration.ofMinutes(30),
    "1 hour" to Duration.ofHours(1),
    "2 hours" to Duration.ofHours(2),
    "4 hours" to Duration.ofHours(4),
)

/** Minutes before the start. Null is none. */
private val REMINDERS: List<Pair<String, Int?>> = listOf(
    "None" to null,
    "At the time" to 0,
    "5 min before" to 5,
    "15 min before" to 15,
    "30 min before" to 30,
    "1 hour before" to 60,
    "1 day before" to 1440,
)

/**
 * Making or changing one event.
 *
 * Deliberately not a page: an event is four or five fields and a page of them would be a form. The
 * sheet writes an [EventRef] and hands it back — it knows nothing about where the event lives, which
 * is the caller's problem and differs between a calendar (the inbox) and a page (that page).
 *
 * Repeat is **not** offered here yet. A rule you can set but that only ever draws its first
 * occurrence would be a control that lies; it arrives with expansion — CALENDAR_PLAN.md §4 — and an
 * event that already carries an `rrule:` from a hand-edited file keeps it untouched through a save,
 * because the sheet copies the original rather than rebuilding it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventSheet(
    initial: EventRef?,
    day: LocalDate,
    /** Where the tap landed on a timeline, if that is how this was opened. */
    atTime: LocalTime? = null,
    onSave: (EventRef) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val y = Yantra.colors
    val start0 = initial?.time?.start ?: day.atTime(atTime ?: LocalTime.of(defaultHour(), 0))

    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var allDay by remember { mutableStateOf(initial?.time?.allDay ?: false) }
    var date by remember { mutableStateOf(start0.toLocalDate()) }
    var time by remember { mutableStateOf(start0.toLocalTime()) }
    var length by remember {
        mutableStateOf(
            initial?.time?.takeIf { !it.allDay }?.duration?.takeIf { !it.isZero } ?: Duration.ofHours(1)
        )
    }
    var location by remember { mutableStateOf(initial?.location.orEmpty()) }
    var reminder by remember { mutableStateOf(initial?.reminderMin) }

    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var lengthMenu by remember { mutableStateOf(false) }
    var reminderMenu by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = y.page) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.W700, color = y.textPrimary),
                cursorBrush = SolidColor(y.accent),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (title.isEmpty()) {
                            Text("New event", fontSize = 19.sp, fontWeight = FontWeight.W700, color = y.textMuted)
                        }
                        inner()
                    }
                },
            )

            SheetRow("All day") {
                Switch(checked = allDay, onCheckedChange = { allDay = it })
            }
            SheetRow("Date", onClick = { showDate = true }) {
                Text(date.toString(), fontFamily = YantraMono, fontSize = 13.sp, color = y.textMuted)
            }
            if (!allDay) {
                SheetRow("Starts", onClick = { showTime = true }) {
                    Text(time.toString(), fontFamily = YantraMono, fontSize = 13.sp, color = y.textMuted)
                }
                Box {
                    SheetRow("Length", onClick = { lengthMenu = true }) {
                        Text(lengthLabel(length), fontSize = 13.sp, color = y.textMuted)
                    }
                    DropdownMenu(expanded = lengthMenu, onDismissRequest = { lengthMenu = false }) {
                        LENGTHS.forEach { (label, d) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { length = d; lengthMenu = false })
                        }
                    }
                }
            }
            Box {
                SheetRow("Reminder", onClick = { reminderMenu = true }) {
                    Text(
                        REMINDERS.firstOrNull { it.second == reminder }?.first ?: "None",
                        fontSize = 13.sp,
                        color = y.textMuted,
                    )
                }
                DropdownMenu(expanded = reminderMenu, onDismissRequest = { reminderMenu = false }) {
                    REMINDERS.forEach { (label, mins) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { reminder = mins; reminderMenu = false })
                    }
                }
            }
            SheetRow("Where") {
                BasicTextField(
                    value = location,
                    onValueChange = { location = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = y.textPrimary),
                    cursorBrush = SolidColor(y.accent),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterEnd) {
                            if (location.isEmpty()) Text("None", fontSize = 13.sp, color = y.textMuted)
                            inner()
                        }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null) {
                    TextButton(onClick = { onDelete(); onDismiss() }) {
                        Text("Delete", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.width(0.dp))
                TextButton(onClick = onDismiss) { Text("Cancel", color = y.textMuted) }
                TextButton(
                    onClick = {
                        onSave(
                            build(
                                initial = initial,
                                title = title.trim(),
                                allDay = allDay,
                                date = date,
                                time = time,
                                length = length,
                                location = location.trim(),
                                reminder = reminder,
                            )
                        )
                        onDismiss()
                    },
                ) { Text("Save", color = y.accent, fontWeight = FontWeight.W700) }
            }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        PickerDialog(onDismiss = { showDate = false }, onConfirm = {
            state.selectedDateMillis?.let {
                // The picker works in UTC midnights; reading it back in the device's zone would
                // shift the day by one either side of the meridian.
                date = LocalDateTime.ofEpochSecond(it / 1000, 0, ZoneOffset.UTC).toLocalDate()
            }
            showDate = false
        }) { DatePicker(state = state, showModeToggle = false, title = null, headline = null) }
    }

    if (showTime) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        PickerDialog(onDismiss = { showTime = false }, onConfirm = {
            time = LocalTime.of(state.hour, state.minute)
            showTime = false
        }) { TimePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    val y = Yantra.colors
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(y.cardBg)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = y.textMuted) }
                TextButton(onClick = onConfirm) { Text("OK", color = y.accent) }
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, onClick: (() -> Unit)? = null, value: @Composable () -> Unit) {
    val y = Yantra.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = y.textPrimary, modifier = Modifier.weight(1f))
        value()
    }
}

/**
 * The event the sheet's fields describe, keeping everything the sheet does not show.
 *
 * Built from [initial] by copy rather than from scratch, so a rule, a series reference, labels and
 * attendees written by hand or by a newer build survive an edit made here. A sheet that rebuilt the
 * event would silently drop every field it does not have a control for.
 */
private fun build(
    initial: EventRef?,
    title: String,
    allDay: Boolean,
    date: LocalDate,
    time: LocalTime,
    length: Duration,
    location: String,
    reminder: Int?,
): EventRef {
    val start = if (allDay) date.atStartOfDay() else date.atTime(time)
    val end = if (allDay) date.plusDays(1).atStartOfDay() else start.plus(length)
    val when0 = EventTime(start = start, end = end, zone = initial?.time?.zone, allDay = allDay)
    val base = initial ?: EventRef(id = "", title = "", time = when0)
    return base.copy(
        title = title,
        time = when0,
        location = location.ifEmpty { null },
        reminderMin = reminder,
        raw = null,          // the line has to be re-rendered; it no longer says what it said
    )
}

private fun lengthLabel(d: Duration): String =
    LENGTHS.firstOrNull { it.second == d }?.first
        ?: if (d.toMinutes() % 60 == 0L) "${d.toHours()} hours" else "${d.toMinutes()} min"

/** The next whole hour, which is when a thing made now is most often for. */
private fun defaultHour(): Int = (LocalTime.now().hour + 1).coerceAtMost(23)
