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
 * Splits rows into to-do and done by a membership decided *earlier*, not now.
 *
 * **Why this is not `partition { it.done }`.** Ticking a box changed which half a row belonged to,
 * so the row left from under the finger that had just tapped it: on a smart list it jumped out of
 * where you were reading and reappeared under DONE, and the completion animation — a strike drawn
 * through the title — played somewhere you were no longer looking. The gesture said "this one is
 * finished"; the list answered by moving it, which is a different statement and a worse one,
 * because the row you were about to tick next moved too.
 *
 * So [settledDone] is the answer from when the list was built, and it is the only thing that
 * decides sections. A row ticked now strikes through where it stands and joins DONE the next time
 * the list is built — reopened, or navigated back to. Undoing is symmetric: a finished row
 * un-ticked stays under DONE, lit but in place, until the same moment.
 *
 * **Only membership is settled, never order.** Within a half the rows stay in the order the caller
 * supplied, so a drag still previews where it is dropped and a smart list's own sorting still
 * applies. Freezing the order here as well would have quietly overruled both.
 */
fun <T> settleSections(
    settledDone: Set<String>,
    live: List<T>,
    idOf: (T) -> String,
): Sections<T> {
    if (settledDone.isEmpty()) return Sections(live, emptyList())
    val todo = ArrayList<T>(live.size)
    val done = ArrayList<T>()
    live.forEach { if (idOf(it) in settledDone) done += it else todo += it }
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
): Sections<T> {
    // A plain holder rather than a MutableState: it is written once per key, read in the same
    // composition that writes it, and nothing should recompose *because* of it.
    val holder = androidx.compose.runtime.remember(key) { SettleHolder() }
    if (holder.doneIds == null && live.isNotEmpty()) {
        holder.doneIds = live.filter(isDone).mapTo(HashSet(), idOf)
    }
    return settleSections(holder.doneIds.orEmpty(), live, idOf)
}

private class SettleHolder {
    var doneIds: Set<String>? = null
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
