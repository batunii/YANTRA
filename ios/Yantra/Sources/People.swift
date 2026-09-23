import Foundation
import YantraCore

/// Somebody a task can be given to — `People.kt`.
///
/// `isYou` rather than comparing logins at every call site: which account is signed in is a fact
/// about the device, and a screen that has to look it up in order to say "you" will eventually
/// forget to.
///
/// `onRepo` is deliberately three-valued. False means GitHub has told us who can push here and this
/// person is not among them — an assignment nobody will ever see. **Nil means we do not know**, and
/// that is a different thing: no roster has been fetched, because the phone has been offline, or
/// nobody has asked for one, or GitHub refuses to list them for this token. Collapsing nil into
/// false would put a warning beside every name on a repository whose roster simply has not arrived,
/// which teaches people to ignore the warning that matters.
struct Person: Identifiable, Equatable {
    let login: String
    var isYou = false
    var inUse = false
    var onRepo: Bool?
    var id: String { login }
}

/// Who can be put on a task, per workspace.
///
/// Three sources, unioned, in descending order of certainty and ascending order of how much has to
/// work for them to exist:
///
///  1. **The signed-in login.** Always there, needs nothing, and covers the overwhelmingly common
///     case of assigning something to yourself.
///  2. **Logins already written on tasks**, read out of the index. Also needs nothing — they are in
///     the files — so the second task you give somebody costs no network either.
///  3. **The repository's collaborators**, from GitHub, cached.
///
/// The ordering is the design. This is an offline-first app whose whole storage story is "the files
/// are the truth", and a picker that is empty until a request comes back would be a screen that does
/// not work on a train. The network only ever *adds* names, and typing a login is always allowed —
/// so somebody can be assigned before a roster has ever been fetched, and the fetch is an
/// improvement rather than a gate.
///
/// **Anyone who can push can assign.** Assignment is a word on a line in a file, not a permission:
/// joining a repository you can push to is all it takes. The roster exists to offer the right names,
/// never to decide who is allowed to do the giving.
///
/// The cache lives in the app group's preferences, keyed by workspace — deliberately **not** in the
/// workspace's files. Everything in there is committed and pulled by everyone, and a collaborator
/// list is derived remote state each device can fetch for itself; putting it in the repository would
/// turn "somebody joined the project" into a merge conflict in a file nobody edits.
@MainActor
final class People: ObservableObject {
    static let shared = People()

    /// Bumped when a roster arrives, so pickers rebuild.
    @Published private(set) var version = 0
    /// What the last fetch had to say, for the screen that asked.
    @Published var note: String?
    @Published private(set) var fetching = false

    private func key(_ workspaceId: String) -> String {
        "people:\(workspaceId.isEmpty ? "local" : workspaceId)"
    }

    private func cached(_ workspaceId: String) -> [String] {
        AppGroup.defaults.stringArray(forKey: key(workspaceId)) ?? []
    }

    /// Everyone who could be put on a task in this workspace, the signed-in account first.
    func candidates(workspaceId: String, index: WorkspaceIndex) -> [Person] {
        let you = SyncSettings.login
        let roster = cached(workspaceId)
        let known: Set<String>? = roster.isEmpty ? nil : Set(roster.map { $0.lowercased() })
        let inUse = Set(index.nodes.values
            .filter { $0.workspaceId == workspaceId }
            .compactMap { $0.assignee })

        var out: [Person] = []
        var seen = Set<String>()
        func add(_ login: String) {
            guard seen.insert(login.lowercased()).inserted else { return }
            out.append(Person(login: login,
                              isYou: login.caseInsensitiveCompare(you ?? "\u{0}") == .orderedSame,
                              inUse: inUse.contains(where: { $0.caseInsensitiveCompare(login) == .orderedSame }),
                              onRepo: known.map { $0.contains(login.lowercased()) }))
        }
        if let you { add(you) }
        for login in inUse.sorted() { add(login) }
        for login in roster.sorted() { add(login) }
        return out
    }

    /// Just the logins, for the capture grammar — `@name` is only an assignee if it names one of
    /// these, which is what stops "email me @ 5" becoming an assignment.
    func logins(workspaceId: String, index: WorkspaceIndex) -> [String] {
        candidates(workspaceId: workspaceId, index: index).map(\.login)
    }

    /// Every login this device knows about, across every workspace it has open.
    ///
    /// The capture grammar needs this rather than one workspace's list: the line decides where it
    /// lands *after* it is parsed — `~work` can send it to another repository entirely — so a parse
    /// that only knew the current workspace's people would drop an `@name` that the picker had just
    /// offered. Assigning somebody who cannot push *there* is a wrong name on a task, which is
    /// visible and fixable; silently deleting the name from the line is neither.
    func everyLogin(index: WorkspaceIndex) -> [String] {
        var seen = Set<String>(), out: [String] = []
        for id in Set(index.nodes.values.map(\.workspaceId)).sorted() {
            for person in candidates(workspaceId: id, index: index) where seen.insert(person.login.lowercased()).inserted {
                out.append(person.login)
            }
        }
        return out
    }

    /// Whether this sign-in may add people to a workspace's repository. Nil until asked.
    @Published private(set) var canInvite: [String: Bool] = [:]

    /// Asks once, so a screen can offer the field or the link to GitHub rather than both.
    func checkCanInvite(workspaceId: String) async {
        guard canInvite[workspaceId] == nil,
              let repo = SyncSettings.repo(for: workspaceId),
              let token = await SyncSettings.freshToken() else { return }
        canInvite[workspaceId] = await GitHubTransport(repo: repo, token: token).canAdminister()
    }

    /// Adds somebody to the workspace's repository, at push level.
    ///
    /// Returns what to say about it. The roster is re-fetched on success and deliberately will not
    /// yet contain them: an invitation is not a collaborator until it is accepted, and pretending
    /// otherwise would put a name in the picker that GitHub does not recognise.
    func invite(_ login: String, workspaceId: String) async -> String {
        guard let repo = SyncSettings.repo(for: workspaceId) else { return "This workspace has no repository." }
        guard let token = await SyncSettings.freshToken() else { return "Not signed in to GitHub." }
        fetching = true
        defer { fetching = false }
        let outcome = await GitHubTransport(repo: repo, token: token).invite(login)
        Diagnostics.log("people.invited", ["workspace": workspaceId.isEmpty ? "local" : workspaceId,
                                           "outcome": "\(outcome)"])
        switch outcome {
        case let .invited(who):
            await refresh(workspaceId: workspaceId)
            return "Invited \(who) to \(repo.slug). They can push — and be assigned — once they accept."
        case let .already(who):
            await refresh(workspaceId: workspaceId)
            return "\(who) could already push to \(repo.slug)."
        case let .noSuchPerson(who):
            return "GitHub has no account called \(who)."
        case .notAllowed:
            canInvite[workspaceId] = false
            return "This sign-in cannot add people to \(repo.slug). Use GitHub's own access page."
        case let .failed(why):
            return why
        }
    }

    /// Asks GitHub who can push to this workspace's repository, and remembers the answer.
    func refresh(workspaceId: String) async {
        guard !fetching else { return }
        guard let repo = SyncSettings.repo(for: workspaceId) else {
            note = "This workspace is not linked to a repository."
            return
        }
        guard let token = await SyncSettings.freshToken() else {
            note = "Not signed in to GitHub."
            return
        }
        fetching = true
        defer { fetching = false }
        switch await NetworkActivity.shared.during(NetworkActivity.Words.checking, {
            await GitHubTransport(repo: repo, token: token).collaborators()
        }) {
        case let .ok(logins):
            AppGroup.defaults.set(logins, forKey: key(workspaceId))
            Diagnostics.log("people.fetched", ["workspace": workspaceId.isEmpty ? "local" : workspaceId,
                                               "count": logins.count])
            version += 1
            // Named by repository, because "only you" is a fact about a specific one and the obvious
            // next question is which.
            note = logins.count <= 1
                ? "Only you can push to \(repo.slug) — nobody else to assign yet."
                : "\(logins.count) people can push to \(repo.slug)."
        // Said plainly, with the reason, because the obvious reading of a failure here is "the app is
        // broken" and the true one is "GitHub will not tell this app that". The picker still works,
        // which is the sentence that stops it being alarming.
        case let .notPermitted(code):
            Diagnostics.log("people.refused", ["code": code])
            note = "GitHub won't list \(repo.slug)'s collaborators for this app — reading a repository's people needs a heavier permission than syncing its files. Type a login below and it will work all the same."
        case .unauthorized:
            Diagnostics.log("people.unauthorized")
            note = "That sign-in was refused by GitHub. Sign in again."
        case let .failed(why):
            Diagnostics.log("people.failed", ["why": why])
            note = "Could not reach GitHub — \(why). You can still type a login."
        }
    }
}
