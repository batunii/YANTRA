package ie.napkin.supertasks.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import ie.napkin.supertasks.AddResult
import ie.napkin.supertasks.AppContainer
import ie.napkin.supertasks.data.sync.Credentials
import ie.napkin.supertasks.data.sync.RepoCreate
import ie.napkin.supertasks.data.sync.RepoRef
import ie.napkin.supertasks.ui.appContainer
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.SelectChip
import ie.napkin.supertasks.ui.components.YantraButton
import ie.napkin.supertasks.ui.components.YantraField
import ie.napkin.supertasks.ui.theme.Yantra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adding a workspace.
 *
 * The two things people arrive wanting are genuinely different operations, so they are two modes
 * rather than one field that tries to tell them apart. Joining a repository that already exists is
 * the common case — someone sent you a link, or it is your own project — and creating one is how a
 * shared list starts.
 *
 * What makes this safe to point at a working codebase is the branch. Tasks are committed to
 * `yantra-tasks`, which shares no history with anything else in the repository: the code is never
 * downloaded, never touched, and never appears in a diff beside a checkbox. That is worth saying on
 * the screen, because "let an app into my repo" is a reasonable thing to hesitate over.
 */
@Composable
fun AddWorkspaceScreen(nav: NavHostController) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val y = Yantra.colors

    val account = remember { container.credentials.login(Credentials.ACCOUNT) }
    var existing by remember { mutableStateOf(true) }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var invitees by remember { mutableStateOf("") }
    var ownToken by remember { mutableStateOf(account == null) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(false) }

    val effectiveToken = if (ownToken) token.trim() else ""
    val ready = when {
        busy -> false
        ownToken && effectiveToken.isBlank() -> false
        existing -> RepoRef.parse(url) != null
        else -> name.isNotBlank()
    }

    Column(Modifier.fillMaxSize().background(y.page).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavCircle(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Back",
                onClick = { nav.popBackStack() },
                iconSize = 20.dp,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Add a workspace",
                style = MaterialTheme.typography.headlineSmall,
                color = y.textPrimary,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 40.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SelectChip(
                    "Existing repo", existing,
                    modifier = Modifier.weight(1f), stretch = true,
                    onClick = { existing = true; note = null },
                )
                SelectChip(
                    "New shared repo", !existing,
                    modifier = Modifier.weight(1f), stretch = true,
                    onClick = { existing = false; note = null },
                )
            }

            Spacer(Modifier.height(24.dp))
            if (existing) {
                SectionLabel("Repository")
                Spacer(Modifier.height(2.dp))
                Text(
                    "Paste the address, or type owner/name",
                    color = y.textMuted,
                    fontSize = 12.5.sp,
                )
                Spacer(Modifier.height(12.dp))
                YantraField(url, { url = it; note = null }, "github.com/you/project", mono = true)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Tasks are kept on a branch called yantra-tasks. Your code is never downloaded "
                        + "and never changed — the two never share a commit.",
                    color = y.textDim,
                    fontSize = 11.5.sp,
                )
            } else {
                SectionLabel("Name")
                Spacer(Modifier.height(2.dp))
                Text("A new private repository, for tasks only", color = y.textMuted, fontSize = 12.5.sp)
                Spacer(Modifier.height(12.dp))
                YantraField(name, { name = it; note = null }, "team-tasks", mono = true)

                Spacer(Modifier.height(20.dp))
                SectionLabel("Invite")
                Spacer(Modifier.height(2.dp))
                Text(
                    "GitHub usernames, separated by commas. They can add and finish tasks once they "
                        + "accept.",
                    color = y.textMuted,
                    fontSize = 12.5.sp,
                )
                Spacer(Modifier.height(12.dp))
                YantraField(invitees, { invitees = it }, "optional", mono = true)
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Access")
            Spacer(Modifier.height(2.dp))
            if (!ownToken && account != null) {
                Text("Using your GitHub account, $account", color = y.textMuted, fontSize = 12.5.sp)
                Spacer(Modifier.height(8.dp))
                Link("Use a different token") { ownToken = true }
            } else {
                Text(
                    if (existing)
                        "A fine-grained token with Contents: read and write on that repository"
                    else
                        "A token allowed to create repositories for your account",
                    color = y.textMuted,
                    fontSize = 12.5.sp,
                )
                Spacer(Modifier.height(12.dp))
                YantraField(token, { token = it; note = null }, "github_pat_…", secret = true)
                if (account != null) {
                    Spacer(Modifier.height(8.dp))
                    Link("Use my account, $account, instead") { ownToken = false; token = "" }
                }
            }

            Spacer(Modifier.height(24.dp))
            YantraButton(
                label = if (existing) "Add workspace" else "Create and share",
                busy = busy,
                enabled = ready,
                onClick = {
                    busy = true
                    note = null
                    failed = false
                    scope.launch {
                        val outcome =
                            if (existing) join(container, url, effectiveToken)
                            else create(container, name.trim(), invitees, effectiveToken)
                        busy = false
                        note = outcome.message
                        failed = !outcome.ok
                        added = outcome.ok
                    }
                },
            )

            note?.let {
                Spacer(Modifier.height(14.dp))
                Note(it, bad = failed, good = !failed)
            }
            if (added) {
                Spacer(Modifier.height(14.dp))
                YantraButton("Done", primary = false, onClick = { nav.popBackStack() })
            }
        }
    }
}

/** What the screen says back, and whether it should be read as a refusal. */
private data class Said(val ok: Boolean, val message: String)

/** Joining a repository someone already has. */
private suspend fun join(container: AppContainer, url: String, ownToken: String): Said =
    withContext(Dispatchers.IO) {
        val token = ownToken.ifBlank { container.credentials.token(Credentials.ACCOUNT) }
            ?: return@withContext Said(false, "No token to use. Sign in, or paste one.")
        when (val result = container.addWorkspace(url, token)) {
            is AddResult.Refused -> Said(false, result.reason)
            is AddResult.Ok -> Said(
                true,
                if (result.adopted) "Joined ${result.name}. Its tasks are on their way in."
                else "Started ${result.name}. It is yours to fill.",
            )
        }
    }

/**
 * Making a repository and a workspace in it, then inviting people.
 *
 * The invitations come last and their failure is reported without undoing anything. The workspace is
 * real and working by then, and tearing it down because a username was misspelled would be a far
 * worse answer than saying which name did not work.
 */
private suspend fun create(
    container: AppContainer,
    name: String,
    invitees: String,
    ownToken: String,
): Said = withContext(Dispatchers.IO) {
    val token = ownToken.ifBlank { container.credentials.token(Credentials.ACCOUNT) }
        ?: return@withContext Said(false, "No token to use. Sign in, or paste one.")

    val ref = when (val made = container.github.createRepo(name, token)) {
        is RepoCreate.Ok -> made.ref
        // The repository the user asked for exists. Linking to it is almost certainly what they
        // meant, and the linker still refuses if it turns out to hold somebody else's tasks.
        is RepoCreate.Taken -> made.ref
        is RepoCreate.Failed -> return@withContext Said(false, made.message)
    }

    when (val added = container.addWorkspace(ref.slug, token, name)) {
        is AddResult.Refused -> return@withContext Said(false, added.reason)
        is AddResult.Ok -> Unit
    }

    val names = invitees.split(',', ' ', '\n')
        .map { it.trim().removePrefix("@") }
        .filter { it.isNotEmpty() }
    if (names.isEmpty()) return@withContext Said(true, "Created ${ref.slug}. It is yours to fill.")

    val failed = names.filterNot { container.github.addCollaborator(ref, it, token) }
    Said(
        true,
        when {
            failed.isEmpty() ->
                "Created ${ref.slug} and invited ${names.joinToString(", ")}. They appear once they accept."
            failed.size == names.size ->
                "Created ${ref.slug}, but none of the invitations went out. Check the usernames."
            else ->
                "Created ${ref.slug}. Could not invite ${failed.joinToString(", ")} — check those usernames."
        },
    )
}
