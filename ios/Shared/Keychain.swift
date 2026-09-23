import Foundation
import Security
import YantraCore

/// Tokens in the Keychain, shared across the App Group, readable after first unlock so a background
/// refresh can sync with the screen locked — the iOS counterpart of Android's Keystore-sealed prefs.
public enum Keychain {
    static let service = "ie.shoonya.yantra"
    static let group = "group.ie.shoonya.yantra"

    public static func set(_ data: Data, for key: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
        SecItemDelete(q as CFDictionary)
        var add = q
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
    public static func get(_ key: String) -> Data? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key,
                                kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: CFTypeRef?
        return SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess ? out as? Data : nil
    }
    public static func remove(_ key: String) {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key] as CFDictionary)
    }

    /// The signed-in account's token — `Credentials.ACCOUNT` on Android.
    public static var accountToken: GitHubAuth.Token? {
        get { get("token:@account").flatMap { try? JSONDecoder().decode(GitHubAuth.Token.self, from: $0) } }
        set { if let t = newValue, let d = try? JSONEncoder().encode(t) { set(d, for: "token:@account") } else { remove("token:@account") } }
    }
}

/// Which repository the local workspace pushes to, and the last sync's outcome — device-local state.
public enum SyncSettings {
    /// The repository a given workspace pushes to.
    ///
    /// Keyed by workspace id, with the local workspace keeping the bare `sync_repo` key it has
    /// always had — an upgrade must not silently disconnect the one workspace everybody already
    /// has, and a key that changes shape between builds is exactly how that happens.
    public static func repo(for workspaceId: String) -> RepoRef? {
        AppGroup.defaults.string(forKey: repoKey(workspaceId)).flatMap(RepoRef.parse)
    }
    public static func setRepo(_ ref: RepoRef?, for workspaceId: String) {
        AppGroup.defaults.set(ref?.slug, forKey: repoKey(workspaceId))
    }
    private static func repoKey(_ id: String) -> String { id.isEmpty ? "sync_repo" : "sync_repo:\(id)" }

    /// The local workspace's repository — what the GitHub screen and every one-workspace caller mean.
    public static var repo: RepoRef? {
        get { repo(for: "") }
        set { setRepo(newValue, for: "") }
    }
    /// Which registration the stored credential came from.
    ///
    /// Kept beside the token because everything downstream depends on it: which client id renews
    /// it, whether it can create a repository, whether it needs installing somewhere before it can
    /// see anything, and which application page revokes it. A token whose method is forgotten is a
    /// token the app has to guess about, and both guesses are wrong half the time.
    public static var method: GitHubAuth.Method {
        get {
            AppGroup.defaults.string(forKey: GitHubAuth.methodKey)
                .flatMap(GitHubAuth.Method.init(rawValue:)) ?? .full
        }
        set { AppGroup.defaults.set(newValue.rawValue, forKey: GitHubAuth.methodKey) }
    }

    public static var login: String? {
        get { AppGroup.defaults.string(forKey: "github_login") }
        set { AppGroup.defaults.set(newValue, forKey: "github_login") }
    }
    public static var lastStatus: String? {
        get { status(for: "") }
        set { setStatus(newValue, for: "") }
    }
    public static func status(for workspaceId: String) -> String? {
        AppGroup.defaults.string(forKey: statusKey(workspaceId))
    }
    public static func setStatus(_ s: String?, for workspaceId: String) {
        AppGroup.defaults.set(s, forKey: statusKey(workspaceId))
    }
    private static func statusKey(_ id: String) -> String { id.isEmpty ? "sync_last_status" : "sync_last_status:\(id)" }

    /// A token good for the next call, or why there is not one.
    ///
    /// A GitHub App issues user tokens that lapse after a few hours unless the App is registered
    /// with expiry switched off. Android already learned this the hard way — sync worked for an
    /// afternoon, then failed with "not authorized", and only signing in again fixed it, with
    /// nothing on screen saying why.
    public enum Credential: Equatable {
        case ok(String)
        /// Nobody is signed in, or the sign-in is scrap and only signing in again will fix it.
        case needsSignIn(String)
    }

    /// Renews the account token when it is close enough to lapsing to matter.
    ///
    /// Called before every sync pass, so it is cheap when there is nothing to do — a pasted personal
    /// token and a non-expiring App token both store no refresh token and return immediately with no
    /// request at all.
    public static func credential() async -> Credential {
        guard var t = Keychain.accountToken else { return .needsSignIn("Sign in to sync") }
        guard t.needsRenewal else { return .ok(t.accessToken) }

        switch await GitHubAuth.refresh(t, method) {
        case let .renewed(fresh):
            Diagnostics.log("token.renewed")
            // GitHub rotates the refresh token, so what came back replaces what was sent.
            t = fresh
            Keychain.accountToken = t
            return .ok(t.accessToken)
        case .nothingToDo:
            return .ok(t.accessToken)
        case .couldNotAsk:
            Diagnostics.log("token.couldNotAsk")
            // Nothing was said, so nothing is changed. The pass carries on with the token it has; if
            // that one still works, this was never needed. A tunnel must not sign anybody out.
            return .ok(t.accessToken)
        case let .needsSignIn(why):
            // Refused, which is different. The stored credential is scrap, and keeping it would mean
            // presenting a dead token on every sync forever while the screen still claims to be
            // signed in.
            Keychain.accountToken = nil
            login = nil
            Diagnostics.log("token.refused", ["why": why])
            return .needsSignIn(why)
        }
    }

    /// The access token alone, for a caller with nothing useful to do about the difference.
    public static func freshToken() async -> String? {
        if case let .ok(t) = await credential() { return t }
        return nil
    }

    /// Connects this device to a repository, deciding first whether to adopt or to push.
    ///
    /// The decision has to happen **before** the first merge, not during it. A device that seeded a
    /// starter workspace on first launch and is now joining a repository that already has one will,
    /// under the ordinary merge, keep both — two Inboxes, two Todays, both claiming the same system
    /// key. The merge is not wrong; it is being asked a question that should never have reached it.
    ///
    /// Returns `.ask` without touching anything when both sides hold real work. That case is a fork
    /// only the person can take, so it is handed back rather than guessed at.
    public static func connect(store: WorkspaceStore, message: String = "connect") async -> (WorkspaceLink.Decision, SyncResult) {
        guard let engine = await engine(for: store) else {
            return (.push, SyncResult(error: SyncError.noRemote.description))
        }
        let decision: WorkspaceLink.Decision
        do { decision = try await engine.linkDecision() }
        catch { return (.push, SyncResult(error: "\(error)")) }

        switch decision {
        case .ask:
            return (.ask, SyncResult())
        case .adopt:
            do {
                var r = try await engine.adoptRemote()
                setStatus(r.error.map { "Not synced: \($0)" } ?? "Joined the repository's tasks", for: store.id)
                // Anything this device had beyond the starter set is gone by design; a normal pass
                // now has a common ancestor and behaves like any other.
                if r.error == nil { r.pulled = true }
                return (.adopt, r)
            } catch { return (.adopt, SyncResult(error: "\(error)")) }
        case .push:
            return (.push, await syncNow(store: store, message: message))
        }
    }

    /// Takes the repository as it stands, discarding this device's workspace. Only ever called
    /// because the person chose it after `.ask`.
    public static func adoptRepository(store: WorkspaceStore) async -> SyncResult {
        guard let engine = await engine(for: store) else { return SyncResult(error: SyncError.noRemote.description) }
        do {
            let r = try await engine.adoptRemote()
            setStatus(r.error.map { "Not synced: \($0)" } ?? "Joined the repository's tasks", for: store.id)
            return r
        } catch { return SyncResult(error: "\(error)") }
    }

    /// The engine for the connected repository, with a token good for the next call.
    private static func engine(for store: WorkspaceStore) async -> SyncEngine? {
        guard let repo = repo(for: store.id) else { return nil }
        guard case let .ok(token) = await credential() else { return nil }
        return engine(store: store, repo: repo, token: token)
    }

    /// The base snapshot a workspace merges against lives beside the workspace, one directory per
    /// workspace. Sharing one would have a second repository's tree treated as the first one's
    /// common ancestor, which is every file in both looking like a conflict.
    private static func engine(store: WorkspaceStore, repo: RepoRef, token: String) -> SyncEngine {
        SyncEngine(store: store, transport: GitHubTransport(repo: repo, token: token),
                   device: login ?? AppGroup.device,
                   stateDir: AppGroup.container.appendingPathComponent(
                       "sync/\(store.id.isEmpty ? "local" : store.id)", isDirectory: true))
    }

    /// One sync pass against the connected repository, if any.
    public static func syncNow(store: WorkspaceStore, message: String = "sync") async -> SyncResult {
        guard let repo = repo(for: store.id) else { return SyncResult(error: SyncError.noRemote.description) }
        let token: String
        switch await credential() {
        case let .ok(t): token = t
        // Said in the words GitHub used, because "sign in again" with no reason is the failure this
        // whole path exists to stop repeating.
        case let .needsSignIn(why):
            setStatus("Not synced: \(why)", for: store.id)
            Diagnostics.log("sync.needsSignIn", ["why": why])
            return SyncResult(error: "\(why) — sign in again")
        }
        let began = Date()
        let result = await engine(store: store, repo: repo, token: token).sync(message: message)
        // The single most useful line in a week of logs: what a pass did, and what it cost. A sync
        // that silently stopped happening on Thursday is invisible without it.
        Diagnostics.log("sync.pass", [
            "workspace": store.id.isEmpty ? "local" : store.id,
            "reason": message,
            "seconds": Int(Date().timeIntervalSince(began) * 1000) / 1000,
            "pushed": result.pushed, "pulled": result.pulled,
            "conflicts": result.conflicts.count,
            "error": result.error ?? "",
        ])
        setStatus(result.error.map { "Not synced: \($0)" } ?? (result.conflicts.isEmpty ? (result.pushed || result.pulled ? "Synced" : "Nothing to sync")
                                                                    : "Synced · \(result.conflicts.count) conflict\(result.conflicts.count == 1 ? "" : "s") resolved"),
                  for: store.id)
        return result
    }
}
