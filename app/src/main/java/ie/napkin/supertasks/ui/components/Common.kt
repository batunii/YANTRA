package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.ui.theme.Yantra
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import ie.napkin.supertasks.data.label.LabelPalette
import ie.napkin.supertasks.ui.theme.YantraText
import androidx.compose.ui.geometry.Size

/**
 * What a chip *means*, over and above what it says. Kept out of [ChipData.color] because the
 * status voices are theme values ([YantraColors.overdue] etc.) and [chipFor] is non-composable —
 * so the meaning is decided at build time and the colour resolved at draw time.
 */
enum class ChipStatus { None, Overdue, Due, Warn, Done }

data class ChipData(
    val defId: String,
    val label: String,
    val color: Color?,
    val icon: ImageVector? = null,
    val status: ChipStatus = ChipStatus.None,
    /** Set on the Priority chip so rows can carry priority on the checkbox too. */
    val isPriority: Boolean = false,
)

/** The packed-ARGB Long a label stores, opaque — the form [LabelPalette] speaks in. */
internal fun Color.toStoredValue(): Long = copy(alpha = 1f).toArgb().toLong() and 0xFFFFFFFFL

/** Resolved chip surface/dot/text for a (possibly null) identity color. */
data class ChipStyle(val bg: Color, val dot: Color, val text: Color)

/** Status wins over identity colour: urgency has to out-shout decoration. */
@Composable
fun chipStyleFor(chip: ChipData): ChipStyle {
    val y = Yantra.colors
    return when (chip.status) {
        ChipStatus.Overdue -> ChipStyle(y.overdueChipBg, y.overdue, y.overdue)
        ChipStatus.Due -> ChipStyle(y.dueChipBg, y.dueText, y.dueText)
        ChipStatus.Warn -> ChipStyle(y.warningChipBg, y.warning, y.warning)
        ChipStatus.Done -> ChipStyle(y.successChipBg, y.success, y.success)
        ChipStatus.None -> chipStyleFor(chip.color)
    }
}

@Composable
fun chipStyleFor(rawBase: Color?): ChipStyle {
    val y = Yantra.colors
    // A label stores one colour and wears two: the palette swaps in the twin for this theme, so a
    // tag named on light paper still reads at night. Anything not from the palette — a select
    // option's colour, something from an older build — comes back untouched.
    val base = rawBase?.let { Color(LabelPalette.display(it.toStoredValue(), y.isDark)) }
    return if (base == null) {
        ChipStyle(bg = y.neutralChipBg, dot = y.textDim, text = y.textSecondary)
    } else {
        // Lighten toward paper on dark, darken on light, so the label reads at chip size.
        val text = if (y.isDark) lerp(base, Color.White, 0.45f) else lerp(base, Color.Black, 0.35f)
        ChipStyle(bg = base.copy(alpha = if (y.isDark) 0.20f else 0.14f), dot = base, text = text)
    }
}

/**
 * Compact read-only chip shown beneath row titles (priority / due / label / …). Icon-forward
 * (so the chip's kind reads at a glance, not just its color) with a color-dot fallback for any
 * legacy chip with no icon assigned.
 */
@Composable
fun PropertyChip(chip: ChipData, modifier: Modifier = Modifier) {
    val s = chipStyleFor(chip)
    Row(
        modifier = modifier
            .background(s.bg, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (chip.icon != null) {
            Icon(chip.icon, contentDescription = null, tint = s.dot, modifier = Modifier.size(11.dp))
        } else {
            Box(Modifier.size(6.dp).background(s.dot, RoundedCornerShape(1.dp)))
        }
        Text(
            chip.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.W600,
            color = s.text,
        )
    }
}

/** Timer glyph + count, shown on task rows that have logged focus sessions. */
@Composable
fun FocusCount(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Default.Timer,
            contentDescription = null,
            tint = Yantra.colors.accent,
            modifier = Modifier.size(12.dp),
        )
        Text(
            "$count",
            fontSize = 11.sp,
            fontWeight = FontWeight.W600,
            color = Yantra.colors.textSecondary,
        )
    }
}

/** Small uppercase section label with wide tracking — read like an instrument bearing. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Yantra.colors.textMuted) {
    Text(
        text.uppercase(),
        modifier = modifier,
        fontFamily = YantraText,
        fontSize = 11.sp,
        fontWeight = FontWeight.W700,
        letterSpacing = 1.4.sp,
        color = color,
    )
}

/**
 * The compass — Yantra's progress indicator. The brass arc reads the fraction done over four
 * faint cardinal ticks (a compass dial at rest). No needle. At 100% the ring closes.
 */
@Composable
fun Compass(fraction: Float, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    val y = Yantra.colors
    val ring = y.textPrimary.copy(alpha = 0.13f)
    val tickC = y.textPrimary.copy(alpha = 0.34f)
    val acc = y.accent
    Canvas(modifier.size(size)) {
        val sw = this.size.minDimension * 0.09f
        val r = this.size.minDimension / 2f - sw / 2f - 0.5f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(color = ring, radius = r, center = c, style = Stroke(width = sw))
        val f = fraction.coerceIn(0f, 1f)
        if (f >= 1f) {
            drawCircle(color = acc, radius = r, center = c, style = Stroke(width = sw))
        } else if (f > 0f) {
            drawArc(
                color = acc, startAngle = -90f, sweepAngle = 360f * f, useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
        }
        for (i in 0 until 4) {
            val ang = (i * 90.0) * PI / 180.0
            val ca = cos(ang).toFloat()
            val sa = sin(ang).toFloat()
            drawLine(
                color = tickC,
                start = Offset(c.x + ca * (r + sw * 0.55f), c.y + sa * (r + sw * 0.55f)),
                end = Offset(c.x + ca * (r - sw * 0.55f), c.y + sa * (r - sw * 0.55f)),
                strokeWidth = sw * 0.42f + 0.6f, cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun TextFieldDialog(
    title: String,
    confirmLabel: String,
    initial: String = "",
    placeholder: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String = "Delete",
    /** What backing out is called. "Cancel" is wrong wherever the choice is not destroy-or-abort. */
    dismissLabel: String = "Cancel",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}

private val dateFmt = DateTimeFormatter.ofPattern("MMM d")
private val dateFmtYear = DateTimeFormatter.ofPattern("MMM d, yyyy")

fun dateLabel(epochMillis: Long): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> "Today"
        date == today.plusDays(1) -> "Tomorrow"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> date.format(dateFmt)
        else -> date.format(dateFmtYear)
    }
}

private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** "Today 09:00" — [dateLabel] plus the localized short time of the same instant. */
fun dateTimeLabel(epochMillis: Long): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime()
    return "${dateLabel(epochMillis)} ${time.format(timeFmt)}"
}

/** "3d left" / "Due today" / "2d over" — countdown to a local-midnight deadline. */
fun deadlineLabel(epochMillis: Long): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), date)
    return when {
        days > 0L -> "${days}d left"
        days == 0L -> "Due today"
        else -> "${-days}d over"
    }
}

fun durationLabel(totalSecs: Int): String {
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${totalSecs}s"
    }
}
