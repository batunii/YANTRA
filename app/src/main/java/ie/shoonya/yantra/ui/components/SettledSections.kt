package ie.shoonya.yantra.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ie.shoonya.yantra.ui.theme.Yantra

/** A list split in two: what is still to do, and what is finished. */
data class Sections<T>(val todo: List<T>, val done: List<T>)

/**
 * Splits rows into to-do and done, holding a *completion* still but letting an *undo* go.
 *
 * **Ticking must not move the row.** It used to: on a smart list `tasks` and `completed` are two
 * queries, so the write landed and the row left from under the finger that had just tapped it —
 * down into DONE, where the strike animation then played somewhere you were no longer looking. The
 * gesture says "this one is finished"; answering by moving it is a different statement and a worse
 * one, because the row you were about to tick next moves as well. So [settledDone] — the answer
 * from when the list was built — decides who *may* be in the finished half, and a row ticked now
 * strikes through where it stands and joins DONE the next time the list is built.
 *
 * **Un-ticking is not symmetric, deliberately.** A row you un-tick is one you have just said is
 * still to do, and leaving it sitting under a heading that reads DONE states the opposite. Nothing
 * is disturbed by letting it go either: you are not working down the finished half the way you work
 * down the to-do half, so there is no place under your finger to lose. Hence [isDone] as well as
 * [settledDone] — the finished half is rows that were settled there *and* are still finished.
 *
 * **Only membership is decided here, never order.** Within a half the rows keep the order the
 * caller supplied, so a drag still previews where it will land and a smart list still floats what
 * is running to the top. Freezing order here would have quietly overruled both.
 */
fun <T> settleSections(
    settledDone: Set<String>,
    live: List<T>,
    idOf: (T) -> String,
    isDone: (T) -> Boolean,
): Sections<T> {
    if (settledDone.isEmpty()) return Sections(live, emptyList())
    val todo = ArrayList<T>(live.size)
    val done = ArrayList<T>()
    live.forEach { if (idOf(it) in settledDone && isDone(it)) done += it else todo += it }
    return Sections(todo, done)
}

/**
 * [settleSections], with the settling remembered for as long as this list is on screen.
 *
 * Keyed on [key] — the page or view being shown — so walking away and coming back settles again,
 * which is exactly when the reorder is wanted. The first composition that has anything to show
 * decides it; before then there is nothing to hold still.
 */
@Composable
fun <T> rememberSettledSections(
    key: Any?,
    live: List<T>,
    idOf: (T) -> String,
    isDone: (T) -> Boolean,
    /**
     * Whether this row can be judged yet.
     *
     * A row whose answer has not arrived is left unsettled rather than settled wrongly. An event's
     * end time comes from a different query than the row itself, so for one frame a finished
     * meeting looks exactly like an unfinished one — and settling on that frame would file it under
     * the wrong heading for the rest of the visit. This is the same race that put finished tasks in
     * the to-do half, one layer down, and the same answer: do not decide until there is something
     * to decide with.
     */
    canJudge: (T) -> Boolean = { true },
): Sections<T> {
    // A plain holder rather than a MutableState: it is written during composition, read in the same
    // composition, and nothing should recompose *because* of it.
    val holder = androidx.compose.runtime.remember(key) { SettleHolder() }
    // **Each row settles the first time it is seen, not all of them at once.**
    //
    // Settling the whole list on the first non-empty composition looked equivalent and was not. A
    // smart list reads its open tasks and its finished ones from two different queries, which
    // arrive separately: when the open half landed first, the list was non-empty, nothing in it was
    // done, and DONE was settled as empty — permanently, because the settling had happened. The
    // finished tasks then arrived and were drawn as things still to do, with no DONE heading at
    // all. It depended on which query answered first, so it happened *sometimes*, which is the
    // worst way for it to happen.
    //
    // Per row there is no race to lose: a task seen for the first time as finished belongs under
    // DONE whenever it turns up, and one seen first as open stays where it is when you tick it.
    live.forEach { row ->
        if (!canJudge(row)) return@forEach
        val id = idOf(row)
        if (holder.seen.add(id) && isDone(row)) holder.doneIds += id
    }
    return settleSections(holder.doneIds, live, idOf, isDone)
}

private class SettleHolder {
    /** Rows whose half has been decided. Membership is decided once, on first sight. */
    val seen = HashSet<String>()

    /** Of those, the ones that were already finished when they first appeared. */
    val doneIds = HashSet<String>()
}

/**
 * The header that opens and closes the finished half.
 *
 * Collapsed by default: what is left to do is the list, and what is finished is the receipt. The
 * count is on the header so the receipt does not have to be opened to be read.
 */
@Composable
fun DoneSectionHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onToggle)
            .padding(start = 4.dp, top = 22.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionLabel("DONE · $count")
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "Hide finished tasks" else "Show finished tasks",
            tint = Yantra.colors.textMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}
