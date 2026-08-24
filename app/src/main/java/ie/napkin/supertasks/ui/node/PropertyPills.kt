package ie.napkin.supertasks.ui.node

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.db.LabelEntity
import ie.napkin.supertasks.data.db.PropertyDefEntity
import ie.napkin.supertasks.data.db.PropertyKind
import ie.napkin.supertasks.data.db.PropertyValueEntity
import ie.napkin.supertasks.ui.components.DueSheet
import ie.napkin.supertasks.ui.components.chipFor
import ie.napkin.supertasks.ui.components.chipStyleFor
import ie.napkin.supertasks.ui.components.horizontalFadingEdge
import ie.napkin.supertasks.ui.components.selectConfig
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import ie.napkin.supertasks.ui.components.selectOptionColor
import ie.napkin.supertasks.ui.theme.Yantra

/**
 * Superlist-style always-visible properties: one pill per built-in property definition
 * (Priority, Due — the fixed set) plus the task's labels, directly on the task page. Set values
 * render filled with the value; unset render as dimmed ghost "+ Name" pills. Tapping edits in
 * place. There is no "+ Property" affordance here anymore — arbitrary custom fields are what
 * [LabelChipsRow] replaces, since a schema-creation dialog behind a lightweight-looking ghost
 * pill was the source of the old add/remove asymmetry.
 *
 * One scrolling line, not a wrapping grid, and set values come first: what the task *has* is
 * information and reads at full strength; what it *could* have is an offer and sits at
 * [GHOST_ALPHA], scrolling off under a fade. Two rows of equally-loud "+ Something" pills
 * turned the top of every task page into a form.
 */
@Composable
fun PropertyRow(
    defs: List<PropertyDefEntity>,
    values: Map<String, PropertyValueEntity>,
    allLabels: List<LabelEntity>,
    attachedLabels: List<LabelEntity>,
    onSet: (def: PropertyDefEntity, text: String?, number: Double?, date: Long?, bool: Boolean?) -> Unit,
    onSetDue: (dateMillis: Long, hasTime: Boolean, reminderMin: Int?) -> Unit,
    onSetDeadline: (dateMillis: Long) -> Unit,
    onClear: (defId: String) -> Unit,
    onAttachLabel: (LabelEntity) -> Unit,
    onDetachLabel: (LabelEntity) -> Unit,
    onCreateAndAttachLabel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // Only dissolve the edge when there is genuinely something past it, or the last pill of a
    // row that fits would fade for no reason.
    val overflows = scroll.maxValue > 0
    val (set, unset) = defs.partition { values[it.id] != null }
    Row(
        modifier = modifier
            .let { if (overflows) it.horizontalFadingEdge() else it }
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (set + unset).forEach { def ->
            PropertyPill(
                def = def,
                value = values[def.id],
                onSet = { t, n, d, b -> onSet(def, t, n, d, b) },
                onSetDue = onSetDue,
                onSetDeadline = onSetDeadline,
                onClear = { onClear(def.id) },
            )
        }
        attachedLabels.forEach { label ->
            LabelChip(label = label, onClick = { onDetachLabel(label) })
        }
        GhostPill(label = "+ Label", dashed = true, onClick = { picking = true })
        Spacer(Modifier.width(12.dp))
    }

    if (picking) {
        LabelPickerDialog(
            allLabels = allLabels,
            attachedIds = attachedLabels.map { it.id }.toSet(),
            onDismiss = { picking = false },
            onPick = { label -> onAttachLabel(label); picking = false },
            onCreate = { name -> onCreateAndAttachLabel(name); picking = false },
        )
    }
}

/**
 * The one open-ended, user-extensible mechanism: freely create, attach and detach labels
 * per task, with a real delete for the label itself — no schema ceremony either way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelChipsRow(
    allLabels: List<LabelEntity>,
    attached: List<LabelEntity>,
    onDetach: (LabelEntity) -> Unit,
    onAttach: (LabelEntity) -> Unit,
    onCreateAndAttach: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attached.forEach { label ->
            LabelChip(label = label, onClick = { onDetach(label) })
        }
        GhostPill(label = "+ Label", dashed = true, onClick = { picking = true })
    }

    if (picking) {
        LabelPickerDialog(
            allLabels = allLabels,
            attachedIds = attached.map { it.id }.toSet(),
            onDismiss = { picking = false },
            onPick = { label -> onAttach(label); picking = false },
            onCreate = { name -> onCreateAndAttach(name); picking = false },
        )
    }
}

@Composable
private fun LabelChip(label: LabelEntity, onClick: () -> Unit) {
    val s = chipStyleFor(label.color?.let { Color(it) })
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(s.bg, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, tint = s.dot, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(label.name, fontSize = 11.5.sp, fontWeight = FontWeight.W600, color = s.text)
    }
}

/** Tap an existing label to attach it, or type a new name and create it — attach/detach and
 * delete are both plain, symmetric operations, unlike the old global-property-def flow. */
@Composable
private fun LabelPickerDialog(
    allLabels: List<LabelEntity>,
    attachedIds: Set<String>,
    onDismiss: () -> Unit,
    onPick: (LabelEntity) -> Unit,
    onCreate: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query, allLabels, attachedIds) {
        allLabels.filter { it.id !in attachedIds && it.name.contains(query, ignoreCase = true) }
    }
    val exactMatch = remember(query, allLabels) {
        allLabels.any { it.name.equals(query.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search or create…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(Modifier.padding(top = 6.dp)) {
                    matches.forEach { label ->
                        Text(
                            label.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(label) }
                                .padding(vertical = 10.dp),
                        )
                    }
                    if (query.isNotBlank() && !exactMatch) {
                        Text(
                            "Create \"${query.trim()}\"",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCreate(query.trim()) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropertyPill(
    def: PropertyDefEntity,
    value: PropertyValueEntity?,
    onSet: (text: String?, number: Double?, date: Long?, bool: Boolean?) -> Unit,
    onSetDue: (dateMillis: Long, hasTime: Boolean, reminderMin: Int?) -> Unit,
    onSetDeadline: (dateMillis: Long) -> Unit,
    onClear: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showDueSheet by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    val isDue = def.kind == PropertyKind.DATE && def.name == BuiltIns.DUE_NAME
    val isDeadline = def.kind == PropertyKind.DATE && def.name == BuiltIns.DEADLINE_NAME

    val chip = value?.let { chipFor(def, it) }

    Box {
        val onClick: () -> Unit = {
            when {
                def.kind == PropertyKind.SELECT -> menu = true
                isDue -> if (value?.vDate != null) menu = true else showDueSheet = true
                def.kind == PropertyKind.DATE -> if (value?.vDate != null) menu = true else showDatePicker = true
                def.kind == PropertyKind.CHECKBOX ->
                    if (value?.vBool == true) onClear() else onSet(null, null, null, true)
                else -> showTextDialog = true
            }
        }

        if (chip != null) {
            // chipStyleFor(chip), not chip.color: a set Due that has gone past reads red here
            // too, so the page header and the row chips can never disagree about urgency.
            val s = chipStyleFor(chip)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(s.bg, RoundedCornerShape(5.dp))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                if (chip.icon != null) {
                    Icon(chip.icon, contentDescription = null, tint = s.dot, modifier = Modifier.size(12.dp))
                } else {
                    Box(Modifier.size(6.dp).background(s.dot, RoundedCornerShape(1.dp)))
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "${def.name} · ${chip.label}",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.W600,
                    color = s.text,
                )
            }
        } else {
            GhostPill(label = "+ ${def.name}", dashed = true, onClick = onClick)
        }

        // ---- inline editors ----
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            when {
                isDue -> {
                    DropdownMenuItem(
                        text = { Text("Change…") },
                        onClick = { menu = false; showDueSheet = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Clear", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onClear() },
                    )
                }
                else -> when (def.kind) {
                PropertyKind.SELECT -> {
                    selectConfig(def).options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.name) },
                            leadingIcon = {
                                val c = selectOptionColor(def, option.name)
                                    ?: MaterialTheme.colorScheme.onSurfaceVariant
                                Box(
                                    Modifier
                                        .size(9.dp)
                                        .background(c, CircleShape)
                                )
                            },
                            onClick = { menu = false; onSet(option.name, null, null, null) },
                        )
                    }
                    if (value?.vText != null) {
                        DropdownMenuItem(
                            text = { Text("Clear", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onClear() },
                        )
                    }
                }
                PropertyKind.DATE -> {
                    DropdownMenuItem(
                        text = { Text("Change date…") },
                        onClick = { menu = false; showDatePicker = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Clear", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onClear() },
                    )
                }
                else -> Unit
                }
            }
        }
    }

    if (showDueSheet) {
        DueSheet(
            initialDateMillis = value?.vDate,
            initialHasTime = value?.vBool == true,
            initialReminderMin = value?.vNumber?.toInt(),
            onDismiss = { showDueSheet = false },
            onSet = onSetDue,
            onClear = value?.let { { onClear() } },
        )
    }

    if (showDatePicker) {
        // Initial value: local date re-encoded as the picker's UTC-midnight convention.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value?.vDate?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { picked ->
                            // Picker yields UTC-midnight; convert to the local-day instant.
                            val local = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            if (isDeadline) onSetDeadline(local) else onSet(null, null, local, null)
                        }
                        showDatePicker = false
                    },
                ) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showTextDialog) {
        val isNumber = def.kind == PropertyKind.NUMBER
        var text by remember {
            mutableStateOf(
                if (isNumber) value?.vNumber?.let {
                    if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()
                }.orEmpty()
                else value?.vText.orEmpty()
            )
        }
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text(def.name) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(if (isNumber) "0" else "Value") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isNumber) text.toDoubleOrNull()?.let { onSet(null, it, null, null) }
                        else if (text.isNotBlank()) onSet(text.trim(), null, null, null)
                        showTextDialog = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (value != null) {
                        TextButton(onClick = { onClear(); showTextDialog = false }) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { showTextDialog = false }) { Text("Cancel") }
                }
            },
        )
    }
}

/** Ghost pills are offers, not facts — [GHOST_ALPHA] keeps them findable without competing. */
private const val GHOST_ALPHA = 0.55f

@Composable
private fun GhostPill(label: String, dashed: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(5.dp)
    val borderColor = y.textPrimary.copy(alpha = if (dashed) 0.28f else 0.22f)
    val base = Modifier
        .alpha(GHOST_ALPHA)
        .clip(shape)
        .clickable(onClick = onClick)
    val bordered = if (dashed) {
        base.drawBehind {
            val stroke = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f),
            )
            drawRoundRect(
                color = borderColor,
                style = stroke,
                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
            )
        }
    } else {
        base.border(1.dp, borderColor, shape)
    }
    Text(
        label,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.W600,
        color = y.textSecondary,
        modifier = bordered.padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
