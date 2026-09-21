package ie.shoonya.yantra.data.filter

import ie.shoonya.yantra.data.db.BuiltIns

/**
 * What a row is allowed to say about a task, given where the task is being looked at.
 *
 * A field earns its place on a row only when the view has not already said it. "Due today" on a
 * row in Today is a sentence that ends where it began; the same words on a list page are the one
 * thing the row is scanned for. So a field's importance is not a property of the field — it is a
 * property of the *view*, and the view is a [Filter]. This is the same walk [workspacesNamed] does,
 * asked a different question: for each thing a row could carry, how much of it does the rule
 * already fix?
 *
 * The answer is a [Weight] per [Field], and [RowGrammar] is what a row does with it.
 */
sealed interface Field {
    /** A typed property: due, deadline, assignee, or a user def. */
    data class Prop(val defId: String) : Field
    /** One label. Pinning `#work` drops `#work` from the row and leaves every other tag alone. */
    data class Label(val labelId: String) : Field
    /** The list the task lives in. */
    data object OriginList : Field
    /** The repository the task came from. */
    data object Workspace : Field
}

/**
 * How much the view has already said about a field, least informative first.
 *
 * - [Pinned]: every task here has the same value (an `EQ`, an `IS_SET`, a label, a workspace,
 *   or the page the list *is*). Saying it on the row is saying it twice.
 * - [Bounded]: every task is inside a band (`LTE today`) but the value still varies inside it —
 *   "5 Aug" and "today" are both due-today-or-earlier and are not the same news. Shown, demoted.
 * - [Branched]: the field is one arm of an `AnyOf`. A task is here *because* of one branch, and
 *   which one is exactly what the reader wants to know. Promoted above everything.
 * - [Free]: the rule never mentions it. Ordinary metadata, ranked by the default order.
 */
enum class Weight { Pinned, Bounded, Branched, Free }

/**
 * Where a row is being read.
 *
 * [filter] is null for a plain list page, which has no rule — its rule is "children of this page",
 * and that pins [Field.OriginList] by construction. [singleWorkspace] says whether this device has
 * only one repository open; when it does, the workspace distinguishes nothing and is pinned no
 * matter what the rule says, which is the rule the spine already follows on a smart list.
 */
data class ViewContext(
    val filter: Filter?,
    val singleWorkspace: Boolean,
)

/**
 * The row's grammar for one view: what rides the title slot, what the sub-line says and in what
 * order, and what the spine means. Every entry is a [Field]; the row resolves each to a chip.
 *
 * [subLine] is ordered most-to-least important and the row ellipsises from the tail, so the order
 * is the budget. [pinned] is what the engine chose *not* to say, kept so an override can put it
 * back and so a debug view can show why a row is quiet.
 */
data class RowGrammar(
    val titleSlot: Field?,
    val subLine: List<Field>,
    val spine: Field?,
    val pinned: Set<Field>,
    /**
     * What the rule branched on, whether or not it reached [subLine].
     *
     * Labels never reach [subLine] — they are many, and the row already holds the whole set — so
     * this is how a row learns which tag is the *reason* it is on this screen. It is an ordering
     * fact, not an ink one: the matched tag leads the tag run and is drawn like every other tag.
     */
    val branched: Set<Field>,
)

object Salience {

    /**
     * The default rank for a field the rule leaves alone — what a row carries when nothing is
     * telling it otherwise. Due first because it is what a row is scanned for; the list and the
     * repository last because they are where a task *is*, not what it *needs*.
     */
    private val defaultOrder: List<Field> = listOf(
        Field.Prop(BuiltIns.DUE_DEF_ID),
        Field.Prop(BuiltIns.DEADLINE_DEF_ID),
        Field.Prop(BuiltIns.ASSIGNEE_DEF_ID),
        Field.OriginList,
        Field.Workspace,
    )

    /** The weight of every field the rule mentions; anything absent is [Weight.Free]. */
    fun weigh(context: ViewContext): Map<Field, Weight> {
        val out = mutableMapOf<Field, Weight>()
        context.filter?.let { walk(it, inAny = false, negated = false, out) }
        // A list page pins its own list; a rule does not name the list it reads from.
        if (context.filter == null) out[Field.OriginList] = Weight.Pinned
        // A device fact outranks what the rule hoped for: with one repository open, `ws A OR ws B`
        // can only ever match one of them, so the workspace distinguishes nothing and the spine is
        // absent. This overwrites a Branched weight deliberately.
        if (context.singleWorkspace) out[Field.Workspace] = Weight.Pinned
        return out
    }

    /**
     * Turn weights into a row. Labels are not in [defaultOrder] — they are many and the rule may
     * pin some of them — so the row appends the unpinned ones itself, after the properties and
     * before the list; this decides only what the rule has an opinion on.
     */
    fun grammar(context: ViewContext): RowGrammar {
        val weights = weigh(context)
        fun w(f: Field) = weights[f] ?: Weight.Free

        val pinned = weights.filterValues { it == Weight.Pinned }.keys
        val candidates = (defaultOrder + weights.keys.filter { it is Field.Prop })
            .distinct()
            .filter { w(it) != Weight.Pinned }
        // Branched leads, then free in default order, then bounded — a bounded value is still
        // news but the least of it, because the reader already knows the band it sits in.
        val ranked = candidates.sortedWith(
            compareBy<Field> { rank(w(it)) }.thenBy { defaultOrder.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        )

        // The title slot takes the first date, if there is one worth saying; a slot that carried
        // an assignee would read as a title. The spine is the workspace whenever it varies.
        val titleSlot = ranked.firstOrNull { it.isDate() }
        val spine = Field.Workspace.takeIf { w(Field.Workspace) != Weight.Pinned }
        val subLine = ranked.filter { it != titleSlot && it != spine }
        val branched = weights.filterValues { it == Weight.Branched }.keys
        return RowGrammar(titleSlot, subLine, spine, pinned, branched)
    }

    private fun rank(w: Weight) = when (w) {
        Weight.Branched -> 0
        Weight.Free -> 1
        Weight.Bounded -> 2
        Weight.Pinned -> 3
    }

    private fun Field.isDate() =
        this == Field.Prop(BuiltIns.DUE_DEF_ID) || this == Field.Prop(BuiltIns.DEADLINE_DEF_ID)

    private fun walk(f: Filter, inAny: Boolean, negated: Boolean, out: MutableMap<Field, Weight>) {
        when (f) {
            // De Morgan, pushed down as we walk: under a negation an All is an AnyOf and an AnyOf
            // is an All. Without it `Not(AnyOf(due ≤ today, deadline ≤ today))` promotes both dates
            // to the front of the row as the reason it is there, when they are the reason it is not.
            //
            // A one-armed AnyOf is an All wearing a costume, either way round.
            is Filter.All ->
                f.filters.forEach { walk(it, inAny || (negated && f.filters.size > 1), negated, out) }
            is Filter.AnyOf ->
                f.filters.forEach { walk(it, inAny || (!negated && f.filters.size > 1), negated, out) }
            is Filter.Not -> walk(f.filter, inAny, !negated, out)
            // Priority joins done, in-progress and type: it is drawn as the enclosure around the
            // task glyph, never as a word on the meta line, so the meta line has no opinion to
            // form about it. Without this the row would be told to stay quiet about a priority the
            // checkbox is shouting — the duplication this engine exists to delete.
            is Filter.Prop ->
                if (f.defId != BuiltIns.PRIORITY_DEF_ID) {
                    put(out, Field.Prop(f.defId), weightOf(f.op, inAny, negated))
                }
            is Filter.HasLabel -> put(
                out,
                Field.Label(f.labelId),
                when {
                    // A tag no matching row need carry cannot be the reason one is here.
                    inAny && negated -> Weight.Bounded
                    inAny -> Weight.Branched
                    // On every row, or on none: either way there is nothing to draw.
                    else -> Weight.Pinned
                },
            )
            is Filter.InWorkspace -> put(
                out,
                Field.Workspace,
                when {
                    inAny && negated -> Weight.Bounded
                    inAny -> Weight.Branched
                    // Excluding one repository leaves the others, so the workspace still varies
                    // and still earns its spine.
                    negated -> Weight.Bounded
                    else -> Weight.Pinned
                },
            )
            // Done, in-progress and type are the glyph's business, not the meta line's.
            is Filter.Done, is Filter.InProgress, is Filter.Type -> Unit
        }
    }

    private fun weightOf(op: Op, inAny: Boolean, negated: Boolean): Weight = when {
        // A negated clause under a branch fixes nothing at all: the row may be here for another
        // arm, and this arm only says what the value is *not*. It never pins and never leads.
        inAny && negated -> Weight.Bounded
        inAny -> Weight.Branched
        // NOT(is_set) pins absence: nothing to draw. NOT(EQ x) only excludes one value, so the
        // rest still vary — treat as bounded. Negated ranges likewise.
        negated -> if (op == Op.IS_SET) Weight.Pinned else Weight.Bounded
        op == Op.EQ || op == Op.IS_SET || op == Op.NOT_SET -> Weight.Pinned
        else -> Weight.Bounded
    }

    /** A field mentioned twice keeps its *more* informative weight: one OR arm outranks a pin. */
    private fun put(out: MutableMap<Field, Weight>, f: Field, w: Weight) {
        val prev = out[f]
        if (prev == null || rank(w) < rank(prev)) out[f] = w
    }
}
