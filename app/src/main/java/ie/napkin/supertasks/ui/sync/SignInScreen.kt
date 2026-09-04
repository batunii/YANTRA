package ie.napkin.supertasks.ui.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import ie.napkin.supertasks.AddResult
import ie.napkin.supertasks.AppContainer
import ie.napkin.supertasks.data.sync.Credentials
import ie.napkin.supertasks.data.sync.DeviceCode
import ie.napkin.supertasks.data.sync.DeviceStart
import ie.napkin.supertasks.data.sync.DevicePoll
import ie.napkin.supertasks.data.sync.GitHubAuth
import ie.napkin.supertasks.data.sync.InstallState
import ie.napkin.supertasks.data.sync.RepoCheck
import ie.napkin.supertasks.data.sync.RepoRef
import ie.napkin.supertasks.ui.appContainer
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.PageHeader
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.ButtonTone
import ie.napkin.supertasks.ui.components.YantraButton
import ie.napkin.supertasks.ui.components.YantraField
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraMono
import ie.napkin.supertasks.ui.theme.YantraText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the sign-in has got to. */
/**
 * A dead end, said on screen and written down.
 *
 * The message a user can read has to be one line; the reason it happened rarely fits in one. Sync
 * learned this the hard way — a failure with nothing behind it is undiagnosable the moment the
 * screen is dismissed, and sign-in is the one flow where being stuck is the whole experience.
 */
private fun failed(reason: String): Stage {
    Log.w("YantraSignIn", "sign-in failed: $reason")
    return Stage.Failed(reason)
}

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
 * **Nobody is ever asked to create an access token.** That was the first design and it was wrong:
 * making a fine-grained PAT is intimidating even for people who do this for a living, and it is a
 * strange thing to demand as the first act of a task app. Signing in is one tap and a short code
 * typed on GitHub's own page.
 *
 * The cost of that is a shape rather than a compromise, and it is worth naming because it looks like
 * an extra step until you see what it buys. A GitHub App cannot create a repository in a personal
 * account — there is no such permission, only the old blanket `repo` scope of an OAuth app could do
 * it — so instead of asking for write access to everything the user owns, the app opens GitHub's own
 * new-repository form with the name and visibility already filled in, and the user presses one
 * button. The App itself only ever asks for `Contents: read and write`: enough to read and write task
 * files, and nothing that could delete a repository or change who can see it.
 *
 * So the whole flow is three taps in the browser at most — sign in, grant access, create — and the
 * app holds no secret bigger than what its daily job needs. Pasting a token remains, one screen down,
 * for anyone who would rather grant one repository and nothing else.
 */
@Composable
fun SignInScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val y = Yantra.colors

    var account by remember { mutableStateOf(container.credentials.login(Credentials.ACCOUNT)) }
    var viaApp by remember { mutableStateOf(container.credentials.viaApp(Credentials.ACCOUNT)) }
    var stage: Stage by remember { mutableStateOf<Stage>(Stage.Idle) }
    // Open already when there is no App registered to sign into. Otherwise this screen says
    // "signing in is unavailable" and hides the only thing that works behind a link, which reads as
    // a dead end. Once a client id is set this state never occurs.
    var pasting by remember { mutableStateOf(!GitHubAuth.configured) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    /** Set while polls are failing, so the wait does not silently pretend to be going well. */
    var struggling by remember { mutableStateOf<String?>(null) }
    /**
     * True while a look-for-the-new-repository attempt is in flight.
     *
     * Closing the browser and the app being brought forward are two resumes, so without this the
     * effect runs twice, both jobs read the same pending name, and the workspace is attached twice —
     * the second one arriving as a failure on top of a success.
     */
    var checking by remember { mutableStateOf(false) }

    var install by remember { mutableStateOf<InstallState?>(null) }
    var accountId by remember { mutableStateOf(container.credentials.accountId(Credentials.ACCOUNT)) }
    var localSlug by remember { mutableStateOf(container.slugOf("")) }
    var repoName by remember { mutableStateOf("yantra-tasks") }
    var awaiting by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteBad by remember { mutableStateOf(false) }
    /** Set when the repository turned out to have a task list of its own — see [LinkOutcome.Asks]. */
    var asking by remember { mutableStateOf<LinkOutcome.Asks?>(null) }
    /** True while an existing repository is being looked up and attached. */
    var linking by remember { mutableStateOf(false) }

    /**
     * Everything that happened in the browser, noticed on the way back.
     *
     * The two web trips — granting access, creating the repository — end with the user returning to
     * this screen and nothing else telling us they did. So resuming *is* the signal: re-ask whether
     * the App is installed, and if we sent someone off to make a repository, look for it.
     */
    LifecycleResumeEffect(account, viaApp) {
        val job = scope.launch {
            val tok = container.credentials.token(Credentials.ACCOUNT)
            if (account == null || tok == null) {
                install = null
                return@launch
            }
            // A pasted token has no App installation and needs none — asking would send someone off
            // to install something they have no use for.
            install = if (!viaApp) InstallState.Installed
            else withContext(Dispatchers.IO) {
                container.github.installState(tok, GitHubAuth.APP_SLUG)
            }

            // An account signed in before the id was being stored still deserves the direct link.
            // Only when there is something to install, and only once — the answer never changes.
            if (install == InstallState.Absent && container.credentials.accountId(Credentials.ACCOUNT) == null) {
                withContext(Dispatchers.IO) { container.github.account(tok) }
                    ?.let { container.credentials.rememberAccountId(Credentials.ACCOUNT, it.id) }
                accountId = container.credentials.accountId(Credentials.ACCOUNT)
            }

            val wanted = awaiting
            if (wanted != null && install == InstallState.Installed && !checking) {
                checking = true
                try {
                    when (val outcome = linkCreated(container, account!!, wanted, tok)) {
                        null -> Unit
                        is LinkOutcome.Done -> {
                            awaiting = null
                            note = outcome.said.message
                            noteBad = !outcome.said.ok
                            localSlug = container.slugOf("")
                        }
                        // Nothing was written. The repository has a list of its own, and which list
                        // wins is not a question this screen may answer on its own.
                        is LinkOutcome.Asks -> {
                            awaiting = null
                            asking = outcome
                        }
                    }
                } finally {
                    checking = false
                }
            }
        }
        onPauseOrDispose { job.cancel() }
    }

    asking?.let { ask ->
        RepoHasTasksDialog(
            ask = ask,
            onUseRepo = {
                asking = null
                scope.launch {
                    val r = container.attachRemote("", ask.slug, ask.token, adopt = true)
                    note = when (r) {
                        is AddResult.Ok -> "This device now shows the tasks in ${ask.slug}"
                        is AddResult.Refused -> r.reason
                        is AddResult.HasTasks -> "${ask.slug} could not be joined"
                    }
                    noteBad = r !is AddResult.Ok
                    localSlug = container.slugOf("")
                }
            },
            onKeepBoth = {
                asking = null
                scope.launch {
                    val r = container.addWorkspace(ask.slug, ask.token)
                    note = when (r) {
                        is AddResult.Ok -> "Added ${r.name}. Your tasks here are untouched."
                        is AddResult.Refused -> r.reason
                        is AddResult.HasTasks -> "${ask.slug} could not be joined"
                    }
                    noteBad = r !is AddResult.Ok
                }
            },
            onCancel = { asking = null },
        )
    }

    /** Polls until the user finishes on github.com, or until the code dies. */
    LaunchedEffect(stage) {
        val waiting = stage as? Stage.Waiting ?: return@LaunchedEffect
        var interval = waiting.code.intervalSecs
        var waited = 0
        var offline = 0
        while (waited < waiting.code.expiresInSecs) {
            delay(interval * 1000L)
            waited += interval
            when (val poll = withContext(Dispatchers.IO) { container.deviceAuth.poll(waiting.code) }) {
                is DevicePoll.Token -> {
                    struggling = null
                    val who = withContext(Dispatchers.IO) { container.github.account(poll.token) }
                    val login = who?.login
                    if (login == null) {
                        stage = failed("GitHub gave us a token it then would not accept")
                    } else {
                        container.credentials.store(
                            Credentials.ACCOUNT, poll.token, login, viaApp = true,
                            // Kept whether or not GitHub sends them. Both null means the token does
                            // not lapse; anything else is what TokenRenewal needs to keep it alive.
                            refreshToken = poll.refreshToken,
                            expiresAt = poll.expiresInSecs?.let { System.currentTimeMillis() + it * 1000L },
                            // Kept now so the install link can be aimed at this account rather than
                            // landing on a chooser. It costs nothing here and is a request we would
                            // otherwise have to make later, at the one moment someone is waiting.
                            accountId = who.id,
                        )
                        account = login
                        accountId = who.id
                        viaApp = true
                        stage = Stage.Idle

                        // Straight on to the second half, without coming back here to be told to.
                        //
                        // Signing in and granting access are two errands on GitHub, and only the
                        // first one ends by itself. The second used to wait behind a button on this
                        // screen — so the reward for finishing sign-in was a screen saying there was
                        // one more step, and a token that could see nothing until it was taken.
                        // Chaining them makes it one trip: enter the code, choose All repositories,
                        // and the Setup URL brings you back finished.
                        //
                        // Only here, never on resume. The resume handler also knows when the App is
                        // not installed, and opening the browser from *there* would send someone
                        // who backed out of the page straight back into it, forever.
                        val installed = withContext(Dispatchers.IO) {
                            container.github.installState(poll.token, GitHubAuth.APP_SLUG)
                        }
                        install = installed
                        if (installed == InstallState.Absent) {
                            uri.openUri(GitHubAuth.installUrl(who.id))
                        }
                    }
                    return@LaunchedEffect
                }
                is DevicePoll.Failed -> {
                    stage = failed(poll.reason)
                    return@LaunchedEffect
                }
                // A dropped request is not an answer. Keep asking — but say so, because a screen
                // that reads "waiting for you" while it is actually failing is a lie, and give up
                // eventually so a genuinely dead network does not look like a hang forever.
                is DevicePoll.Offline -> {
                    offline++
                    if (offline >= MAX_OFFLINE_POLLS) {
                        stage = failed("Cannot reach GitHub — ${poll.reason}")
                        return@LaunchedEffect
                    }
                    struggling = poll.reason
                }
                // GitHub sets the floor and we take it. Polling faster than asked is how an OAuth
                // app gets rate-limited for every install of it, not just this one.
                is DevicePoll.SlowDown -> {
                    struggling = null
                    interval = poll.intervalSecs
                }
                DevicePoll.Pending -> {
                    offline = 0
                    struggling = null
                }
            }
        }
        stage = failed("The code expired. Start again for a fresh one")
    }

    Column(Modifier.fillMaxSize().background(y.page).statusBarsPadding()) {
        val pageScroll = rememberScrollState()
        // Folds as soon as the page has actually moved. A threshold rather than
        // `> 0` because a scroll state twitches by a pixel on layout, and a band
        // that folds and unfolds on its own is the texture the motion law forbids.
        val collapsed by remember { derivedStateOf { pageScroll.value > 80 } }
        PageHeader("GitHub", onBack = { nav.popBackStack() }, collapsed = collapsed)

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(pageScroll)
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 40.dp),
        ) {
            if (account != null) {
                SignedIn(
                    account = account!!,
                    install = install,
                    localSlug = localSlug,
                    repoName = repoName,
                    awaiting = awaiting,
                    note = note,
                    noteBad = noteBad,
                    onRepoName = { repoName = it; note = null },
                    onInstall = { uri.openUri(GitHubAuth.installUrl(accountId)) },
                    onCreate = {
                        note = null
                        awaiting = repoName.trim()
                        uri.openUri(GitHubAuth.newRepoUrl(repoName.trim()))
                    },
                    linking = linking,
                    onUseExisting = {
                        note = null
                        linking = true
                        scope.launch {
                            try {
                                val tok = container.credentials.token(Credentials.ACCOUNT)
                                val who = account
                                when {
                                    tok == null || who == null -> {
                                        note = "Sign in again — we do not know who you are"
                                        noteBad = true
                                    }
                                    else -> when (
                                        val outcome = linkCreated(container, who, repoName.trim(), tok)
                                    ) {
                                        // Only the resume check may treat "not there yet" as
                                        // patience; asked for directly, it is an answer.
                                        null -> {
                                            note = "${who}/${repoName.trim()} is not there, or " +
                                                "Yantra has not been given access to it"
                                            noteBad = true
                                        }
                                        is LinkOutcome.Done -> {
                                            note = outcome.said.message
                                            noteBad = !outcome.said.ok
                                            localSlug = container.slugOf("")
                                        }
                                        is LinkOutcome.Asks -> asking = outcome
                                    }
                                }
                            } finally {
                                linking = false
                            }
                        }
                    },
                    onSignOut = {
                        // Only the account. A workspace keeps its own copy of the token, so signing
                        // out stops this app reaching GitHub on your behalf and does not break the
                        // workspaces that already sync — which is what signing out actually means.
                        container.credentials.clear(Credentials.ACCOUNT)
                        account = null
                        accountId = null
                        viaApp = false
                        install = null
                        awaiting = null
                        note = null
                        stage = Stage.Idle
                    },
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
                    is Stage.Waiting -> DeviceCodePanel(
                        code = s.code,
                        copied = copied,
                        struggling = struggling,
                        onCopy = {
                            ctx.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("code", s.code.userCode))
                            copied = true
                        },
                        onOpen = { uri.openUri(s.code.verificationUri) },
                    )

                    else -> {
                        YantraButton(
                            label = "Sign in with GitHub",
                            modifier = Modifier.fillMaxWidth(),
                            busy = s is Stage.Starting,
                            onClick = {
                                stage = Stage.Starting
                                copied = false
                                scope.launch {
                                    stage = when (
                                        val started =
                                            withContext(Dispatchers.IO) { container.deviceAuth.start() }
                                    ) {
                                        is DeviceStart.Ok -> Stage.Waiting(started.code)
                                        is DeviceStart.Failed -> failed(started.reason)
                                    }
                                }
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Asks to read and write files in your repositories — enough to keep task "
                                + "lists there, and nothing that can delete a repository or change "
                                + "who can see it. You approve it on GitHub.",
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
                    "A fine-grained token with Contents: read and write. More work than signing in, "
                        + "and it can be limited to a single repository.",
                    color = y.textMuted,
                    fontSize = 12.5.sp,
                )
                Spacer(Modifier.height(12.dp))
                YantraField(token, { token = it }, "github_pat_…", secret = true)
                Spacer(Modifier.height(10.dp))
                YantraButton(
                    label = "Connect",
                    modifier = Modifier.fillMaxWidth(),
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
                                stage = failed("GitHub rejected that token")
                            } else {
                                container.credentials.store(Credentials.ACCOUNT, token.trim(), login)
                                account = login
                                viaApp = false
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
 * Everything after the account exists: whether the App can see anything, and where the tasks live.
 *
 * Split out because the signed-in half is a different screen wearing the same header, and reading one
 * function that is two screens was the thing making this file hard to follow.
 */
@Composable
internal fun SignedIn(
    account: String,
    install: InstallState?,
    localSlug: String?,
    repoName: String,
    awaiting: String?,
    note: String?,
    noteBad: Boolean,
    onRepoName: (String) -> Unit,
    onInstall: () -> Unit,
    onCreate: () -> Unit,
    /** Point Personal at a repository that is already there. */
    onUseExisting: () -> Unit,
    onSignOut: () -> Unit,
    linking: Boolean = false,
) {
    val y = Yantra.colors

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
            Text(account, color = y.textPrimary, fontFamily = YantraText, fontWeight = FontWeight.W700, fontSize = 15.sp)
            Text(
                "The name on your commits, and who a task is assigned to",
                color = y.textMuted,
                fontSize = 11.5.sp,
            )
        }
    }

    when (install) {
        // Authenticated and able to see nothing at all, which is the most confusing state there is,
        // so it gets the whole screen until it is fixed rather than a warning under something else.
        InstallState.Absent -> {
            Spacer(Modifier.height(26.dp))
            SectionLabel("One more step")
            Spacer(Modifier.height(2.dp))
            Text(
                "Yantra needs your permission to read and write files in your repositories. Choose "
                    + "All repositories so that a repo you make later is included without coming "
                    + "back here.",
                color = y.textMuted,
                fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(12.dp))
            YantraButton(
                "Grant access on GitHub",
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                onClick = onInstall,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "This screen notices when you come back.",
                color = y.textDim,
                fontSize = 11.5.sp,
            )
        }

        InstallState.Unauthorized -> {
            Spacer(Modifier.height(26.dp))
            Note(
                "This sign-in no longer works — it may have been revoked, or the key that protects "
                    + "it was replaced when this device was restored. Sign in again.",
                bad = true,
            )
        }

        is InstallState.Failed -> {
            Spacer(Modifier.height(26.dp))
            Note("Could not reach GitHub: ${install.message}")
        }

        InstallState.Installed -> {
            Spacer(Modifier.height(26.dp))
            SectionLabel(if (localSlug == null) "Back up your tasks" else "Your tasks")
            Spacer(Modifier.height(2.dp))
            Text(
                localSlug?.let { "Everything on this device is pushed to $it" }
                    ?: "Your tasks are only on this phone. A private repository gives them somewhere "
                    + "to live and a second device to appear on.",
                color = y.textMuted,
                fontSize = 12.5.sp,
            )
            if (localSlug == null) {
                Spacer(Modifier.height(12.dp))
                YantraField(repoName, onRepoName, "repository name", mono = true)
                Spacer(Modifier.height(10.dp))
                YantraButton(
                    label = "Create a private repository",
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    busy = awaiting != null,
                    enabled = repoName.isNotBlank(),
                    onClick = onCreate,
                )
                Spacer(Modifier.height(8.dp))
                // The other half of the question, and it was missing.
                //
                // "Create a private repository" is the only thing this card offered, so someone who
                // *already had* one had no way to say so: the working route was to type its name,
                // press Create, create nothing, and come back so the resume check found it. Failing
                // that you added it from Add a workspace, which makes a second workspace — and if
                // its manifest says Personal, a second Personal.
                YantraButton(
                    label = "Use a repository I already have",
                    tone = ButtonTone.Quiet,
                    modifier = Modifier.fillMaxWidth(),
                    busy = linking,
                    enabled = repoName.isNotBlank() && awaiting == null,
                    onClick = onUseExisting,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (awaiting != null)
                        "Waiting for $awaiting to appear. Press Create repository on GitHub, then "
                            + "come back."
                    else
                        "Opens GitHub with the name and Private already filled in — press one button. "
                            + "Yantra cannot create repositories itself, and asking for permission "
                            + "broad enough to do it would mean access to far more than task files.",
                    color = y.textDim,
                    fontSize = 11.5.sp,
                )
            }
            note?.let {
                Spacer(Modifier.height(12.dp))
                Note(it, bad = noteBad, good = !noteBad)
            }
        }

        null -> Unit    // still asking
    }

    Spacer(Modifier.height(26.dp))
    YantraButton(label = "Sign out", tone = ButtonTone.Quiet, modifier = Modifier.fillMaxWidth(), onClick = onSignOut)
    Spacer(Modifier.height(8.dp))
    Text(
        "Your workspaces keep syncing. Remove Yantra's access on GitHub to stop them.",
        color = y.textDim,
        fontSize = 11.5.sp,
    )
}

/** The code, big enough to read off one screen and type into another. */
@Composable
private fun DeviceCodePanel(
    code: DeviceCode,
    copied: Boolean,
    struggling: String?,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    val y = Yantra.colors
    SectionLabel("Type this on GitHub")
    Spacer(Modifier.height(10.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .background(y.cardBg, RoundedCornerShape(14.dp))
            .border(1.dp, y.tileBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onCopy)
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                code.userCode,
                color = y.textPrimary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.W700,
                fontSize = 30.sp,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Default.ContentCopy, null, tint = y.textDim, modifier = Modifier.size(12.dp))
                Text(if (copied) "Copied" else "Tap to copy", color = y.textDim, fontSize = 11.5.sp)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    YantraButton(
        label = "Open GitHub",
        modifier = Modifier.fillMaxWidth(),
        icon = Icons.AutoMirrored.Filled.OpenInNew,
        onClick = onOpen,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        if (struggling == null) "Waiting for you to approve it. This screen will notice by itself."
        else "Having trouble reaching GitHub — still trying. Your code is still good.",
        color = if (struggling == null) y.textMuted else y.warning,
        fontSize = 12.5.sp,
    )
}

/**
 * How many consecutive unanswered polls before giving up.
 *
 * Roughly half a minute at GitHub's five-second floor: long enough to ride out a handover between
 * wifi and mobile, short enough that a genuinely dead network is not mistaken for a hang.
 */
private const val MAX_OFFLINE_POLLS = 6

/**
 * Looks for the repository the user was sent off to create, and attaches the local tasks to it.
 *
 * Returns null while it is genuinely not there yet — someone who opened the form and wandered off
 * should find the button still waiting rather than an error telling them they failed. Only an answer
 * that settles the matter comes back as a message.
 */
/**
 * What linking ended in: something to report, or something to ask.
 *
 * The ask exists because a repository that already holds tasks is genuinely ambiguous — it is either
 * someone else's list or your own after a reinstall, and those want opposite things. See
 * [ie.napkin.supertasks.data.sync.LinkResult.HasTasks].
 */
internal sealed interface LinkOutcome {
    data class Done(val said: Said) : LinkOutcome
    data class Asks(val slug: String, val localTasks: Int, val token: String) : LinkOutcome
}

private suspend fun linkCreated(
    container: AppContainer,
    login: String,
    name: String,
    token: String,
): LinkOutcome? = withContext(Dispatchers.IO) {
    val ref = RepoRef(login, name)
    when (val check = container.github.check(ref, token)) {
        is RepoCheck.Ok ->
            if (!check.canPush) {
                LinkOutcome.Done(Said(false, "${ref.slug} exists but Yantra cannot push to it"))
            } else when (val attached = container.attachRemote("", ref.slug, token)) {
                is AddResult.Ok -> LinkOutcome.Done(Said(true, "Your tasks are now in ${ref.slug}"))
                is AddResult.Refused -> LinkOutcome.Done(Said(false, attached.reason))
                is AddResult.HasTasks ->
                    LinkOutcome.Asks(attached.slug, attached.localTasks, token)
            }
        // Not there yet, or the App has not been granted it. Either way: keep waiting.
        RepoCheck.NotFound -> null
        RepoCheck.Unauthorized ->
            LinkOutcome.Done(Said(false, "Yantra was not given access to ${ref.slug}"))
        is RepoCheck.Failed -> null
    }
}

/**
 * The repository already has a task list. Which one is the real one?
 *
 * Only the person knows. Two histories with no common ancestor look identical from the app's side
 * whether they are two people's lists or one person's list and a phone that has been reinstalled
 * since it last saw it — and the two want opposite things. So this asks, and says what taking the
 * repository would cost, because "replaces the 3 tasks on this device" and "replaces the 214 tasks
 * on this device" are not the same offer.
 *
 * Neither button is destructive by accident: keeping both is the safe reading and is one tap, and
 * dismissing does nothing at all.
 */
@Composable
private fun RepoHasTasksDialog(
    ask: LinkOutcome.Asks,
    onUseRepo: () -> Unit,
    onKeepBoth: () -> Unit,
    onCancel: () -> Unit,
) {
    val y = Yantra.colors
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("${ask.slug} already has tasks") },
        text = {
            Text(
                if (ask.localTasks == 0) {
                    "That repository already holds a Yantra list. There is nothing on this device " +
                        "yet, so taking it costs nothing."
                } else {
                    "That repository already holds a Yantra list of its own. Using it replaces the " +
                        "${ask.localTasks} ${if (ask.localTasks == 1) "task" else "tasks"} on this " +
                        "device — keep both instead and it is added as a separate workspace."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onUseRepo) {
                Text(
                    "USE THE REPOSITORY",
                    fontFamily = YantraMono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 1.4.sp,
                    color = y.accent,
                )
            }
        },
        dismissButton = { TextButton(onClick = onKeepBoth) { Text("Keep both") } },
    )
}
