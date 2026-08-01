package ie.napkin.supertasks.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Flag
import androidx.compose.ui.graphics.Color
import ie.napkin.supertasks.data.db.LabelEntity
import ie.napkin.supertasks.data.db.NodeLabelEntity
import ie.napkin.supertasks.data.db.PropertyDefEntity
import ie.napkin.supertasks.data.db.PropertyKind
import ie.napkin.supertasks.data.db.PropertyValueEntity
import ie.napkin.supertasks.data.filter.FilterJson
import ie.napkin.supertasks.data.repo.SelectConfig

/** Accent used where a due/date chip needs colour; date chips are neutral by default now. */
val DueChipColor = Color(0xFF709AFB)

/** Turns raw typed values into displayable chips, grouped by node id. */
fun buildChips(
    defs: List<PropertyDefEntity>,
    values: List<PropertyValueEntity>,
): Map<String, List<ChipData>> {
    val defById = defs.associateBy { it.id }
    return values
        .mapNotNull { v ->
            val def = defById[v.defId] ?: return@mapNotNull null
            val chip = chipFor(def, v) ?: return@mapNotNull null
            v.nodeId to chip
        }
        .groupBy({ it.first }, { it.second })
}

/** Turns label attachments into displayable chips, grouped by node id — merges with [buildChips]. */
fun buildLabelChips(
    labels: List<LabelEntity>,
    nodeLabels: List<NodeLabelEntity>,
): Map<String, List<ChipData>> {
    val labelById = labels.associateBy { it.id }
    return nodeLabels
        .mapNotNull { nl -> labelById[nl.labelId]?.let { nl.nodeId to labelChipFor(it) } }
        .groupBy({ it.first }, { it.second })
}

// The only two built-in fields — identified by name since PropertyKind.SELECT/DATE alone
// isn't specific enough (orphaned legacy custom defs could share a kind).
private const val PRIORITY_NAME = "Priority"
private const val DUE_NAME = "Due"

fun chipFor(def: PropertyDefEntity, v: PropertyValueEntity): ChipData? = when (def.kind) {
    PropertyKind.SELECT -> v.vText?.let { name ->
        val option = selectConfig(def).options.firstOrNull { it.name == name }
        val icon = if (def.name == PRIORITY_NAME) Icons.Default.Flag else null
        ChipData(def.id, name, option?.color?.let { Color(it) }, icon)
    }
    PropertyKind.DATE -> v.vDate?.let {
        val icon = if (def.name == DUE_NAME) Icons.Default.DateRange else null
        ChipData(def.id, dateLabel(it), null, icon)
    }
    PropertyKind.NUMBER -> v.vNumber?.let {
        val label = if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()
        ChipData(def.id, "${def.name}: $label", null)
    }
    PropertyKind.CHECKBOX -> if (v.vBool == true) ChipData(def.id, def.name, null) else null
    else -> v.vText?.takeIf { it.isNotBlank() }?.let { ChipData(def.id, it, null) }
}

fun labelChipFor(label: LabelEntity): ChipData =
    ChipData(label.id, label.name, label.color?.let { Color(it) }, Icons.AutoMirrored.Filled.Label)

fun selectConfig(def: PropertyDefEntity): SelectConfig =
    def.config
        ?.let { runCatching { FilterJson.decodeFromString(SelectConfig.serializer(), it) }.getOrNull() }
        ?: SelectConfig()

fun selectOptionColor(def: PropertyDefEntity, optionName: String): Color? =
    selectConfig(def).options.firstOrNull { it.name == optionName }?.color?.let { Color(it) }
