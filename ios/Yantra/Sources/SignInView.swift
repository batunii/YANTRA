import SwiftUI
import YantraCore

/// GitHub: the device flow, the install trip, and connecting a repository — `SignInScreen`.
struct SignInView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var phase
    @Binding var path: NavigationPath

    enum Stage: Equatable { case idle, starting, waiting(GitHubAuth.DeviceCode), failed(String) }
    @State private var stage: Stage = .idle
    @State private var login: String? = SyncSettings.login
    @State private var repoText = SyncSettings.repo?.slug ?? ""
    @State private var newName = "yantra-tasks"
    @State private var note: (String, Bool)? = nil
    @State private var busy = false
    @State private var copied = false
    /// Set when polling has missed GitHub more than once, so the screen can say it is still trying
    /// rather than claiming to be waiting on the person.
    @State private var struggling: String?
    /// GitHub's approval page while it is up, presented over the app.
    @State private var approving: URL?
    /// Which bargain is being struck. Remembered once signed in, because everything the token can
    /// and cannot do follows from it.
    @State private var method: GitHubAuth.Method = SyncSettings.method
    /// Join something that exists, or start something that does not. Two genuinely different
    /// operations, so two modes rather than one field trying to tell them apart.
    @State private var joining = true
    /// The repositories this sign-in can actually see. Empty until they arrive, and empty is a
    /// perfectly ordinary answer — a fresh account has none.
    @State private var mine: [GitHubTransport.Listed] = []
    @State private var loadingRepos = false
    @State private var search = ""
    /// The repository already has tasks and this device has its own. Only the person can say which
    /// reading is true, so the screen asks rather than merging behind their back.
    @State private var forkInTheRoad: RepoRef?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack { NavCircle(mark: .back) { path.removeLast() }; Spacer() }
                Text("GitHub").font(Face.display(32)).tracking(-0.6).foregroundStyle(y.ink).padding(.top, 18).padding(.bottom, 8)
                if let login { signedIn(login) } else { signedOut }
                Spacer().frame(height: 40)
            }.padding(.horizontal, Layout.pageMargin).padding(.top, 8).readableColumn()
        }
        .background(y.page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .sheet(item: $approving) { url in
            // Nothing is read from the sheet closing: the poll already running is the authority on
            // whether the approval landed, and it notices by itself.
            WebSignIn(url: url) { approving = nil }.ignoresSafeArea()
        }
        .task { await loadRepos() }
        .onChange(of: login) { _, _ in Task { await loadRepos() } }
    }

    // MARK: signed out

    @ViewBuilder private var signedOut: some View {
        Text("Sync your tasks across devices, and share a list with people who can add to it.").font(Face.text(14.5)).foregroundStyle(y.secondary)
        switch stage {
        case .idle, .failed, .starting:
            // Two registrations, two bargains, and the difference is worth a screen rather than a
            // footnote: it decides how many devices you may use, whether Yantra can see a repository
            // without you installing it there, and whether a new repository can be made from inside
            // the app at all. Both are the device flow underneath.
            SectionLabel(text: "How much Yantra may see").padding(.top, 6)
            ForEach(GitHubAuth.offered(), id: \.self) { m in
                Button { method = m } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 8) {
                            YantraIcon(mark: method == m ? .check : .ring, size: YantraIcons.small,
                                       tint: method == m ? y.accent : y.dim)
                            Text(m.title).font(Face.text(14.5, .semibold)).foregroundStyle(y.ink)
                        }
                        Text(m.summary).font(Face.text(12)).foregroundStyle(y.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: Layout.cardRadius)
                        .fill(method == m ? y.accentFill.opacity(0.5) : y.cardBg))
                    .overlay(RoundedRectangle(cornerRadius: Layout.cardRadius)
                        .stroke(method == m ? y.accentBorder : y.tileBorder, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("signin.method.\(m.rawValue)")
            }
            YantraButton(label: "Sign in with GitHub", tone: .soft, enabled: stage != .starting) { Task { await start() } }
            Text("Asks to read and write files in your repositories — enough to keep task lists there, and nothing that can delete a repository or change who can see it. You approve it on GitHub.")
                .font(Face.text(12)).foregroundStyle(y.dim)
            if case let .failed(why) = stage { Note(text: why, bad: true) }
        case let .waiting(code):
            SectionLabel(text: "Your code").padding(.top, 8)
            Button { copy(code) } label: {
                HStack {
                    Text(code.userCode).font(Face.mono(30, bold: true)).kerning(4).foregroundStyle(y.ink)
                    Spacer()
                    YantraIcon(mark: .copy, size: YantraIcons.medium, tint: y.secondary)
                    Text(copied ? "Copied" : "Tap to copy").font(Face.text(12)).foregroundStyle(y.muted)
                }.padding(16).background(RoundedRectangle(cornerRadius: 14).fill(y.cardBg))
            }.buttonStyle(.plain)
            // Copied on the way out, every time — the same button, as on Android.
            //
            // Copying used to be a separate tap on the code box that you had to know was there, so
            // the ordinary route — read the code, press Open GitHub — arrived at GitHub's page with
            // nothing on the clipboard. There is no way to tell that apart from a page that refuses
            // to paste, and it reads as the second one.
            //
            // GitHub cannot be made to fill the field in for us: `?user_code=` on the verification
            // URL is carried through their sign-in redirect but ignored on arrival. The web flow
            // that would avoid the code entirely needs a client secret even with PKCE, and a secret
            // shipped inside an app is not a secret. So the code stays, and the most that can be
            // done is to make sure it is always there to paste.
            YantraButton(label: "Copy and continue", tone: .soft) {
                copy(code)
                // Inside the app, not out of it — see `WebSignIn` for why this is the device flow
                // wearing a web sheet rather than a real OAuth redirect.
                approving = URL(string: code.verificationUri)
            }
            Text("The code is copied when the page opens, so it is there to paste. GitHub asks for it once and then remembers this phone.")
                .font(Face.text(12)).foregroundStyle(y.dim)
            Text(struggling == nil
                 ? "Come back here once you have approved it — this screen picks it up as soon as you do."
                 : "Having trouble reaching GitHub — still trying. Your code is still good.")
                .font(Face.text(12.5)).foregroundStyle(struggling == nil ? y.muted : y.warning)
        }
    }

    private func copy(_ code: GitHubAuth.DeviceCode) {
        UIPasteboard.general.string = code.userCode
        copied = true
    }

    private func start() async {
        stage = .starting
        struggling = nil
        do {
            let code = try await GitHubAuth.requestCode(method)
            stage = .waiting(code)
            // Deliberately **not** opened here. Throwing the browser up the instant the code
            // arrives puts GitHub's "enter the code" page in front of somebody who has not seen the
            // code yet and has nothing on their clipboard to paste. The button below does both, in
            // that order, when they are ready.
            var interval = code.interval, offline = 0
            let deadline = Date().addingTimeInterval(TimeInterval(code.expiresIn))
            while Date() < deadline {
                try? await Task.sleep(for: .seconds(interval))
                switch await GitHubAuth.poll(code, method) {
                case .pending: struggling = nil; offline = 0; continue
                case let .slowDown(i): interval = i; struggling = nil; offline = 0
                case .offline:
                    offline += 1
                    // Two misses before saying anything: a single dropped request stays silent, and
                    // a network that is actually gone is named within about ten seconds rather than
                    // leaving a screen that reads "waiting for you" while nothing is reaching
                    // GitHub. Six before giving up — long enough to ride out a wifi handover.
                    if offline >= 6 { stage = .failed("Cannot reach GitHub — check the connection and try again"); return }
                    if offline >= 2 { struggling = "cannot reach GitHub" }
                case .expired: stage = .failed("The code expired. Start again for a fresh one"); return
                case .denied: stage = .failed("You declined on GitHub"); return
                case let .failed(e): stage = .failed(e); return
                case let .token(t):
                    Keychain.accountToken = t
                    // Stored before anything is done with it: the method decides which client id
                    // renews this token and what it is allowed to do.
                    SyncSettings.method = method
                    if let who = try? await GitHubTransport(repo: RepoRef(owner: "x", name: "x"), token: t.accessToken).viewer() {
                        SyncSettings.login = who.login; login = who.login
                    } else { stage = .failed("GitHub gave us a token it then would not accept"); return }
                    stage = .idle
                    return
                }
            }
            stage = .failed("The code expired. Start again for a fresh one")
        } catch { stage = .failed("Cannot reach GitHub — \(error.localizedDescription)") }
    }

    // MARK: signed in

    @ViewBuilder private func signedIn(_ login: String) -> some View {
        HStack(spacing: 12) {
            YantraIcon(mark: .check, size: YantraIcons.medium, tint: y.accent)
            VStack(alignment: .leading, spacing: 2) {
                Text(login).font(Face.text(15, .bold)).foregroundStyle(y.ink)
                Text("The name on your commits, and who a task is assigned to").font(Face.text(12)).foregroundStyle(y.muted)
            }
        }.padding(16).background(RoundedRectangle(cornerRadius: 14).fill(y.cardBg))

        // A GitHub App authenticates perfectly and can see nothing at all until it is installed on
        // the repositories it may read. That state is the most confusing one to leave somebody in —
        // signed in, and every repository "not found" — so it is said here rather than discovered.
        if SyncSettings.method.needsInstall {
            Button { openURL(GitHubAuth.installURL()) } label: {
                linkRow("Choose repositories on GitHub",
                        "This sign-in sees only the ones you install Yantra on")
            }.buttonStyle(.plain)
        }

        if let repo = SyncSettings.repo { connected(repo) } else { notConnected(login) }

        if let (text, bad) = note { Note(text: text, bad: bad) }
        YantraButton(label: "Sign out", tone: .quiet) { deleteAccountCredential() }.padding(.top, 10)
        Text("Signing out deletes the token from this device. Your workspace keeps its files.")
            .font(Face.text(12)).foregroundStyle(y.dim)
        // Yantra has no account of its own to delete — GitHub holds the authorisation, and this is
        // where it is withdrawn. Reachable from inside the app rather than described in a sentence,
        // because "go and find it on the website" is not a way to revoke anything.
        YantraButton(label: "Revoke access on GitHub", tone: .quiet, mark: .openOut) {
            deleteAccountCredential()
            openURL(GitHubAuth.revokeURL(SyncSettings.method))
        }.padding(.top, 8)
        Text("Removes Yantra's permission on your account, for every device. Sign in again to undo it.")
            .font(Face.text(12)).foregroundStyle(y.dim)
    }

    // MARK: a repository is connected

    @ViewBuilder private func connected(_ repo: RepoRef) -> some View {
        SectionLabel(text: "Your tasks").padding(.top, 10)
        Text("Everything on this device is pushed to \(repo.slug)").font(Face.text(14)).foregroundStyle(y.secondary)
        YantraButton(label: busy ? "Syncing…" : "Sync now", tone: .soft, enabled: !busy) { Task { await syncNow() } }
        if let s = SyncSettings.lastStatus { Text(s).font(Face.text(12.5)).foregroundStyle(s.hasPrefix("Not") ? y.overdue : y.muted) }

        // Who can push here, and how to add somebody — in the app where the sign-in allows it, and
        // on GitHub where it does not. See `PeoplePanel`.
        SectionLabel(text: "People").padding(.top, 18)
        PeoplePanel(workspaceId: "")

        SectionLabel(text: "On GitHub").padding(.top, 18)
        Button { openURL(GitHubAuth.repoSettingsURL(repo.slug)) } label: {
            linkRow("Repository settings", "Rename it, or delete it — Yantra never does either")
        }.buttonStyle(.plain)

        YantraButton(label: "Disconnect repository", tone: .quiet) {
            SyncSettings.repo = nil
            note = ("Disconnected. Your tasks stay on this device, and stay in \(repo.slug).", false)
        }.padding(.top, 14)
        Text("Stops syncing. Nothing is deleted on either side.").font(Face.text(12)).foregroundStyle(y.dim)
    }

    private func linkRow(_ title: String, _ subtitle: String) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(Face.text(15, .semibold)).foregroundStyle(y.ink)
                Text(subtitle).font(Face.text(12)).foregroundStyle(y.muted)
            }
            Spacer()
            YantraIcon(mark: .openOut, size: YantraIcons.small, tint: y.dim)
        }
        .padding(14).background(RoundedRectangle(cornerRadius: Layout.cardRadius).fill(y.cardBg)).padding(.top, 8)
    }

    // MARK: nothing is connected yet

    @ViewBuilder private func notConnected(_ login: String) -> some View {
        SectionLabel(text: "Back up your tasks").padding(.top, 10)
        Text("Your tasks are only on this phone. A private repository gives them somewhere to live and a second device to appear on.")
            .font(Face.text(14)).foregroundStyle(y.secondary)

        HStack(spacing: 8) {
            SelectChip(label: "A repository you have", selected: joining, stretch: true) { joining = true; note = nil }
            SelectChip(label: "Make a new one", selected: !joining, stretch: true) { joining = false; note = nil }
        }.padding(.top, 6)

        if joining { join } else { make(login) }

        Text("Tasks are kept on a branch called yantra-tasks. Your code is never downloaded and never changed — the two never share a commit.")
            .font(Face.text(12)).foregroundStyle(y.dim).padding(.top, 4)

        if let ref = forkInTheRoad { fork(ref) }
    }

    @ViewBuilder private var join: some View {
        // Typed from memory is how this gets pointed at a repository that is not there, and the
        // failure then arrives a screen later as a 404. The list is also the only honest answer for
        // an App installation given three repositories: a fourth cannot be reached however
        // correctly its name is spelled.
        SectionLabel(text: "Your repositories").padding(.top, 14)
        if loadingRepos {
            Text("Asking GitHub…").font(Face.text(12.5)).foregroundStyle(y.muted).padding(.vertical, 8)
        } else if mine.isEmpty {
            Text("None came back. Type one below, or make a new one.").font(Face.text(12.5)).foregroundStyle(y.muted).padding(.vertical, 8)
        } else {
            if mine.count > 6 {
                TextField("Search", text: $search).font(Face.text(14)).foregroundStyle(y.ink)
                    .autocorrectionDisabled().textInputAutocapitalization(.never)
                    .padding(12).background(RoundedRectangle(cornerRadius: 12).fill(y.surfaceHigh))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(y.tileBorder, lineWidth: 1))
                    .padding(.bottom, 6)
            }
            let shown = mine.filter { search.isEmpty || $0.slug.localizedCaseInsensitiveContains(search) }.prefix(12)
            ForEach(Array(shown)) { r in
                Button { repoText = r.slug; Task { await connect() } } label: {
                    HStack(spacing: 10) {
                        YantraIcon(mark: .list, size: YantraIcons.small, tint: y.secondary)
                        Text(r.slug).font(Face.mono(13)).foregroundStyle(r.canPush ? y.ink : y.dim).lineLimit(1)
                        Spacer(minLength: 6)
                        // Only the surprising half is said. Private is what a task list should be
                        // and what Yantra makes; public is the one worth noticing before you put
                        // your week into it.
                        if !r.isPrivate { Text("public").font(Face.text(11)).foregroundStyle(y.warning) }
                        // Said here rather than after a failed push: read access looks identical to
                        // write access until the first commit, a week later.
                        if !r.canPush { Text("read only").font(Face.text(11)).foregroundStyle(y.dim) }
                        if repoText == r.slug, busy { Text("…").font(Face.text(12)).foregroundStyle(y.muted) }
                    }
                    .padding(.horizontal, 14).padding(.vertical, 11)
                    .background(RoundedRectangle(cornerRadius: 12).fill(y.cardBg))
                }
                .buttonStyle(.plain).disabled(!r.canPush || busy).padding(.bottom, 6)
            }
        }

        SectionLabel(text: "Or type one").padding(.top, 10)
        TextField("owner/name, or its address", text: $repoText).font(Face.mono(14)).foregroundStyle(y.ink)
            .autocorrectionDisabled().textInputAutocapitalization(.never)
            .padding(14).background(RoundedRectangle(cornerRadius: 12).fill(y.surfaceHigh))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(y.tileBorder, lineWidth: 1))
        // What the address resolved to, said before anything is connected: the parser accepts a
        // link from anywhere inside a repository, and the price of being that forgiving is that the
        // person gets to see what it understood.
        if !repoText.isEmpty {
            Text(RepoRef.parse(repoText).map { "Will use \($0.slug)" } ?? "No repository in that — paste its address, or type owner/name")
                .font(Face.text(12)).foregroundStyle(RepoRef.parse(repoText) != nil ? y.secondary : y.dim)
        }
        YantraButton(label: busy ? "Checking…" : "Use this repository", tone: .soft,
                     enabled: RepoRef.parse(repoText) != nil && !busy) { Task { await connect() } }
    }

    @ViewBuilder private func make(_ login: String) -> some View {
        let canMake = SyncSettings.method.makesRepos
        SectionLabel(text: "Name").padding(.top, 14)
        Text(canMake
             ? "A new private repository, for tasks only"
             : "This sign-in cannot make repositories — only `repo` can, and a GitHub App's permissions are fixed at registration. GitHub's form opens filled in; make it, install Yantra on it, then add it as one you have.")
            .font(Face.text(12.5)).foregroundStyle(y.muted).fixedSize(horizontal: false, vertical: true)
        TextField("yantra-tasks", text: $newName).font(Face.mono(14)).foregroundStyle(y.ink)
            .autocorrectionDisabled().textInputAutocapitalization(.never)
            .padding(14).background(RoundedRectangle(cornerRadius: 12).fill(y.surfaceHigh))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(y.tileBorder, lineWidth: 1))
            .padding(.top, 8)
        if canMake {
            YantraButton(label: busy ? "Making it…" : "Create the repository", tone: .soft,
                         enabled: !newName.trimmingCharacters(in: .whitespaces).isEmpty && !busy) { Task { await createRepo(login) } }
            Text("Made private, and connected here. Invite people to it once it exists.")
                .font(Face.text(12)).foregroundStyle(y.dim)
        } else {
            // Nothing is attempted and nothing is claimed: the repository appears on GitHub, and is
            // added here afterwards like any other one you already have.
            YantraButton(label: "Make it on GitHub", tone: .soft, mark: .openOut,
                         enabled: !newName.trimmingCharacters(in: .whitespaces).isEmpty) {
                openURL(GitHubAuth.newRepoURL(name: newName.trimmingCharacters(in: .whitespaces)))
                joining = true
                repoText = "\(login)/\(newName.trimmingCharacters(in: .whitespaces))"
            }
        }
    }

    /// The repository has tasks, and so has this device.
    ///
    /// Not a refusal — a fork in the road, and one only the person can take. The two readings are
    /// indistinguishable from here and want opposite things: your own repository seen by a phone
    /// that has been reinstalled, where taking the repository is obviously right; or two real sets
    /// of work, where replacing one with the other would delete a task list. So it says what each
    /// button does in full, and does nothing until one is pressed.
    @ViewBuilder private func fork(_ ref: RepoRef) -> some View {
        SectionLabel(text: "\(ref.slug) already has tasks").padding(.top, 20)
        Text("There are tasks in that repository, and tasks on this phone, and they did not come from each other. Which of them is the real list?")
            .font(Face.text(13.5)).foregroundStyle(y.secondary).padding(.bottom, 4)
        YantraButton(label: busy ? "Joining…" : "Use the repository's tasks", tone: .soft, enabled: !busy) {
            Task { await adopt(ref) }
        }
        Text("What is on this phone is replaced by what is in \(ref.slug). Do this when it is your own list and this device has simply lost it.")
            .font(Face.text(12)).foregroundStyle(y.dim)
        YantraButton(label: "Keep both, and merge them", tone: .quiet, enabled: !busy) {
            Task { await mergeAnyway(ref) }
        }.padding(.top, 8)
        Text("Nothing is lost, and nothing is tidied: you end up with both lists side by side, including two of anything the two sets both start with.")
            .font(Face.text(12)).foregroundStyle(y.dim)
        YantraButton(label: "Not now", tone: .quiet, enabled: !busy) { forkInTheRoad = nil; SyncSettings.repo = nil }.padding(.top, 8)
    }

    /// Deletes the credential this device holds — the only thing Yantra stores about you.
    ///
    /// This is Yantra's account deletion, in the sense Guideline 5.1.1(v) asks about. The app
    /// creates no account: there is no sign-up, no server and no record of anybody. It signs in
    /// *with* GitHub and keeps one user token so it can push to a repository you already own, so
    /// deleting everything it holds is deleting that token. `GitHubAuth.revokeURL`, offered beside
    /// this, withdraws the authorisation itself at GitHub for every device — the half that is not
    /// ours to delete. See `docs/APP_STORE_SUBMISSION.md`.
    private func deleteAccountCredential() {
        Keychain.accountToken = nil
        SyncSettings.login = nil
        login = nil
        mine = []
    }

    /// Loads what this sign-in can see, once, when there is nothing connected to show instead.
    private func loadRepos() async {
        guard SyncSettings.repo == nil, mine.isEmpty, !loadingRepos, login != nil else { return }
        guard let token = await SyncSettings.freshToken() else { return }
        loadingRepos = true
        mine = await GitHubTransport.repositories(token: token)
        loadingRepos = false
    }

    private func createRepo(_ login: String) async {
        guard let token = await SyncSettings.freshToken() else {
            note = ("Signed out — sign in again to make a repository", true); return
        }
        busy = true; defer { busy = false }
        switch await GitHubTransport.createRepo(name: newName.trimmingCharacters(in: .whitespaces), token: token) {
        // Built on the reference GitHub sent back rather than on the name that was typed: GitHub
        // normalises names, and pointing the workspace at what somebody typed would push to a
        // repository that is not there.
        case let .ok(ref):
            repoText = ref.slug
            note = ("Made \(ref.slug). Putting your tasks in it…", false)
            await connect()
        case .exists:
            note = ("You already have a repository called that — join it instead", true)
            joining = true; repoText = "\(login)/\(newName.trimmingCharacters(in: .whitespaces))"
        // Said without naming a sign-in, because a credential narrow enough to sync and too narrow
        // to create a repository is an ordinary thing to hold, and "sign in again" is no use to
        // whoever holds one.
        case .unauthorized:
            note = ("This sign-in is not allowed to make repositories. Make one on GitHub, then join it here.", true)
        case let .failed(why):
            note = ("Could not make it: \(why)", true)
        }
    }

    /// Connects, asking first whether this device should adopt the repository or start it.
    ///
    /// The question has to be asked **before** the first merge, not during it. This used to call
    /// `syncNow` straight out, so a device that had seeded a starter workspace and was now joining
    /// a repository that already had one kept both — two Inboxes, two Todays, both claiming the
    /// same system key, and the sync reporting it as "2 conflicts resolved".
    private func connect() async {
        guard let ref = RepoRef.parse(repoText), let token = await SyncSettings.freshToken() else { return }
        busy = true; defer { busy = false }
        switch await NetworkActivity.shared.during(NetworkActivity.Words.checking, {
            await GitHubTransport(repo: ref, token: token).check()
        }) {
        case .ok(true): break
        case .ok(false): note = ("\(ref.slug) exists but Yantra cannot push to it", true); return
        case .notFound: note = ("\(ref.slug) is not there, or Yantra has not been given access to it", true); return
        case .unauthorized: note = ("Yantra was not given access to \(ref.slug)", true); return
        case let .failed(e): note = ("Could not reach GitHub: \(e)", true); return
        }
        SyncSettings.repo = ref
        let (decision, result) = await NetworkActivity.shared.during(NetworkActivity.Words.linking) {
            await SyncSettings.connect(store: model.store, message: "attach")
        }
        model.wake()
        switch decision {
        case .ask:
            // Nothing has been written. The repository stays set only so the buttons below know
            // which one is being talked about; "Not now" takes it back off.
            forkInTheRoad = ref
            note = nil
        case .adopt:
            note = result.error.map { ("Could not join: \($0)", true) } ?? ("Joined \(ref.slug) — its tasks are on their way in", false)
        case .push:
            note = result.error.map { ("Could not sync: \($0)", true) } ?? ("Your tasks are now in \(ref.slug)", false)
        }
        // Who can be assigned, asked once, here — the moment the app has a repository and a token
        // for it and is already on the network. Fetching it lazily instead meant a workspace arrived
        // knowing nobody, and "nobody has been loaded yet" and "nobody else can push here" look
        // identical from a picker. It is never allowed to fail the connection: the repository is
        // joined either way, and a roster is something to retry rather than a reason to refuse.
        await People.shared.refresh(workspaceId: "")
    }

    private func adopt(_ ref: RepoRef) async {
        busy = true; defer { busy = false }
        let r = await SyncSettings.adoptRepository(store: model.store)
        model.wake()
        forkInTheRoad = nil
        note = r.error.map { ("Could not join: \($0)", true) } ?? ("Joined \(ref.slug). Its tasks are here.", false)
    }

    /// Both lists, kept. The ordinary merge, reached deliberately rather than by accident.
    private func mergeAnyway(_ ref: RepoRef) async {
        busy = true; defer { busy = false }
        let r = await SyncSettings.syncNow(store: model.store, message: "attach")
        model.wake()
        forkInTheRoad = nil
        note = r.error.map { ("Could not sync: \($0)", true) } ?? ("Both lists are now in \(ref.slug)", false)
    }

    private func syncNow() async {
        busy = true; defer { busy = false }
        _ = await SyncSettings.syncNow(store: model.store, message: "asked to sync")
        model.wake()
    }
}

struct Note: View {
    let text: String
    var bad = false
    @Environment(\.y) private var y
    var body: some View {
        Text(text).font(Face.text(12.5)).foregroundStyle(y.ink).padding(12).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 10).fill(bad ? y.overdue.opacity(0.14) : y.surfaceHigh))
    }
}
