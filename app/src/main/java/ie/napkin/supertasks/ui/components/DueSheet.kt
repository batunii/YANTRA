package ie.napkin.supertasks.ui.components

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ie.napkin.supertasks.ui.theme.Yantra
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
    initialReminderMin: Int?,
    onDismiss: () -> Unit,
    onSet: (dateMillis: Long, hasTime: Boolean, reminderMin: Int?) -> Unit,
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
    var reminder by remember { mutableStateOf(initialReminderMin) }
    var showTimePicker by remember { mutableStateOf(false) }
    var reminderMenu by remember { mutableStateOf(false) }
    val requestPermissions = rememberReminderPermissionRequest()
    val timeFmt = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

    fun utcMidnight(d: LocalDate): Long = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Due", style = MaterialTheme.typography.titleLarge, color = y.textPrimary)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

            DatePicker(state = dateState, showModeToggle = false, title = null, headline = null)

            // Time row
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showTimePicker = true }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = y.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Time", color = y.textPrimary, modifier = Modifier.weight(1f))
                Text(time?.format(timeFmt) ?: "None", color = y.textMuted)
                if (time != null) {
                    IconButton(onClick = {
                        time = null
                        // A timed offset makes no sense on an all-day task; back to None.
                        if (reminder != null && reminder != REMIND_ON_THE_DAY) reminder = null
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear time", tint = y.textMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Reminder row
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { reminderMenu = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Alarm, contentDescription = null, tint = y.textSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Reminder", color = y.textPrimary, modifier = Modifier.weight(1f))
                    Text(reminderLabel(reminder, timed = time != null), color = y.textMuted)
                }
                DropdownMenu(expanded = reminderMenu, onDismissRequest = { reminderMenu = false }) {
                    val options: List<Int?> =
                        if (time != null) listOf(null, REMIND_ON_TIME, 30, 60, 1440)
                        else listOf(null, REMIND_ON_THE_DAY)
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(reminderLabel(option, timed = time != null)) },
                            onClick = { reminder = option; reminderMenu = false },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
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
                        onSet(instant, time != null, reminder)
                        if (reminder != null) requestPermissions()
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
                    // Fresh time defaults the reminder to "On time" unless one is chosen.
                    if (reminder == null || reminder == REMIND_ON_THE_DAY) reminder = REMIND_ON_TIME
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
        )
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
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT in 31..32 &&
            !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            )
        }
    }
}
