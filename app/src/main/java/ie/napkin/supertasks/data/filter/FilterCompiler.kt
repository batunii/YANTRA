package ie.napkin.supertasks.data.filter

import ie.napkin.supertasks.data.time.localDateOf
import ie.napkin.supertasks.data.time.endOfDay
import ie.napkin.supertasks.data.time.startOfDay

/** A compiled SELECT over node + property_value, ready for a Room @RawQuery. */
data class CompiledQuery(val sql: String, val args: List<Any>)

/**
 * Compiles a [Filter] tree + [SortSpec]s into the recursive-CTE query from the schema doc.
 * Property clauses become EXISTS subqueries on property_value, so any number of them
 * compose with AND / OR / NOT without join explosion.
 */
object FilterCompiler {

    fun compile(
        scopeRootId: String?,
        filter: Filter?,
        sort: List<SortSpec> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
    ): CompiledQuery {
        val args = mutableListOf<Any>()
        val sb = StringBuilder()

        if (scopeRootId != null) {
            sb.append(
                """
                WITH RECURSIVE subtree(id) AS (
                    SELECT id FROM node WHERE deleted_at IS NULL AND parent_id = ?
                  UNION ALL
                    SELECT n.id FROM node n JOIN subtree s ON n.parent_id = s.id
                     WHERE n.deleted_at IS NULL
                )
                SELECT n.* FROM node n JOIN subtree st ON st.id = n.id
                """.trimIndent()
            )
            args += scopeRootId
        } else {
            // whole workspace: drop the subtree join entirely
            sb.append("SELECT n.* FROM node n")
        }

        sb.append("\nWHERE n.deleted_at IS NULL")
        if (filter != null) {
            sb.append(" AND ")
            appendClause(sb, args, filter, nowMillis)
        }

        // Started first, always, before whatever the list sorts by. A task you are in the middle of
        // outranks one you have not opened — that is what saying "I am on this" was for, and a
        // marker that does not move the task up the page is only a decoration. It costs nothing on
        // lists where nothing is started, and a completed-tasks list can never have one.
        sb.append("\nORDER BY n.in_progress DESC, ")
        if (sort.isEmpty()) {
            sb.append("n.created_at DESC")
        } else {
            sort.forEachIndexed { i, s ->
                if (i > 0) sb.append(", ")
                appendSort(sb, args, s)
            }
        }

        return CompiledQuery(sb.toString(), args)
    }

    private fun appendClause(sb: StringBuilder, args: MutableList<Any>, f: Filter, now: Long) {
        when (f) {
            is Filter.All -> appendGroup(sb, args, f.filters, " AND ", now)
            is Filter.AnyOf -> appendGroup(sb, args, f.filters, " OR ", now)
            is Filter.Not -> {
                sb.append("NOT (")
                appendClause(sb, args, f.filter, now)
                sb.append(")")
            }
            is Filter.Done -> {
                sb.append("n.done = ?")
                args += if (f.value) 1L else 0L
            }
            is Filter.InProgress -> {
                sb.append("n.in_progress = ?")
                args += if (f.value) 1L else 0L
            }
            is Filter.Type -> {
                sb.append("n.type = ?")
                args += f.value
            }
            is Filter.Prop -> appendProp(sb, args, f, now)
            is Filter.HasLabel -> {
                sb.append("EXISTS (SELECT 1 FROM node_label nl WHERE nl.node_id = n.id AND nl.label_id = ?)")
                args += f.labelId
            }
        }
    }

    private fun appendGroup(
        sb: StringBuilder, args: MutableList<Any>, filters: List<Filter>, sep: String, now: Long,
    ) {
        if (filters.isEmpty()) {
            sb.append("1=1")
            return
        }
        sb.append("(")
        filters.forEachIndexed { i, f ->
            if (i > 0) sb.append(sep)
            appendClause(sb, args, f, now)
        }
        sb.append(")")
    }

    private fun appendProp(sb: StringBuilder, args: MutableList<Any>, f: Filter.Prop, now: Long) {
        when (f.op) {
            Op.IS_SET -> {
                sb.append("EXISTS (SELECT 1 FROM property_value pv WHERE pv.node_id = n.id AND pv.def_id = ?)")
                args += f.defId
                return
            }
            Op.NOT_SET -> {
                sb.append("NOT EXISTS (SELECT 1 FROM property_value pv WHERE pv.node_id = n.id AND pv.def_id = ?)")
                args += f.defId
                return
            }
            else -> Unit
        }

        val (column, value) = valueColumn(f, now)
        val cmp = when (f.op) {
            Op.EQ -> "="
            Op.NEQ -> "<>"
            Op.LT -> "<"
            Op.LTE -> "<="
            Op.GT -> ">"
            Op.GTE -> ">="
            else -> error("unreachable")
        }
        sb.append("EXISTS (SELECT 1 FROM property_value pv WHERE pv.node_id = n.id AND pv.def_id = ? AND pv.$column $cmp ?)")
        args += f.defId
        args += value
    }

    private fun valueColumn(f: Filter.Prop, now: Long): Pair<String, Any> = when {
        f.dateRel != null -> "v_date" to resolveDateRel(f.dateRel, now)
        f.text != null -> "v_text" to f.text
        f.number != null -> "v_number" to f.number
        f.date != null -> "v_date" to f.date
        f.bool != null -> "v_bool" to if (f.bool) 1L else 0L
        else -> error("prop filter for ${f.defId} has no value")
    }

    private fun resolveDateRel(rel: DateRel, now: Long): Long {
        val today = localDateOf(now)
        return when (rel) {
            DateRel.TODAY_START -> startOfDay(today)
            DateRel.TODAY_END -> endOfDay(today)
        }
    }

    private fun appendSort(sb: StringBuilder, args: MutableList<Any>, s: SortSpec) {
        val dir = if (s.desc) "DESC" else "ASC"
        when (s.by) {
            SortBy.TITLE -> sb.append("n.title COLLATE NOCASE $dir")
            SortBy.CREATED -> sb.append("n.created_at $dir")
            SortBy.PROP_DATE, SortBy.PROP_NUMBER, SortBy.PROP_TEXT -> {
                val column = when (s.by) {
                    SortBy.PROP_DATE -> "v_date"
                    SortBy.PROP_NUMBER -> "v_number"
                    else -> "v_text"
                }
                val defId = requireNotNull(s.defId) { "prop sort needs defId" }
                val sub = "(SELECT pv.$column FROM property_value pv WHERE pv.node_id = n.id AND pv.def_id = ?)"
                if (s.nullsLast) {
                    sb.append("$sub IS NULL, ")
                    args += defId
                }
                sb.append("$sub $dir")
                args += defId
            }
        }
    }
}
