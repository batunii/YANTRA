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

        func head(branch: String) async throws -> RemoteHead? {
            guard let h = head else { return nil }
            return RemoteHead(commit: h, treeSha: "tree-" + h, tree: commits[h]!)
        }
        func blob(sha: String) async throws -> Data { blobs[sha]! }
        func commit(branch: String, parent: String?, baseTree: String?, files: [String: Data?], message: String) async throws -> String? {
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

    func testAMigrationAndAnUnrelatedManifestEditBothSurvive() async throws {
        let a = Device("a", remote: remote, root: root), b = Device("b", remote: remote, root: root)
        seed(a); a.store.writeManifest(a.store.readManifest()!.copyWith(formatVersion: 1))
        await ok(a); await ok(b)
        a.store.writeManifest(a.store.readManifest()!.copyWith(formatVersion: 2)); await ok(a)
        b.store.writeManifest(b.store.readManifest()!.copyWith(archiveAfterDays: 30))
        let res = await b.engine.sync()
        XCTAssertTrue(res.ok, res.error ?? "")
        XCTAssertTrue(res.conflicts.contains { $0.path == WorkspaceStore.manifestPath && $0.reason.contains("field by field") })
        await ok(a)
        for d in [a, b] {
            XCTAssertEqual(d.store.readManifest()?.formatVersion, 2, d.name)
            XCTAssertEqual(d.store.readManifest()?.archiveAfterDays, 30, d.name)
        }
        XCTAssertEqual(remote.text(WorkspaceStore.manifestPath), a.store.readManifest()!.compact())
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
}

extension Manifest {
    func copyWith(formatVersion: Int? = nil, archiveAfterDays: Int? = nil) -> Manifest {
        var m = self
        if let f = formatVersion { m.formatVersion = f }
        if let a = archiveAfterDays { m.archiveAfterDays = a }
        return m
    }
}
