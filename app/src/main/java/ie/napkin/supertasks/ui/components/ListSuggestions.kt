package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraText
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
            // Said, not offered. There is nothing here to choose: carrying on typing is already how
            // this list gets made, and the only honest thing left to do is name it back.
            NewListCaption(typed)
        } else {
            matches.forEach { name ->
                SelectChip(
                    label = name,
                    selected = false,
                    size = ChipSize.Small,
                    onClick = { onPick(text.substring(0, span.first) + CaptureParse.LIST_MARK + name) },
                )
            }
        }
    }
}

/**
 * What is about to be made, stated rather than offered.
 *
 * **Deliberately not a chip.** It was one — same fill, same border, same corner radius as the
 * suggestions beside it — and it was tapped, because that is what a pill in a row of tappable pills
 * means. Nothing happened, because there was nothing for it to do. A control that looks like a
 * control has promised something, and "it is only a label" is not a defence the person tapping it
 * can hear.
 *
 * So it is typeset as a caption instead: no fill, no border, dimmed, sitting beside the field rather
 * than in front of it. And it says where the name ends, because that is the one thing that is not
 * obvious — everything after the mark belongs to the name, so the list has to be the last thing on
 * the line, and there is no way to see that from the tinting alone.
 */
@Composable
private fun NewListCaption(name: String) {
    val y = Yantra.colors
    Row(
        Modifier.padding(start = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = y.textDim,
            modifier = Modifier.size(12.dp),
        )
        Text(
            "New list \u201C$name\u201D",
            color = y.textMuted,
            fontFamily = YantraText,
            fontWeight = FontWeight.W600,
            fontSize = 12.sp,
        )
        Text(
            "— the name runs to the end of the line",
            color = y.textDim,
            fontSize = 11.sp,
        )
    }
}
