import Foundation

/// Fetch, three-way merge, resolve, push — `SyncEngine.kt` reshaped for a transport that speaks trees
/// and blobs rather than packfiles. Where Android rebases local commits onto the remote branch, this
/// keeps a snapshot of the tree as it was at the last sync (the base), and for every path compares
/// base, local and remote:
///
/// - only one side moved → take it;
/// - both moved → `ConflictResolver`, exactly the rules Android applies during its rebase;
///
/// then commits the merged tree on top of the remote head. If the branch moved meanwhile the push is
/// refused and the pass runs again, up to three times. The result on the branch is the same commit
/// a rebase would have produced: one parent, the remote head, and a tree both devices agree on.
public struct SyncResult: Equatable {
    public var committed = false, pushed = false, pulled = false
    public var conflicts: [ConflictResolver.Resolution] = []
    public var problems: [String] = []
    public var error: String? = nil
    public var ok: Bool { error == nil }
    public init(error: String? = nil) { self.error = error }
}

/// One pass at a time per workspace is the caller's job — `SyncGate` — because on iOS an engine is
/// built for every pass rather than held for the life of the workspace.
public final class SyncEngine {
    public static let branch = "yantra-tasks"
    public static let maxAttempts = 3

    public let store: WorkspaceStore
    public let transport: GitTransport
    public let device: String
    /// Where the base snapshot lives: outside the workspace so it is never committed.
    public let stateDir: URL
    /// Shared with every process that writes this workspace; see `WorkspaceLock`.
    let lock: WorkspaceLock
    private let fm = FileManager.default

    public init(store: WorkspaceStore, transport: GitTransport, device: String, stateDir: URL) {
        self.store = store; self.transport = transport; self.device = device; self.stateDir = stateDir
        lock = WorkspaceLock(root: store.root)
    }

    // MARK: the base snapshot

    /// The tree both sides agreed on at the end of the last pass: path → blob id, plus the commit.
    ///
    /// **Written so a crash cannot leave it half-made.** A missing base is not an inconvenience: with
    /// no common ancestor every page both sides touched falls to whole-file last-writer-wins, and one
    /// side's edits are gone. It used to be rewritten in place — delete every blob, write them back one
    /// by one, then the index — so a pass killed in the middle (background time running out is
    /// ordinary) left exactly that.
    ///
    /// Now the blobs are content-addressed under `objects/<sha>` and only ever added; `base.json` is
    /// replaced atomically as the single commit point, and blobs nothing refers to any more are swept
    /// afterwards. At every instant `base.json` names blobs that exist.
    struct Base: Codable, Equatable { var commit: String; var shas: [String: String] }
    var baseFile: URL { stateDir.appendingPathComponent("base.json") }
    var objects: URL { stateDir.appendingPathComponent("objects", isDirectory: true) }
    /// The snapshot as earlier builds kept it — the tree's files at their own paths. Read, never written.
    var legacyBlobs: URL { stateDir.appendingPathComponent("base", isDirectory: true) }

    func loadBase() -> Base? {
        guard let d = try? Data(contentsOf: baseFile) else { return nil }
        return try? JSONDecoder().decode(Base.self, from: d)
    }

    /// Records the new base. `shas` is the agreed tree; `blobs` supplies content for ids that may not
    /// be stored yet (anything already stored is kept, so an unchanged file costs nothing).
    func saveBase(commit: String, shas: [String: String], blobs: [String: Data]) throws {
        try fm.createDirectory(at: objects, withIntermediateDirectories: true)
        for sha in Set(shas.values) {
            let f = objects.appendingPathComponent(sha)
            if fm.fileExists(atPath: f.path) { continue }
            if let data = blobs[sha] ?? legacyBlob(sha, in: shas) { try AtomicFile.write(data, to: f) }
            // Otherwise it is left out, and fetched from the remote by its id when a merge needs it.
        }
        try AtomicFile.write(try JSONEncoder().encode(Base(commit: commit, shas: shas)), to: baseFile)
        // Only now, with the new base in place, is anything the old one needed safe to remove.
        let keep = Set(shas.values)
        for name in (try? fm.contentsOfDirectory(atPath: objects.path)) ?? [] where !keep.contains(name) {
            try? fm.removeItem(at: objects.appendingPathComponent(name))
        }
        try? fm.removeItem(at: legacyBlobs)
    }

    /// Convenience for a base that is exactly these files.
    func saveBase(commit: String, files: [String: Data]) throws {
        var shas: [String: String] = [:], blobs: [String: Data] = [:]
        for (path, data) in files { let s = gitBlobSha(data); shas[path] = s; blobs[s] = data }
        try saveBase(commit: commit, shas: shas, blobs: blobs)
    }

    private func legacyBlob(_ sha: String, in shas: [String: String]) -> Data? {
        for (path, s) in shas where s == sha {
            if let d = try? Data(contentsOf: legacyBlobs.appendingPathComponent(path)), gitBlobSha(d) == sha { return d }
        }
        return nil
    }

    /// The common ancestor's bytes for one path. Checked against its id, and fetched from the remote
    /// by that id when the local copy is missing or wrong — the base is a commit the remote has, so a
    /// lost snapshot file is never a reason to fall back to last-writer-wins.
    func baseBlob(_ path: String, sha: String) async throws -> Data {
        if let d = try? Data(contentsOf: objects.appendingPathComponent(sha)), gitBlobSha(d) == sha { return d }
        if let d = try? Data(contentsOf: legacyBlobs.appendingPathComponent(path)), gitBlobSha(d) == sha { return d }
        return try await transport.blob(sha: sha)
    }

    // MARK: the working tree

    /// Every file under the workspace root that belongs in the repository, path → bytes.
    ///
    /// Only `.git` itself is skipped, not everything whose name begins with it. A prefix test on
    /// `".git"` also skipped `.gitignore`, so a pass read it as deleted here and pushed the deletion;
    /// the next pass pulled it back from Android's copy, and the one after deleted it again, forever.
    public func localFiles() -> [String: Data] {
        var out: [String: Data] = [:]
        guard let e = fm.enumerator(at: store.root, includingPropertiesForKeys: [.isRegularFileKey]) else { return out }
        let rootPath = store.root.standardizedFileURL.path
        for case let url as URL in e {
            let rel = String(url.standardizedFileURL.path.dropFirst(rootPath.count + 1))
            if rel.isEmpty || rel == ".git" || rel.hasPrefix(".git/") || rel.hasSuffix(".tmp") { continue }
            if (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true, let d = try? Data(contentsOf: url) { out[rel] = d }
        }
        return out
    }

    /// Puts `data` at `path` (nil deletes) **only if the file still holds `expected`** — what this
    /// pass read at its start (nil: absent). Returns whether it did.
    ///
    /// A pass reads the tree, then spends seconds on the network. Anything written meanwhile — a
    /// keystroke in the app, a tick from a widget, a capture from the share sheet — is newer than
    /// what the pass read, and writing the pulled version over it would throw that edit away without
    /// a trace. So the file is compared first, under the lock the writer takes, and swapped by an
    /// atomic rename in the same breath. If it changed, it is left alone: it is a local edit like any
    /// other, and the next pass merges it against the base this one leaves for that path.
    func replaceLocal(_ path: String, with data: Data?, ifStill expected: Data?) throws -> Bool {
        let f = store.root.appendingPathComponent(path)
        return try lock.withLock {
            let current = try? Data(contentsOf: f)
            guard current == expected else { return false }
            if let data {
                if current != data { try AtomicFile.write(data, to: f) }
            } else if current != nil {
                try fm.removeItem(at: f)
            }
            return true
        }
    }

    /// Unconditionally, for adopting a repository — the one case where replacing what is here is the
    /// point. Still atomic, and still under the lock.
    func writeLocal(_ path: String, _ data: Data?) throws {
        let f = store.root.appendingPathComponent(path)
        try lock.withLock {
            if let data { try AtomicFile.write(data, to: f) }
            else if fm.fileExists(atPath: f.path) { try fm.removeItem(at: f) }
        }
    }

    // MARK: the pass

    public func sync(message: String = "sync") async -> SyncResult {
        var result = SyncResult()
        for attempt in 1...Self.maxAttempts {
            do {
                let outcome = try await pass(message: message)
                result = outcome.result
                if outcome.retry, attempt < Self.maxAttempts { continue }
                // Android's words: going round three times and losing each time is a problem, but it
                // is not this device's work that is wrong.
                if outcome.retry { result.error = "kept losing a race with another device; will try again later" }
                return result
            } catch {
                result.error = Self.readable(error)
                return result
            }
        }
        return result
    }

    struct Outcome { var result: SyncResult; var retry: Bool }

    static let leftForNextPass = "a change made during sync is kept, and syncs on the next pass"

    func pass(message: String) async throws -> Outcome {
        var result = SyncResult()
        let local = localFiles()
        let base = loadBase()
        let remote = try await transport.head(branch: Self.branch)

        // First contact with an empty branch: everything local is the first commit.
        guard let remote else {
            let sha = try await transport.commit(branch: Self.branch, parent: nil, baseTree: nil, files: local.mapValues { Optional($0) }, message: "scaffold")
            guard let sha else { return Outcome(result: result, retry: true) }
            try saveBase(commit: sha, files: local)
            result.committed = true; result.pushed = true
            return Outcome(result: result, retry: false)
        }

        // Nothing moved anywhere.
        let localShas = local.mapValues(gitBlobSha)
        if let b = base, b.commit == remote.commit, b.shas == localShas { return Outcome(result: result, retry: false) }

        // The new base, path → id, and the bytes for ids it may not hold yet.
        var agreed: [String: String] = [:]
        var blobs: [String: Data] = [:]
        func agree(_ path: String, _ data: Data?) {
            guard let data else { return }
            let s = gitBlobSha(data); agreed[path] = s; blobs[s] = data
        }
        /// A path this pass could not settle because it was written meanwhile keeps its old ancestor,
        /// so the next pass sees the edit for what it is: a change on top of that ancestor.
        func deferPath(_ path: String) {
            if let b = base?.shas[path] { agreed[path] = b }
            if !result.problems.contains(Self.leftForNextPass) { result.problems.append(Self.leftForNextPass) }
        }

        var toPush: [String: Data?] = [:]
        let paths = Set(local.keys).union(remote.tree.keys).union(Set(base?.shas.keys.map { $0 } ?? []))
        for path in paths.sorted() {
            let l = local[path], lSha = localShas[path]
            let bSha = base?.shas[path], rSha = remote.tree[path]
            let localMoved = lSha != bSha
            let remoteMoved = rSha != bSha
            switch (localMoved, remoteMoved) {
            case (false, false):
                agree(path, l)
            case (true, false):
                agree(path, l)
                toPush[path] = l   // nil deletes
            case (false, true):
                let r = try await fetch(rSha)
                if try replaceLocal(path, with: r, ifStill: l) {
                    agree(path, r)
                    result.pulled = true
                } else {
                    deferPath(path)
                }
            case (true, true):
                if lSha == rSha { agree(path, l); continue }
                let r = try await fetch(rSha)
                let ancestor = try await bSha.asyncMap { try await baseBlob(path, sha: $0) }
                let res = ConflictResolver.resolve(path: path, local: l, remote: r, device: device, otherDevice: "", base: ancestor)
                if try replaceLocal(path, with: res.bytes, ifStill: l) {
                    result.conflicts.append(res)
                    agree(path, res.bytes)
                    if res.bytes.map(gitBlobSha) != rSha { toPush[path] = res.bytes }
                    result.pulled = true
                } else {
                    // Resolved against a local side that is no longer there. Pushing that answer
                    // would record the newer edit as already merged; leave the path for the next pass.
                    deferPath(path)
                }
            }
        }
        if toPush.isEmpty {
            try saveBase(commit: remote.commit, shas: agreed, blobs: blobs)
            return Outcome(result: result, retry: false)
        }
        result.committed = true
        // The remote's tree is the base_tree; deletions only make sense against it.
        guard let sha = try await transport.commit(branch: Self.branch, parent: remote.commit, baseTree: remote.treeSha, files: toPush, message: message) else {
            return Outcome(result: result, retry: true)
        }
        result.pushed = true
        try saveBase(commit: sha, shas: agreed, blobs: blobs)
        return Outcome(result: result, retry: false)
    }

    func fetch(_ sha: String?) async throws -> Data? {
        guard let sha else { return nil }
        return try await transport.blob(sha: sha)
    }
}

extension Optional {
    func asyncMap<U>(_ f: (Wrapped) async throws -> U) async rethrows -> U? {
        guard let w = self else { return nil }
        return try await f(w)
    }
}

// MARK: - what a failed pass says

public extension SyncEngine {
    /// What to put on screen when a pass fails — `SyncEngine.readable` on Android, the same sentences
    /// for the same situations.
    ///
    /// **Walks the cause chain** (`NSUnderlyingErrorKey`), because the outer error is rarely the one
    /// worth reading, and matches the domain and code as well as the message: URLSession's reasons are
    /// in its codes, the way JGit's are in its class names. Every branch says what to *do*.
    static func readable(_ error: Error) -> String {
        if let e = error as? SyncError {
            switch e {
            case .pushRefused, .refused, .noRemote: return e.description
            case .offline: return e.description
            case let .http(code, _, message):
                if code == 401 { return Words.signIn }
                if let m = message, let w = words(for: m) { return w }
                return e.description
            case .corrupt: return e.description
            }
        }
        var chain: [NSError] = []
        var next: NSError? = error as NSError
        while let n = next, chain.count < 8 { chain.append(n); next = n.userInfo[NSUnderlyingErrorKey] as? NSError }

        func has(_ domain: String, _ codes: Int...) -> Bool { chain.contains { $0.domain == domain && codes.contains($0.code) } }
        let text = chain.map { "\($0.domain) \($0.code): \($0.localizedDescription)" }.joined(separator: " | ")

        if has(NSPOSIXErrorDomain, Int(EHOSTUNREACH), Int(ENETUNREACH)) { return Words.noRoute }
        if has(NSURLErrorDomain, NSURLErrorUserAuthenticationRequired, NSURLErrorUserCancelledAuthentication) { return Words.signIn }
        if has(NSPOSIXErrorDomain, Int(ENOSPC)) || has(NSCocoaErrorDomain, NSFileWriteOutOfSpaceError) { return Words.noSpace }
        if chain.contains(where: { $0.domain == NSURLErrorDomain }) { return Words.unreachable }
        return words(for: text) ?? (chain.last?.localizedDescription ?? "\(error)")
    }

    /// The needles Android matches, for text that carries them.
    internal static func words(for text: String) -> String? {
        func has(_ needles: String...) -> Bool { needles.contains { text.range(of: $0, options: .caseInsensitive) != nil } }
        if has("not authorized", "Authentication is required", "invalid credentials", "Bad credentials", "Requires authentication") { return Words.signIn }
        if has("NoRouteToHost", "Host unreachable", "No route to host") { return Words.noRoute }
        if has("UnknownHost", "Unable to resolve host", "Connection refused", "timed out", "Network is unreachable", "offline") { return Words.unreachable }
        if has("non-fast-forward", "not a fast forward") { return Words.nonFastForward }
        if has("protected branch", "pre-receive hook declined", "GH006") { return Words.protected }
        if has("No space left", "ENOSPC") { return Words.noSpace }
        return nil
    }

    enum Words {
        public static let signIn = "GitHub would not accept the sign-in — sign in again in Settings"
        public static let noRoute = "This network cannot reach GitHub — if it persists, turn Wi-Fi off and on"
        public static let unreachable = "Could not reach GitHub — this will sync when the connection is back"
        public static let nonFastForward = "Someone else pushed first — the next sync will pick their work up"
        public static let protected = "GitHub refused the push — the branch is protected"
        public static let noSpace = "The phone is out of storage — free some space and sync again"
    }
}
