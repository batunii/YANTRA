package ie.shoonya.yantra.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import ie.shoonya.yantra.data.db.BuiltIns
import ie.shoonya.yantra.data.filter.Field
import ie.shoonya.yantra.data.filter.RowGrammar
import ie.shoonya.yantra.data.filter.Salience
import ie.shoonya.yantra.data.filter.ViewContext
import ie.shoonya.yantra.data.sync.Credentials
import ie.shoonya.yantra.ui.smart.Origin

/**
 * What the reader already knows, so a row can stop saying it.
 *
 * Deliberately two fields. Three more were specified and each turned out to be a signal something
 * cheaper already carries:
 *
 *  - **no `today`.** The chip builder has already decided that — [ChipStatus.Due] *is* due-today
 *    and [ChipStatus.Warn] is a deadline landing today — so a second clock here would be a second
 *    thing to go stale over midnight.
 *  - **no `hereWorkspace`.** Whether a spine can be painted at all is `origin.workspaceHue`, which
 *    the data already carries and which is absent below two open repositories.
 *  - **no `sharedWorkspace`.** See [planRow]: an absence is drawn where the *view's question* is
 *    the absence, never because a repository happens to have collaborators. The specified signal
 *    was "more than one distinct assignee across the visible set", which makes one row's content
 *    depend on the other rows — a ragged pile moved from height into text.
 *  - **no `hereList`.** Two things already guarantee it and this would have been a third that
 *    disagreed with both: a list page has no rule, so [Field.OriginList] is Pinned by
 *    construction; and a node page passes no `origin` at all, so there is no name to print. The
 *    specified version compared a node **id** against a list **name** and could never have
 *    matched. If a scoped smart list ever needs this, model the scope in `ViewContext` instead.
 */
data class Expected(
    /** The viewer's login per workspace id, plus [Credentials.ACCOUNT] as the fallback. */
    val logins: Map<String, String?>,
) {
    /**
     * Who the reader is, in the repository this row came from.
     *
     * Asked per row rather than per view, because a smart list draws from every open repository at
     * once and a person may be a different login in each.
     */
    fun me(workspaceId: String): String? =
        logins[workspaceId]?.takeIf { it.isNotBlank() }
            ?: logins[Credentials.ACCOUNT]?.takeIf { it.isNotBlank() }

    companion object {
        val NONE = Expected(emptyMap())
    }
}

/** The grammar of the view a row is being read in, and what its reader already knows. */
data class RowContext(val grammar: RowGrammar, val expected: Expected)

/**
 * Static, because the grammar changes per *view* and never per row.
 *
 * The default is the grammar of a plain single-repository page — so a row composed somewhere
 * nobody thought about says exactly what it has always said, rather than nothing.
 */
val LocalRowContext = staticCompositionLocalOf {
    RowContext(Salience.grammar(ViewContext(null, singleWorkspace = true)), Expected.NONE)
}

/** The list name is the one run that is not a chip: it keeps its own hue, unlerped, as today. */
data class PlaceRun(val text: String, val hue: Long?)

/**
 * What a row says, decided in one place and drawn without a single field name at the draw site.
 *
 * [consumed] is every chip the title slot has taken — **including one it took and then silenced** —
 * so a date can appear in exactly one place by construction, rather than by somebody remembering
 * to filter it out of the line below.
 */
data class MetaPlan(
    val slot: ChipData?,
    val consumed: Set<ChipData>,
    val tags: List<ChipData>,
    val props: List<ChipData>,
    val unassigned: Boolean,
    val place: PlaceRun?,
)

/** Two characters for a field with its name missing. The word "unassigned" is refused at any width. */
const val UNASSIGNED = "@?"

private val DUE = Field.Prop(BuiltIns.DUE_DEF_ID)
private val DEADLINE = Field.Prop(BuiltIns.DEADLINE_DEF_ID)
private val ASSIGNEE = Field.Prop(BuiltIns.ASSIGNEE_DEF_ID)

private fun isDate(f: Field) = f == DUE || f == DEADLINE

/**
 * The chip a field is, or null when the field is not something a chip can be.
 *
 * Branch on `isLabel` **first**: a label chip carries the *label's* id in `defId`, so a user
 * property whose id happened to match a label id would otherwise resolve to the wrong thing.
 */
fun resolve(field: Field, chips: List<ChipData>, origin: Origin?): ChipData? = when (field) {
    is Field.Label -> chips.firstOrNull { it.isLabel && it.defId == field.labelId }
    is Field.Prop -> chips.firstOrNull { !it.isLabel && it.defId == field.defId }
    // Resolved by the caller as a [PlaceRun] instead: the list keeps `LabelPalette.display`
    // untouched, where a chip's ink is lerped toward the paper. Two inks for one hue is exactly
    // the drift the colour law exists to prevent.
    Field.OriginList -> null
    // **The workspace is a hue, not a word** — DESIGN.md §6. Structurally, not conditionally, so a
    // future caller cannot talk itself into printing a repository name on a row.
    Field.Workspace -> null
}

/** The title slot prints the date, not the commentary: the row's ink already says it is late. */
fun slotText(chip: ChipData): String = chip.label.removeSuffix(" · overdue")

/**
 * What this row may say, given where it is being read.
 *
 * **Overrides beat expected beats view weight**, and the override list is two: a due or deadline
 * that is already overdue, and a deadline landing today. Both are the world asking about
 * something, which no view's rule can make uninteresting. Adding a third needs a written reason
 * here — the point of a short list is that it stays short.
 */
fun planRow(
    grammar: RowGrammar,
    expected: Expected,
    chips: List<ChipData>,
    origin: Origin?,
    workspaceId: String,
    done: Boolean,
    timing: Boolean,
): MetaPlan {
    // A struck row carrying a crimson date is the world asking about something already answered.
    val alert = if (done) null else chips.firstOrNull { alertDate(it) }

    // The grammar names a field; a task carries a value. On Today the grammar names `due`, and a
    // task that is here for its *deadline* has no due date at all — so the slot falls through to
    // the next date that actually resolves. Filtered on resolution only: an absent value promotes
    // the next candidate, an uninteresting one does not, because those are different facts.
    val candidates = (listOfNotNull(grammar.titleSlot) + grammar.subLine.filter(::isDate)).distinct()
    val chosen = candidates.firstNotNullOfOrNull { resolve(it, chips, origin) }
    val slot = alert ?: chosen?.takeUnless { expectedDate(grammar, it) }

    // What the slot actually took, and only that.
    //
    // Two different reasons a date leaves the sub-line, and they are not the same reason:
    //
    //  - the slot **drew** it, or **silenced** it because the view had already said it. Either
    //    way it is spoken for, and printing it below would be printing it twice.
    //  - an **override** displaced it. Then it was never drawn and never silenced, and it belongs
    //    on the line below like any other field.
    //
    // Consuming both cost a real row its deadline: in Today an overdue task took the slot with its
    // due date, and the deadline the slot would otherwise have carried disappeared from the row
    // entirely. The same task on its own list page showed both, which is what made it visible.
    val consumed = if (alert != null) setOf(alert) else setOfNotNull(chosen)

    // Tags lead the line, and the matched one leads the tags — by position only. No ink change, no
    // weight, no marker glyph: a task must not change *shape* between two screens, only content.
    val tags = chips
        .filter { it.isLabel && Field.Label(it.defId) !in grammar.pinned }
        .sortedByDescending { Field.Label(it.defId) in grammar.branched }

    // The slot's own field is a candidate down here too. It is not usually one — the slot took it
    // — but an override can displace it, and then it has to land somewhere. Without this a real
    // row lost its deadline: the rule ranked the deadline into the slot, the overdue due date
    // displaced it, and `subLine` had never heard of it.
    val props = (listOfNotNull(grammar.titleSlot) + grammar.subLine)
        .filterIsInstance<Field.Prop>()
        .distinct()
        .mapNotNull { resolve(it, chips, origin) }
        .filterNot { it in consumed || silenced(it, workspaceId, expected, done) }

    // The absence, drawn almost nowhere: only where the view's question *is* who has this. On
    // every other screen an unclaimed task says nothing at all, and reserves no room for saying it.
    val unassigned = !done &&
        ASSIGNEE in grammar.branched &&
        chips.none { !it.isLabel && it.defId == BuiltIns.ASSIGNEE_DEF_ID }

    // Always last: the least urgent thing on the line, and so the one that should pay first when
    // the line runs out of room. A list page never reaches here — its rule pins the list — so this
    // needs no second test for "the page you are standing in".
    val place = origin?.list
        ?.takeIf { Field.OriginList in grammar.subLine }
        ?.let { PlaceRun(it, origin.listHue) }

    return MetaPlan(
        // Two rows draw no slot at all, and the date is *consumed* either way so it cannot fall
        // through to the line below:
        //
        //  - a **timed** row, whose trailing slot is already a clock — two answers to "when" is
        //    one too many;
        //  - a **finished** row, which has no date to meet. Dropping only the override would have
        //    left the ordinary path printing the same crimson `18 Sep` through a strikethrough,
        //    which is the world asking about something already answered.
        slot = if (timing || done) null else slot,
        consumed = consumed,
        tags = tags,
        props = props,
        unassigned = unassigned,
        place = place,
    )
}

/** The two overrides, and the only crimson-or-amber the slot may take. */
private fun alertDate(chip: ChipData): Boolean = when {
    chip.isLabel -> false
    chip.defId == BuiltIns.DUE_DEF_ID -> chip.status == ChipStatus.Overdue
    // Warn on a deadline is the chip's existing encoding of "lands today".
    chip.defId == BuiltIns.DEADLINE_DEF_ID ->
        chip.status == ChipStatus.Overdue || chip.status == ChipStatus.Warn
    else -> false
}

/**
 * Whether the view has already said this date.
 *
 * Both halves are needed. The rule has to have *named* the field — on a plain list page every date
 * is Free and nothing is ever silenced — and the value has to be the one the rule implies, which
 * the chip has already worked out for itself.
 */
private fun expectedDate(grammar: RowGrammar, chip: ChipData): Boolean {
    val field = Field.Prop(chip.defId)
    val named = field in grammar.subLine || (field == grammar.titleSlot && field in grammar.pinned)
    if (!named && field !in grammar.branched) return false
    return when (chip.defId) {
        BuiltIns.DUE_DEF_ID -> chip.status == ChipStatus.Due
        BuiltIns.DEADLINE_DEF_ID -> chip.status == ChipStatus.Warn
        else -> false
    }
}

/** My own name on my own task is the reader's name, and the reader knows it. */
private fun silenced(
    chip: ChipData,
    workspaceId: String,
    expected: Expected,
    done: Boolean,
): Boolean {
    if (chip.defId != BuiltIns.ASSIGNEE_DEF_ID) return false
    if (done) return true
    val me = expected.me(workspaceId) ?: return false
    return chip.raw?.equals(me, ignoreCase = true) == true
}

/**
 * The only place a spine's hue is decided.
 *
 * Both gates, or a third surface will draw a stripe off a workspace hue alone — which is how the
 * smart list and the list widget each grew their own answer to the same question. `grammar.spine`
 * is the engine's opinion; `origin.workspaceHue` is whether it can be painted at all, and it is
 * absent below two open repositories and on every node page, which is why a plain list page has no
 * spine without anything having to say so.
 */
fun spineHue(grammar: RowGrammar, origin: Origin?): Long? =
    origin?.workspaceHue?.takeIf { grammar.spine != null }
