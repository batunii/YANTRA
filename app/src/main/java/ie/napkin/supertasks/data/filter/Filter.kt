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

/** One property value to auto-set on tasks created inside a smart list (write side). */
@Serializable
data class ApplyOnCreate(
    val defId: String,
    val text: String? = null,
    val number: Double? = null,
    val date: Long? = null,
    val bool: Boolean? = null,
)

val FilterJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    classDiscriminator = "kind"
}

/**
 * Per the spec: equality clauses are writable and self-satisfying, so the write-side
 * apply_on_create values are derived straight from the filter's EQ clauses.
 */
fun deriveApplyOnCreate(filter: Filter): List<ApplyOnCreate> = when (filter) {
    is Filter.All -> filter.filters.flatMap { deriveApplyOnCreate(it) }
    is Filter.Prop ->
        if (filter.op == Op.EQ && filter.dateRel == null) {
            listOf(
                ApplyOnCreate(
                    defId = filter.defId,
                    text = filter.text,
                    number = filter.number,
                    date = filter.date,
                    bool = filter.bool,
                )
            )
        } else emptyList()
    else -> emptyList() // any/not/comparisons can't be auto-matched -> read-mostly
}
