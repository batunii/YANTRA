package ie.shoonya.yantra.ui.sync

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import ie.shoonya.yantra.AddResult
import ie.shoonya.yantra.AppContainer
import ie.shoonya.yantra.data.sync.Credentials
import ie.shoonya.yantra.data.sync.GitHubAuth
import ie.shoonya.yantra.data.sync.RepoCheck
import ie.shoonya.yantra.data.sync.RepoRef
import ie.shoonya.yantra.ui.appContainer
import ie.shoonya.yantra.ui.components.NavCircle
import ie.shoonya.yantra.ui.components.rememberHeaderFold
import androidx.compose.ui.input.nestedscroll.nestedScroll
import ie.shoonya.yantra.ui.components.PageHeader
import ie.shoonya.yantra.ui.components.SectionLabel
import ie.shoonya.yantra.ui.components.SelectChip
import ie.shoonya.yantra.ui.components.ButtonTone
import ie.shoonya.yantra.ui.components.YantraButton
import ie.shoonya.yantra.ui.components.YantraField
import ie.shoonya.yantra.ui.theme.Yantra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ie.shoonya.yantra.data.sync.RepoCreate
import ie.shoonya.yantra.ui.theme.YantraType

/**
 * Adding a workspace.
 *
 * The two things people arrive wanting are genuinely different operations, so they are two modes
 * rather than one field that tries to tell them apart. Joining a repository that already exists is
 * the common case — someone sent you a link, or it is your own project — and creating one is how a
 * shared list starts.
 *
 * Creating happens here, in the app. It used to go out to the browser because a GitHub App cannot
 * make a repository in a personal account at all, so the best available was GitHub's own form with
 * the fields filled in — a trip out, a button pressed there, a trip back, and a wait while this
 * screen watched for the repository to appear. An OAuth app's `repo` scope makes it one tap. Inviting
 * people still goes to GitHub, because it needs Administration rights this app has no reason to hold.
 *
 * What makes this safe to point at a working codebase is the branch. Tasks are committed to
 * `yantra-tasks`, which shares no history with anything else in the repository: the code is never
 * downloaded, never touched, and never appears in a diff beside a checkbox.
 */
@Composable
fun AddWorkspaceScreen(nav: NavHostController) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val y = Yantra.colors

    val account = remember { container.credentials.login(Credentials.ACCOUNT) }
    var existing by remember { mutableStateOf(true) }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var ownToken by remember { mutableStateOf(account == null) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf<String?>(null) }

    val effectiveToken = if (ownToken) token.trim() else ""
    val ready = when {
        busy -> false
        ownToken && effectiveToken.isBlank() -> false
        existing -> RepoRef.parse(url) != null
        else -> name.isNotBlank()
    }

    Column(Modifier.fillMaxSize().background(y.page).statusBarsPadding()) {
        val fold = rememberHeaderFold()
        PageHeader("Add a workspace", onBack = { nav.popBackStack() }, collapsed = fold.collapsed)

        Column(
            Modifier
                .fillMaxWidth()
                .nestedScroll(fold.connection)
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
                Text("Paste the address, or type owner/name", color = y.textMuted, fontSize = YantraType.meta)
                Spacer(Modifier.height(12.dp))
                YantraField(url, { url = it; note = null }, "github.com/you/project", mono = true)
                // What this address resolved to, said before anything is linked.
                //
                // The parser accepts a link from anywhere inside a repository — an issue, a file, a
                // release — because that is where you are standing when you decide to link it. The
                // price of being that forgiving is that the person should be able to see what it
                // understood, so a paste of the wrong tab is caught by them rather than by a failed
                // push a week later. An empty box says nothing; only a non-empty one is answered.
                val resolved = RepoRef.parse(url)
                if (url.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        resolved?.let { "Will use ${it.slug}" }
                            ?: "No repository in that — paste its address, or type owner/name",
                        color = if (resolved != null) y.textSecondary else y.textDim,
                        fontSize = YantraType.section,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Tasks are kept on a branch called yantra-tasks. Your code is never downloaded "
                        + "and never changed — the two never share a commit.",
                    color = y.textDim,
                    fontSize = YantraType.caption,
                )
            } else {
                SectionLabel("Name")
                Spacer(Modifier.height(2.dp))
                Text("A new private repository, for tasks only", color = y.textMuted, fontSize = YantraType.meta)
                Spacer(Modifier.height(12.dp))
                YantraField(name, { name = it; note = null }, "team-tasks", mono = true)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (busy) "Making it, and setting the workspace up."
                    else "Made private, here. Invite people to it once it exists.",
                    color = y.textDim,
                    fontSize = YantraType.caption,
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Access")
            Spacer(Modifier.height(2.dp))
            if (!ownToken && account != null) {
                Text("Using your GitHub account, $account", color = y.textMuted, fontSize = YantraType.meta)
                Spacer(Modifier.height(8.dp))
                Link("Use a different token") { ownToken = true }
            } else {
                Text(
                    "A fine-grained token with Contents: read and write on that repository",
                    color = y.textMuted,
                    fontSize = YantraType.meta,
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
                label = if (existing) "Add workspace" else "Create the repository",
                modifier = Modifier.fillMaxWidth(),
                busy = busy,
                enabled = ready,
                onClick = {
                    note = null
                    failed = false
                    busy = true
                    scope.launch {
                        val outcome =
                            if (existing) join(container, url, effectiveToken)
                            else create(container, name.trim(), effectiveToken)
                        busy = false
                        note = outcome.message
                        failed = !outcome.ok
                        if (outcome.ok) {
                            added = if (existing) RepoRef.parse(url)?.slug else outcome.slug
                        }
                    }
                },
            )

            note?.let {
                Spacer(Modifier.height(14.dp))
                Note(it, bad = failed, good = !failed)
            }
            added?.let { slug ->
                Spacer(Modifier.height(14.dp))
                // Inviting needs Administration rights, which is far more than reading and writing
                // task files. So it happens where it belongs: on the repository's own settings page.
                Link("Invite people to $slug") { uri.openUri(GitHubAuth.accessSettingsUrl(slug)) }
                Spacer(Modifier.height(10.dp))
                YantraButton("Done", tone = ButtonTone.Quiet, modifier = Modifier.fillMaxWidth(), onClick = { nav.popBackStack() })
            }
        }
    }
}

/** Joining a repository someone already has. */
private suspend fun join(container: AppContainer, url: String, ownToken: String): Said =
    withContext(Dispatchers.IO) {
        val token = ownToken.ifBlank { container.credentials.token(Credentials.ACCOUNT) }
            ?: return@withContext Said(false, "No token to use. Sign in, or paste one.")
        when (val result = container.addWorkspace(url, token)) {
            is AddResult.Refused -> Said(false, result.reason)
            // Joining is already the answer to "the repository has tasks", so addWorkspace never
            // asks. Here because the compiler is right to insist on it.
            is AddResult.HasTasks -> Said(false, "${result.slug} could not be joined")
            is AddResult.Ok -> Said(
                true,
                if (result.adopted) "Joined ${result.name}. Its tasks are on their way in."
                else "Started ${result.name}. It is yours to fill.",
            )
        }
    }

/**
 * Makes the repository and starts a workspace in it.
 *
 * One call now does what a browser trip, a return, and a polling loop used to: the repository is
 * made, and the workspace is built on the reference GitHub sent back rather than on the name that
 * was typed — GitHub normalises names, and a workspace pointed at the name someone typed would push
 * to a repository that does not exist.
 */
private suspend fun create(container: AppContainer, name: String, ownToken: String): Said =
    withContext(Dispatchers.IO) {
        val token = ownToken.ifBlank { container.credentials.token(Credentials.ACCOUNT) }
            ?: return@withContext Said(false, "No token to use. Sign in, or paste one.")

        when (val made = container.github.createRepo(name, token)) {
            is RepoCreate.Ok -> when (val result = container.addWorkspace(made.ref.slug, token, name)) {
                is AddResult.Refused -> Said(false, result.reason)
                is AddResult.HasTasks -> Said(false, "${result.slug} could not be joined")
                is AddResult.Ok -> Said(true, "Created ${made.ref.slug}. It is yours to fill.", made.ref.slug)
            }
            RepoCreate.Exists ->
                Said(false, "You already have a $name — add it as an existing repo instead")
            RepoCreate.Unauthorized ->
                Said(false, "This sign-in cannot make repositories. Sign in again.")
            is RepoCreate.Failed -> Said(false, made.message)
        }
    }
