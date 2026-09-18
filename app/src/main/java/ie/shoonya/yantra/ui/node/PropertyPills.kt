package ie.shoonya.yantra.ui.node

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import ie.shoonya.yantra.data.db.BuiltIns
import ie.shoonya.yantra.data.db.LabelEntity
import ie.shoonya.yantra.data.db.PropertyDefEntity
import ie.shoonya.yantra.data.db.PropertyKind
import ie.shoonya.yantra.data.db.PropertyValueEntity
import ie.shoonya.yantra.ui.components.DueSheet
import ie.shoonya.yantra.ui.components.chipFor
import ie.shoonya.yantra.data.label.LabelPalette
import ie.shoonya.yantra.ui.components.chipStyleFor
import ie.shoonya.yantra.ui.components.horizontalFadingEdge
import ie.shoonya.yantra.ui.components.selectConfig
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import ie.shoonya.yantra.ui.components.selectOptionColor
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.components.YantraMark
import ie.shoonya.yantra.ui.components.YantraIcon
import ie.shoonya.yantra.ui.components.YantraIcons
import ie.shoonya.yantra.ui.theme.YantraType
import ie.shoonya.yantra.ui.theme.YantraRadius

/**
 * Something a pill needs the **page** to open on its behalf.
 *
 * Every editor behind a property pill is a dialog or a sheet, and the row those pills live in is in
 * the page's header band — which folds the moment the keyboard comes up. A dialog whose open/closed
 * flag is remembered inside that row dies with the row, mid-keystroke, and the app looks like it
 * crashed. It did exactly that with the label picker, and five more were one keystroke behind it.
 *
 * So a pill does not open anything. It *asks*, the page holds the request, and [PillDialogHost]
 * draws it somewhere the band cannot reach — see the note on [PropertyRow.onRequest].
 *
 * `FoldableDialogOwnershipTest` fails the build if a component in the band starts owning one again.
 */
internal sealed interface PillRequest {
    /** A plain date — the picker. */
    data class Date(val def: PropertyDefEntity, val value: PropertyValueEntity?) : PillRequest
    /** Due, which is a date plus a time plus a reminder, and has its own sheet. */
    data class Due(val def: PropertyDefEntity, val value: PropertyValueEntity?) : PillRequest
    /** Text or a number — the one you type into, and so the one that folds the band. */
    data class Text(val def: PropertyDefEntity, val value: PropertyValueEntity?) : PillRequest
    /** The roster. */
    data class Assignee(val def: PropertyDefEntity, val value: PropertyValueEntity?) : PillRequest
    /** Recolouring a label already attached, from its chip. */
    data class Recolour(val label: LabelEntity) : PillRequest
    /** The label picker: search the ones that exist, or type a new name. */
    data object Label : PillRequest
}

/**
 * Draws whatever the property row asked for — **call this from the screen, never from the row**.
 *
 * It is one composable rather than six call sites so the page adds a single line and cannot wire
 * four of the five by accident.
 */
@Composable
internal fun PillDialogHost(
    request: PillRequest?,
    allLabels: List<LabelEntity>,
    attachedLabels: List<LabelEntity>,
    onSet: (def: PropertyDefEntity, text: String?, number: Double?, date: Long?, bool: Boolean?) -> Unit,
    onSetDue: (dateMillis: Long, hasTime: Boolean, reminderMin: Int?) -> Unit,
    onSetDeadline: (dateMillis: Long) -> Unit,
    onClear: (defId: String) -> Unit,
    onAttachLabel: (LabelEntity) -> Unit,
    onCreateAndAttachLabel: (String, Long?) -> Unit,
    onRecolourLabel: (LabelEntity, Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    when (request) {
        null -> Unit
        is PillRequest.Label -> LabelPickerDialog(
            allLabels = allLabels,
            attachedIds = attachedLabels.map { it.id }.toSet(),
            onDismiss = onDismiss,
            onPick = { label -> onAttachLabel(label); onDismiss() },
            onCreate = { name, colour -> onCreateAndAttachLabel(name, colour); onDismiss() },
        )
        is PillRequest.Recolour -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(request.label.name) },
            text = {
                SwatchStrip(
                    selected = request.label.color,
                    onPick = { onRecolourLabel(request.label, it); onDismiss() },
                )
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        is PillRequest.Assignee -> {
            val source = LocalPeople.current
            AssigneeSheet(
                current = request.value?.vText,
                people = source.people,
                onRefresh = source.onRefresh,
                refreshing = source.refreshing,
                refreshNote = source.note,
                onPick = { login -> onSet(request.def, login, null, null, null); onDismiss() },
                onClear = { onClear(request.def.id); onDismiss() },
                onDismiss = onDismiss,
            )
        }
        is PillRequest.Due -> DueSheet(
            initialDateMillis = request.value?.vDate,
            initialHasTime = request.value?.vBool == true,
            initialReminderMin = request.value?.vNumber?.toInt(),
            onDismiss = onDismiss,
            onSet = { d, hasTime, rem -> onSetDue(d, hasTime, rem); onDismiss() },
            onClear = request.value?.let { { onClear(request.def.id); onDismiss() } },
        )
        is PillRequest.Date -> {
            val isDeadline = request.def.kind == PropertyKind.DATE &&
                request.def.name == BuiltIns.DEADLINE_NAME
            // Initial value: local date re-encoded as the picker's UTC-midnight convention.
            val state = rememberDatePickerState(
                initialSelectedDateMillis = request.value?.vDate?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }
            )
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(
                        onClick = {
                            state.selectedDateMillis?.let { picked ->
                                // Picker yields UTC-midnight; convert to the local-day instant.
                                val local = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC)
                                    .toLocalDate().atStartOfDay(ZoneId.systemDefault())
                                    .toInstant().toEpochMilli()
                                if (isDeadline) onSetDeadline(local)
                                else onSet(request.def, null, null, local, null)
                            }
                            onDismiss()
                        },
                    ) { Text("Set") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            ) {
                DatePicker(state = state)
            }
        }
        is PillRequest.Text -> {
            val isNumber = request.def.kind == PropertyKind.NUMBER
            var text by remember(request) {
                mutableStateOf(
                    if (isNumber) request.value?.vNumber?.let {
                        if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()
                    }.orEmpty()
                    else request.value?.vText.orEmpty()
                )
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(request.def.name) },
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
                            if (isNumber) text.toDoubleOrNull()
                                ?.let { onSet(request.def, null, it, null, null) }
                            else if (text.isNotBlank()) onSet(request.def, text.trim(), null, null, null)
                            onDismiss()
                        },
                    ) { Text("Save") }
                },
                dismissButton = {
                    Row {
                        if (request.value != null) {
                            TextButton(onClick = { onClear(request.def.id); onDismiss() }) {
                                Text("Clear", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                },
            )
        }
    }
}

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
internal fun PropertyRow(
    defs: List<PropertyDefEntity>,
    values: Map<String, PropertyValueEntity>,
    allLabels: List<LabelEntity>,
    attachedLabels: List<LabelEntity>,
    onSet: (def: PropertyDefEntity, text: String?, number: Double?, date: Long?, bool: Boolean?) -> Unit,
    onClear: (defId: String) -> Unit,
    onDetachLabel: (LabelEntity) -> Unit,
    /**
     * Ask the page to open an editor — **this row must never open one itself**.
     *
     * The row lives in the page's header band, and the band folds the moment the keyboard comes up
     * (`collapsed || (imeVisible && !titleFocused)`). Anything remembered in here goes with it:
     * tapping into "Search or create…" raised the keyboard, the band folded, this composable left
     * composition, and the picker vanished mid-keystroke. It reads as a crash and was reported as
     * one — and the label picker was only the first of six.
     *
     * The meeting header learned this first; see the note on `collapsedExtra` in NodePageScreen.
     * **A dialog does not belong to the thing that opened it.** The page holds the request and
     * [PillDialogHost] draws it. `FoldableDialogOwnershipTest` keeps it that way.
     */
    onRequest: (PillRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    // Only dissolve the edge when there is genuinely something past it, or the last pill of a
    // row that fits would fade for no reason.
    //
    // `canScrollForward`, not `maxValue`: the latter is the row's total overflow and says nothing
    // about where you currently are in it, so the edge went on dissolving after you had scrolled
    // to the end — and, read before the row had been measured, could be wrong in both directions.
    // A cut-off chip with no fade behind it is just a chip that looks broken.
    val overflows = scroll.canScrollForward
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
                onClear = { onClear(def.id) },
                onRequest = onRequest,
            )
        }
        attachedLabels.forEach { label ->
            LabelChip(
                label = label,
                onClick = { onDetachLabel(label) },
                onRecolour = { onRequest(PillRequest.Recolour(label)) },
            )
        }
        GhostPill(label = "+ Label", dashed = true, onClick = { onRequest(PillRequest.Label) })
        Spacer(Modifier.width(12.dp))
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
    onCreateAndAttach: (String, Long?) -> Unit,
    onRecolour: (LabelEntity, Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // This row is on the property sheet, not in the page's folding band, so it may own its own
    // dialogs — nothing here leaves composition when the keyboard arrives. The band's copy of the
    // same controls (PropertyRow) may not; see PillRequest.
    var picking by remember { mutableStateOf(false) }
    var recolouring by remember { mutableStateOf<LabelEntity?>(null) }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attached.forEach { label ->
            LabelChip(
                label = label,
                onClick = { onDetach(label) },
                onRecolour = { recolouring = label },
            )
        }
        GhostPill(label = "+ Label", dashed = true, onClick = { picking = true })
    }

    if (picking) {
        LabelPickerDialog(
            allLabels = allLabels,
            attachedIds = attached.map { it.id }.toSet(),
            onDismiss = { picking = false },
            onPick = { label -> onAttach(label); picking = false },
            onCreate = { name, colour -> onCreateAndAttach(name, colour); picking = false },
        )
    }

    recolouring?.let { label ->
        AlertDialog(
            onDismissRequest = { recolouring = null },
            title = { Text(label.name) },
            text = {
                SwatchStrip(
                    selected = label.color,
                    onPick = { onRecolour(label, it); recolouring = null },
                )
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { recolouring = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The five palette colours, plus the neutral that means "no colour".
 *
 * A closed strip, not a colour wheel: the palette is curated so labels stay legible on warm paper
 * and out of the hues the colour law has already spoken for. Tapping one commits immediately —
 * there is nothing to confirm about a colour you can see.
 */
@Composable
private fun SwatchStrip(
    selected: Long?,
    onPick: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val y = Yantra.colors
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelPalette.swatches.forEach { sw ->
            val shown = Color(LabelPalette.display(sw.light, y.isDark))
            Swatch(color = shown, selected = selected == sw.light) { onPick(sw.light) }
        }
        Swatch(color = y.textDim, selected = selected == null) { onPick(null) }
    }
}

/** One colour dot. Selection is a ring around it, so the colour itself is never obscured. */
@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    Box(
        // clickable before the insets, so the target is the whole swatch and not the drawn circle.
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .then(if (selected) Modifier.border(2.dp, y.textPrimary, CircleShape) else Modifier)
            .padding(if (selected) 6.dp else 4.dp)
            .background(color, CircleShape),
    )
}

/**
 * An attached label. Tap detaches it; long-press recolours it.
 *
 * Long-press is what this app already means by "act on the thing you are touching" — it is how a
 * task is marked in progress and how a block earns its handles — so recolouring lives there rather
 * than behind a settings screen the app does not have.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LabelChip(label: LabelEntity, onClick: () -> Unit, onRecolour: () -> Unit = {}) {
    val s = chipStyleFor(label.color?.let { Color(it) })

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(s.bg, RoundedCornerShape(YantraRadius.tiny))
            .combinedClickable(onClick = onClick, onLongClick = onRecolour)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        YantraIcon(YantraMark.Label, tint = s.dot, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(label.name, fontSize = YantraType.caption, fontWeight = FontWeight.W600, color = s.text)
    }

}

/** Tap an existing label to attach it, or type a new name and create it — attach/detach and
 * delete are both plain, symmetric operations, unlike the old global-property-def flow. */
@Composable
internal fun LabelPickerDialog(
    allLabels: List<LabelEntity>,
    attachedIds: Set<String>,
    onDismiss: () -> Unit,
    onPick: (LabelEntity) -> Unit,
    onCreate: (String, Long?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Seeded from the name so a new tag is never colourless and two new tags rarely collide;
    // touching a swatch pins it, after which typing on does not move it back.
    var picked by remember { mutableStateOf<Long?>(null) }
    var pinned by remember { mutableStateOf(false) }
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(label) }
                                .padding(vertical = 10.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(chipStyleFor(label.color?.let { Color(it) }).dot, CircleShape)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(label.name)
                        }
                    }
                    if (query.isNotBlank() && !exactMatch) {
                        val colour = if (pinned) picked else LabelPalette.defaultFor(query.trim())
                        SwatchStrip(
                            selected = colour,
                            onPick = { picked = it; pinned = true },
                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                        )
                        Text(
                            "Create \"${query.trim()}\"",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCreate(query.trim(), colour) }
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
    onClear: () -> Unit,
    /** Every editor this pill offers is drawn by the page — see [PillRequest]. */
    onRequest: (PillRequest) -> Unit,
) {
    // The menu may stay. It is a dropdown anchored to this pill, it raises no keyboard, and a menu
    // that closes when its anchor folds away is a menu behaving correctly — unlike a dialog, which
    // is a window of its own and has no business tracking the life of the chip that opened it.
    var menu by remember { mutableStateOf(false) }
    val isDue = def.kind == PropertyKind.DATE && def.name == BuiltIns.DUE_NAME
    val isDeadline = def.kind == PropertyKind.DATE && def.name == BuiltIns.DEADLINE_NAME
    // By id, not by name. A workspace scaffolded by an older build could carry a def called
    // "Assignee" that is not this one, and the id is the thing that travels between devices.
    val isAssignee = def.id == BuiltIns.ASSIGNEE_DEF_ID

    // The page's own chip, and the only one that needs the roster passed in by hand — the row
    // chips get it from the view model. Same predicate either way, so a task cannot read as
    // assignable on its own page and unreachable one screen up.
    val people = LocalPeople.current.people
    val chip = value?.let { v ->
        chipFor(def, v) { _, login ->
            people.any { it.login.equals(login, ignoreCase = true) && it.onRepo == false }
        }
    }

    Box {
        val onClick: () -> Unit = {
            when {
                isAssignee -> onRequest(PillRequest.Assignee(def, value))
                def.kind == PropertyKind.SELECT -> menu = true
                isDue -> if (value?.vDate != null) menu = true else onRequest(PillRequest.Due(def, value))
                def.kind == PropertyKind.DATE ->
                    if (value?.vDate != null) menu = true else onRequest(PillRequest.Date(def, value))
                def.kind == PropertyKind.CHECKBOX ->
                    if (value?.vBool == true) onClear() else onSet(null, null, null, true)
                else -> onRequest(PillRequest.Text(def, value))
            }
        }

        if (chip != null) {
            // chipStyleFor(chip), not chip.color: a set Due that has gone past reads red here
            // too, so the page header and the row chips can never disagree about urgency.
            val s = chipStyleFor(chip)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(s.bg, RoundedCornerShape(YantraRadius.tiny))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                if (chip.mark != null) {
                    YantraIcon(chip.mark, size = YantraIcons.Small, tint = s.dot)
                } else {
                    Box(Modifier.size(6.dp).background(s.dot, RoundedCornerShape(YantraRadius.tiny)))
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    // A login already reads as a person, and "Assignee · @batunii" says the same
                    // thing twice in a row that has no room to. Every other field needs its name
                    // because "· High" alone means nothing.
                    if (isAssignee) chip.label else "${def.name} · ${chip.label}",
                    fontSize = YantraType.caption,
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
                        onClick = { menu = false; onRequest(PillRequest.Due(def, value)) },
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
                        onClick = { menu = false; onRequest(PillRequest.Date(def, value)) },
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

}

/** Ghost pills are offers, not facts — [GHOST_ALPHA] keeps them findable without competing. */
private const val GHOST_ALPHA = 0.55f

@Composable
private fun GhostPill(label: String, dashed: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(YantraRadius.tiny)
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
        fontSize = YantraType.caption,
        fontWeight = FontWeight.W600,
        color = y.textSecondary,
        modifier = bordered.padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
