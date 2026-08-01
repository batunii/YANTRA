package ie.napkin.supertasks.ui.smart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.data.db.LabelEntity
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.db.PropertyDefEntity
import ie.napkin.supertasks.data.db.PropertyKind
import ie.napkin.supertasks.data.filter.DateRel
import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.filter.Op
import ie.napkin.supertasks.data.filter.SortBy
import ie.napkin.supertasks.data.filter.SortSpec
import ie.napkin.supertasks.ui.components.selectConfig
import ie.napkin.supertasks.ui.theme.Yantra
import kotlinx.coroutines.launch

private enum class ShowMode(val label: String) { OPEN("Open"), ALL("All"), DONE("Completed") }

private enum class LabelMatchMode { ANY, ALL }

/**
 * Either a property condition (defId set) or a label condition (labelIds set, possibly still
 * empty right after being added and before the picker is confirmed) — never both. Multiple
 * label conditions can coexist ("Match all of" ANDs them), and each one's own Any/All mode
 * controls whether it needs any or all of its own labels — composing both gives AND-of-ORs
 * without a dedicated boolean-tree editor.
 */
private data class Cond(
    val defId: String? = null,
    val labelIds: List<String> = emptyList(),
    val labelMatch: LabelMatchMode = LabelMatchMode.ANY,
    val op: Op = Op.IS_SET,
    val text: String? = null,
    val number: Double? = null,
    val dateRel: DateRel? = null,
    val bool: Boolean? = null,
) {
    val isLabelCond: Boolean get() = defId == null
}

private data class OpOption(val label: String, val op: Op, val dateRel: DateRel? = null, val bool: Boolean? = null)

private fun opsFor(kind: String): List<OpOption> = when (kind) {
    PropertyKind.SELECT -> listOf(
        OpOption("is", Op.EQ), OpOption("is not", Op.NEQ),
        OpOption("is set", Op.IS_SET), OpOption("is empty", Op.NOT_SET),
    )
    PropertyKind.DATE -> listOf(
        OpOption("today or earlier", Op.LTE, DateRel.TODAY_END),
        OpOption("today or later", Op.GTE, DateRel.TODAY_START),
        OpOption("has a date", Op.IS_SET), OpOption("no date", Op.NOT_SET),
    )
    PropertyKind.NUMBER -> listOf(
        OpOption("equals", Op.EQ), OpOption("less than", Op.LT), OpOption("greater than", Op.GT),
        OpOption("is set", Op.IS_SET), OpOption("empty", Op.NOT_SET),
    )
    PropertyKind.CHECKBOX -> listOf(
        OpOption("is checked", Op.EQ, bool = true), OpOption("is unchecked", Op.EQ, bool = false),
    )
    else -> listOf( // text
        OpOption("is", Op.EQ), OpOption("is set", Op.IS_SET), OpOption("is empty", Op.NOT_SET),
    )
}

private fun defaultCond(def: PropertyDefEntity): Cond {
    val op = opsFor(def.kind).first()
    val firstOption = if (def.kind == PropertyKind.SELECT) selectConfig(def).options.firstOrNull()?.name else null
    return Cond(defId = def.id, op = op.op, text = firstOption, dateRel = op.dateRel, bool = op.bool)
}

/** Quick-fill starting points shown at the top of the builder — they pre-populate Show/conditions
 * in place rather than gating a separate screen before you reach the real controls. */
private enum class SmartTemplate(val label: String) {
    DUE_TODAY("Due today"),
    HIGH_PRIORITY("High priority"),
    ALL_OPEN("All open tasks"),
}

private fun presetFor(template: SmartTemplate, defs: List<PropertyDefEntity>): Pair<ShowMode, List<Cond>> {
    val dueDef = defs.firstOrNull { it.name.equals("Due", ignoreCase = true) }
    val priorityDef = defs.firstOrNull { it.name.equals("Priority", ignoreCase = true) }
    return when (template) {
        SmartTemplate.DUE_TODAY -> ShowMode.OPEN to listOfNotNull(
            dueDef?.let { Cond(defId = it.id, op = Op.LTE, dateRel = DateRel.TODAY_END) }
        )
        SmartTemplate.HIGH_PRIORITY -> ShowMode.OPEN to listOfNotNull(
            priorityDef?.let { Cond(defId = it.id, op = Op.EQ, text = "High") }
        )
        SmartTemplate.ALL_OPEN -> ShowMode.OPEN to emptyList()
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SmartListBuilderSheet(
    defs: List<PropertyDefEntity>,
    labels: List<LabelEntity>,
    lists: List<NodeEntity>,
    onCreateLabel: suspend (String) -> LabelEntity,
    onDismiss: () -> Unit,
    onCreate: (String, Filter, List<SortSpec>, String?) -> Unit,
    initialName: String = "",
) {
    val y = Yantra.colors
    var name by remember { mutableStateOf(initialName) }
    var show by remember { mutableStateOf(ShowMode.OPEN) }
    val conds = remember { mutableStateListOf<Cond>() }
    var homeId by remember { mutableStateOf(lists.firstOrNull()?.id) }
    var addMenu by remember { mutableStateOf(false) }
    var pickingLabelsForIndex by remember { mutableStateOf<Int?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val defsById = defs.associateBy { it.id }
    val labelsById = labels.associateBy { it.id }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = y.cardBg) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = y.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("New smart list", style = MaterialThemeTitle(), color = y.textPrimary)
            }

            Field(
                value = name, onValue = { name = it }, placeholder = "Name — e.g. This week", y = y,
            )

            // Start from — quick-fills Show + conditions below; nothing is locked in until Create.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Start from", y)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmartTemplate.entries.forEach { t ->
                        PresetChip(t.label) {
                            val (presetShow, presetConds) = presetFor(t, defs)
                            show = presetShow
                            conds.clear()
                            conds.addAll(presetConds)
                        }
                    }
                }
            }

            // Show
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Show", y)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShowMode.entries.forEach { m -> SegChip(m.label, show == m) { show = m }; }
                }
            }

            // Conditions
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Match all of", y)
                conds.forEachIndexed { i, c ->
                    if (c.isLabelCond) {
                        LabelConditionCard(
                            labelNames = c.labelIds.mapNotNull { labelsById[it]?.name },
                            match = c.labelMatch,
                            onEdit = { pickingLabelsForIndex = i },
                            onModeChange = { mode -> conds[i] = c.copy(labelMatch = mode) },
                            onRemove = { conds.removeAt(i) },
                        )
                    } else {
                        val def = defsById[c.defId]
                        if (def != null) {
                            ConditionRow(
                                def = def, cond = c,
                                onChange = { conds[i] = it },
                                onRemove = { conds.removeAt(i) },
                            )
                        }
                    }
                }
                Box {
                    GhostButton("+ Add condition") { addMenu = true }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        if (defs.isEmpty()) {
                            DropdownMenuItem(text = { Text("No properties yet") }, enabled = false, onClick = {})
                        }
                        defs.forEach { d ->
                            DropdownMenuItem(
                                text = { Text(d.name) },
                                onClick = { addMenu = false; conds.add(defaultCond(d)) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Labels") },
                            onClick = {
                                addMenu = false
                                conds.add(Cond())
                                pickingLabelsForIndex = conds.lastIndex
                            },
                        )
                    }
                }
                if (conds.isEmpty()) {
                    Text(
                        "No conditions — this list will show every ${if (show == ShowMode.DONE) "completed" else "open"} task.",
                        fontSize = 12.sp, color = y.textDim,
                    )
                }
            }

            // Home list (write target)
            if (lists.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("New tasks land in", y)
                    var homeMenu by remember { mutableStateOf(false) }
                    val homeName = lists.firstOrNull { it.id == homeId }?.title?.ifBlank { "Untitled" } ?: "Choose a list"
                    Box {
                        DropChip(homeName) { homeMenu = true }
                        DropdownMenu(expanded = homeMenu, onDismissRequest = { homeMenu = false }) {
                            lists.forEach { l ->
                                DropdownMenuItem(
                                    text = { Text(l.title?.ifBlank { "Untitled" } ?: "Untitled") },
                                    onClick = { homeId = l.id; homeMenu = false },
                                )
                            }
                        }
                    }
                    Text("Quick-add here auto-tags new tasks to match this view.", fontSize = 11.sp, color = y.textDim)
                }
            }

            // Create
            val valid = name.isNotBlank()
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(if (valid) y.accentFill else y.neutralChipBg, RoundedCornerShape(14.dp))
                    .then(if (valid) Modifier.border(1.dp, y.accentBorder, RoundedCornerShape(14.dp)) else Modifier)
                    .clickable(enabled = valid) {
                        onCreate(name.trim(), buildFilter(show, conds), buildSort(conds), homeId)
                    }
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Create smart list", fontWeight = FontWeight.W800, fontSize = 15.sp, color = if (valid) y.accentText else y.textDim)
            }
        }
    }

    pickingLabelsForIndex?.let { idx ->
        val current = conds.getOrNull(idx)
        if (current != null) {
            LabelChecklistDialog(
                allLabels = labels,
                initiallyChecked = current.labelIds.toSet(),
                onCreateLabel = onCreateLabel,
                onDismiss = {
                    // A freshly-added card that's cancelled before picking anything is just noise.
                    if (current.labelIds.isEmpty()) conds.removeAt(idx)
                    pickingLabelsForIndex = null
                },
                onConfirm = { selected ->
                    if (selected.isEmpty()) conds.removeAt(idx) else conds[idx] = current.copy(labelIds = selected.toList())
                    pickingLabelsForIndex = null
                },
            )
        }
    }
}

private fun buildFilter(show: ShowMode, conds: List<Cond>): Filter {
    val base = ArrayList<Filter>()
    base.add(Filter.Type(NodeType.TASK))
    when (show) {
        ShowMode.OPEN -> base.add(Filter.Done(false))
        ShowMode.DONE -> base.add(Filter.Done(true))
        ShowMode.ALL -> Unit
    }
    conds.forEach { c ->
        if (c.isLabelCond) {
            if (c.labelIds.isNotEmpty()) {
                val labelFilters = c.labelIds.map { Filter.HasLabel(it) }
                base.add(if (c.labelMatch == LabelMatchMode.ALL) Filter.All(labelFilters) else Filter.AnyOf(labelFilters))
            }
        } else if (c.defId != null) {
            base.add(Filter.Prop(defId = c.defId, op = c.op, text = c.text, number = c.number, date = null, bool = c.bool, dateRel = c.dateRel))
        }
    }
    return Filter.All(base)
}

private fun buildSort(conds: List<Cond>): List<SortSpec> {
    val dated = conds.firstOrNull { it.dateRel != null && it.defId != null }
    return if (dated?.defId != null) listOf(SortSpec(by = SortBy.PROP_DATE, defId = dated.defId))
    else listOf(SortSpec(by = SortBy.CREATED, desc = true))
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ConditionRow(def: PropertyDefEntity, cond: Cond, onChange: (Cond) -> Unit, onRemove: () -> Unit) {
    val y = Yantra.colors
    val ops = opsFor(def.kind)
    val current = ops.firstOrNull { it.op == cond.op && it.dateRel == cond.dateRel && it.bool == cond.bool } ?: ops.first()
    val showValue = cond.op == Op.EQ || cond.op == Op.NEQ || cond.op == Op.LT || cond.op == Op.GT
    Row(
        Modifier
            .fillMaxWidth()
            .background(y.tileWarm2, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(def.name, fontWeight = FontWeight.W700, fontSize = 14.sp, color = y.textPrimary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // operator
                var opMenu by remember { mutableStateOf(false) }
                Box {
                    DropChip(current.label) { opMenu = true }
                    DropdownMenu(expanded = opMenu, onDismissRequest = { opMenu = false }) {
                        ops.forEach { o ->
                            DropdownMenuItem(text = { Text(o.label) }, onClick = {
                                opMenu = false
                                val clears = o.op == Op.IS_SET || o.op == Op.NOT_SET
                                onChange(
                                    cond.copy(
                                        op = o.op, dateRel = o.dateRel, bool = o.bool,
                                        text = if (clears) null else if (def.kind == PropertyKind.SELECT && cond.text == null) selectConfig(def).options.firstOrNull()?.name else cond.text,
                                        number = if (clears) null else cond.number,
                                    )
                                )
                            })
                        }
                    }
                }
                // value
                if (showValue) {
                    when (def.kind) {
                        PropertyKind.SELECT -> {
                            var vMenu by remember { mutableStateOf(false) }
                            Box {
                                DropChip(cond.text ?: "value") { vMenu = true }
                                DropdownMenu(expanded = vMenu, onDismissRequest = { vMenu = false }) {
                                    selectConfig(def).options.forEach { opt ->
                                        DropdownMenuItem(text = { Text(opt.name) }, onClick = { onChange(cond.copy(text = opt.name)); vMenu = false })
                                    }
                                }
                            }
                        }
                        PropertyKind.NUMBER -> ValueField(
                            value = cond.number?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "",
                            numeric = true, y = y,
                            onValue = { onChange(cond.copy(number = it.toDoubleOrNull())) },
                        )
                        PropertyKind.CHECKBOX, PropertyKind.DATE -> Unit
                        else -> ValueField(
                            value = cond.text ?: "", numeric = false, y = y,
                            onValue = { onChange(cond.copy(text = it.ifBlank { null })) },
                        )
                    }
                }
            }
        }
        Box(Modifier.size(28.dp).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, "Remove condition", tint = y.textDim, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * One or more labels, matched by Any/All. Tapping the body reopens the checklist to adjust
 * the selection; the mode toggle only matters (and only shows) once 2+ labels are picked.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LabelConditionCard(
    labelNames: List<String>,
    match: LabelMatchMode,
    onEdit: () -> Unit,
    onModeChange: (LabelMatchMode) -> Unit,
    onRemove: () -> Unit,
) {
    val y = Yantra.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(y.tileWarm2, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onEdit), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Labels", fontWeight = FontWeight.W700, fontSize = 14.sp, color = y.textPrimary)
            if (labelNames.isEmpty()) {
                Text("Tap to choose labels…", fontSize = 12.sp, color = y.textDim)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (labelNames.size > 1) {
                        MiniSegChip("Any", match == LabelMatchMode.ANY) { onModeChange(LabelMatchMode.ANY) }
                        MiniSegChip("All", match == LabelMatchMode.ALL) { onModeChange(LabelMatchMode.ALL) }
                    }
                    labelNames.forEach { name -> LabelNameChip(name) }
                }
            }
        }
        Box(Modifier.size(28.dp).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, "Remove condition", tint = y.textDim, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun MiniSegChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier
            .background(if (selected) y.accent.copy(alpha = 0.18f) else y.cardBg, shape)
            .then(if (selected) Modifier.border(1.dp, y.accent, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.W700, color = if (selected) y.accentText else y.textDim)
    }
}

@Composable
private fun LabelNameChip(name: String) {
    val y = Yantra.colors
    Box(
        Modifier.background(y.cardBg, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(name, fontSize = 12.sp, fontWeight = FontWeight.W600, color = y.textSecondary)
    }
}

/** Multi-select checklist plus inline get-or-create — no need to leave the smart-list builder
 * to make a new label, and one made here is immediately usable to tag real tasks elsewhere. */
@Composable
private fun LabelChecklistDialog(
    allLabels: List<LabelEntity>,
    initiallyChecked: Set<String>,
    onCreateLabel: suspend (String) -> LabelEntity,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val y = Yantra.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var checked by remember { mutableStateOf(initiallyChecked) }
    var localLabels by remember { mutableStateOf(allLabels) }
    var query by remember { mutableStateOf("") }
    val matches = remember(query, localLabels) { localLabels.filter { it.name.contains(query, ignoreCase = true) } }
    val exactMatch = remember(query, localLabels) { localLabels.any { it.name.equals(query.trim(), ignoreCase = true) } }

    fun toggle(id: String) {
        checked = if (id in checked) checked - id else checked + id
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter by labels") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Field(value = query, onValue = { query = it }, placeholder = "Search or create…", y = y)
                Column(Modifier.padding(top = 6.dp)) {
                    matches.forEach { label ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { toggle(label.id) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = label.id in checked,
                                onCheckedChange = { toggle(label.id) },
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(label.name, color = y.textPrimary)
                        }
                    }
                    if (query.isNotBlank() && !exactMatch) {
                        Text(
                            "Create \"${query.trim()}\"",
                            color = y.accentText,
                            fontWeight = FontWeight.W700,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val toCreate = query.trim()
                                    scope.launch {
                                        val created = onCreateLabel(toCreate)
                                        localLabels = localLabels + created
                                        checked = checked + created.id
                                        query = ""
                                    }
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(checked) }) { Text("Apply") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ---- small building blocks ----

@Composable
private fun MaterialThemeTitle() = TextStyle(
    fontFamily = ie.napkin.supertasks.ui.theme.YantraDisplay, fontWeight = FontWeight.W700, fontSize = 22.sp, letterSpacing = (-0.3).sp,
)

@Composable
private fun Label(text: String, y: ie.napkin.supertasks.ui.theme.YantraColors) =
    ie.napkin.supertasks.ui.components.SectionLabel(text, color = y.textMuted)

@Composable
private fun DropChip(label: String, onClick: () -> Unit) {
    val y = Yantra.colors
    Row(
        Modifier
            .background(y.cardBg, RoundedCornerShape(9.dp))
            .border(1.dp, y.tileBorder, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.W600, color = y.textSecondary)
        Icon(Icons.Default.ExpandMore, null, tint = y.textDim, modifier = Modifier.size(16.dp).padding(start = 2.dp))
    }
}

@Composable
private fun SegChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .background(if (selected) y.accent.copy(alpha = 0.16f) else y.tileWarm2, shape)
            .then(if (selected) Modifier.border(1.dp, y.accent, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.W700 else FontWeight.W600, color = if (selected) y.accentText else y.textSecondary)
    }
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .background(y.tileWarm2, shape)
            .border(1.dp, y.tileBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.W600, color = y.textSecondary)
    }
}

@Composable
private fun GhostButton(label: String, onClick: () -> Unit) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Add, null, tint = y.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label.removePrefix("+ "), fontSize = 13.5.sp, fontWeight = FontWeight.W700, color = y.accentText)
    }
}

@Composable
private fun Field(value: String, onValue: (String) -> Unit, placeholder: String, y: ie.napkin.supertasks.ui.theme.YantraColors) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(y.tileWarm2, RoundedCornerShape(12.dp))
            .border(1.dp, y.tileBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        BasicTextField(
            value = value, onValueChange = onValue, singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = y.textPrimary),
            cursorBrush = SolidColor(y.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = y.textDim)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun ValueField(value: String, numeric: Boolean, y: ie.napkin.supertasks.ui.theme.YantraColors, onValue: (String) -> Unit) {
    Box(
        Modifier
            .width(if (numeric) 84.dp else 150.dp)
            .background(y.cardBg, RoundedCornerShape(9.dp))
            .border(1.dp, y.tileBorder, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BasicTextField(
            value = value, onValueChange = onValue, singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = y.textPrimary),
            cursorBrush = SolidColor(y.accent),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text, imeAction = ImeAction.Done),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(if (numeric) "0" else "value", fontSize = 13.sp, color = y.textDim)
                    inner()
                }
            },
        )
    }
}
