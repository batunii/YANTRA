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
    public static var repo: RepoRef? {
        get { AppGroup.defaults.string(forKey: "sync_repo").flatMap(RepoRef.parse) }
        set { AppGroup.defaults.set(newValue?.slug, forKey: "sync_repo") }
    }
    public static var login: String? {
        get { AppGroup.defaults.string(forKey: "github_login") }
        set { AppGroup.defaults.set(newValue, forKey: "github_login") }
    }
    public static var lastStatus: String? {
        get { AppGroup.defaults.string(forKey: "sync_last_status") }
        set { AppGroup.defaults.set(newValue, forKey: "sync_last_status") }
    }

    /// A token good for the next call, renewed first when it is about to expire.
    public static func freshToken() async -> String? {
        guard var t = Keychain.accountToken else { return nil }
        if t.needsRenewal, let renewed = await GitHubAuth.refresh(t) { t = renewed; Keychain.accountToken = t }
        return t.accessToken
    }

    /// One sync pass against the connected repository, if any.
    public static func syncNow(store: WorkspaceStore, message: String = "sync") async -> SyncResult {
        guard let repo else { return SyncResult(error: SyncError.noRemote.description) }
        guard let token = await freshToken() else { return SyncResult(error: "Sign in again — we do not know who you are") }
        let engine = SyncEngine(store: store, transport: GitHubTransport(repo: repo, token: token), device: login ?? AppGroup.device,
                                stateDir: AppGroup.container.appendingPathComponent("sync/local", isDirectory: true))
        let result = await engine.sync(message: message)
        lastStatus = result.error.map { "Not synced: \($0)" } ?? (result.conflicts.isEmpty ? (result.pushed || result.pulled ? "Synced" : "Nothing to sync")
                                                                    : "Synced · \(result.conflicts.count) conflict\(result.conflicts.count == 1 ? "" : "s") resolved")
        return result
    }
}
