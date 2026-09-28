import XCTest
@testable import YantraCore

/// Two devices, one in-memory remote that behaves like the Git Data API. The same cases the Android
/// on-device suite runs through JGit: both devices converge, and convergence costs nobody their work.
final class SyncEngineTests: XCTestCase {

    /// A remote branch as GitHub would hold it: commits with trees, refusing a non-fast-forward move.
    final class FakeRemote: GitTransport {
        var blobs: [String: Data] = [:]
        var commits: [String: [String: String]] = [:]   // commit sha → tree (path → blob sha)
        var head: String?
        var counter = 0
        /// Runs as a blob is read — the middle of a pass, where an edit from elsewhere can land.
        var duringBlob: ((String) -> Void)?
        /// A refusal no retry can fix, thrown as the transport throws a 403 or a protected branch.
        var refusal: SyncError?
        var commitAttempts = 0
        var blobReads = 0

        func head(branch: String) async throws -> RemoteHead? {
            guard let h = head else { return nil }
            return RemoteHead(commit: h, treeSha: "tree-" + h, tree: commits[h]!)
        }
        /// Every push loses the race, as if another device kept moving the branch.
        var alwaysMoves = false
        /// The connection drops on this blob read (1-based).
        var dropOnBlobRead: Int?

        func blob(sha: String) async throws -> Data {
            blobReads += 1
            if blobReads == dropOnBlobRead { throw URLError(.networkConnectionLost) }
            duringBlob?(sha)
            return blobs[sha]!
        }
        func commit(branch: String, parent: String?, baseTree: String?, files: [String: Data?], message: String) async throws -> String? {
            commitAttempts += 1
            if let refusal { throw refusal }
            if alwaysMoves { return nil }
            if parent != head { return nil }   // the branch moved: refused, like a 422 on the ref
            var tree = parent.flatMap { commits[$0] } ?? [:]
            for (path, data) in files {
                if let data { let sha = gitBlobSha(data); blobs[sha] = data; tree[path] = sha } else { tree.removeValue(forKey: path) }
            }
            counter += 1
            let sha = "c\(counter)"
            commits[sha] = tree; head = sha
            return sha
        }
        func text(_ path: String) -> String? { head.flatMap { commits[$0]?[path] }.flatMap { blobs[$0] }.map { String(decoding: $0, as: UTF8.self) } }
    }

    struct Device {
        let store: WorkspaceStore, writer: WorkspaceWriter, engine: SyncEngine, name: String
        init(_ name: String, remote: FakeRemote, root: URL) {
            self.name = name
            store = WorkspaceStore(root: root.appendingPathComponent(name), id: name)
            writer = WorkspaceWriter(store: store, device: name)
            engine = SyncEngine(store: store, transport: remote, device: name, stateDir: root.appendingPathComponent("\(name)-sync"))
        }
        func page(_ id: String) -> PageDoc? { store.readPage(id) }
    }

    var root: URL!
    var remote: FakeRemote!

    override func setUp() {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("sync-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        remote = FakeRemote()
    }

    func ok(_ d: Device, file: StaticString = #filePath, line: UInt = #line) async {
        let r = await d.engine.sync()
        XCTAssertTrue(r.ok, "\(d.name): \(r.error ?? "")", file: file, line: line)
    }

    func seed(_ d: Device) {
        d.store.scaffold(name: "Shared", now: 1_000)
        d.store.writePage(PageDoc(id: "list", type: NodeType.list, parent: nil, title: "Shared list", modifiedAt: Date(timeIntervalSince1970: 1), device: "seed",
                                  blocks: [.task(TaskRef(id: "t1", title: "Original")), .task(TaskRef(id: "t2", title: "Untouched"))]))
    }

    func testEditsToDifferentLinesOfOnePageMerge() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a); await ok(a)
        await ok(b)
        XCTAssertNotNil(b.page("list"))

        try a.writer.setDone("t1", true); await ok(a)
        try b.writer.setTitle("t2", "Renamed by B")
        let res = await b.engine.sync()
        XCTAssertTrue(res.ok, res.error ?? "")
        XCTAssertTrue(res.conflicts.allSatisfy { $0.reason.contains("merged") }, "\(res.conflicts.map(\.reason))")
        await ok(a)

        for d in [a, b] {
            let tasks = d.page("list")!.blocks.compactMap { if case let .task(t) = $0 { return t }; return nil }
            XCTAssertEqual(tasks.first { $0.id == "t1" }?.status, .done, d.name)
            XCTAssertEqual(tasks.first { $0.id == "t2" }?.title, "Renamed by B", d.name)
        }
        XCTAssertEqual(PageCodec.encode(a.page("list")!), PageCodec.encode(b.page("list")!))
    }

    func testFocusLogsUnionBySessionId() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a); await ok(a); await ok(b)
        let sa = FocusSession(id: "s1", nodeId: "t1", startedAt: 1_700_000_000_000, plannedSecs: 1500)
        try a.writer.appendFocus(sa.line, month: sa.monthKey)
        try a.writer.appendFocus(FocusLedger.settle(sa, actualSecs: 1500, outcome: FocusOutcome.ranOut, now: 1_700_000_001_500_000).line, month: sa.monthKey)
        await ok(a)
        let sb = FocusSession(id: "s2", nodeId: "t2", startedAt: 1_700_000_100_000, plannedSecs: 0)
        try b.writer.appendFocus(sb.line, month: sb.monthKey)
        await ok(b); await ok(a)
        for d in [a, b] {
            let ids = Set(FocusLedger.read(d.store).map(\.id))
            XCTAssertEqual(ids, ["s1", "s2"], d.name)
            XCTAssertEqual(FocusLedger.read(d.store).first { $0.id == "s1" }?.outcome, FocusOutcome.ranOut)
        }
    }

    /// §12, as Android does it at 17d0beb: a manifest both devices changed keeps the side of the
    /// device that resolves it — its own — and the other device then takes that answer like any pull.
    /// There is no field-by-field merge; one only this app ran would answer differently on each side.
    func testAConflictedManifestKeepsTheResolvingDevicesSide() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a); a.store.writeManifest(a.store.readManifest()!.copyWith(formatVersion: 1))
        await ok(a); await ok(b)
        a.store.writeManifest(a.store.readManifest()!.copyWith(formatVersion: 2)); await ok(a)
        b.store.writeManifest(b.store.readManifest()!.copyWith(archiveAfterDays: 30))
        let mine = b.store.readManifest()!.compact()
        let res = await b.engine.sync()
        XCTAssertTrue(res.ok, res.error ?? "")
        XCTAssertTrue(res.conflicts.contains { $0.path == WorkspaceStore.manifestPath && $0.reason == "kept the local side of an unmergeable file" },
                      "\(res.conflicts.map(\.reason))")
        await ok(a)
        XCTAssertEqual(remote.text(WorkspaceStore.manifestPath), mine)
        for d in [a, b] { XCTAssertEqual(d.store.readManifest()?.compact(), mine, d.name) }
    }

    func testADeleteNeverBeatsAnEdit() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a); await ok(a); await ok(b)
        try a.writer.deletePage("list"); await ok(a)
        try b.writer.setTitle("t1", "Edited on B")
        await ok(b); await ok(a)
        XCTAssertNotNil(a.page("list"), "the deleted page came back with B's edit")
        XCTAssertEqual(a.page("list")!.blocks.compactMap { if case let .task(t) = $0 { return t.title }; return nil }.first, "Edited on B")
    }

    func testTheBranchMovingUnderneathIsRetried() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a); await ok(a); await ok(b)
        try a.writer.setTitle("t1", "A"); try b.writer.setTitle("t2", "B")
        await ok(a)
        // B's first attempt commits on a stale parent; the fake refuses, B fetches and merges.
        let res = await b.engine.sync()
        XCTAssertTrue(res.ok && res.pushed, res.error ?? "")
        XCTAssertTrue(remote.text("pages/list.md")!.contains("- [ ] A ^t1"))
        XCTAssertTrue(remote.text("pages/list.md")!.contains("- [ ] B ^t2"))
    }

    // MARK: the working tree is not sync's alone

    func titles(_ d: Device) -> [String: String] {
        var out: [String: String] = [:]
        for b in d.page("list")?.blocks ?? [] { if case let .task(t) = b { out[t.id] = t.title } }
        return out
    }

    /// A1. `.gitignore` begins with `.git`, and a prefix test skipped it: the pass read it as deleted
    /// here, pushed the deletion, pulled Android's copy back, and deleted it again — every pass.
    func testGitignoreIsNeitherDeletedNorPingPonged() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a)
        let ignore = "# Half-written files, mid-rename. Never a real edit.\n*.tmp\n"
        try Data(ignore.utf8).write(to: a.store.root.appendingPathComponent(".gitignore"))
        await ok(a)
        await ok(b)
        XCTAssertEqual(try String(contentsOf: b.store.root.appendingPathComponent(".gitignore"), encoding: .utf8), ignore)
        let tip = remote.head
        await ok(b); await ok(a); await ok(b)
        XCTAssertEqual(remote.head, tip, "a pass with nothing to say pushed something")
        XCTAssertEqual(remote.text(".gitignore"), ignore)
    }

    /// A2. An edit that lands while a pass is on the network — a widget tick, a keystroke — is newer
    /// than what the pass read. Writing the pulled page over it lost it silently. It is kept, and the
    /// next pass merges it.
    func testAnEditMadeDuringAPullIsNotOverwritten() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a); await ok(a); await ok(b)
        try a.writer.setTitle("t1", "From A"); await ok(a)

        var once = true
        remote.duringBlob = { _ in
            guard once else { return }; once = false
            try? b.writer.setTitle("t2", "From B, mid-pass")
        }
        let first = await b.engine.sync()
        remote.duringBlob = nil
        XCTAssertTrue(first.ok, first.error ?? "")
        XCTAssertEqual(titles(b)["t2"], "From B, mid-pass", "the pull wrote over an edit made during it")
        XCTAssertTrue(first.problems.contains(SyncEngine.leftForNextPass), "\(first.problems)")

        await ok(b); await ok(a)
        for d in [a, b] {
            XCTAssertEqual(titles(d)["t1"], "From A", d.name)
            XCTAssertEqual(titles(d)["t2"], "From B, mid-pass", d.name)
        }
    }

    /// A2, the conflict case. The page was resolved against the local side the pass read; the file
    /// changed meanwhile. Pushing that resolution would record the newer edit as already merged, and
    /// the next pass would push it over the remote's change. It waits for the next pass instead.
    func testAnEditMadeDuringAConflictResolutionIsNotLost() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a); await ok(a); await ok(b)
        try a.writer.setTitle("t1", "From A"); await ok(a)
        try b.writer.setTitle("t2", "B, first draft")

        var once = true
        remote.duringBlob = { _ in
            guard once else { return }; once = false
            try? b.writer.setTitle("t2", "B, second draft")
        }
        let first = await b.engine.sync()
        remote.duringBlob = nil
        XCTAssertTrue(first.ok, first.error ?? "")
        XCTAssertEqual(titles(b)["t2"], "B, second draft")
        XCTAssertFalse(remote.text("pages/list.md")!.contains("first draft"), "a resolution of a stale local side was pushed")

        await ok(b); await ok(a)
        for d in [a, b] {
            XCTAssertEqual(titles(d)["t1"], "From A", d.name)
            XCTAssertEqual(titles(d)["t2"], "B, second draft", d.name)
        }
    }

    /// Nothing sync writes is ever half a file, and it leaves no temporary files behind.
    func testSyncWritesLeaveNoTemporaryFiles() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a); await ok(a); await ok(b)
        let names = FileManager.default.enumerator(atPath: b.store.root.path)?.compactMap { $0 as? String } ?? []
        XCTAssertFalse(names.contains { $0.hasSuffix(".tmp") }, "\(names)")
        XCTAssertEqual(b.engine.localFiles().keys.sorted(), a.engine.localFiles().keys.sorted())
    }

    // MARK: the base snapshot

    /// A6. At every moment `base.json` names blobs that exist, so a pass killed part way leaves a
    /// base that is still true.
    func testTheBaseNeverNamesABlobItDoesNotHave() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a); await ok(a); await ok(b)
        try a.writer.setTitle("t1", "Again"); await ok(a); await ok(b)
        let base = try XCTUnwrap(b.engine.loadBase())
        for (path, sha) in base.shas {
            let d = try Data(contentsOf: b.engine.objects.appendingPathComponent(sha))
            XCTAssertEqual(gitBlobSha(d), sha, path)
        }
        let stored = try FileManager.default.contentsOfDirectory(atPath: b.engine.objects.path)
        XCTAssertEqual(Set(stored), Set(base.shas.values), "blobs nothing refers to were kept, or needed ones swept")
    }

    /// With the snapshot's blobs gone, the ancestor is fetched from the remote by its id rather than
    /// falling back to whole-file last-writer-wins — which would drop one side's edit.
    func testALostSnapshotBlobStillMergesBlockByBlock() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a); await ok(a); await ok(b)
        try FileManager.default.removeItem(at: b.engine.objects)
        try a.writer.setTitle("t1", "From A"); await ok(a)
        try b.writer.setTitle("t2", "From B")
        let res = await b.engine.sync()
        XCTAssertTrue(res.ok, res.error ?? "")
        XCTAssertTrue(res.conflicts.contains { $0.reason == "merged the page around the disagreement" }, "\(res.conflicts.map(\.reason))")
        await ok(a)
        for d in [a, b] { XCTAssertEqual(titles(d), ["t1": "From A", "t2": "From B"], d.name) }
    }

    /// The layout earlier builds wrote — each base file at its own path — is still read, and replaced
    /// on the next save.
    func testTheSnapshotFromAnEarlierBuildIsStillRead() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a); await ok(a); await ok(b)
        let fm = FileManager.default
        for (path, data) in b.engine.localFiles() {
            let f = b.engine.legacyBlobs.appendingPathComponent(path)
            try fm.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: f)
        }
        try fm.removeItem(at: b.engine.objects)
        let readsBefore = remote.blobReads
        try a.writer.setTitle("t1", "From A"); await ok(a)
        try b.writer.setTitle("t2", "From B")
        let res = await b.engine.sync()
        XCTAssertTrue(res.ok, res.error ?? "")
        XCTAssertEqual(remote.blobReads - readsBefore, 1, "the ancestor should have come from the old snapshot, not the network")
        XCTAssertFalse(fm.fileExists(atPath: b.engine.legacyBlobs.path))
        XCTAssertEqual(titles(b), ["t1": "From A", "t2": "From B"])
    }

    /// Adopting fetches everything before writing anything: a connection dropped halfway leaves the
    /// device as it was, not half starter set and half repository with no base to say which.
    func testAnInterruptedAdoptionChangesNothing() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a); await ok(a)
        b.store.scaffold(name: "Mine", now: 5)
        WorkspaceSeeder.seed(b.store)
        let before = b.engine.localFiles()
        remote.dropOnBlobRead = 2
        do { _ = try await b.engine.adoptRemote(); XCTFail("the dropped connection went unnoticed") } catch {}
        XCTAssertEqual(b.engine.localFiles(), before)
        XCTAssertNil(b.engine.loadBase())
    }

    // MARK: refusals

    /// B3. A refusal no retry can fix is reported as what it is, after one attempt — not retried
    /// three times and then called a lost race.
    func testAPushRefusalIsReportedNotRetried() async throws {
        let a = Device("a", remote: remote, root: root)
        seed(a); await ok(a)
        try a.writer.setTitle("t1", "Edited")
        remote.refusal = .pushRefused("Protected branch update failed for refs/heads/yantra-tasks.")
        remote.commitAttempts = 0
        let res = await a.engine.sync()
        XCTAssertEqual(remote.commitAttempts, 1)
        XCTAssertEqual(res.error, "the remote refused the push: Protected branch update failed for refs/heads/yantra-tasks.")
    }

    func testLosingTheRaceThreeTimesSaysSoInAndroidsWords() async throws {
        let a = Device("a", remote: remote, root: root)
        seed(a); await ok(a)
        try a.writer.setTitle("t1", "Edited")
        remote.alwaysMoves = true
        remote.commitAttempts = 0
        let res = await a.engine.sync()
        XCTAssertEqual(remote.commitAttempts, SyncEngine.maxAttempts)
        XCTAssertEqual(res.error, "kept losing a race with another device; will try again later")
    }

    func testOfflineReadsAsOfflineInAndroidsWords() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a); await ok(a)
        remote.dropOnBlobRead = remote.blobReads + 1
        let res = await b.engine.sync()
        XCTAssertEqual(res.error, "Could not reach GitHub — this will sync when the connection is back")
    }
}

extension Manifest {
    func copyWith(formatVersion: Int? = nil, archiveAfterDays: Int? = nil) -> Manifest {
        var m = self
        if let f = formatVersion { m.formatVersion = f }
        if let a = archiveAfterDays { m.archiveAfterDays = a }
        return m
    }
}
