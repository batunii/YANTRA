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

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack { NavCircle(mark: .back) { path.removeLast() }; Spacer() }
                Text("GitHub").font(Face.display(32)).tracking(-0.6).foregroundStyle(y.ink).padding(.top, 18).padding(.bottom, 8)
                if let login { signedIn(login) } else { signedOut }
                Spacer().frame(height: 40)
            }.padding(.horizontal, Layout.pageMargin).padding(.top, 8)
        }
        .background(y.page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }

    // MARK: signed out

    @ViewBuilder private var signedOut: some View {
        Text("Sync your tasks across devices, and share a list with people who can add to it.").font(Face.text(14.5)).foregroundStyle(y.secondary)
        switch stage {
        case .idle, .failed, .starting:
            YantraButton(label: "Sign in with GitHub", tone: .soft, enabled: stage != .starting) { Task { await start() } }
            Text("Asks to read and write files in your repositories — enough to keep task lists there, and nothing that can delete a repository or change who can see it. You approve it on GitHub.")
                .font(Face.text(12)).foregroundStyle(y.dim)
            if case let .failed(why) = stage { Note(text: why, bad: true) }
        case let .waiting(code):
            SectionLabel(text: "Type this on GitHub").padding(.top, 8)
            Button {
                UIPasteboard.general.string = code.userCode; copied = true
            } label: {
                HStack {
                    Text(code.userCode).font(Face.mono(30, bold: true)).kerning(4).foregroundStyle(y.ink)
                    Spacer()
                    YantraIcon(mark: .copy, size: YantraIcons.medium, tint: y.secondary)
                    Text(copied ? "Copied" : "Tap to copy").font(Face.text(12)).foregroundStyle(y.muted)
                }.padding(16).background(RoundedRectangle(cornerRadius: 14).fill(y.cardBg))
            }.buttonStyle(.plain)
            YantraButton(label: "Open GitHub", tone: .soft, mark: .openOut) { openURL(URL(string: code.verificationUri)!) }
            Text("Waiting for you to approve it. This screen will notice by itself.").font(Face.text(12.5)).foregroundStyle(y.muted)
        }
    }

    private func start() async {
        stage = .starting
        do {
            let code = try await GitHubAuth.requestCode()
            stage = .waiting(code)
            openURL(URL(string: code.verificationUri)!)
            var interval = code.interval, offline = 0
            let deadline = Date().addingTimeInterval(TimeInterval(code.expiresIn))
            while Date() < deadline {
                try? await Task.sleep(for: .seconds(interval))
                switch await GitHubAuth.poll(code) {
                case .pending: continue
                case let .slowDown(i): interval = i
                case .offline: offline += 1; if offline >= 6 { stage = .failed("Cannot reach GitHub — check the connection and try again"); return }
                case .expired: stage = .failed("The code expired. Start again for a fresh one"); return
                case .denied: stage = .failed("You declined on GitHub"); return
                case let .failed(e): stage = .failed(e); return
                case let .token(t):
                    Keychain.accountToken = t
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

        if let repo = SyncSettings.repo {
            SectionLabel(text: "Your tasks").padding(.top, 10)
            Text("Everything on this device is pushed to \(repo.slug)").font(Face.text(14)).foregroundStyle(y.secondary)
            YantraButton(label: busy ? "Syncing…" : "Sync now", tone: .soft, enabled: !busy) { Task { await syncNow() } }
            if let s = SyncSettings.lastStatus { Text(s).font(Face.text(12.5)).foregroundStyle(s.hasPrefix("Not") ? y.overdue : y.muted) }
            YantraButton(label: "Disconnect repository", tone: .quiet) { SyncSettings.repo = nil; note = nil }
        } else {
            SectionLabel(text: "Back up your tasks").padding(.top, 10)
            Text("Your tasks are only on this phone. A private repository gives them somewhere to live and a second device to appear on.").font(Face.text(14)).foregroundStyle(y.secondary)
            SectionLabel(text: "Repository").padding(.top, 8)
            TextField("owner/name, or its address", text: $repoText).font(Face.mono(14)).foregroundStyle(y.ink).autocorrectionDisabled().textInputAutocapitalization(.never)
                .padding(14).background(RoundedRectangle(cornerRadius: 12).fill(y.surfaceHigh)).overlay(RoundedRectangle(cornerRadius: 12).stroke(y.tileBorder, lineWidth: 1))
            YantraButton(label: busy ? "Checking…" : "Use this repository", tone: .soft, enabled: RepoRef.parse(repoText) != nil && !busy) { Task { await connect() } }
            Text("Tasks are kept on a branch called yantra-tasks. Your code is never downloaded and never changed — the two never share a commit.").font(Face.text(12)).foregroundStyle(y.dim)
            SectionLabel(text: "Or make a new one").padding(.top, 8)
            HStack(spacing: 10) {
                TextField("repository name", text: $newName).font(Face.mono(14)).foregroundStyle(y.ink).autocorrectionDisabled().textInputAutocapitalization(.never)
                    .padding(14).background(RoundedRectangle(cornerRadius: 12).fill(y.surfaceHigh)).overlay(RoundedRectangle(cornerRadius: 12).stroke(y.tileBorder, lineWidth: 1))
                YantraButton(label: "Create", tone: .quiet, mark: .openOut) {
                    openURL(GitHubAuth.newRepoURL(name: newName)); repoText = "\(login)/\(newName)"
                }.frame(width: 120)
            }
            Text("Opens GitHub with the name and Private already filled in — press one button, then come back and tap Use this repository.").font(Face.text(12)).foregroundStyle(y.dim)
        }
        if let (text, bad) = note { Note(text: text, bad: bad) }
        YantraButton(label: "Sign out", tone: .quiet) { deleteAccountCredential() }.padding(.top, 10)
        Text("Signing out deletes the token from this device. Your workspace keeps its files.")
            .font(Face.text(12)).foregroundStyle(y.dim)
        // Yantra has no account of its own to delete — GitHub holds the authorisation, and this is
        // where it is withdrawn. Reachable from inside the app rather than described in a sentence,
        // because "go and find it on the website" is not a way to revoke anything.
        YantraButton(label: "Revoke access on GitHub", tone: .quiet, mark: .openOut) {
            deleteAccountCredential()
            openURL(GitHubAuth.revokeURL)
        }.padding(.top, 8)
        Text("Removes Yantra's permission on your account, for every device. Sign in again to undo it.")
            .font(Face.text(12)).foregroundStyle(y.dim)
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
    }

    private func connect() async {
        guard let ref = RepoRef.parse(repoText), let token = await SyncSettings.freshToken() else { return }
        busy = true; defer { busy = false }
        switch await GitHubTransport(repo: ref, token: token).check() {
        case .ok(true):
            SyncSettings.repo = ref
            let r = await SyncSettings.syncNow(store: model.store, message: "attach")
            model.wake()
            note = r.ok ? ("Your tasks are now in \(ref.slug)", false) : ("Could not sync: \(r.error ?? "")", true)
        case .ok(false): note = ("\(ref.slug) exists but Yantra cannot push to it", true)
        case .notFound: note = ("\(ref.slug) is not there, or Yantra has not been given access to it", true)
        case .unauthorized: note = ("Yantra was not given access to \(ref.slug)", true)
        case let .failed(e): note = ("Could not reach GitHub: \(e)", true)
        }
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
