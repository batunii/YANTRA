import CryptoKit
import Foundation

// MARK: - the GitHub App, and its device flow

/// Same GitHub App as Android: the device flow needs no redirect, so one registration serves both.
public enum GitHubAuth {

    /// The two ways in, and the bargain each one is — the same pair Android offers.
    ///
    /// **Both are the device flow.** The difference is not the protocol but the *registration*: one
    /// is a GitHub App, the other an OAuth app, and GitHub treats them very differently. The web
    /// OAuth flow — the one that would redirect back with a code — needs a client secret, and a
    /// secret inside a downloadable binary is not a secret; the device flow needs none, for either
    /// registration, which is why both go through it.
    public enum Method: String, CaseIterable, Codable, Sendable {
        /// The OAuth app, with the `repo` scope.
        case full
        /// The GitHub App, installed on chosen repositories.
        case restricted

        /// Public, and different per method: these are two separate registrations on GitHub.
        public var clientId: String {
            switch self {
            case .full: return "Ov23liWz2CApMbchpQOg"
            case .restricted: return "Iv23lijaR2qLqzo9ALWw"
            }
        }

        /// What the token may do, sent with the device-code request.
        ///
        /// Empty for `restricted`: a GitHub App's permissions are fixed at registration and chosen
        /// again at each installation, so there is nothing to ask for. `repo` for `full` is the only
        /// scope that can create a repository, and it appears in the limit GitHub enforces — ten
        /// tokens per user, per application, *per scope*. Changing the string starts a fresh set of
        /// ten and strands every token already issued under the old one.
        public var scope: String {
            switch self { case .full: return "repo"; case .restricted: return "" }
        }

        /// False when this half was never registered, in which case it is not offered at all.
        public var configured: Bool { !clientId.isEmpty }

        /// Whether a fresh token can see anything yet.
        ///
        /// A GitHub App reaches nothing until it is installed somewhere, and a user token with no
        /// installation is not broken — it authenticates perfectly and can see nothing at all,
        /// which is the most confusing state to leave somebody in. An OAuth token has no such step.
        public var needsInstall: Bool { self == .restricted }

        /// Only `repo` can `POST /user/repos`. The other has to send the person to GitHub's form.
        public var makesRepos: Bool { self == .full }

        /// The name of the bargain, for the place where it is chosen.
        public var title: String {
            switch self {
            case .full: return "All my repositories"
            case .restricted: return "Only the ones I pick"
            }
        }

        /// The rest of the bargain, in the two sentences that actually decide it.
        public var summary: String {
            switch self {
            case .full:
                return "Works on as many devices as you like, and makes repositories without leaving the app. Yantra can read and write every repository you own."
            case .restricted:
                return "Yantra sees only the repositories you install it on. Two devices at most — signing in on a third ends the oldest — and new repositories are made on GitHub."
            }
        }
    }

    /// What the sign-in button uses when nobody has said otherwise.
    public static let defaultMethod = Method.full

    /// The methods this build can actually offer.
    public static func offered() -> [Method] { Method.allCases.filter(\.configured) }

    /// The method a stored credential was obtained with, so the app knows what that token can do.
    public static let methodKey = "github_method"

    public static let appSlug = "yantra-tasks"

    public static func installURL(targetId: Int64? = nil) -> URL {
        let base = "https://github.com/apps/\(appSlug)/installations"
        return URL(string: targetId.map { "\(base)/new/permissions?suggested_target_id=\($0)" } ?? "\(base)/new")!
    }
    /// Where a person withdraws Yantra's access to their account.
    ///
    /// The app creates no account of its own — GitHub owns the identity and this page owns the
    /// permission — so this, not a row in a settings screen, is what "delete my account" means here.
    public static func revokeURL(_ method: Method = defaultMethod) -> URL {
        URL(string: "https://github.com/settings/connections/applications/\(method.clientId)")!
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

    public static func requestCode(_ method: Method = defaultMethod) async throws -> DeviceCode {
        var fields = ["client_id": method.clientId]
        // Sent only when there is one: an empty `scope` on a GitHub App request is a parameter
        // GitHub has no use for, and the App's permissions are not this app's to ask for anyway.
        if !method.scope.isEmpty { fields["scope"] = method.scope }
        let data = try await form("https://github.com/login/device/code", fields)
        return try JSONDecoder().decode(DeviceCode.self, from: data)
    }

    public static func poll(_ code: DeviceCode, _ method: Method = defaultMethod) async -> Poll {
        do {
            let data = try await form("https://github.com/login/oauth/access_token", [
                "client_id": method.clientId, "device_code": code.deviceCode, "grant_type": "urn:ietf:params:oauth:grant-type:device_code"])
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
    public static func refresh(_ token: Token, _ method: Method = defaultMethod) async -> Renewal {
        guard let r = token.refreshToken else { return .nothingToDo }
        // The registration that issued the token has to be the one that renews it: a refresh sent
        // with the other client id is refused, and refused means "sign in again" to everything
        // downstream — which would sign somebody out for using the other half of their own app.
        guard let data = try? await form("https://github.com/login/oauth/access_token", [
            "client_id": method.clientId, "grant_type": "refresh_token", "refresh_token": r]) else {
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

// MARK: - making a repository, and finding one

public extension GitHubAuth {
    /// The repository's own access page, where people are invited. Inviting needs Administration
    /// rights this App has no reason to hold, so it happens on GitHub rather than here.
    static func accessSettingsURL(_ slug: String) -> URL {
        URL(string: "https://github.com/\(slug)/settings/access")!
    }
    /// Where a repository is deleted. Yantra never deletes one: "delete" here means forgetting a
    /// workspace, and destroying somebody's repository is not a thing a task app should be able to
    /// do — see `AppModel.forgetWorkspace` and Android's `forgetWorkspace`.
    static func repoSettingsURL(_ slug: String) -> URL {
        URL(string: "https://github.com/\(slug)/settings")!
    }
}

public extension GitHubTransport {
    /// What `POST /user/repos` came to — `RepoCreate` on Android.
    enum RepoCreate: Equatable {
        case ok(RepoRef)
        /// A repository by that name is already on the account. Joining it is the answer, not a
        /// second attempt.
        case exists
        /// The credential is not allowed to make repositories. A fine-grained token or a GitHub App
        /// installation can be perfectly able to sync and unable to create, which is not a sign-in
        /// problem and must not be reported as one.
        case unauthorized
        case failed(String)
    }

    /// Makes a **private** repository for tasks, and says where it actually landed.
    ///
    /// Private without asking: a task list is the most personal thing this app holds, and a public
    /// repository cannot be made private again by anyone who is not an admin of it — so the default
    /// is the one that can be widened later rather than the one that cannot be narrowed.
    ///
    /// The name is sent as typed and the answer is read back rather than assembled from it. GitHub
    /// normalises names (spaces become dashes, and it does not say so anywhere a person looks), and
    /// a workspace pointed at the name somebody typed would push to a repository that is not there.
    static func createRepo(name: String, token: String, host: String = "github.com",
                           description: String = "Tasks, kept by Yantra") async -> RepoCreate {
        let api = host == "github.com" ? "https://api.github.com" : "https://\(host)/api/v3"
        var req = URLRequest(url: URL(string: api + "/user/repos")!)
        req.httpMethod = "POST"
        req.timeoutInterval = 30
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: [
            "name": name, "description": description, "private": true, "auto_init": false,
        ])
        guard let (data, resp) = try? await URLSession.shared.data(for: req) else {
            return .failed("could not reach GitHub")
        }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        switch code {
        case 201:
            guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let full = obj["full_name"] as? String, let ref = RepoRef.parse(full) else {
                return .failed("GitHub made it but would not say where")
            }
            return .ok(RepoRef(host: host, owner: ref.owner, name: ref.name))
        case 401, 403: return .unauthorized
        // 422 is every kind of "no" this endpoint gives — a name already taken, a name made only of
        // punctuation, a plan limit. Only the first is worth its own answer, and GitHub names it in
        // the body rather than in the status.
        case 422:
            let why = String(data: data, encoding: .utf8) ?? ""
            return why.localizedCaseInsensitiveContains("already exists") ? .exists
                : .failed("GitHub would not make that one")
        default: return .failed("GitHub returned \(code)")
        }
    }

    /// One repository in a picker: where it is, and whether tasks could be pushed to it.
    struct Listed: Identifiable, Equatable, Sendable {
        public let slug: String, isPrivate: Bool, canPush: Bool, updatedAt: String
        public var id: String { slug }
        public var ref: RepoRef? { RepoRef.parse(slug) }
    }

    /// The repositories this credential can actually see, newest first.
    ///
    /// Typing `owner/name` from memory is how a connection gets pointed at a repository that does
    /// not exist, and the failure arrives a screen later as a 404. A list is also the only honest
    /// answer for a GitHub App installation, where "what can this token see" is a question only
    /// GitHub can answer — an installation given three repositories cannot reach a fourth, however
    /// correctly its name is spelled.
    ///
    /// One page. Somebody with more than a hundred repositories has a search field for it.
    static func repositories(token: String, host: String = "github.com") async -> [Listed] {
        let api = host == "github.com" ? "https://api.github.com" : "https://\(host)/api/v3"
        var req = URLRequest(url: URL(string: api + "/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member")!)
        req.timeoutInterval = 30
        req.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return rows.compactMap { r in
            guard let slug = r["full_name"] as? String else { return nil }
            let perms = (r["permissions"] as? [String: Bool]) ?? [:]
            return Listed(slug: slug, isPrivate: (r["private"] as? Bool) ?? false,
                          canPush: (perms["push"] ?? false) || (perms["admin"] ?? false),
                          updatedAt: (r["updated_at"] as? String) ?? "")
        }
    }
}

// MARK: - who can be assigned

public extension GitHubTransport {
    /// Who can push to a repository — the people a task in it can be given to.
    ///
    /// A GET like everything else here, and for the same reason: inviting somebody needs a
    /// permission far heavier than this app asks for, and happens on GitHub's own pages. This only
    /// reads the answer.
    ///
    /// Typed rather than a nullable list, and that is the point of it. This endpoint has more ways
    /// to say no than any other call in this file, and they need different things said: a token that
    /// has lapsed is a sign-in, a token that is fine but not allowed to see the roster is a
    /// permission on GitHub, and a dead network is neither. Collapsing them into nil produces "could
    /// not reach GitHub" while the phone is online and GitHub has answered perfectly promptly with a
    /// refusal.
    enum Collaborators: Equatable {
        case ok([String])
        case unauthorized
        /// 403 and 404 are the same answer wearing different clothes: GitHub hides what you may not
        /// see rather than admitting it exists, so a token without this endpoint's permission gets a
        /// 404 for a repository it can otherwise read and push to. Which one it was is kept, because
        /// it says *why*.
        case notPermitted(Int)
        case failed(String)
    }

    /// One page. A hundred collaborators on a task repository is not the case worth paginating for,
    /// and the picker searches what it has rather than scrolling it.
    func collaborators() async -> Collaborators {
        do {
            let (code, data) = try await request("GET", "/repos/\(repo.slug)/collaborators?per_page=100")
            switch code {
            case 200:
                let rows = (try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]) ?? []
                let logins = rows.compactMap { $0["login"] as? String }.filter { !$0.isEmpty }
                var seen = Set<String>()
                return .ok(logins.filter { seen.insert($0.lowercased()).inserted })
            case 401: return .unauthorized
            case 403, 404: return .notPermitted(code)
            default: return .failed("GitHub returned \(code)")
            }
        } catch {
            return .failed(error.localizedDescription)
        }
    }
}

// MARK: - inviting somebody to the repository

public extension GitHubTransport {
    /// What asking GitHub to add somebody came to.
    enum Invite: Equatable {
        /// An invitation is on its way. They are **not** a collaborator until they accept it, which
        /// is why this is not the same as `already`: the roster will not include them yet, and a
        /// task assigned to them in the meantime is a name on a line rather than a mistake.
        case invited(String)
        /// They could already push here. Nothing was sent and nothing needed to be.
        case already(String)
        /// GitHub has no such account.
        case noSuchPerson(String)
        /// This sign-in cannot add people to this repository — it is not an admin of it, or the
        /// registration was never allowed to be. The way through is GitHub's own access page.
        case notAllowed
        case failed(String)
    }

    /// Adds somebody to the repository, at push level.
    ///
    /// **Push, not admin.** What a collaborator needs here is to read and write task files; handing
    /// out administration because the app happened to have it would be the app making a decision
    /// about somebody's repository that nobody asked it to make.
    ///
    /// Only the OAuth sign-in can do this, and only on a repository the account administers: the
    /// `repo` scope carries admin on your own repositories, while a GitHub App's permissions are
    /// fixed at registration and do not include adding people. `canAdminister` is how a screen knows
    /// which of those it is looking at before it offers anything.
    func invite(_ login: String, as permission: String = "push") async -> Invite {
        let name = login.trimmingCharacters(in: .whitespaces)
            .trimmingCharacters(in: CharacterSet(charactersIn: "@"))
        guard !name.isEmpty else { return .failed("no login given") }
        do {
            let (code, data) = try await request("PUT", "/repos/\(repo.slug)/collaborators/\(name)",
                                                 body: ["permission": permission])
            switch code {
            case 201:
                // The invitation's own id is not worth carrying: what a person needs to know is that
                // it was sent and to whom.
                _ = data
                return .invited(name)
            case 204: return .already(name)
            case 403: return .notAllowed
            // 404 is both "no such user" and "you may not do this here" — GitHub hides what you may
            // not see. The account is the likelier of the two from a screen where somebody has just
            // typed a name, and the other is already ruled out by `canAdminister` before offering.
            case 404: return .noSuchPerson(name)
            case 422: return .failed("GitHub would not add \(name) — check the login, and that it is not your own")
            default: return .failed("GitHub returned \(code)")
            }
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    /// Whether this sign-in may add people to this repository.
    ///
    /// Asked before anything is offered, because a button that produces "you are not allowed" is
    /// worse than no button: the person has already decided to invite somebody by the time they
    /// press it.
    func canAdminister() async -> Bool {
        guard let (code, data) = try? await request("GET", "/repos/\(repo.slug)"), code == 200,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let perms = obj["permissions"] as? [String: Bool] else { return false }
        return perms["admin"] ?? false
    }
}
