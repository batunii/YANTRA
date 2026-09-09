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

public final class SyncEngine {
    public static let branch = "yantra-tasks"
    public static let maxAttempts = 3

    public let store: WorkspaceStore
    public let transport: GitTransport
    public let device: String
    /// Where the base snapshot lives: outside the workspace so it is never committed.
    public let stateDir: URL
    private let fm = FileManager.default

    public init(store: WorkspaceStore, transport: GitTransport, device: String, stateDir: URL) {
        self.store = store; self.transport = transport; self.device = device; self.stateDir = stateDir
    }

    // MARK: the base snapshot

    struct Base: Codable { var commit: String; var shas: [String: String] }
    var baseFile: URL { stateDir.appendingPathComponent("base.json") }
    var baseBlobs: URL { stateDir.appendingPathComponent("base", isDirectory: true) }

    func loadBase() -> Base? {
        guard let d = try? Data(contentsOf: baseFile) else { return nil }
        return try? JSONDecoder().decode(Base.self, from: d)
    }
    func saveBase(commit: String, files: [String: Data]) throws {
        try? fm.removeItem(at: baseBlobs)
        var shas: [String: String] = [:]
        for (path, data) in files {
            let f = baseBlobs.appendingPathComponent(path)
            try fm.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: f)
            shas[path] = gitBlobSha(data)
        }
        try fm.createDirectory(at: stateDir, withIntermediateDirectories: true)
        try JSONEncoder().encode(Base(commit: commit, shas: shas)).write(to: baseFile)
    }
    func baseBlob(_ path: String) -> Data? { try? Data(contentsOf: baseBlobs.appendingPathComponent(path)) }

    // MARK: the working tree

    /// Every file under the workspace root that belongs in the repository, path → bytes.
    public func localFiles() -> [String: Data] {
        var out: [String: Data] = [:]
        guard let e = fm.enumerator(at: store.root, includingPropertiesForKeys: [.isRegularFileKey]) else { return out }
        let rootPath = store.root.standardizedFileURL.path
        for case let url as URL in e {
            let rel = String(url.standardizedFileURL.path.dropFirst(rootPath.count + 1))
            if rel.hasPrefix(".git") || rel.hasSuffix(".tmp") || rel.isEmpty { continue }
            if (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true, let d = try? Data(contentsOf: url) { out[rel] = d }
        }
        return out
    }

    func writeLocal(_ path: String, _ data: Data?) {
        let f = store.root.appendingPathComponent(path)
        if let data {
            try? fm.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? data.write(to: f)
        } else { try? fm.removeItem(at: f) }
    }

    // MARK: the pass

    public func sync(message: String = "sync") async -> SyncResult {
        var result = SyncResult()
        for attempt in 1...Self.maxAttempts {
            do {
                let outcome = try await pass(message: message)
                result = outcome.result
                if outcome.retry, attempt < Self.maxAttempts { continue }
                if outcome.retry { result.error = "the branch kept moving; try again" }
                return result
            } catch let e as SyncError {
                result.error = e.description; return result
            } catch {
                result.error = (error as NSError).domain == NSURLErrorDomain ? SyncError.offline.description : error.localizedDescription
                return result
            }
        }
        return result
    }

    struct Outcome { var result: SyncResult; var retry: Bool }

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

        var merged: [String: Data] = [:]
        var toPush: [String: Data?] = [:]
        let paths = Set(local.keys).union(remote.tree.keys).union(Set(base?.shas.keys.map { $0 } ?? []))
        for path in paths.sorted() {
            let l = local[path], lSha = localShas[path]
            let bSha = base?.shas[path], rSha = remote.tree[path]
            let localMoved = lSha != bSha
            let remoteMoved = rSha != bSha
            switch (localMoved, remoteMoved) {
            case (false, false):
                if let l { merged[path] = l }
            case (true, false):
                merged[path] = l ?? nil
                toPush[path] = l   // nil deletes
            case (false, true):
                let r = try await fetch(rSha)
                if let r { merged[path] = r } else { merged.removeValue(forKey: path) }
                writeLocal(path, r)
                result.pulled = true
            case (true, true):
                if lSha == rSha { if let l { merged[path] = l }; continue }
                let r = try await fetch(rSha)
                let res = ConflictResolver.resolve(path: path, local: l, remote: r, device: device, otherDevice: "", base: bSha.flatMap { _ in baseBlob(path) })
                result.conflicts.append(res)
                if let bytes = res.bytes { merged[path] = bytes } else { merged.removeValue(forKey: path) }
                writeLocal(path, res.bytes)
                if res.bytes.map(gitBlobSha) != rSha { toPush[path] = res.bytes }
                result.pulled = true
            }
        }
        if toPush.isEmpty {
            try saveBase(commit: remote.commit, files: merged)
            return Outcome(result: result, retry: false)
        }
        result.committed = true
        // The remote's tree is the base_tree; deletions only make sense against it.
        guard let sha = try await transport.commit(branch: Self.branch, parent: remote.commit, baseTree: remote.treeSha, files: toPush, message: message) else {
            return Outcome(result: result, retry: true)
        }
        result.pushed = true
        try saveBase(commit: sha, files: merged)
        return Outcome(result: result, retry: false)
    }

    func fetch(_ sha: String?) async throws -> Data? {
        guard let sha else { return nil }
        return try await transport.blob(sha: sha)
    }
}
