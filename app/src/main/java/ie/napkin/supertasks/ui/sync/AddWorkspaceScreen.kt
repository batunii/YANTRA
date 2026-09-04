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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import ie.napkin.supertasks.AddResult
import ie.napkin.supertasks.AppContainer
import ie.napkin.supertasks.data.sync.Credentials
import ie.napkin.supertasks.data.sync.GitHubAuth
import ie.napkin.supertasks.data.sync.RepoCheck
import ie.napkin.supertasks.data.sync.RepoRef
import ie.napkin.supertasks.ui.appContainer
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.PageHeader
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.SelectChip
import ie.napkin.supertasks.ui.components.ButtonTone
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
 * Creating goes out to the browser, as it does on the sign-in screen and for the same reason: the App
 * asks for `Contents: read and write` and nothing more, which is not enough to make a repository or
 * to invite anyone to one. Both of those are one-off privileged acts, and they belong on GitHub's own
 * pages rather than being bought with a permission the app would then hold forever.
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
    var awaiting by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<String?>(null) }

    val effectiveToken = if (ownToken) token.trim() else ""
    val ready = when {
        busy || awaiting != null -> false
        ownToken && effectiveToken.isBlank() -> false
        existing -> RepoRef.parse(url) != null
        else -> name.isNotBlank()
    }

    // Coming back from creating a repository in the browser. Nothing else tells us it happened.
    LifecycleResumeEffect(awaiting) {
        val wanted = awaiting
        val job = scope.launch {
            if (wanted == null) return@launch
            val outcome = joinCreated(container, wanted, effectiveToken)
            if (outcome != null) {
                awaiting = null
                note = outcome.message
                failed = !outcome.ok
                if (outcome.ok) added = outcome.slug
            }
        }
        onPauseOrDispose { job.cancel() }
    }

    Column(Modifier.fillMaxSize().background(y.page).statusBarsPadding()) {
        val pageScroll = rememberScrollState()
        // Folds as soon as the page has actually moved. A threshold rather than
        // `> 0` because a scroll state twitches by a pixel on layout, and a band
        // that folds and unfolds on its own is the texture the motion law forbids.
        val collapsed by remember { derivedStateOf { pageScroll.value > 80 } }
        PageHeader("Add a workspace", onBack = { nav.popBackStack() }, collapsed = collapsed)

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(pageScroll)
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
                Text("Paste the address, or type owner/name", color = y.textMuted, fontSize = 12.5.sp)
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
                Spacer(Modifier.height(10.dp))
                Text(
                    if (awaiting != null)
                        "Waiting for $awaiting to appear. Press Create repository on GitHub, then "
                            + "come back."
                    else
                        "Opens GitHub with the name and Private already filled in — press one button, "
                            + "then come back here.",
                    color = y.textDim,
                    fontSize = 11.5.sp,
                )
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
                    "A fine-grained token with Contents: read and write on that repository",
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
                label = if (existing) "Add workspace" else "Create on GitHub",
                modifier = Modifier.fillMaxWidth(),
                icon = if (existing) null else Icons.AutoMirrored.Filled.OpenInNew,
                busy = busy || awaiting != null,
                enabled = ready,
                onClick = {
                    note = null
                    failed = false
                    if (!existing) {
                        awaiting = name.trim()
                        uri.openUri(GitHubAuth.newRepoUrl(name.trim()))
                    } else {
                        busy = true
                        scope.launch {
                            val outcome = join(container, url, effectiveToken)
                            busy = false
                            note = outcome.message
                            failed = !outcome.ok
                            if (outcome.ok) added = RepoRef.parse(url)?.slug
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

/** What came back after the browser trip, plus where it landed so the invite link can point at it. */
private data class Created(val ok: Boolean, val message: String, val slug: String?)

/**
 * Looks for the repository the user was sent off to create, and makes a workspace in it.
 *
 * Null while it is genuinely not there yet, so someone who opened the form and wandered off finds the
 * button still waiting rather than an error telling them they failed.
 */
private suspend fun joinCreated(
    container: AppContainer,
    name: String,
    ownToken: String,
): Created? = withContext(Dispatchers.IO) {
    val token = ownToken.ifBlank { container.credentials.token(Credentials.ACCOUNT) }
        ?: return@withContext Created(false, "No token to use. Sign in, or paste one.", null)
    val login = container.credentials.login(Credentials.ACCOUNT)
        ?: return@withContext Created(false, "Sign in again — we do not know who you are", null)

    val ref = RepoRef(login, name)
    when (val check = container.github.check(ref, token)) {
        is RepoCheck.Ok -> {
            if (!check.canPush) return@withContext Created(false, "${ref.slug} exists but Yantra cannot push to it", null)
            when (val result = container.addWorkspace(ref.slug, token, name)) {
                is AddResult.Refused -> Created(false, result.reason, null)
                is AddResult.HasTasks -> Created(false, "${result.slug} could not be joined", null)
                is AddResult.Ok -> Created(true, "Created ${ref.slug}. It is yours to fill.", ref.slug)
            }
        }
        // Not there yet, or the App has not been granted it. Either way: keep waiting.
        RepoCheck.NotFound -> null
        RepoCheck.Unauthorized -> Created(false, "Yantra was not given access to ${ref.slug}", null)
        is RepoCheck.Failed -> null
    }
}
