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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
    /** Where the caret is, which is how this knows whether the name is still being typed. */
    caret: Int,
    lists: List<String>,
    modifier: Modifier = Modifier,
    onPick: (TextFieldValue) -> Unit,
) {
    val draft = remember(text) { CaptureParse.listDraft(text) } ?: return
    val (span, typed) = draft

    // Only while the caret is actually in the name. This is what settling means, and why settling
    // needs no closing mark to record it: once the caret has moved back in front of the token, the
    // name is finished by the plain fact that nobody is typing it. Without this the strip would sit
    // there for the rest of the line, offering to complete a name that was decided several words
    // ago.
    if (caret <= span.first) return

    val matches = remember(typed, lists) { CaptureParse.listSuggestions(typed, lists) }

    // A name that is already exactly one of the lists needs no help; offering the thing that is
    // already typed is noise, and the tint has confirmed it landed.
    if (matches.size == 1 && matches.first().equals(typed, ignoreCase = true)) return
    if (matches.isEmpty() && typed.isBlank()) return

    /**
     * Settles the destination and hands the caret back to the task.
     *
     * No closing mark. The mark was the wrong answer twice over: on a name that already exists it
     * changes nothing, because such a name is matched against the ones there are and ends itself;
     * and on a new one it left a character in the line that the person tapping had not typed.
     *
     * What ends a new name instead is *position* — it runs to the end of the line, so it ends by
     * being last. So the destination is left where it is and the caret is moved in front of it,
     * with a space either side. Whatever is typed next lands in the task, the list stays last, and
     * the line reads exactly as though it had been typed in that order.
     */
    fun settle(name: String): TextFieldValue {
        val head = text.substring(0, span.first).trimEnd()
        return TextFieldValue(
            text = "$head  ${CaptureParse.LIST_MARK}$name",
            selection = TextRange(head.length + 1),
        )
    }

    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .horizontalFadingEdge()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (matches.isEmpty()) {
            NewListChip(typed) { onPick(settle(typed)) }
        } else {
            matches.forEach { name ->
                SelectChip(
                    label = name,
                    selected = false,
                    size = ChipSize.Small,
                    onClick = { onPick(settle(name)) },
                )
            }
        }
    }
}

/**
 * The list about to be made, and the way to say its name is finished.
 *
 * **Only a new name needs this.** One that already exists is matched against the names there are,
 * so the parser knows where it stops and the rest of the line is yours already. A new one has
 * nothing to be matched against, so it runs to the end of the line — which would make a new list
 * the last thing you could say. Tapping settles it: the name stays where it is, at the end, and the
 * caret comes back to the task in front of it.
 *
 * It is a chip rather than a caption because it now does something. It was a caption for a while,
 * on the reasoning that there was nothing for a tap to do — which was true only because the thing a
 * tap should do had not been built.
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
            "tap to carry on with the task",
            color = Yantra.colors.textDim,
            fontSize = 11.sp,
        )
    }
}
