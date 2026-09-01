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
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.format.Links
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Person

/**
 * What the token you are half-way through typing could mean, offered while there is still time.
 *
 * A `~` that names a list the workspace does not have now *makes* one, which is what lets someone
 * file into a new list without leaving capture. The risk that comes with it is the obvious one: a
 * typo is a new list, and `~Grocries` sitting next to `~Groceries` is a mess someone has to go and
 * tidy up. This is the answer to that. The lists you already have are shown as you type toward one,
 * so the common case is a tap rather than a spelling test, and a name that matches nothing says so
 * plainly — `New list "Camping"` — before it becomes real.
 *
 * One strip for all three marks — `~` a list, `@` a person, `[[` another task — because only one
 * of them can be under the caret at a time, and three rows that each know about one would be three
 * chances for the bar to jump as you type.
 *
 * Matching is [CaptureParse]'s own, the same rule the parser resolves with, so the field cannot
 * offer something the line would not actually produce. `@` and `[[` are closed lists and `~` is
 * not: you may invent a list by naming it, and you may invent neither a colleague nor a task.
 *
 * Nothing is drawn unless a mark is being typed. A row that is merely empty still takes its space,
 * and the capture bar moving up and down as you type would be worse than no help at all.
 */
@Composable
fun CaptureSuggestions(
    text: String,
    /** Where the caret is, which is how this knows whether the name is still being typed. */
    caret: Int,
    lists: List<String> = emptyList(),
    people: List<String> = emptyList(),
    /** Tasks matching the `[[` being typed. Searched by the caller — this file runs no queries. */
    tasks: List<NodeEntity> = emptyList(),
    modifier: Modifier = Modifier,
    onPick: (TextFieldValue) -> Unit,
) {
    // Most specific mark first. A `[[` draft and a `~` draft cannot both hold the caret, but the
    // order still has to be written down: `Links.draft` and `assigneeDraft` are both satisfied by a
    // caret at the end of the line, and whichever is asked first decides what is being typed.
    Links.draft(text, caret)?.let { (span, typed) ->
        LinkStrip(text, span, typed, tasks, modifier, onPick)
        return
    }
    CaptureParse.assigneeDraft(text, caret)?.let { (span, typed) ->
        PeopleStrip(text, span, typed, people, modifier, onPick)
        return
    }
    ListStrip(text, caret, lists, modifier, onPick)
}

/**
 * Somebody to hand it to.
 *
 * Nothing to offer means nothing is drawn, and there is deliberately no "assign anyway" row: a name
 * outside this list is left in the title by the parser, which is the closed-list rule the assignee
 * sheet enforces, said the same way in the other place a name can be typed.
 */
@Composable
private fun PeopleStrip(
    text: String,
    span: IntRange,
    typed: String,
    people: List<String>,
    modifier: Modifier,
    onPick: (TextFieldValue) -> Unit,
) {
    val matches = remember(typed, people) { CaptureParse.peopleSuggestions(typed, people) }
    if (matches.isEmpty()) return
    if (matches.size == 1 && matches.first().equals(typed, ignoreCase = true)) return

    Strip(modifier) {
        matches.forEach { login ->
            SelectChip(
                label = "@$login",
                selected = false,
                size = ChipSize.Small,
                icon = Icons.Default.Person,
                onClick = { onPick(settle(text, span, "@$login")) },
            )
        }
    }
}

/** Another task to point at. Closed for the reason `NodeDao.searchLinkTargets` gives. */
@Composable
private fun LinkStrip(
    text: String,
    span: IntRange,
    typed: String,
    tasks: List<NodeEntity>,
    modifier: Modifier,
    onPick: (TextFieldValue) -> Unit,
) {
    Strip(modifier) {
        if (tasks.isEmpty()) {
            Text(
                if (typed.isBlank()) "Type to find a task to link"
                else "No task called \u201C$typed\u201D",
                color = Yantra.colors.textDim,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            tasks.forEach { task ->
                val title = task.title?.takeIf { it.isNotBlank() } ?: "Untitled"
                SelectChip(
                    label = title,
                    selected = false,
                    size = ChipSize.Small,
                    icon = Icons.Default.CheckCircleOutline,
                    onClick = { onPick(settle(text, span, Links.encode(title, task.id))) },
                )
            }
        }
    }
}

/**
 * Replaces the token being typed and puts the caret after it, with a space to carry on into.
 *
 * Unlike a list name, neither of these needs a closing mark or a trailing position: a login ends at
 * a space and a link ends at its brackets, so the rest of the line is yours again immediately.
 */
private fun settle(text: String, span: IntRange, written: String): TextFieldValue {
    val next = text.replaceRange(span, "$written ")
    return TextFieldValue(next, TextRange(span.first + written.length + 1))
}

@Composable
private fun ListStrip(
    text: String,
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
    fun settleList(name: String): TextFieldValue {
        val head = text.substring(0, span.first).trimEnd()
        return TextFieldValue(
            text = "$head  ${CaptureParse.LIST_MARK}$name",
            selection = TextRange(head.length + 1),
        )
    }

    Strip(modifier) {
        if (matches.isEmpty()) {
            NewListChip(typed) { onPick(settleList(typed)) }
        } else {
            matches.forEach { name ->
                SelectChip(
                    label = name,
                    selected = false,
                    size = ChipSize.Small,
                    onClick = { onPick(settleList(name)) },
                )
            }
        }
    }
}

/** The one row every strip is drawn in, so they cannot disagree about spacing or the fade. */
@Composable
private fun Strip(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .horizontalFadingEdge()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
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
