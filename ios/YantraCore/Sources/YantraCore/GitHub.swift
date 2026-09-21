import CryptoKit
import Foundation

// MARK: - the GitHub App, and its device flow

/// Same GitHub App as Android: the device flow needs no redirect, so one registration serves both.
public enum GitHubAuth {
    public static let clientId = "Iv23lijaR2qLqzo9ALWw"
    public static let appSlug = "yantra-tasks"

    public static func installURL(targetId: Int64? = nil) -> URL {
        let base = "https://github.com/apps/\(appSlug)/installations"
        return URL(string: targetId.map { "\(base)/new/permissions?suggested_target_id=\($0)" } ?? "\(base)/new")!
    }
    public static func newRepoURL(name: String) -> URL {
        var c = URLComponents(string: "https://github.com/new")!
        c.queryItems = [.init(name: "name", value: name), .init(name: "visibility", value: "private"), .init(name: "description", value: "Yantra tasks")]
        return c.url!
    }

    public struct DeviceCode: Decodable, Equatable {
        public let deviceCode: String, userCode: String, verificationUri: String, expiresIn: Int, interval: Int
        enum CodingKeys: String, CodingKey { case deviceCode = "device_code", userCode = "user_code", verificationUri = "verification_uri", expiresIn = "expires_in", interval }
    }

    public struct Token: Codable, Equatable {
        public var accessToken: String
        public var refreshToken: String?
        /// Epoch seconds, or nil when the token does not expire.
        public var expiresAt: Double?
        enum CodingKeys: String, CodingKey { case accessToken = "access_token", refreshToken = "refresh_token", expiresIn = "expires_in", expiresAt }
        public init(accessToken: String, refreshToken: String? = nil, expiresAt: Double? = nil) { self.accessToken = accessToken; self.refreshToken = refreshToken; self.expiresAt = expiresAt }
        public init(from d: Decoder) throws {
            let c = try d.container(keyedBy: CodingKeys.self)
            accessToken = try c.decode(String.self, forKey: .accessToken)
            refreshToken = try c.decodeIfPresent(String.self, forKey: .refreshToken)
            if let at = try c.decodeIfPresent(Double.self, forKey: .expiresAt) { expiresAt = at }
            else if let e = try c.decodeIfPresent(Int.self, forKey: .expiresIn) { expiresAt = Date().timeIntervalSince1970 + Double(e) }
            else { expiresAt = nil }
        }
        public func encode(to e: Encoder) throws {
            var c = e.container(keyedBy: CodingKeys.self)
            try c.encode(accessToken, forKey: .accessToken); try c.encodeIfPresent(refreshToken, forKey: .refreshToken); try c.encodeIfPresent(expiresAt, forKey: .expiresAt)
        }
        /// Renew five minutes early, as Android does.
        public var needsRenewal: Bool { refreshToken != nil && (expiresAt == nil || Date().timeIntervalSince1970 >= expiresAt! - 300) }
    }

    public enum Poll: Equatable { case pending, slowDown(Int), token(Token), expired, denied, failed(String), offline }

    public static func requestCode() async throws -> DeviceCode {
        let data = try await form("https://github.com/login/device/code", ["client_id": clientId])
        return try JSONDecoder().decode(DeviceCode.self, from: data)
    }

    public static func poll(_ code: DeviceCode) async -> Poll {
        do {
            let data = try await form("https://github.com/login/oauth/access_token", [
                "client_id": clientId, "device_code": code.deviceCode, "grant_type": "urn:ietf:params:oauth:grant-type:device_code"])
            if let t = try? JSONDecoder().decode(Token.self, from: data) { return .token(t) }
            let obj = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
            switch obj["error"] as? String {
            case "authorization_pending": return .pending
            case "slow_down": return .slowDown((obj["interval"] as? Int) ?? code.interval + 5)
            case "expired_token": return .expired
            case "access_denied": return .denied
            case .some: return .failed(describe(obj) ?? "Sign-in failed")
            default: return .failed("unexpected reply")
            }
        } catch { return .offline }
    }

    /// What a renewal attempt came to — `TokenRenewal.Outcome` on Android.
    ///
    /// Three answers, not two, and the third is the point. A refresh that was **refused** means the
    /// sign-in is gone and only signing in again will fix it. A refresh that could not be **asked**
    /// — a tunnel, a dead cell, a captive portal — means nothing at all, and must leave the stored
    /// credential exactly where it is. Collapsing those two into "nil" is how a train journey signs
    /// somebody out, and how a genuinely dead token goes on being presented forever instead of
    /// saying so.
    public enum Renewal: Equatable {
        case renewed(Token)
        /// GitHub refused it. The stored credential is scrap.
        case needsSignIn(String)
        /// GitHub was not reachable. Nothing is known and nothing should change.
        case couldNotAsk
        /// There is nothing to renew — a token with no refresh token beside it never expires.
        case nothingToDo
    }

    /// Trades a refresh token for a fresh access token.
    ///
    /// GitHub **rotates the refresh token on every use**, so the one that comes back has to be
    /// stored in place of the one that was sent: keeping the old one makes the next refresh fail in
    /// exactly the way this exists to prevent.
    public static func refresh(_ token: Token) async -> Renewal {
        guard let r = token.refreshToken else { return .nothingToDo }
        guard let data = try? await form("https://github.com/login/oauth/access_token", [
            "client_id": clientId, "grant_type": "refresh_token", "refresh_token": r]) else {
            return .couldNotAsk
        }
        if let t = try? JSONDecoder().decode(Token.self, from: data) { return .renewed(t) }
        let obj = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        return .needsSignIn(describe(obj) ?? "GitHub would not renew the sign-in")
    }

    /// What GitHub said went wrong, preferring its sentence to its slug.
    static func describe(_ obj: [String: Any]) -> String? {
        switch obj["error"] as? String {
        case "device_flow_disabled": return "This build's GitHub app does not have device flow enabled"
        case "unsupported_grant_type", "incorrect_client_credentials":
            return "This build's GitHub app is misconfigured"
        case nil: return nil
        default: return (obj["error_description"] as? String) ?? (obj["error"] as? String)
        }
    }

    static func form(_ url: String, _ fields: [String: String]) async throws -> Data {
        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = "POST"
        req.timeoutInterval = 15
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.httpBody = Data(fields.map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")" }.joined(separator: "&").utf8)
        let (data, _) = try await URLSession.shared.data(for: req)
        return data
    }
}

/// `owner/name`, a browser URL, a clone URL or `git@host:owner/repo`.
public struct RepoRef: Equatable, Codable {
    public var host: String, owner: String, name: String
    public var slug: String { "\(owner)/\(name)" }
    public init(host: String = "github.com", owner: String, name: String) { self.host = host; self.owner = owner; self.name = name }
    public static func parse(_ input: String) -> RepoRef? {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        if s.hasPrefix("git@") {
            let rest = s.dropFirst(4); guard let colon = rest.firstIndex(of: ":") else { return nil }
            let host = String(rest[..<colon]); var path = String(rest[rest.index(after: colon)...])
            if path.hasSuffix(".git") { path.removeLast(4) }
            let p = path.split(separator: "/"); guard p.count == 2 else { return nil }
            return RepoRef(host: host, owner: String(p[0]), name: String(p[1]))
        }
        var host = "github.com"
        if let u = URL(string: s), let h = u.host, s.contains("://") { host = h; s = u.path }
        if s.hasPrefix("/") { s.removeFirst() }
        if s.hasSuffix(".git") { s.removeLast(4) }
        let p = s.split(separator: "/").map(String.init)
        guard p.count >= 2, !p[0].isEmpty, !p[1].isEmpty else { return nil }
        return RepoRef(host: host, owner: p[0], name: p[1])
    }
}

// MARK: - the Git Data API, as the transport

/// What the sync engine needs from a remote: one branch's tree, blobs, and a way to commit on top.
/// A protocol so the engine can be tested against a fake without a network.
public protocol GitTransport {
    func head(branch: String) async throws -> RemoteHead?
    func blob(sha: String) async throws -> Data
    /// Writes a commit whose tree is `files` (path → content; nil deletes), parented on `parent`
    /// (nil for the first commit), and moves the branch to it. Returns the new commit sha, or nil
    /// when the branch moved underneath and the caller should fetch and try again.
    func commit(branch: String, parent: String?, baseTree: String?, files: [String: Data?], message: String) async throws -> String?
}

/// One branch tip: the commit, its tree id, and every blob by path.
public struct RemoteHead: Equatable {
    public var commit: String, treeSha: String, tree: [String: String]
    public init(commit: String, treeSha: String, tree: [String: String]) { self.commit = commit; self.treeSha = treeSha; self.tree = tree }
}

public struct GitHubTransport: GitTransport {
    public let repo: RepoRef
    public let token: String
    public init(repo: RepoRef, token: String) { self.repo = repo; self.token = token }
    var api: String { repo.host == "github.com" ? "https://api.github.com" : "https://\(repo.host)/api/v3" }

    func request(_ method: String, _ path: String, body: Any? = nil) async throws -> (Int, Data) {
        var req = URLRequest(url: URL(string: api + path)!)
        req.httpMethod = method
        req.timeoutInterval = 30
        // Never a cached answer. GitHub sends an ETag on every read, and URLSession's default policy
        // will happily serve the stored body for a repeated GET — so a ref read straight after a
        // push can report the tip the branch had *before* it. Sync reads the same three URLs over
        // and over by design, and a stale one is indistinguishable from another device having moved
        // the branch: the engine rebases onto the past, the update is refused, and it retries until
        // it gives up with "the branch kept moving".
        req.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        if let body { req.httpBody = try JSONSerialization.data(withJSONObject: body); req.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, resp) = try await URLSession.shared.data(for: req)
        return ((resp as? HTTPURLResponse)?.statusCode ?? 0, data)
    }

    public func head(branch: String) async throws -> RemoteHead? {
        let (code, data) = try await request("GET", "/repos/\(repo.slug)/git/ref/heads/\(branch)")
        // 404 is "no such branch". 409 is "Git Repository is empty" — a repository with no commits
        // at all, which is what GitHub hands back for one freshly created. Both mean the same thing
        // to a caller: there is nothing up there yet. Treating 409 as an error made the very first
        // sync into a new repository fail, which is the one sync everybody does.
        if code == 404 || code == 409 { return nil }
        guard code == 200, let ref = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let obj = ref["object"] as? [String: Any], let sha = obj["sha"] as? String else { throw SyncError.http(code, "ref") }
        let (c2, cdata) = try await request("GET", "/repos/\(repo.slug)/git/commits/\(sha)")
        guard c2 == 200, let commit = try JSONSerialization.jsonObject(with: cdata) as? [String: Any],
              let treeSha = (commit["tree"] as? [String: Any])?["sha"] as? String else { throw SyncError.http(c2, "commit") }
        let (c3, tdata) = try await request("GET", "/repos/\(repo.slug)/git/trees/\(treeSha)?recursive=1")
        guard c3 == 200, let tree = try JSONSerialization.jsonObject(with: tdata) as? [String: Any], let entries = tree["tree"] as? [[String: Any]] else { throw SyncError.http(c3, "tree") }
        var files: [String: String] = [:]
        for e in entries where (e["type"] as? String) == "blob" { if let p = e["path"] as? String, let s = e["sha"] as? String { files[p] = s } }
        return RemoteHead(commit: sha, treeSha: treeSha, tree: files)
    }

    public func blob(sha: String) async throws -> Data {
        let (code, data) = try await request("GET", "/repos/\(repo.slug)/git/blobs/\(sha)")
        guard code == 200, let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any], let content = obj["content"] as? String else { throw SyncError.http(code, "blob") }
        return Data(base64Encoded: content.replacingOccurrences(of: "\n", with: "")) ?? Data()
    }

    /// Whether this repository has no commits at all, which GitHub answers 409 to.
    func isEmptyRepository(branch: String) async throws -> Bool {
        let (code, _) = try await request("GET", "/repos/\(repo.slug)/git/ref/heads/\(branch)")
        return code == 409
    }

    /// Brings an empty repository into existence, because the ordinary path cannot.
    ///
    /// The Git Data API refuses **every** call on a repository with no commits — blobs included —
    /// with 409 "Git Repository is empty", so the first commit cannot be built the way every later
    /// one is. The Contents API can make it, and once a single commit exists the ordinary path works
    /// forever after.
    ///
    /// The file it writes is one of the files being pushed, chosen deterministically, rather than a
    /// placeholder: a `.gitkeep` invented here would be pulled down into every workspace afterwards
    /// and belong to nothing.
    func bootstrapEmptyRepository(branch: String, files: [String: Data?], message: String) async throws -> RemoteHead? {
        let present = files.compactMap { path, content in content.map { (path, $0) } }
        guard let (path, content) = present.min(by: { $0.0 < $1.0 }) else { return nil }
        let encoded = path.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? path
        let (code, _) = try await request("PUT", "/repos/\(repo.slug)/contents/\(encoded)", body: [
            "message": message,
            "content": content.base64EncodedString(),
            "branch": branch,
        ])
        guard code == 201 else { throw SyncError.http(code, "first commit") }
        return try await head(branch: branch)
    }

    public func commit(branch: String, parent: String?, baseTree: String?, files: [String: Data?], message: String) async throws -> String? {
        var parent = parent, baseTree = baseTree
        // A first push into a repository that has never held a commit has to start it off before
        // anything else can be written to it.
        if parent == nil, try await isEmptyRepository(branch: branch) {
            guard let started = try await bootstrapEmptyRepository(branch: branch, files: files, message: message) else { return nil }
            parent = started.commit
            baseTree = started.treeSha
        }
        var entries: [[String: Any?]] = []
        for (path, content) in files.sorted(by: { $0.key < $1.key }) {
            if let content {
                let (c, d) = try await request("POST", "/repos/\(repo.slug)/git/blobs", body: ["content": content.base64EncodedString(), "encoding": "base64"])
                guard c == 201, let obj = try JSONSerialization.jsonObject(with: d) as? [String: Any], let sha = obj["sha"] as? String else { throw SyncError.http(c, "blob write") }
                entries.append(["path": path, "mode": "100644", "type": "blob", "sha": sha])
            } else if baseTree != nil {
                entries.append(["path": path, "mode": "100644", "type": "blob", "sha": nil])
            }
        }
        var treeBody: [String: Any] = ["tree": entries.map { $0.mapValues { $0 ?? NSNull() } }]
        if let baseTree { treeBody["base_tree"] = baseTree }
        let (tc, td) = try await request("POST", "/repos/\(repo.slug)/git/trees", body: treeBody)
        guard tc == 201, let t = try JSONSerialization.jsonObject(with: td) as? [String: Any], let treeSha = t["sha"] as? String else { throw SyncError.http(tc, "tree write") }
        var commitBody: [String: Any] = ["message": message, "tree": treeSha, "author": ["name": "Yantra", "email": "yantra@shoonya.ie"]]
        if let parent { commitBody["parents"] = [parent] }
        let (cc, cd) = try await request("POST", "/repos/\(repo.slug)/git/commits", body: commitBody)
        guard cc == 201, let cobj = try JSONSerialization.jsonObject(with: cd) as? [String: Any], let commitSha = cobj["sha"] as? String else { throw SyncError.http(cc, "commit write") }
        if parent == nil {
            let (rc, _) = try await request("POST", "/repos/\(repo.slug)/git/refs", body: ["ref": "refs/heads/\(branch)", "sha": commitSha])
            return rc == 201 ? commitSha : nil
        }
        // Not a force: a 422 means the branch moved, and the caller rebases again.
        let (rc, _) = try await request("PATCH", "/repos/\(repo.slug)/git/refs/heads/\(branch)", body: ["sha": commitSha, "force": false])
        return rc == 200 ? commitSha : nil
    }

    /// `GET /user` — the login that becomes the device name on commits and lines.
    public func viewer() async throws -> (login: String, id: Int64)? {
        let (code, data) = try await request("GET", "/user")
        guard code == 200, let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any], let login = obj["login"] as? String else { return nil }
        return (login, (obj["id"] as? NSNumber)?.int64Value ?? 0)
    }

    public enum RepoCheck { case ok(canPush: Bool), notFound, unauthorized, failed(String) }
    public func check() async -> RepoCheck {
        do {
            let (code, data) = try await request("GET", "/repos/\(repo.slug)")
            switch code {
            case 200:
                let perms = ((try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["permissions"] as? [String: Bool]) ?? [:]
                return .ok(canPush: (perms["push"] ?? false) || (perms["admin"] ?? false))
            case 401, 403: return .unauthorized
            case 404: return .notFound
            default: return .failed("HTTP \(code)")
            }
        } catch { return .failed(error.localizedDescription) }
    }
}

public enum SyncError: Error, CustomStringConvertible {
    case http(Int, String), offline, noRemote, refused(String)
    public var description: String {
        switch self {
        case let .http(c, what): return "GitHub answered \(c) reading \(what)"
        case .offline: return "Cannot reach GitHub"
        case .noRemote: return "No repository is connected"
        case let .refused(r): return r
        }
    }
}

/// `git hash-object`: the blob id for content, so unchanged files are never re-fetched.
public func gitBlobSha(_ data: Data) -> String {
    var h = Insecure.SHA1()
    h.update(data: Data("blob \(data.count)\u{0}".utf8))
    h.update(data: data)
    return h.finalize().map { String(format: "%02x", $0) }.joined()
}
