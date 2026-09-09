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
            case let e?: return .failed(e)
            default: return .failed("unexpected reply")
            }
        } catch { return .offline }
    }

    /// GitHub rotates the refresh token: store what comes back.
    public static func refresh(_ token: Token) async -> Token? {
        guard let r = token.refreshToken, let data = try? await form("https://github.com/login/oauth/access_token", [
            "client_id": clientId, "grant_type": "refresh_token", "refresh_token": r]) else { return nil }
        return try? JSONDecoder().decode(Token.self, from: data)
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
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        if let body { req.httpBody = try JSONSerialization.data(withJSONObject: body); req.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, resp) = try await URLSession.shared.data(for: req)
        return ((resp as? HTTPURLResponse)?.statusCode ?? 0, data)
    }

    public func head(branch: String) async throws -> RemoteHead? {
        let (code, data) = try await request("GET", "/repos/\(repo.slug)/git/ref/heads/\(branch)")
        if code == 404 { return nil }
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

    public func commit(branch: String, parent: String?, baseTree: String?, files: [String: Data?], message: String) async throws -> String? {
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
