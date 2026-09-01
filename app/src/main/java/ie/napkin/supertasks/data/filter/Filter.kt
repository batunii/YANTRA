package ie.napkin.supertasks.data.filter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serializable filter tree stored in smart_list_def.filter_json.
 * A small query-compiler ([FilterCompiler]) turns this into SQL, so the UI only edits JSON.
 */
@Serializable
sealed interface Filter {

    @Serializable
    @SerialName("all")
    data class All(val filters: List<Filter>) : Filter

    @Serializable
    @SerialName("any")
    data class AnyOf(val filters: List<Filter>) : Filter

    @Serializable
    @SerialName("not")
    data class Not(val filter: Filter) : Filter

    /** Matches node.done. */
    @Serializable
    @SerialName("done")
    data class Done(val value: Boolean) : Filter

    /**
     * Matches node.in_progress — the task glyph's middle state, the one you swipe to set.
     *
     * It was storable and drawable but not askable: nothing in the query layer knew it existed, so
     * "what am I in the middle of" could be seen and never gathered. A state the app renders but
     * cannot reason about is decoration.
     */
    @Serializable
    @SerialName("in_progress")
    data class InProgress(val value: Boolean) : Filter

    /** Matches node.type; smart lists normally filter for tasks. */
    @Serializable
    @SerialName("type")
    data class Type(val value: String) : Filter

    /**
     * Matches a typed property value. Exactly one of [text]/[number]/[date]/[bool]/[dateRel]
     * should be set (none for IS_SET / NOT_SET). [dateRel] is resolved to epoch millis when
     * the query is compiled, so "due today" style lists stay correct across days.
     */
    @Serializable
    @SerialName("prop")
    data class Prop(
        val defId: String,
        val op: Op,
        val text: String? = null,
        val number: Double? = null,
        val date: Long? = null,
        val bool: Boolean? = null,
        val dateRel: DateRel? = null,
    ) : Filter

    /** Matches nodes tagged with the given label. "Doesn't have" is `Not(HasLabel(id))`. */
    @Serializable
    @SerialName("has_label")
    data class HasLabel(val labelId: String) : Filter

    /**
     * Matches everything that came from one repo — `node.workspace_id`, asked directly.
     *
     * Provenance was filterable before this existed, but only by borrowing [HasLabel]: the reader
     * derived a label per workspace and attached it to every task, so "everything from Work" could
     * be said in the vocabulary that already had a query behind it. That worked and cost more than
     * it looked. The label was a second copy of a column every one of those rows already carried,
     * it shared a namespace with the tags people type — a workspace called `v2-tasks` and a
     * `#v2-tasks` tag are one row, which is unique on (workspace_id, name) — and being a label made
     * it look detachable, so tapping the chip it drew rewrote the file to remove a tag that was
     * never in it. It was also attached to tasks only, while the column is on lists and blocks too.
     *
     * Asking the column is the whole feature with none of that. Note this is a *filter*, distinct
     * from [FilterCompiler.compile]'s `workspaceId`, which fences a rule into the repo that wrote
     * it. This one is a clause a rule may state about itself, and composes: an unscoped Today can
     * say `Any(InWorkspace(a), InWorkspace(b))`, which a fence cannot express.
     */
    @Serializable
    @SerialName("in_workspace")
    data class InWorkspace(val workspaceId: String) : Filter
}

/**
 * Every workspace this rule names, so a view can tell whether it can actually answer itself.
 *
 * A smart list lives in Personal and its clauses may name any repo, which means a device that has
 * not added one of them will match nothing for it — and a Today quietly missing half your tasks is
 * worse than no Today. Nothing here can fix that; the point is to make it sayable.
 *
 * An empty result means the rule names no workspace at all, which is not a rule that spans none —
 * it is a rule that spans every repo this device has, and always answers fully.
 */
fun Filter.workspacesNamed(): Set<String> = buildSet { collectWorkspaces(this) }

private fun Filter.collectWorkspaces(into: MutableSet<String>) {
    when (this) {
        is Filter.InWorkspace -> into += workspaceId
        is Filter.All -> filters.forEach { it.collectWorkspaces(into) }
        is Filter.AnyOf -> filters.forEach { it.collectWorkspaces(into) }
        is Filter.Not -> filter.collectWorkspaces(into)
        // Deliberately exhaustive rather than an else: a new clause that can carry a workspace has
        // to come here too, and the compiler is the only thing that will remember to say so.
        is Filter.Done, is Filter.InProgress, is Filter.Type, is Filter.Prop, is Filter.HasLabel -> Unit
    }
}

@Serializable
enum class Op {
    @SerialName("eq") EQ,
    @SerialName("neq") NEQ,
    @SerialName("lt") LT,
    @SerialName("lte") LTE,
    @SerialName("gt") GT,
    @SerialName("gte") GTE,
    @SerialName("is_set") IS_SET,
    @SerialName("not_set") NOT_SET,
}

@Serializable
enum class DateRel {
    @SerialName("today_start") TODAY_START,
    @SerialName("today_end") TODAY_END,
}

@Serializable
data class SortSpec(
    val by: SortBy,
    val defId: String? = null,   // required for the PROP_* kinds
    val desc: Boolean = false,
    val nullsLast: Boolean = true,
)

@Serializable
enum class SortBy {
    @SerialName("prop_date") PROP_DATE,
    @SerialName("prop_number") PROP_NUMBER,
    @SerialName("prop_text") PROP_TEXT,
    @SerialName("title") TITLE,
    @SerialName("created") CREATED,
}

/**
 * One property value to auto-set on tasks created inside a smart list (write side).
 * [dateRel] defers date resolution to insert time ("due today" lists stamp the actual
 * today) — see SmartListRepository.addTask.
 */
@Serializable
data class ApplyOnCreate(
    val defId: String,
    val text: String? = null,
    val number: Double? = null,
    val date: Long? = null,
    val bool: Boolean? = null,
    val dateRel: DateRel? = null,
)

val FilterJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    classDiscriminator = "kind"
}

/**
 * Per the spec: equality clauses are writable and self-satisfying, so the write-side
 * apply_on_create values are derived straight from the filter's EQ clauses. Two extensions:
 *  - today-relative comparisons ("due today or earlier/later") are satisfied by Due = today,
 *    emitted as a deferred [ApplyOnCreate.dateRel] resolved at insert time;
 *  - an AnyOf is satisfied by satisfying one branch — the first derivable branch wins.
 */
fun deriveApplyOnCreate(filter: Filter): List<ApplyOnCreate> = when (filter) {
    is Filter.All -> filter.filters.flatMap { deriveApplyOnCreate(it) }
    is Filter.AnyOf -> filter.filters.firstNotNullOfOrNull { branch ->
        deriveApplyOnCreate(branch).takeIf { it.isNotEmpty() }
    } ?: emptyList()
    is Filter.Prop -> when {
        filter.op == Op.EQ && filter.dateRel == null -> listOf(
            ApplyOnCreate(
                defId = filter.defId,
                text = filter.text,
                number = filter.number,
                date = filter.date,
                bool = filter.bool,
            )
        )
        (filter.op == Op.LTE && filter.dateRel == DateRel.TODAY_END) ||
            (filter.op == Op.GTE && filter.dateRel == DateRel.TODAY_START) -> listOf(
            ApplyOnCreate(defId = filter.defId, bool = false, dateRel = DateRel.TODAY_START)
        )
        else -> emptyList()
    }
    else -> emptyList() // not/other comparisons can't be auto-matched -> read-mostly
}

/**
 * Rewrites an "open tasks" filter into its completed counterpart by flipping every
 * [Filter.Done] clause, so the same rules can be asked the opposite question: not "what is
 * still due today" but "what due today did I finish". Returns null when the tree has no Done
 * clause to flip — such a list already admits completed tasks, so there is no separate
 * completed set to fetch.
 */
fun completedVariant(filter: Filter): Filter? =
    // A list of started tasks has no completed half: finishing a task clears in_progress in the
    // same UPDATE, so flipping the done clause would ask for a set that cannot exist and render an
    // empty "DONE" heading under every such view.
    if (!hasDoneClause(filter) || asksForStarted(filter)) null else flipDone(filter)

private fun asksForStarted(f: Filter): Boolean = when (f) {
    is Filter.InProgress -> f.value
    is Filter.All -> f.filters.any { asksForStarted(it) }
    is Filter.AnyOf -> f.filters.any { asksForStarted(it) }
    is Filter.Not -> false
    else -> false
}

private fun hasDoneClause(f: Filter): Boolean = when (f) {
    is Filter.Done -> !f.value
    is Filter.All -> f.filters.any { hasDoneClause(it) }
    is Filter.AnyOf -> f.filters.any { hasDoneClause(it) }
    is Filter.Not -> hasDoneClause(f.filter)
    else -> false
}

private fun flipDone(f: Filter): Filter = when (f) {
    is Filter.Done -> Filter.Done(!f.value)
    is Filter.All -> Filter.All(f.filters.map { flipDone(it) })
    is Filter.AnyOf -> Filter.AnyOf(f.filters.map { flipDone(it) })
    is Filter.Not -> Filter.Not(flipDone(f.filter))
    else -> f
}
