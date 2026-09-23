import SwiftUI
import YantraCore

/// Adding a workspace — `AddWorkspaceScreen.kt`.
///
/// The two things people arrive wanting are genuinely different operations, so they are two modes
/// rather than one field that tries to tell them apart. Joining a repository that already exists is
/// the common case — someone sent you a link, or it is your own project — and creating one is how a
/// shared list starts.
///
/// What makes this safe to point at a working codebase is the branch. Tasks are committed to
/// `yantra-tasks`, which shares no history with anything else in the repository: the code is never
/// downloaded, never touched, and never appears in a diff beside a checkbox.
///
/// Inviting people goes to GitHub, because it needs Administration rights this app has no reason to
/// hold — and for the same reason, nothing here can delete a repository.
struct AddWorkspaceView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.openURL) private var openURL
    @Binding var path: NavigationPath

    @State private var joining = true
    @State private var repoText = ""
    @State private var name = ""
    @State private var busy = false
    @State private var note: (String, Bool)? = nil
    @State private var added: String?
    @State private var mine: [GitHubTransport.Listed] = []
    @State private var loading = false

    private var signedIn: Bool { SyncSettings.login != nil }
    private var alreadyLinked: Set<String> {
        Set(model.allStores.compactMap { SyncSettings.repo(for: $0.id)?.slug })
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack { NavCircle(mark: .back) { path.removeLast() }; Spacer() }
                Text("Add a workspace").font(Face.display(32)).tracking(-0.6).foregroundStyle(y.ink)
                    .padding(.top, 18).padding(.bottom, 4)

                if !signedIn {
                    Text("A workspace is a GitHub repository, so this needs a GitHub sign-in first.")
                        .font(Face.text(14)).foregroundStyle(y.secondary)
                    YantraButton(label: "Sign in with GitHub", tone: .soft) { path.append(Route.github) }
                } else {
                    Text("Each workspace is its own repository. Home shows every list you have, wherever it lives, and Today spans all of them.")
                        .font(Face.text(14)).foregroundStyle(y.secondary)

                    HStack(spacing: 8) {
                        SelectChip(label: "Existing repo", selected: joining, stretch: true) { joining = true; note = nil }
                        SelectChip(label: "New shared repo", selected: !joining, stretch: true) { joining = false; note = nil }
                    }.padding(.top, 6)

                    if joining { join } else { make }

                    if let (text, bad) = note { Note(text: text, bad: bad) }
                    if let slug = added {
                        // Inviting needs Administration rights, which is far more than reading and
                        // writing task files. So it happens where it belongs: on the repository's
                        // own settings page.
                        Button { openURL(GitHubAuth.accessSettingsURL(slug)) } label: {
                            HStack {
                                Text("Invite people to \(slug)").font(Face.text(14, .semibold)).foregroundStyle(y.accentText)
                                Spacer()
                                YantraIcon(mark: .openOut, size: YantraIcons.small, tint: y.accentText)
                            }.padding(14).background(RoundedRectangle(cornerRadius: 12).fill(y.accentFill))
                        }.buttonStyle(.plain)
                        YantraButton(label: "Done", tone: .quiet) { path.removeLast() }
                    }
                }
                Spacer().frame(height: 40)
            }.padding(.horizontal, Layout.pageMargin).padding(.top, 8).readableColumn()
        }
        .background(y.page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task { await load() }
    }

    @ViewBuilder private var join: some View {
        SectionLabel(text: "Your repositories").padding(.top, 12)
        if loading {
            Text("Asking GitHub…").font(Face.text(12.5)).foregroundStyle(y.muted).padding(.vertical, 6)
        } else if mine.isEmpty {
            Text("None came back. Type one below, or make a new one.").font(Face.text(12.5)).foregroundStyle(y.muted)
        } else {
            ForEach(mine.filter { !alreadyLinked.contains($0.slug) }.prefix(10)) { r in
                Button { repoText = r.slug; Task { await add() } } label: {
                    HStack(spacing: 10) {
                        YantraIcon(mark: .list, size: YantraIcons.small, tint: y.secondary)
                        Text(r.slug).font(Face.mono(13)).foregroundStyle(r.canPush ? y.ink : y.dim).lineLimit(1)
                        Spacer(minLength: 6)
                        if !r.canPush { Text("read only").font(Face.text(11)).foregroundStyle(y.dim) }
                    }
                    .padding(.horizontal, 14).padding(.vertical, 11)
                    .background(RoundedRectangle(cornerRadius: 12).fill(y.cardBg))
                }.buttonStyle(.plain).disabled(!r.canPush || busy).padding(.bottom, 6)
            }
        }
        SectionLabel(text: "Or type one").padding(.top, 8)
        Text("Paste the address, or type owner/name").font(Face.text(12.5)).foregroundStyle(y.muted)
        field($repoText, "github.com/you/project")
        if !repoText.isEmpty {
            Text(RepoRef.parse(repoText).map { "Will use \($0.slug)" } ?? "No repository in that — paste its address, or type owner/name")
                .font(Face.text(12)).foregroundStyle(RepoRef.parse(repoText) != nil ? y.secondary : y.dim)
        }
        YantraButton(label: busy ? "Adding…" : "Add workspace", tone: .soft,
                     enabled: RepoRef.parse(repoText) != nil && !busy) { Task { await add() } }
        Text("Tasks are kept on a branch called yantra-tasks. Your code is never downloaded and never changed — the two never share a commit.")
            .font(Face.text(12)).foregroundStyle(y.dim)
    }

    @ViewBuilder private var make: some View {
        // Which sign-in is in use decides whether this can happen in the app at all: only the `repo`
        // scope can `POST /user/repos`, and a GitHub App's permissions are fixed at registration.
        // The button says which it is rather than opening a browser under a label that promised
        // otherwise.
        let canMake = SyncSettings.method.makesRepos
        SectionLabel(text: "Name").padding(.top, 12)
        Text(canMake
             ? "A new private repository, for tasks only"
             : "Your sign-in sees only the repositories you install Yantra on, which cannot include one that does not exist yet. GitHub's form opens filled in — make it, install Yantra on it, then add it as an existing repo.")
            .font(Face.text(12.5)).foregroundStyle(y.muted).fixedSize(horizontal: false, vertical: true)
        field($name, "team-tasks")
        if canMake {
            YantraButton(label: busy ? "Making it…" : "Create the repository", tone: .soft,
                         enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty && !busy) { Task { await create() } }
            Text("Made private, here. Invite people to it once it exists.").font(Face.text(12)).foregroundStyle(y.dim)
        } else {
            YantraButton(label: "Make it on GitHub", tone: .soft, mark: .openOut,
                         enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty) {
                openURL(GitHubAuth.newRepoURL(name: name.trimmingCharacters(in: .whitespaces)))
                joining = true
                repoText = name.trimmingCharacters(in: .whitespaces)
            }
        }
    }

    private func field(_ text: Binding<String>, _ placeholder: String) -> some View {
        TextField(placeholder, text: text).font(Face.mono(14)).foregroundStyle(y.ink)
            .autocorrectionDisabled().textInputAutocapitalization(.never)
            .padding(14).background(RoundedRectangle(cornerRadius: 12).fill(y.surfaceHigh))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(y.tileBorder, lineWidth: 1))
    }

    private func load() async {
        guard signedIn, mine.isEmpty, !loading, let token = await SyncSettings.freshToken() else { return }
        loading = true
        mine = await GitHubTransport.repositories(token: token)
        loading = false
    }

    private func create() async {
        guard let token = await SyncSettings.freshToken() else { note = ("Signed out — sign in again", true); return }
        busy = true
        switch await GitHubTransport.createRepo(name: name.trimmingCharacters(in: .whitespaces), token: token) {
        case let .ok(ref):
            busy = false
            // Built on the reference GitHub sent back, not on the name that was typed: GitHub
            // normalises names, and a workspace pointed at what somebody typed would push to a
            // repository that is not there.
            repoText = ref.slug
            await add()
        case .exists:
            busy = false
            note = ("You already have a repository called that — add it as an existing repo instead", true)
            joining = true
        case .unauthorized:
            busy = false
            note = ("This sign-in is not allowed to make repositories. Make one on GitHub, then add it here.", true)
        case let .failed(why):
            busy = false
            note = ("Could not make it: \(why)", true)
        }
    }

    /// Joins the repository as a **new** workspace of its own.
    ///
    /// Nothing is seeded before the first look. A device that scaffolded a starter set and then
    /// merged it into a repository that already had one is how you end up with two Inboxes and two
    /// Todays, both claiming the same system key — so this asks the repository what is there first,
    /// and only starts a workspace when the answer is "nothing".
    private func add() async {
        guard let ref = RepoRef.parse(repoText) else { return }
        guard !alreadyLinked.contains(ref.slug) else {
            note = ("\(ref.slug) is already one of your workspaces", true); return
        }
        guard let token = await SyncSettings.freshToken() else { note = ("Signed out — sign in again", true); return }
        busy = true; defer { busy = false }

        switch await GitHubTransport(repo: ref, token: token).check() {
        case .ok(true): break
        case .ok(false): note = ("You can read \(ref.slug) but cannot push to it", true); return
        case .notFound: note = ("\(ref.slug) is not there, or Yantra has not been given access to it", true); return
        case .unauthorized: note = ("Yantra was not given access to \(ref.slug)", true); return
        case let .failed(e): note = ("Could not reach GitHub: \(e)", true); return
        }

        let id = UUID().uuidString.lowercased()
        let dir = model.registry.dir(for: id)
        let store = WorkspaceStore(root: dir, id: id)
        let label = ref.name
        SyncSettings.setRepo(ref, for: id)

        let transport = GitHubTransport(repo: ref, token: token)
        let remoteHasWorkspace: Bool
        do {
            let head = try await transport.head(branch: SyncEngine.branch)
            remoteHasWorkspace = head.map { WorkspaceLink.looksLikeAWorkspace($0.tree.keys) } ?? false
        } catch {
            // Nothing has been written but the directory and a preference, and both go: a half-made
            // workspace coming back on every launch is worse than a failed button.
            undo(id, dir)
            note = ("Could not reach \(ref.slug): \(error)", true)
            return
        }

        // A workspace that is joining takes what is there. A workspace that is starting one gets a
        // manifest and nothing else — no starter Inbox, which belongs to this device's own list and
        // would arrive in a shared repository as somebody else's clutter.
        store.scaffold(name: label, now: Int64(Date().timeIntervalSince1970 * 1000))
        model.registry.add(WorkspaceEntry(id: id, name: label, slug: ref.slug))
        model.workspacesChanged()

        let result = remoteHasWorkspace
            ? await SyncSettings.adoptRepository(store: store)
            : await SyncSettings.syncNow(store: store, message: "start a workspace")
        model.workspacesChanged()

        if let error = result.error {
            undo(id, dir)
            model.workspacesChanged()
            note = ("Could not add it: \(error)", true)
            return
        }
        // A workspace that was joined already has a name, chosen by whoever started it: taking ours
        // over theirs would rename the same shared project on every device. And that name can
        // already be taken here — joining your own repository gives you a second "Personal", and two
        // workspaces answering to one name are indistinguishable in every list that shows them — so
        // the slug, which is what actually tells them apart, is what disambiguates.
        let theirName = model.stores[id]?.readManifest()?.name
        let chosen = (theirName?.isEmpty == false ? theirName! : label)
        let taken = model.allStores.filter { $0.id != id }.map { model.workspaceName($0.id) }
        model.registry.add(WorkspaceEntry(id: id, name: taken.contains(chosen) ? "\(chosen) · \(ref.slug)" : chosen, slug: ref.slug))
        model.workspacesChanged()

        // The roster for the workspace just joined, for the same reason as on the GitHub screen.
        await People.shared.refresh(workspaceId: id)

        added = ref.slug
        note = (remoteHasWorkspace ? "Joined \(ref.slug). Its tasks are on their way in."
                                   : "Started \(ref.slug). It is yours to fill.", false)
    }

    private func undo(_ id: String, _ dir: URL) {
        SyncSettings.setRepo(nil, for: id)
        model.registry.remove(id)
        try? FileManager.default.removeItem(at: dir)
    }
}

/// One linked workspace: where it points, how its last sync went, and how to remove it.
struct WorkspaceView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.openURL) private var openURL
    @Binding var path: NavigationPath
    let workspaceId: String
    @State private var confirming = false

    var body: some View {
        let entry = model.registry.entry(workspaceId)
        let store = model.stores[workspaceId]
        let repo = SyncSettings.repo(for: workspaceId)
        let tasks = model.index.nodes.values.filter { $0.workspaceId == workspaceId && $0.type == NodeType.task }.count

        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack { NavCircle(mark: .back) { path.removeLast() }; Spacer() }
                HStack(spacing: 10) {
                    Circle().fill(LabelPalette.swatchColor(model.workspaceColorName(workspaceId), dark: y.dark) ?? y.dim)
                        .frame(width: 12, height: 12)
                    Text(entry?.name ?? store?.readManifest()?.name ?? "Workspace")
                        .font(Face.display(30)).tracking(-0.6).foregroundStyle(y.ink)
                }.padding(.top, 18)
                Text(repo.map { "Kept in \($0.slug)" } ?? "Not connected to a repository")
                    .font(Face.text(14)).foregroundStyle(y.secondary)
                Text("\(tasks) task\(tasks == 1 ? "" : "s") on this device")
                    .font(Face.text(12.5)).foregroundStyle(y.muted)

                SectionLabel(text: "Colour").padding(.top, 22)
                Text("How this workspace's lists are marked wherever they appear beside another's")
                    .font(Face.text(12.5)).foregroundStyle(y.muted)
                let chosen = model.workspaceColorName(workspaceId)
                HStack(spacing: 12) {
                    ForEach(LabelPalette.swatches, id: \.name) { swatch in
                        Button { model.setWorkspaceColor(swatch.name, for: workspaceId) } label: {
                            Circle().fill(LabelPalette.swatchColor(swatch.name, dark: y.dark) ?? y.dim)
                                .frame(width: 30, height: 30)
                                .overlay(Circle().stroke(chosen == swatch.name ? y.ink : .clear, lineWidth: 2).padding(-4))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(swatch.name)
                    }
                }.padding(.top, 8)

                SectionLabel(text: "Sync").padding(.top, 20)
                Text(SyncSettings.status(for: workspaceId) ?? "Every change is saved to a file and committed on its own")
                    .font(Face.text(12.5)).foregroundStyle(y.muted)
                YantraButton(label: model.syncing ? "Syncing…" : "Sync now", tone: .quiet, enabled: repo != nil && !model.syncing) {
                    model.syncInBackground("asked to sync")
                }

                // Who is on it, and how to add somebody — the thing a shared workspace is for.
                SectionLabel(text: "People").padding(.top, 22)
                PeoplePanel(workspaceId: workspaceId)

                if let repo {
                    SectionLabel(text: "On GitHub").padding(.top, 20)
                    Button { openURL(GitHubAuth.accessSettingsURL(repo.slug)) } label: {
                        HStack {
                            Text("Invite people").font(Face.text(14, .semibold)).foregroundStyle(y.ink)
                            Spacer(); YantraIcon(mark: .openOut, size: YantraIcons.small, tint: y.dim)
                        }.padding(14).background(RoundedRectangle(cornerRadius: 12).fill(y.cardBg))
                    }.buttonStyle(.plain)
                }

                SectionLabel(text: "Remove").padding(.top, 24)
                // Said in full, because the word "remove" next to a repository reads as deleting it
                // and this does nothing of the kind.
                Text("Takes this workspace off this device. The repository and every task in it stay exactly where they are — adding it again brings all of it back. Deleting a repository is done on GitHub, by somebody who means it.")
                    .font(Face.text(12.5)).foregroundStyle(y.muted)
                if confirming {
                    YantraButton(label: "Remove \(entry?.name ?? "this workspace") from this device", tone: .quiet) {
                        model.forgetWorkspace(workspaceId)
                        path.removeLast()
                    }
                    YantraButton(label: "Keep it", tone: .soft) { confirming = false }
                } else {
                    YantraButton(label: "Remove from this device", tone: .quiet) { confirming = true }
                }
                Spacer().frame(height: 40)
            }.padding(.horizontal, Layout.pageMargin).padding(.top, 8).readableColumn()
        }
        .background(y.page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }
}
