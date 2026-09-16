package ie.shoonya.yantra.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
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
import ie.shoonya.yantra.data.device.CalendarChoice
import ie.shoonya.yantra.data.device.DeviceCalendar
import ie.shoonya.yantra.data.device.DeviceCalendarSource
import ie.shoonya.yantra.ui.theme.Yantra

/**
 * Whether to draw the phone's own calendars behind yours, and which of them — CALENDAR_PLAN.md §5.
 *
 * **The reason comes before the request.** `READ_CALENDAR` is a dangerous permission and a dialog
 * that arrives unexplained is one people refuse, permanently, on behalf of a feature they never saw.
 * So the untouched state is a sentence about what it would do and a control that asks; nothing is
 * requested until it is pressed.
 *
 * And nothing depends on the answer. The overlay is an addition to your own events, never a
 * precondition for them — refuse it and the calendar is exactly the calendar it was.
 */
@Composable
fun DeviceCalendarSetting() {
    val y = Yantra.colors
    val context = LocalContext.current
    val source = remember { DeviceCalendarSource(context) }
    val choice = remember { CalendarChoice(context) }

    var granted by remember { mutableStateOf(source.hasPermission()) }
    var calendars by remember { mutableStateOf<List<DeviceCalendar>>(emptyList()) }
    var chosen by remember { mutableStateOf(emptySet<Long>()) }
    // Told apart from "nobody has chosen yet", which falls back to the owning app's own ticks. An
    // empty *stored* choice is somebody saying no, and it has to survive a restart as a no.
    var disconnected by remember { mutableStateOf(choice.chosen()?.isEmpty() == true) }

    fun reload() {
        calendars = source.calendars()
        chosen = choice.effective(source)
        disconnected = choice.chosen()?.isEmpty() == true
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) reload()
    }

    LaunchedEffect(granted) { if (granted) reload() }

    if (!granted) {
        SettingCard {
            Text(
                "Show your phone's calendars",
                color = y.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.W600,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Your meetings drawn behind your own day, so a plan is made against what is " +
                    "already there. Read only — YANTRA never changes them, and cannot: it does " +
                    "not ask for permission to write.",
                color = y.textMuted, fontSize = 11.5.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Allow reading",
                color = y.accent, fontSize = 13.sp, fontWeight = FontWeight.W700,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { ask.launch(Manifest.permission.READ_CALENDAR) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        return
    }

    // Disconnected — CALENDAR_PLAN.md §27. Android will not let an app hand a permission back, so
    // the honest version of "remove the connection" is two sentences: nothing is being read, and
    // here is where the permission itself lives if you want that gone too.
    if (disconnected) {
        SettingCard {
            Text("Calendars disconnected", color = y.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.W600)
            Spacer(Modifier.height(4.dp))
            Text(
                "Nothing from the phone's calendars is being read or drawn. Your own events, and " +
                    "any notes you took on a meeting, are untouched.",
                color = y.textMuted, fontSize = 11.5.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Connect again",
                color = y.accent, fontSize = 13.sp, fontWeight = FontWeight.W700,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        // Back to the owning app's own visibility rather than to whatever was
                        // ticked before: reconnecting should mean "my calendar", freshly asked.
                        choice.clear()
                        reload()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Take back the permission in Android settings  \u203a",
                color = y.textDim, fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { openAppSettings(context) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        return
    }

    if (calendars.isEmpty()) {
        SettingCard {
            Text("No calendars on this device", color = y.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.W600)
            Spacer(Modifier.height(4.dp))
            Text(
                "Nothing to draw. Add an account in the phone's calendar app and it will appear here.",
                color = y.textMuted, fontSize = 11.5.sp,
            )
        }
        return
    }

    SettingCard {
        Text("Draw behind your day", color = y.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.W600)
        Spacer(Modifier.height(2.dp))
        Text(
            "Untick one and it stops being drawn. Nothing here changes the calendar itself.",
            color = y.textMuted, fontSize = 11.5.sp,
        )
        Spacer(Modifier.height(10.dp))
        calendars.forEach { cal ->
            val on = cal.id in chosen
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        chosen = if (on) chosen - cal.id else chosen + cal.id
                        choice.set(chosen)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The calendar's own colour, unmapped — it is how you recognise which one this is
                // in the app it came from.
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(cal.color?.let { Color(it) } ?: y.textDim),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        cal.name,
                        color = if (on) y.textPrimary else y.textMuted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (cal.account.isNotBlank() && cal.account != cal.name) {
                        Text(
                            cal.account,
                            color = y.textDim, fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (on) y.accent else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Text("✓", color = y.onAccent, fontSize = 11.sp, fontWeight = FontWeight.W700)
                    else Box(Modifier.size(16.dp).clip(CircleShape).background(y.tileBorder))
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "Disconnect calendars",
            color = y.warning, fontSize = 12.5.sp, fontWeight = FontWeight.W600,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    // An explicit empty choice, which is why `set` and not `clear`: clear means
                    // "ask the owning app again", and would switch the overlay straight back on.
                    choice.set(emptySet())
                    chosen = emptySet()
                    disconnected = true
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** The one screen an app cannot draw for itself: where Android keeps this app's permissions. */
private fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun SettingCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val y = Yantra.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(y.cardBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Top,
        content = content,
    )
}
