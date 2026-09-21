package ie.shoonya.yantra.ui.components

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.height
import androidx.lifecycle.compose.LifecycleResumeEffect
import ie.shoonya.yantra.data.format.DueSpec
import ie.shoonya.yantra.reminders.ReminderReach
import ie.shoonya.yantra.ui.theme.YantraType
import ie.shoonya.yantra.ui.theme.Yantra
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import ie.shoonya.yantra.ui.components.YantraIcon
import ie.shoonya.yantra.ui.components.YantraMark

/**
 * What a whole set of reminders is called, on the row that opens the picker.
 *
 * Two are spelled out, because two is the case this exists for — half an hour before to get there,
 * a day before to have something ready — and reading them back is how you check you set the ones
 * you meant. Beyond that it is a count: three offsets do not fit on a row beside their own label,
 * and the menu below is one tap away and shows every one of them ticked.
 */
private fun remindersLabel(offsets: List<Int>, timed: Boolean): String = when {
    offsets.isEmpty() -> "None"
    offsets.size <= 2 -> offsets.joinToString(", ") { reminderLabel(it, timed) }
    else -> "${offsets.size} reminders"
}

/** Reminder offsets in minutes before the due instant (see BuiltIns docs). */
private const val REMIND_ON_TIME = 0
private const val REMIND_ON_THE_DAY = -540 // 09:00 on the day, for all-day tasks

private fun reminderLabel(min: Int?, timed: Boolean): String = when (min) {
    null -> "None"
    REMIND_ON_TIME -> if (timed) "On time" else "None"
    30 -> "30 min before"
    60 -> "1 hour before"
    1440 -> "1 day before"
    REMIND_ON_THE_DAY -> "On the day (9:00)"
    else -> "${min} min before"
}

/**
 * TickTick-style Due sheet: one surface for date, optional time, and the reminder that hangs
 * off them. Emits the exact instant (timed) or the local-midnight instant (all-day) plus the
 * reminder offset — the repo's setDue writes all three columns together.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueSheet(
    initialDateMillis: Long?,
    initialHasTime: Boolean,
    initialReminders: List<Int>,
    onDismiss: () -> Unit,
    onSet: (dateMillis: Long, hasTime: Boolean, reminders: List<Int>) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val y = Yantra.colors
    val initialZoned = initialDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
    // The M3 date picker traffics in UTC-midnight values; convert deliberately both ways.
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = (initialZoned?.toLocalDate() ?: LocalDate.now())
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    var time by remember { mutableStateOf(if (initialHasTime) initialZoned?.toLocalTime() else null) }
    var reminders by remember { mutableStateOf(DueSpec.reminders(initialReminders)) }
    var showTimePicker by remember { mutableStateOf(false) }
    var reminderMenu by remember { mutableStateOf(false) }
    val requestPermissions = rememberReminderPermissionRequest()
    // Re-asked every time this sheet comes forward, because all three answers change outside the
    // app — in the permission dialog, or in Settings — and a remembered one is wrong exactly when
    // somebody has just gone and fixed it.
    val context = LocalContext.current
    var reach by remember { mutableStateOf(ReminderReach.Fine) }
    LifecycleResumeEffect(Unit) {
        reach = ReminderReach.of(context)
        onPauseOrDispose { }
    }
    val timeFmt = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

    fun utcMidnight(d: LocalDate): Long = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // The sheet's own inset, applied per child rather than to the column.
        //
        // The calendar is the reason. M3's DatePicker lays its month grid out at a fixed size —
        // seven accessibility-sized cells inside its own padding — and does not shrink to fit; give
        // it less room than that and the seventh column is simply cut off at the clip edge, with the
        // selection indicator still drawn for the width the cell was *supposed* to have (which is
        // why the selected Saturday read as a tall thin sliver rather than a circle). So the
        // calendar gets the full width of the sheet and scales down as one piece if even that is
        // not enough; everything else is inset the normal amount.
        val pad = Modifier.padding(horizontal = 20.dp)
        // **Not scrollable, and that is the fix rather than an omission.**
        //
        // This column had `verticalScroll` around it with M3's DatePicker inside. A vertical
        // scrollable nested in a vertical scroll is measured with unbounded height, so the picker
        // laid itself out at its full intrinsic size and the column became far taller than the
        // sheet — which gave the outer scroll a range of hundreds of points where the content
        // overflows by almost none. Dragging up then slid the whole sheet, drag handle and title
        // included, off the top of the screen and left it there: the "gets stuck" in the recording.
        //
        // The calendar already manages its own height and [FitsWidth] already scales it down when
        // the screen is narrow, so the sheet has nothing left to scroll.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Due", style = MaterialTheme.typography.titleLarge, color = y.textPrimary, modifier = pad)

            Row(pad, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Today" to LocalDate.now(),
                    "Tomorrow" to LocalDate.now().plusDays(1),
                    "Next week" to LocalDate.now().plusWeeks(1),
                ).forEach { (label, target) ->
                    FilterChip(
                        selected = dateState.selectedDateMillis == utcMidnight(target),
                        onClick = { dateState.selectedDateMillis = utcMidnight(target) },
                        label = { Text(label) },
                    )
                }
            }

            FitsWidth(needs = CALENDAR_WIDTH) {
                DatePicker(state = dateState, showModeToggle = false, title = null, headline = null)
            }

            // Time row
            Row(
                pad
                    .fillMaxWidth()
                    .clickable { showTimePicker = true }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                YantraIcon(YantraMark.Clock, tint = y.textSecondary, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Time", color = y.textPrimary, modifier = Modifier.weight(1f))
                Text(time?.format(timeFmt) ?: "None", color = y.textMuted)
                if (time != null) {
                    IconButton(onClick = {
                        time = null
                        // A timed offset makes no sense on an all-day task; back to None.
                        // An offset measured from a time cannot survive the time being taken
                        // away; "on the day" is the only one that means anything without it.
                        reminders = reminders.filter { it == REMIND_ON_THE_DAY }
                    }) {
                        YantraIcon(YantraMark.Close, tint = y.textMuted, contentDescription = "Clear time")
                    }
                }
            }

            // Reminder row
            Box(pad) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { reminderMenu = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    YantraIcon(YantraMark.Alarm, tint = y.textSecondary, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Reminder", color = y.textPrimary, modifier = Modifier.weight(1f))
                    Text(remindersLabel(reminders, timed = time != null), color = y.textMuted)
                }
                DropdownMenu(expanded = reminderMenu, onDismissRequest = { reminderMenu = false }) {
                    // Each offset is a toggle and the menu stays open, because picking two is the
                    // whole point: a menu that closed on the first tap would make the second
                    // reminder cost another trip through the row to get back here, and nothing on
                    // screen would have said that a second one was allowed.
                    val options: List<Int> =
                        if (time != null) listOf(REMIND_ON_TIME, 30, 60, 1440)
                        else listOf(REMIND_ON_THE_DAY)
                    options.forEach { option ->
                        val on = option in reminders
                        DropdownMenuItem(
                            text = { Text(reminderLabel(option, timed = time != null)) },
                            trailingIcon = {
                                // Only the chosen ones carry a mark. An empty slot beside every
                                // other line is what says these are not one-of-five.
                                if (on) YantraIcon(YantraMark.Check, tint = y.accent, contentDescription = null)
                            },
                            onClick = {
                                reminders =
                                    if (on) reminders - option
                                    else DueSpec.reminders(reminders + option)
                            },
                        )
                    }
                    // Clearing is a different kind of act from unticking four things, and it is the
                    // one that closes the menu: there is nothing left to look at.
                    DropdownMenuItem(
                        text = { Text("None") },
                        onClick = { reminders = emptyList(); reminderMenu = false },
                    )
                }
            }

            // Said here, where the reminder is being chosen, and not after it fails to arrive.
            //
            // Everything about a reminder worked except arriving: the alarm was armed, the phone
            // woke on time, the task was checked, and then nothing was shown because notifications
            // were off. The sheet went on offering "30 minutes before" as though it meant
            // something. A promise the app cannot keep is worth interrupting for, and this is the
            // only moment anybody is thinking about it.
            //
            // Only when a reminder is actually chosen. Nobody setting a plain due date needs to be
            // told about notifications they are not asking for.
            if (reminders.isNotEmpty() && !reach.willArrive) {
                Box(pad) {
                    // The whole thing is one tappable line, and it has to be: see the note on
                    // ReminderReach.message for what a second line costs in a sheet that cannot
                    // scroll. The remedy is in the sentence rather than under it.
                    Text(
                        reach.message,
                        color = y.warning,
                        fontSize = YantraType.caption,
                        modifier = Modifier
                            .clickable {
                                if (reach == ReminderReach.NotPermitted) requestPermissions()
                                else context.startActivity(notificationSettings(context))
                            }
                            .padding(bottom = 6.dp),
                    )
                }
            }

            Row(
                pad.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                if (onClear != null) {
                    TextButton(onClick = { onClear(); onDismiss() }) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(
                    enabled = dateState.selectedDateMillis != null,
                    onClick = {
                        val localDate = Instant.ofEpochMilli(dateState.selectedDateMillis ?: return@Button)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        // All-day → local midnight; timed → exact instant. DST-gap times
                        // resolve to the shifted valid instant (documented behavior).
                        val instant = (time?.let { localDate.atTime(it) } ?: localDate.atStartOfDay())
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        onSet(instant, time != null, reminders)
                        if (reminders.isNotEmpty()) requestPermissions()
                        onDismiss()
                    },
                ) { Text("Set") }
            }
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = time?.hour ?: 9,
            initialMinute = time?.minute ?: 0,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(timeState.hour, timeState.minute)
                    // Fresh time defaults to "On time" unless something is already chosen. The
                    // all-day offset goes with the all-day-ness it belonged to.
                    val kept = reminders - REMIND_ON_THE_DAY
                    reminders = if (kept.isEmpty()) listOf(REMIND_ON_TIME) else kept
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The width M3's date picker needs before it starts cutting columns off: seven cells at the
 * accessibility-recommended size, inside the picker's own horizontal padding.
 */
private val CALENDAR_WIDTH = 48.dp * 7 + 12.dp * 2

/**
 * Lays [content] out at [needs] and scales the whole thing down if the parent is narrower.
 *
 * Scaling rather than squeezing, because the thing this exists for — a calendar grid — divides a
 * fixed width into seven equal columns and clips whatever will not fit rather than dividing what it
 * is actually given. Shrinking it as one piece keeps every column, keeps each selection indicator
 * concentric with its own number, and keeps the touch targets where they are drawn (a graphics
 * layer transform applies to hit testing too). At or above [needs] this is a plain box.
 */
@Composable
private fun FitsWidth(needs: Dp, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = (maxWidth / needs).coerceAtMost(1f)
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .requiredWidth(needs)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
        ) { content() }
    }
}

/**
 * Contextual permission nudges, invoked when a reminder is confirmed: the notification
 * permission on 33+, and the exact-alarm settings screen on 31/32 if the user revoked it
 * (33+ ships USE_EXACT_ALARM, auto-granted). Denial is non-fatal — the alarm is still set.
 */
@Composable
fun rememberReminderPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    // The notification half is shared now — a focus session needs exactly the same grant, and two
    // copies of the ask is two places for the SDK check to drift.
    val notifications = rememberNotificationPermissionRequest()
    return {
        notifications()
        if (Build.VERSION.SDK_INT in 31..32 &&
            !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            )
        }
    }
}

/**
 * The system screen for this app's notifications, which is the only place the last two
 * [ReminderReach] answers can be undone.
 *
 * Not a permission dialog: once notifications are off for the app or for the channel, nothing the
 * app can ask will turn them back on, and offering a button that silently does nothing would be
 * the same failure one level up.
 */
private fun notificationSettings(context: android.content.Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
