package ie.napkin.supertasks.ui.node

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.data.people.Person
import ie.napkin.supertasks.ui.theme.Yantra

/**
 * Everything a screen needs in order to offer people, in one value.
 *
 * A composition local rather than four more parameters threaded through [PropertyRow] and
 * [PropertySheet]: both of them are generic over "a property def" and neither has any business
 * knowing what a GitHub roster is. The app already carries haptics and completion tempo this way.
 *
 * The default is an empty directory with no way to refresh, so every screen that has not thought
 * about people still renders — with the free-text field and nothing else, which is exactly right
 * for a surface with no workspace in hand.
 */
data class PeopleSource(
    val people: List<Person> = emptyList(),
    val refreshing: Boolean = false,
    /** What the last collaborator fetch turned out to be — worked or not. */
    val note: String? = null,
    /** Null when GitHub cannot be asked here — no linked repository, or no token for it. */
    val onRefresh: (() -> Unit)? = null,
)

val LocalPeople = compositionLocalOf { PeopleSource() }

/**
 * Who this task is for.
 *
 * A sheet rather than the generic text dialog the property row would otherwise give a text field,
 * for the same reason Due has one: the value is a login, and the set of logins that mean anything
 * is small, knowable and worth showing.
 *
 * ## Only people who can push here
 *
 * When the roster is known, this is a **closed list** and the field filters it. Free typing was the
 * first design and it was wrong: a task assigned to somebody outside the repository is a task
 * nobody will ever be shown, and the app has every fact it needs to know that at the moment of the
 * tap. The roster is fetched when the workspace is added and again whenever Collaborators is
 * pressed, so "we don't know who is on this repo" is not a state anyone should normally be in.
 *
 * ## Except when there is genuinely no roster
 *
 * There is one case where the closed list cannot be closed: GitHub answering 403/404 to the
 * collaborators call, which it does for a token that can push files but is not permitted to read a
 * repository's people. Refusing there would make the field permanently unusable on that repository
 * — the app would be enforcing a rule out of ignorance rather than knowledge. So when no roster
 * exists the field accepts a typed login and says plainly why it is having to.
 *
 * A name already on a task is always shown even when it is off the roster, because it is *there* —
 * a collaborator may have written it, or the person may have been removed since. Shown, marked, and
 * not offered again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssigneeSheet(
    current: String?,
    people: List<Person>,
    /** Null when GitHub cannot be asked here — no linked repository, or no token for it. */
    onRefresh: (() -> Unit)?,
    refreshing: Boolean,
    /** What the last fetch turned out to be. Says so whether it worked or not — see [People]. */
    refreshNote: String?,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val y = Yantra.colors
    var typed by remember { mutableStateOf("") }
    val query = typed.trim().removePrefix("@")
    val matches = remember(query, people) {
        if (query.isEmpty()) people
        else people.filter { it.login.contains(query, ignoreCase = true) }
    }
    // Whether anything here can distinguish "not on this repo" from "we never asked". One person
    // with a definite answer is enough — the roster is fetched for everybody at once.
    val rosterKnown = people.any { it.onRepo != null }
    // A typed name becomes assignable only where the app has no roster to check it against. With
    // one, this is a filter over people who can push here and nothing else.
    val novel = !rosterKnown && query.isNotEmpty() &&
        people.none { it.login.equals(query, ignoreCase = true) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Assign to", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (onRefresh != null) {
                    TextButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Collaborators")
                    }
                }
            }

            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = {
                    Text(if (rosterKnown) "Search collaborators…" else "Type a GitHub login…")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Said plainly and left there, at label weight. A roster that could not be fetched
            // costs nothing — everything below still works — so this is a note, not a failure to
            // dismiss; and a roster that *was* fetched needs saying too, or a repository with one
            // collaborator looks exactly like a request that never happened.
            refreshNote?.let {
                Text(it, fontSize = 12.sp, color = y.textMuted)
            }

            Column {
                matches.forEach { person ->
                    PersonRow(
                        person = person,
                        selected = person.login.equals(current, ignoreCase = true),
                        // Somebody off the roster is shown because they are on the task, not
                        // because they are a choice. Tapping them again would only re-make the
                        // assignment this sheet exists to warn about.
                        onClick = if (person.onRepo == false) null else ({ onPick(person.login) }),
                    )
                }
                if (novel) {
                    PersonRow(
                        person = Person(query),
                        selected = false,
                        onClick = { onPick(query) },
                    )
                }
                if (matches.isEmpty() && !novel) {
                    Text(
                        if (rosterKnown) {
                            "Nobody who can push to this repo matches \u201C$query\u201D. " +
                                "Add them on GitHub first, then press Collaborators."
                        } else {
                            "Nobody has been loaded for this repository yet — " +
                                "press Collaborators, or type a login above."
                        },
                        fontSize = 13.sp,
                        color = y.textMuted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            if (current != null) {
                TextButton(onClick = onClear) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * One candidate.
 *
 * A person GitHub has not listed is shown, and shown as assignable — the roster can be a day old,
 * an invitation can be in flight, and a picker that refuses to let you name someone real is worse
 * than one that lets you name someone unreachable. What it must not do is offer them *silently*,
 * which is what it did: everyone ever assigned in any workspace appeared here identically, so
 * handing a shared task to somebody with no access to the repository took one tap and said nothing.
 */
@Composable
private fun PersonRow(
    person: Person,
    selected: Boolean,
    /** Null makes the row a statement rather than a choice — see the call site. */
    onClick: (() -> Unit)?,
) {
    val y = Yantra.colors
    val stranger = person.onRepo == false
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(vertical = 10.dp),
    ) {
        val tint = if (stranger) y.textMuted else y.accent
        Box(
            Modifier.size(26.dp).background(tint.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (stranger) Icons.Default.PersonOff else Icons.Default.Person,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "@${person.login}",
            fontWeight = FontWeight.W600,
            color = if (stranger) y.textSecondary else y.textPrimary,
        )
        if (person.isYou) {
            Spacer(Modifier.width(8.dp))
            Text("you", fontSize = 11.5.sp, color = y.textMuted)
        }
        if (stranger) {
            Spacer(Modifier.width(8.dp))
            Text("can't see this repo", fontSize = 11.5.sp, color = y.warning)
        }
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = y.accent, modifier = Modifier.size(18.dp))
        }
    }
}
