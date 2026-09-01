package ie.napkin.supertasks.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.ui.graphics.Color
import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.db.LabelEntity
import ie.napkin.supertasks.data.db.NodeLabelEntity
import ie.napkin.supertasks.data.db.PropertyDefEntity
import ie.napkin.supertasks.data.db.PropertyKind
import ie.napkin.supertasks.data.db.PropertyValueEntity
import ie.napkin.supertasks.data.filter.FilterJson
import ie.napkin.supertasks.data.time.localDateOf
import ie.napkin.supertasks.data.repo.SelectConfig

/**
 * Turns raw typed values into displayable chips, grouped by node id.
 *
 * [stranger] answers "is this login unable to see the repository this task is in", and exists so a
 * dead assignment is visible on the task itself rather than only in the picker that made it. It
 * takes the workspace as well as the login because a smart list draws rows from every repo at once
 * and each has to be judged against its own roster. It defaults to "no": most callers have no
 * roster to answer with, and a warning nobody can substantiate is worse than none.
 */
fun buildChips(
    defs: List<PropertyDefEntity>,
    values: List<PropertyValueEntity>,
    stranger: (workspaceId: String, login: String) -> Boolean = { _, _ -> false },
): Map<String, List<ChipData>> {
    val defById = defs.associateBy { it.id }
    return values
        .mapNotNull { v ->
            val def = defById[v.defId] ?: return@mapNotNull null
            val chip = chipFor(def, v, stranger) ?: return@mapNotNull null
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

// Built-in fields are identified by name since PropertyKind.SELECT/DATE alone isn't
// specific enough (orphaned legacy custom defs could share a kind).
private const val PRIORITY_NAME = BuiltIns.PRIORITY_NAME
private const val DUE_NAME = BuiltIns.DUE_NAME
private const val DEADLINE_NAME = BuiltIns.DEADLINE_NAME

fun chipFor(
    def: PropertyDefEntity,
    v: PropertyValueEntity,
    stranger: (workspaceId: String, login: String) -> Boolean = { _, _ -> false },
): ChipData? =
    // Assignee before the kind switch. It is stored as text, so the generic text branch would
    // draw it — as a bare word, with no icon and nothing saying it is a person rather than a note
    // somebody left. A name on a task is the one property that has to read as *who*.
    if (def.id == BuiltIns.ASSIGNEE_DEF_ID) {
        v.vText?.takeIf { it.isNotBlank() }?.let { login ->
            // Amber when GitHub has said who can push here and this is not one of them. The
            // assignment stands — the roster can be stale and an invitation can be in flight — but
            // it is a task nobody will ever be shown, and that has to be legible on the task and
            // not only in the sheet where it was made.
            val unreachable = stranger(v.workspaceId, login)
            ChipData(
                def.id,
                if (unreachable) "@$login · no access" else "@$login",
                null,
                if (unreachable) Icons.Default.PersonOff else Icons.Default.Person,
                if (unreachable) ChipStatus.Warn else ChipStatus.None,
            )
        }
    } else chipForKind(def, v)

private fun chipForKind(def: PropertyDefEntity, v: PropertyValueEntity): ChipData? = when (def.kind) {
    PropertyKind.SELECT -> v.vText?.let { name ->
        val option = selectConfig(def).options.firstOrNull { it.name == name }
        val isPriority = def.name == PRIORITY_NAME
        ChipData(
            def.id, name, option?.color?.let { Color(it) },
            if (isPriority) Icons.Default.Flag else null,
            isPriority = isPriority,
        )
    }
    PropertyKind.DATE -> v.vDate?.let { millis ->
        when (def.name) {
            // A date is only worth colouring when it says something: past is red and says so,
            // today is the accent, anything further out stays neutral.
            DUE_NAME -> {
                val day = localDateOf(millis)
                val today = java.time.LocalDate.now()
                val base = if (v.vBool == true) dateTimeLabel(millis) else dateLabel(millis)
                when {
                    day.isBefore(today) ->
                        ChipData(def.id, "$base · overdue", null, Icons.Default.DateRange, ChipStatus.Overdue)
                    else -> ChipData(
                        def.id, base, null,
                        if (v.vNumber != null) Icons.Default.Alarm else Icons.Default.DateRange,
                        if (day == today) ChipStatus.Due else ChipStatus.None,
                    )
                }
            }
            // The deadline countdown is the neutral one — it reads as distance, not alarm —
            // right up until the day it lands (amber) and after it passes (red).
            DEADLINE_NAME -> {
                val day = localDateOf(millis)
                val today = java.time.LocalDate.now()
                ChipData(
                    def.id, deadlineLabel(millis), null, Icons.Default.Adjust,
                    when {
                        day.isBefore(today) -> ChipStatus.Overdue
                        day == today -> ChipStatus.Warn
                        else -> ChipStatus.None
                    },
                )
            }
            else -> ChipData(def.id, dateLabel(millis), null, null)
        }
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

/**
 * The predicate [buildChips] wants, built from what the app has cached.
 *
 * A workspace missing from [rosters] has never been fetched, and a login is only a stranger to a
 * repository whose people are actually known. One place decides that, so the picker and the chip
 * cannot disagree about who is reachable.
 */
fun strangerAgainst(rosters: Map<String, Set<String>>): (String, String) -> Boolean =
    { workspaceId, login ->
        rosters[workspaceId]?.let { login.lowercase() !in it } ?: false
    }
