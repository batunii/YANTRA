package ie.shoonya.yantra.ui.sync

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first
import androidx.navigation.NavHostController
import ie.shoonya.yantra.AddResult
import ie.shoonya.yantra.AppContainer
import ie.shoonya.yantra.data.sync.Credentials
import ie.shoonya.yantra.data.sync.DeviceCode
import ie.shoonya.yantra.data.sync.DeviceStart
import ie.shoonya.yantra.data.sync.DevicePoll
import ie.shoonya.yantra.data.sync.GitHubAuth
import ie.shoonya.yantra.data.sync.RepoCreate
import ie.shoonya.yantra.data.sync.SignInState
import ie.shoonya.yantra.data.sync.RepoCheck
import ie.shoonya.yantra.data.sync.RepoRef
import ie.shoonya.yantra.ui.appContainer
import ie.shoonya.yantra.ui.components.NavCircle
import ie.shoonya.yantra.ui.components.rememberHeaderFold
import androidx.compose.ui.input.nestedscroll.nestedScroll
import ie.shoonya.yantra.ui.components.PageHeader
import ie.shoonya.yantra.ui.components.SectionLabel
import ie.shoonya.yantra.ui.components.ButtonTone
import ie.shoonya.yantra.ui.components.YantraButton
import ie.shoonya.yantra.ui.components.YantraField
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraMono
import ie.shoonya.yantra.ui.theme.YantraText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ie.shoonya.yantra.ui.components.YantraMark
import ie.shoonya.yantra.ui.components.YantraIcon
import ie.shoonya.yantra.ui.theme.YantraType
import ie.shoonya.yantra.ui.theme.YantraRadius

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
 * **The whole flow is one trip to the browser**: enter the code, approve, come back. Granting access
 * and creating the repository used to be two more — the first because a GitHub App reaches nothing
 * until it is installed somewhere, the second because a GitHub App cannot create a repository in a
 * personal account at all, so the app opened GitHub's new-repository form and asked the user to press
 * the button themselves. An OAuth app needs neither: there is no installation, and `repo` can create
 * one directly.
 *
 * What that costs is stated plainly on the button below, because the user is agreeing to it: `repo`
 * is read and write to every repository they own. The narrower permission was real and it is gone,
 * traded for a sign-in that survives a second device — see [GitHubAuth]. Pasting a fine-grained token
 * remains, one screen down, for anyone who would rather grant one repository and nothing else.
 */
@Composable
fun SignInScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val y = Yantra.colors

    var account by remember { mutableStateOf(container.credentials.login(Credentials.ACCOUNT)) }
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
    var signIn by remember { mutableStateOf<SignInState?>(null) }
    var localSlug by remember { mutableStateOf(container.slugOf("")) }
    var repoName by remember { mutableStateOf("yantra-tasks") }
    /** True while a repository is being made and attached. */
    var creating by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteBad by remember { mutableStateOf(false) }
    /** Set when the repository turned out to have a task list of its own — see [LinkOutcome.Asks]. */
    var asking by remember { mutableStateOf<LinkOutcome.Asks?>(null) }
    /** True while an existing repository is being looked up and attached. */
    var linking by remember { mutableStateOf(false) }

    /**
     * Whether the stored sign-in still works, re-asked whenever this screen comes forward.
     *
     * It used to do considerably more: two errands happened in the browser — granting access, then
     * creating the repository — and each ended with the user simply returning here, so resuming was
     * the only signal that either had happened. Both now happen without leaving the app, and what is
     * left is the one question a returning user really might have changed the answer to, by revoking
     * Yantra on GitHub while they were over there.
     */
    LifecycleResumeEffect(account) {
        val job = scope.launch {
            val tok = container.credentials.token(Credentials.ACCOUNT)
            if (account == null || tok == null) {
                signIn = null
                return@launch
            }
            // Asked twice before it is believed, and only for the one answer that costs something.
            //
            // "Sign in again" is the most expensive sentence this screen can say: acting on it mints
            // a new token, and GitHub keeps only ten of those per app before it starts revoking the
            // oldest — which is how a device that was working stops working. A single odd 401, from
            // a request that raced a network handover, is not worth that. Every other answer is
            // either good news or already says it is temporary, so neither needs confirming.
            val first = withContext(Dispatchers.IO) { container.github.signInState(tok) }
            signIn = if (first == SignInState.Unauthorized) {
                withContext(Dispatchers.IO) { container.github.signInState(tok) }
            } else {
                first
            }

            // Then the question this screen could not answer, which is the one that mattered.
            //
            // "Signed in" is about the *account* token. Sync authenticates with each workspace's own
            // copy, and the two can disagree — a copy goes stale and every push fails while this
            // screen stays green, saying the only reassuring thing it knows. Someone looking at it
            // has no way to tell a working sign-in from a broken workspace, so the report that
            // reaches us is "it does not sync", which names neither.
            //
            // Counting them here costs one request per workspace on a screen opened deliberately,
            // and turns that report into a number. It also makes the advice true: signing in now
            // replaces exactly the refused copies.
            if (signIn == SignInState.Ok) {
                val refused = withContext(Dispatchers.IO) {
                    container.credentials.storedIds()
                        .filter { it != Credentials.ACCOUNT }
                        .count { id ->
                            val held = container.credentials.token(id)
                            held == null ||
                                (held != tok &&
                                    container.github.signInState(held) == SignInState.Unauthorized)
                        }
                }
                if (refused > 0) {
                    note = "${refused} ${if (refused == 1) "workspace" else "workspaces"} cannot " +
                        "reach GitHub — its saved access was refused. Sign in again here and it " +
                        "will be repaired."
                    noteBad = true
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(stage) {
        val waiting = stage as? Stage.Waiting ?: return@LaunchedEffect
        var interval = waiting.code.intervalSecs
        var offline = 0
        // Real elapsed time, not a count of intervals. The loop now stops while the app is in the
        // background, so summing the intervals it *meant* to wait would say four minutes had passed
        // when the code had been alive for twelve, and the screen would go on offering a code GitHub
        // had already expired.
        val startedAt = System.currentTimeMillis()
        fun elapsed() = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        while (elapsed() < waiting.code.expiresInSecs) {
            delay(interval * 1000L)
            // Nothing is asked while the app is in the background, because nothing *can* be.
            //
            // This is the whole of "having trouble connecting and then it worked". Pressing the
            // button sends you to the browser, and Android answers by cutting this app off:
            //
            //     Destroyed live tcp sockets for uids={10684}
            //     DNS Requested by 251, 10684(…), 4(FAIL), isBlocked=true
            //
            // `isBlocked=true` is the platform's background network firewall, not a bad network —
            // the browser resolved github.com on the same network two seconds either side of it.
            // So every poll made while you were away was guaranteed to fail, the screen reported
            // exactly that, and the first poll after you came back succeeded. It was telling the
            // truth about a fight it could not win.
            //
            // Waiting to be resumed is the fix rather than a workaround: the platform is right that
            // a backgrounded app should not be holding a connection open, and there is nothing to
            // learn in that window anyway. The one poll that matters is the one after you return.
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            when (val poll = withContext(Dispatchers.IO) { container.deviceAuth.poll(waiting.code) }) {
                is DevicePoll.Token -> {
                    struggling = null
                    val who = withContext(Dispatchers.IO) { container.github.account(poll.token) }
                    val login = who?.login
                    if (login == null) {
                        stage = failed("GitHub gave us a token it then would not accept")
                    } else {
                        // Every credential write in one place off the main thread.
                        //
                        // store() ends in commit() — a synchronous disk write — and encrypts through
                        // the Keystore first. One of those is not worth a thread hop; one per
                        // workspace, on the thread drawing the screen, is how a sign-in becomes a
                        // freeze on the phone with the most workspaces.
                        withContext(Dispatchers.IO) {
                            // Read before it is overwritten: any workspace still holding this exact
                            // string took its token from the account, whatever its viaApp flag says.
                            val previous = container.credentials.token(Credentials.ACCOUNT)
                            container.credentials.store(
                                Credentials.ACCOUNT, poll.token, login, viaApp = true,
                                // Kept whether or not GitHub sends them. Both null means the token
                                // does not lapse; anything else is what TokenRenewal needs.
                                refreshToken = poll.refreshToken,
                                expiresAt = poll.expiresInSecs?.let {
                                    System.currentTimeMillis() + it * 1000L
                                },
                                // GitHub's own id for this account, which outlives a rename in a way
                                // the login does not. Free here — we already have the answer in hand.
                                accountId = who.id,
                            )

                            // Down to the workspaces, or the new token reaches nothing that syncs.
                            // Their copies are snapshots, and a sign-in that leaves them behind
                            // fixes this screen and nothing else.
                            container.credentials.spreadToWorkspaces(
                                poll.token, login, replacing = previous,
                            )

                            // Then the ones no flag and no comparison can identify: a workspace
                            // linked under an account token older than the one just replaced matches
                            // neither.
                            //
                            // Asked rather than assumed, because the alternative is guessing.
                            // Handing the account's token to every workspace of the same login would
                            // repair these and would also quietly replace a pasted fine-grained
                            // token with a broader one nobody asked for — undoing a deliberate
                            // choice, invisibly. A token GitHub refuses is not a choice worth
                            // keeping, and one it accepts is working, whatever it is.
                            //
                            // Affordable only here: one request per workspace, at the one moment the
                            // user is already waiting on the network and a fresh token exists.
                            container.credentials.storedIds()
                                .filter {
                                    it != Credentials.ACCOUNT &&
                                        container.credentials.login(it) == login
                                }
                                .filter { id ->
                                    val held = container.credentials.token(id)
                                    held != null && held != poll.token &&
                                        container.github.signInState(held) ==
                                        SignInState.Unauthorized
                                }
                                .forEach {
                                    container.credentials.store(it, poll.token, login, viaApp = true)
                                }
                        }

                        account = login
                        // Freshly minted seconds ago by GitHub itself, so there is nothing to ask.
                        signIn = SignInState.Ok
                        stage = Stage.Idle
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
                //
                // **Not on the first miss, though.** This polls every few seconds for up to fifteen
                // minutes while you are in another app approving, and one request in that window
                // failing is unremarkable: the phone hands off between Wi-Fi and mobile when the
                // browser opens, and a pooled keep-alive socket that the network dropped in the
                // meantime fails once and then reconnects. Warning on a single miss meant the
                // screen announced trouble during sign-ins that were going perfectly well and
                // completed seconds later — which teaches you to distrust the message, and the
                // message is worth trusting when the network really is gone.
                is DevicePoll.Offline -> {
                    offline++
                    // What actually went wrong, written down — CALENDAR_PLAN.md §26.
                    //
                    // The reason used to exist only as a sentence on screen, which meant a sign-in
                    // that warned and then succeeded left no trace of *why* it warned. "It said it
                    // was having trouble and then worked" is not something anybody can act on, and
                    // guessing at the cause from the outside is how this sort of thing gets a fix
                    // aimed at the wrong layer.
                    //
                    // The exception's own message and nothing else: no code, no token. A device
                    // code is a credential for the next fifteen minutes.
                    ie.shoonya.yantra.Trace.warn("signin", "poll $offline could not reach GitHub: ${poll.reason}")
                    if (offline >= MAX_OFFLINE_POLLS) {
                        stage = failed("Cannot reach GitHub — ${poll.reason}")
                        return@LaunchedEffect
                    }
                    if (offline >= QUIET_OFFLINE_POLLS) struggling = poll.reason
                }
                // GitHub sets the floor and we take it. Polling faster than asked is how an OAuth
                // app gets rate-limited for every install of it, not just this one.
                is DevicePoll.SlowDown -> {
                    struggling = null
                    interval = poll.intervalSecs
                }
                DevicePoll.Pending -> {
                    // Only worth a line when it is recovering from something, so an ordinary
                    // sign-in stays quiet and a recovered one is visible as a recovery.
                    if (offline > 0) {
                        ie.shoonya.yantra.Trace.log("signin", "reached GitHub again after $offline miss(es)")
                    }
                    offline = 0
                    struggling = null
                }
            }
        }
        stage = failed("The code expired. Start again for a fresh one")
    }

    Column(Modifier.fillMaxSize().background(y.page).statusBarsPadding()) {
        val fold = rememberHeaderFold()
        PageHeader("GitHub", onBack = { nav.popBackStack() }, collapsed = fold.collapsed)

        Column(
            Modifier
                .fillMaxWidth()
                .nestedScroll(fold.connection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 40.dp),
        ) {
            if (account != null) {
                SignedIn(
                    account = account!!,
                    signIn = signIn,
                    localSlug = localSlug,
                    repoName = repoName,
                    creating = creating,
                    note = note,
                    noteBad = noteBad,
                    onRepoName = { repoName = it; note = null },
                    onCreate = {
                        note = null
                        noteBad = false
                        creating = true
                        scope.launch {
                            try {
                                val tok = container.credentials.token(Credentials.ACCOUNT)
                                val who = account
                                if (tok == null || who == null) {
                                    note = "Sign in again — we do not know who you are"
                                    noteBad = true
                                    return@launch
                                }
                                val made = withContext(Dispatchers.IO) {
                                    container.github.createRepo(repoName.trim(), tok)
                                }
                                when (made) {
                                    // Attached straight away rather than reported and left for a
                                    // second tap. An empty repository nobody is pushing to is not
                                    // what anyone asked for — making it was only ever the first
                                    // half of "put my tasks somewhere".
                                    is RepoCreate.Ok -> when (
                                        val outcome = linkCreated(container, made.ref, tok)
                                    ) {
                                        null -> {
                                            note = "Made ${made.ref.slug}, but it is not answering yet"
                                            noteBad = true
                                        }
                                        is LinkOutcome.Done -> {
                                            note = outcome.said.message
                                            noteBad = !outcome.said.ok
                                            localSlug = container.slugOf("")
                                        }
                                        is LinkOutcome.Asks -> asking = outcome
                                    }
                                    RepoCreate.Exists -> {
                                        note = "You already have a ${repoName.trim()} — use it below, " +
                                            "or pick another name"
                                        noteBad = true
                                    }
                                    RepoCreate.Unauthorized -> {
                                        note = "This sign-in cannot make repositories. Sign in again."
                                        noteBad = true
                                        signIn = SignInState.Unauthorized
                                    }
                                    is RepoCreate.Failed -> {
                                        note = made.message
                                        noteBad = true
                                    }
                                }
                            } finally {
                                creating = false
                            }
                        }
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
                                        val outcome =
                                            linkCreated(container, RepoRef(who, repoName.trim()), tok)
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
                        signIn = null
                        note = null
                        stage = Stage.Idle
                    },
                )
                return@Column
            }

            Text(
                "Sync your tasks across devices, and share a list with people who can add to it.",
                color = y.textSecondary,
                fontSize = YantraType.label,
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
                        // Copied on the way out, every time.
                        //
                        // Copying used to be a separate tap on the code box that you had to know
                        // was there, so the ordinary route — read the code, press Open GitHub —
                        // arrived at GitHub's page with nothing on the clipboard. There is no way
                        // to tell that apart from a page that refuses to paste, and it reads as the
                        // second one.
                        //
                        // GitHub cannot be made to fill the field in for us: `?user_code=` on the
                        // verification URL is carried through their sign-in redirect but ignored on
                        // arrival — tried on a phone, the box came up empty. The web flow that would
                        // avoid the code entirely needs a client secret even with PKCE, and a secret
                        // shipped inside an APK is not a secret. So the code stays, and the most
                        // that can be done is to make sure it is always there to paste.
                        onOpen = {
                            ctx.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("code", s.code.userCode))
                            copied = true
                            uri.openUri(s.code.verificationUri)
                        },
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
                            "GitHub will ask you to approve access to your repositories. Yantra "
                                + "reads and writes task files, and makes the one private repository "
                                + "you ask it for — it never touches your code.",
                            color = y.textDim,
                            fontSize = YantraType.caption,
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
                    fontSize = YantraType.meta,
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
    signIn: SignInState?,
    localSlug: String?,
    repoName: String,
    creating: Boolean,
    note: String?,
    noteBad: Boolean,
    onRepoName: (String) -> Unit,
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
            .background(y.cardBg, RoundedCornerShape(YantraRadius.card))
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        YantraIcon(YantraMark.Check, tint = y.accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(account, color = y.textPrimary, fontFamily = YantraText, fontWeight = FontWeight.W700, fontSize = YantraType.row)
            Text(
                "The name on your commits, and who a task is assigned to",
                color = y.textMuted,
                fontSize = YantraType.caption,
            )
        }
    }

    when (signIn) {
        SignInState.Unauthorized -> {
            Spacer(Modifier.height(26.dp))
            Note(
                "This sign-in no longer works — it may have been revoked, or the key that protects "
                    + "it was replaced when this device was restored. Sign in again.",
                bad = true,
            )
        }

        is SignInState.Failed -> {
            Spacer(Modifier.height(26.dp))
            Note("Could not reach GitHub: ${signIn.message}")
        }

        SignInState.Ok -> {
            Spacer(Modifier.height(26.dp))
            SectionLabel(if (localSlug == null) "Back up your tasks" else "Your tasks")
            Spacer(Modifier.height(2.dp))
            Text(
                localSlug?.let { "Everything on this device is pushed to $it" }
                    ?: "Your tasks are only on this phone. A private repository gives them somewhere "
                    + "to live and a second device to appear on.",
                color = y.textMuted,
                fontSize = YantraType.meta,
            )
            if (localSlug == null) {
                Spacer(Modifier.height(12.dp))
                YantraField(repoName, onRepoName, "repository name", mono = true)
                Spacer(Modifier.height(10.dp))
                YantraButton(
                    label = "Create a private repository",
                    modifier = Modifier.fillMaxWidth(),
                    busy = creating,
                    enabled = repoName.isNotBlank() && !creating && !linking,
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
                    enabled = repoName.isNotBlank() && !creating && !linking,
                    onClick = onUseExisting,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (creating) "Making it, and moving your tasks in."
                    else "Made private, here, without opening GitHub. Nobody else can see it until "
                        + "you invite them.",
                    color = y.textDim,
                    fontSize = YantraType.caption,
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
        fontSize = YantraType.caption,
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
    SectionLabel("Your code")
    Spacer(Modifier.height(10.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .background(y.cardBg, RoundedCornerShape(YantraRadius.card))
            .border(1.dp, y.tileBorder, RoundedCornerShape(YantraRadius.card))
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
                fontSize = YantraType.hero,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                YantraIcon(YantraMark.Copy, tint = y.textDim)
                Text(if (copied) "Copied" else "Tap to copy", color = y.textDim, fontSize = YantraType.caption)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    YantraButton(
        label = "Copy and open GitHub",
        modifier = Modifier.fillMaxWidth(),
        mark = YantraMark.OpenOut,
        onClick = onOpen,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "The code is copied when you open GitHub, so it is there to paste. GitHub asks for it "
            + "once and then remembers this phone.",
        color = y.textDim,
        fontSize = YantraType.caption,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        if (struggling == null) "Come back here once you have approved it — this screen picks it " +
            "up as soon as you do."
        else "Having trouble reaching GitHub — still trying. Your code is still good.",
        color = if (struggling == null) y.textMuted else y.warning,
        fontSize = YantraType.meta,
    )
}

/**
 * How many consecutive unanswered polls before giving up.
 *
 * Roughly half a minute at GitHub's five-second floor: long enough to ride out a handover between
 * wifi and mobile, short enough that a genuinely dead network is not mistaken for a hang.
 */
/**
 * How many consecutive misses before the screen says anything.
 *
 * Two, so a single dropped request stays silent and a network that is actually gone is still named
 * within about ten seconds. One was too eager — see the Offline branch — and saying nothing at all
 * would put us back to a screen that reads "waiting for you" while nothing is reaching GitHub.
 */
private const val QUIET_OFFLINE_POLLS = 2

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
 * [ie.shoonya.yantra.data.sync.LinkResult.HasTasks].
 */
internal sealed interface LinkOutcome {
    data class Done(val said: Said) : LinkOutcome
    data class Asks(val slug: String, val localTasks: Int, val token: String) : LinkOutcome
}

private suspend fun linkCreated(
    container: AppContainer,
    ref: RepoRef,
    token: String,
): LinkOutcome? = withContext(Dispatchers.IO) {
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
        // Not there yet. Straight after creating one this is a repository GitHub has acknowledged
        // and not yet begun serving, which settles itself in a second or two.
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
                    fontSize = YantraType.section,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 1.4.sp,
                    color = y.accent,
                )
            }
        },
        dismissButton = { TextButton(onClick = onKeepBoth) { Text("Keep both") } },
    )
}
