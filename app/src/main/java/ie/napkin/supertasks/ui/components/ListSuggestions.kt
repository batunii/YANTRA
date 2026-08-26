package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.data.capture.CaptureParse

/**
 * The lists a half-typed `~` could mean, offered while there is still time to pick one.
 *
 * A `~` that names a list the workspace does not have now *makes* one, which is what lets someone
 * file into a new list without leaving capture. The risk that comes with it is the obvious one: a
 * typo is a new list, and `~Grocries` sitting next to `~Groceries` is a mess someone has to go and
 * tidy up. This is the answer to that. The lists you already have are shown as you type toward one,
 * so the common case is a tap rather than a spelling test, and a name that matches nothing says so
 * plainly — `New list "Camping"` — before it becomes real.
 *
 * Matching is [CaptureParse.listSuggestions], the same rule the parser resolves with, so the field
 * cannot offer something the line would not actually produce.
 *
 * Nothing is drawn unless a mark is being typed. A row that is merely empty still takes its space,
 * and the capture bar moving up and down as you type would be worse than no help at all.
 */
@Composable
fun ListSuggestions(
    text: String,
    lists: List<String>,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    val draft = remember(text) { CaptureParse.listDraft(text) } ?: return
    val (span, typed) = draft
    val matches = remember(typed, lists) { CaptureParse.listSuggestions(typed, lists) }

    // A name that is already exactly one of the lists needs no help; offering the thing that is
    // already typed is noise, and the tint has confirmed it landed.
    if (matches.size == 1 && matches.first().equals(typed, ignoreCase = true)) return
    if (matches.isEmpty() && typed.isBlank()) return

    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .horizontalFadingEdge()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (matches.isEmpty()) {
            NewListChip(typed) { onPick(text.trimEnd() + CaptureParse.LIST_MARK + " ") }
        } else {
            matches.forEach { name ->
                SelectChip(
                    label = name,
                    selected = false,
                    size = ChipSize.Small,
                    // No closing mark. A name that already exists ends itself — it is matched
                    // against the names there are, so the parser knows where it stops — and adding
                    // one would put a character in the user's line that changes nothing.
                    onClick = {
                        onPick(text.substring(0, span.first) + CaptureParse.LIST_MARK + name + " ")
                    },
                )
            }
        }
    }
}

/**
 * The list about to be made, and the way to say its name is finished.
 *
 * This has been all three things. It was a chip that did nothing, which was wrong because a chip in
 * a row of chips has promised a tap. Then a caption, which was honest but left the only way to end
 * a new name as a character you had to be told about. It is a chip again, and now it does the thing
 * that was missing all along.
 *
 * **Only a new name needs this.** One that already exists is matched against the names there are,
 * so the parser knows where it stops and the rest of the line is yours already. A new one has
 * nothing to be matched against, so without an end it runs to the end of the line — which made a
 * new list the last thing you could say. Tapping ends it. So does typing `~`, for anyone who never
 * takes their hands off the keyboard, and the two produce the same line.
 */
@Composable
private fun NewListChip(name: String, onEnd: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectChip(
            label = "New list \u201C$name\u201D",
            selected = false,
            size = ChipSize.Small,
            icon = Icons.Default.Add,
            onClick = onEnd,
        )
        Text(
            "tap to end the name",
            color = Yantra.colors.textDim,
            fontSize = 11.sp,
        )
    }
}
