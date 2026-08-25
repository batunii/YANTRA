package ie.napkin.supertasks.ui.sync

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import ie.napkin.supertasks.data.sync.Credentials
import ie.napkin.supertasks.data.sync.DeviceCode
import ie.napkin.supertasks.data.sync.DevicePoll
import ie.napkin.supertasks.data.sync.GitHubAuth
import ie.napkin.supertasks.ui.appContainer
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.YantraButton
import ie.napkin.supertasks.ui.components.YantraField
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the sign-in has got to. */
private sealed interface Stage {
    data object Idle : Stage
    data object Starting : Stage
    /** GitHub has given us a code to show, and we are polling until someone types it. */
    data class Waiting(val code: DeviceCode) : Stage
    data class Failed(val reason: String) : Stage
}

/**
 * Connecting a GitHub account.
 *
 * Two ways in, and the screen is honest about the difference rather than hiding one of them. Signing
 * in is one tap and a short code, and asks for `repo` — enough to *create* repositories, which is
 * the point of it, but reaching every repository the account has. Pasting a fine-grained token is
 * more work and grants exactly one repository. Neither is the "advanced" option; they are for
 * different intentions, and someone who only wants to share one project should use the second.
 *
 * Nothing here ever sees a password. The device flow authorises on GitHub's own page.
 */
@Composable
fun SignInScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val y = Yantra.colors

    var account by remember { mutableStateOf(container.credentials.login(Credentials.ACCOUNT)) }
    var stage by remember { mutableStateOf<Stage>(Stage.Idle) }
    var pasting by remember { mutableStateOf(!GitHubAuth.configured) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    // Backing the local workspace up: only offered while it has nowhere to go.
    var localSlug by remember { mutableStateOf(container.slugOf("")) }
    var repoName by remember { mutableStateOf("yantra-tasks") }
    var backupNote by remember { mutableStateOf<String?>(null) }

    /** Polls until the user finishes on github.com, or until the code dies. */
    LaunchedEffect(stage) {
        val waiting = stage as? Stage.Waiting ?: return@LaunchedEffect
        var interval = waiting.code.intervalSecs
        val deadline = waiting.code.expiresInSecs
        var waited = 0
        while (waited < deadline) {
            delay(interval * 1000L)
            waited += interval
            when (val poll = withContext(Dispatchers.IO) { container.deviceAuth.poll(waiting.code) }) {
                is DevicePoll.Token -> {
                    val login = withContext(Dispatchers.IO) { container.github.viewer(poll.token) }
                    if (login == null) {
                        stage = Stage.Failed("GitHub gave us a token it then would not accept")
                    } else {
                        container.credentials.store(Credentials.ACCOUNT, poll.token, login)
                        account = login
                        stage = Stage.Idle
                    }
                    return@LaunchedEffect
                }
                is DevicePoll.Failed -> {
                    stage = Stage.Failed(poll.reason)
                    return@LaunchedEffect
                }
                // GitHub sets the floor and we take it. Polling faster than asked is how an OAuth
                // app gets rate-limited for every install of it, not just this one.
                is DevicePoll.SlowDown -> interval = poll.intervalSecs
                DevicePoll.Pending -> Unit
            }
        }
        stage = Stage.Failed("The code expired. Start again for a fresh one")
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
            Text("GitHub", style = MaterialTheme.typography.headlineSmall, color = y.textPrimary)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 40.dp),
        ) {
            if (account != null) {
                SectionLabel("Signed in")
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(y.cardBg, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Check, null, tint = y.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            account!!,
                            color = y.textPrimary,
                            fontFamily = YantraText,
                            fontWeight = FontWeight.W700,
                            fontSize = 15.sp,
                        )
                        Text(
                            "The name on your commits, and who a task is assigned to",
                            color = y.textMuted,
                            fontSize = 11.5.sp,
                        )
                    }
                }

                Spacer(Modifier.height(26.dp))
                SectionLabel(if (localSlug == null) "Back up your tasks" else "Your tasks")
                Spacer(Modifier.height(2.dp))
                Text(
                    localSlug?.let { "Everything on this device is pushed to $it" }
                        ?: "Your tasks are only on this phone. A private repository gives them "
                        + "somewhere to live and a second device to appear on.",
                    color = y.textMuted,
                    fontSize = 12.5.sp,
                )
                if (localSlug == null) {
                    Spacer(Modifier.height(12.dp))
                    YantraField(repoName, { repoName = it; backupNote = null }, "repository name", mono = true)
                    Spacer(Modifier.height(10.dp))
                    YantraButton(
                        label = "Create a private repository",
                        busy = busy,
                        enabled = repoName.isNotBlank(),
                        onClick = {
                            busy = true
                            backupNote = null
                            scope.launch {
                                val outcome = backUpLocal(container, repoName.trim())
                                busy = false
                                backupNote = outcome
                                localSlug = container.slugOf("")
                            }
                        },
                    )
                }
                backupNote?.let {
                    Spacer(Modifier.height(10.dp))
                    Note(it)
                }

                Spacer(Modifier.height(26.dp))
                YantraButton(
                    label = "Sign out",
                    primary = false,
                    onClick = {
                        // Only the account. A workspace keeps its own copy of the token, so signing
                        // out stops this app creating repositories and does not break the ones that
                        // already sync — which is what someone signing out actually means.
                        container.credentials.clear(Credentials.ACCOUNT)
                        account = null
                        stage = Stage.Idle
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your workspaces keep syncing. Revoke access on GitHub to stop them.",
                    color = y.textDim,
                    fontSize = 11.5.sp,
                )
                return@Column
            }

            Text(
                "Sync your tasks across devices, and share a list with people who can add to it.",
                color = y.textSecondary,
                fontSize = 13.5.sp,
            )

            if (GitHubAuth.configured) {
                Spacer(Modifier.height(22.dp))
                when (val s = stage) {
                    is Stage.Waiting -> {
                        SectionLabel("Type this on GitHub")
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(y.cardBg, RoundedCornerShape(14.dp))
                                .border(1.dp, y.tileBorder, RoundedCornerShape(14.dp))
                                .clickable {
                                    ctx.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("code", s.code.userCode))
                                    copied = true
                                }
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    s.code.userCode,
                                    color = y.textPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.W700,
                                    fontSize = 30.sp,
                                    // A device code is read off one screen and typed into another,
                                    // so the letters need room to be told apart.
                                    letterSpacing = 4.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy, null,
                                        tint = y.textDim, modifier = Modifier.size(12.dp),
                                    )
                                    Text(
                                        if (copied) "Copied" else "Tap to copy",
                                        color = y.textDim,
                                        fontSize = 11.5.sp,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        YantraButton(
                            label = "Open GitHub",
                            icon = Icons.AutoMirrored.Filled.OpenInNew,
                            onClick = { uri.openUri(s.code.verificationUri) },
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Waiting for you to approve it. This screen will notice by itself.",
                            color = y.textMuted,
                            fontSize = 12.5.sp,
                        )
                    }

                    else -> {
                        YantraButton(
                            label = "Sign in with GitHub",
                            busy = s is Stage.Starting,
                            onClick = {
                                stage = Stage.Starting
                                copied = false
                                scope.launch {
                                    val code = withContext(Dispatchers.IO) { container.deviceAuth.start() }
                                    stage = code?.let { Stage.Waiting(it) }
                                        ?: Stage.Failed("Could not reach GitHub")
                                }
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Asks for access to your repositories, so the app can make one for your "
                                + "tasks. You approve it on GitHub.",
                            color = y.textDim,
                            fontSize = 11.5.sp,
                        )
                        (s as? Stage.Failed)?.let {
                            Spacer(Modifier.height(12.dp))
                            Note(it.reason, bad = true)
                        }
                        Spacer(Modifier.height(14.dp))
                        Link("New to GitHub? Create an account") { uri.openUri("https://github.com/signup") }
                    }
                }
            } else {
                // The client id is empty in this build. Saying so beats a button that fails at the
                // network, and the token path below is a complete way in rather than a consolation.
                Spacer(Modifier.height(18.dp))
                Note("This build has no GitHub app registered, so signing in is unavailable. A token works just as well.")
            }

            Spacer(Modifier.height(26.dp))
            if (!pasting) {
                Link("Use an access token instead") { pasting = true }
            } else {
                SectionLabel("Access token")
                Spacer(Modifier.height(2.dp))
                Text(
                    "A fine-grained token with Contents: read and write. It can be limited to one "
                        + "repository, which signing in cannot.",
                    color = y.textMuted,
                    fontSize = 12.5.sp,
                )
                Spacer(Modifier.height(12.dp))
                YantraField(token, { token = it }, "github_pat_…", secret = true)
                Spacer(Modifier.height(10.dp))
                YantraButton(
                    label = "Connect",
                    busy = busy,
                    enabled = token.isNotBlank(),
                    onClick = {
                        busy = true
                        stage = Stage.Idle
                        scope.launch {
                            val login = withContext(Dispatchers.IO) {
                                container.github.viewer(token.trim())
                            }
                            busy = false
                            if (login == null) {
                                stage = Stage.Failed("GitHub rejected that token")
                            } else {
                                container.credentials.store(Credentials.ACCOUNT, token.trim(), login)
                                account = login
                                token = ""
                            }
                        }
                    },
                )
                (stage as? Stage.Failed)?.let {
                    Spacer(Modifier.height(10.dp))
                    Note(it.reason, bad = true)
                }
                Spacer(Modifier.height(12.dp))
                Link("Make a token on GitHub") {
                    uri.openUri("https://github.com/settings/personal-access-tokens/new")
                }
            }
        }
    }
}

/**
 * Creates the repository and gives the local workspace its remote.
 *
 * A name already in use is not a failure: the repository the user asked for exists, and pushing the
 * tasks to it is what they were trying to do. The linker still refuses if it turns out to have tasks
 * on it already, which is the case where guessing would cost someone their work.
 */
private suspend fun backUpLocal(
    container: ie.napkin.supertasks.AppContainer,
    name: String,
): String = withContext(Dispatchers.IO) {
    val token = container.credentials.token(Credentials.ACCOUNT)
        ?: return@withContext "Sign in again — the stored token could not be read"

    val ref = when (val made = container.github.createRepo(name, token)) {
        is ie.napkin.supertasks.data.sync.RepoCreate.Ok -> made.ref
        is ie.napkin.supertasks.data.sync.RepoCreate.Taken -> made.ref
        is ie.napkin.supertasks.data.sync.RepoCreate.Failed -> return@withContext made.message
    }
    when (val attached = container.attachRemote("", ref.slug, token)) {
        is ie.napkin.supertasks.AddResult.Ok -> "Your tasks are now in ${ref.slug}"
        is ie.napkin.supertasks.AddResult.Refused -> attached.reason
    }
}
