import XCTest
@testable import YantraCore

/// Sync against the real GitHub, not the stand-in.
///
/// `SyncEngineTests` proves the *merge* is right by running it against `FakeRemote`. What it cannot
/// prove is that the transport underneath speaks GitHub correctly: the fake answers whatever this
/// code asks it, in the shape this code expects, so a wrong path, a wrong header, a wrong status
/// code or a base64 mistake is invisible there and self-consistent.
///
/// Skipped unless `YANTRA_LIVE_REPO` and `YANTRA_LIVE_TOKEN` are set, because it needs a network, a
/// credential and a repository it is allowed to write to.
///
///     YANTRA_LIVE_REPO=owner/name YANTRA_LIVE_TOKEN=$(gh auth token) swift test --filter LiveSyncTests
final class LiveSyncTests: XCTestCase {

    var repo: RepoRef!
    var token: String!
    var scratch: URL!

    override func setUpWithError() throws {
        let env = ProcessInfo.processInfo.environment
        guard let slug = env["YANTRA_LIVE_REPO"], let t = env["YANTRA_LIVE_TOKEN"], !t.isEmpty,
              let ref = RepoRef.parse(slug) else {
            throw XCTSkip("set YANTRA_LIVE_REPO and YANTRA_LIVE_TOKEN to run the live sync test")
        }
        repo = ref; token = t
        scratch = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("live-\(UUID())")
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let scratch { try? FileManager.default.removeItem(at: scratch) }
    }

    var transport: GitHubTransport { GitHubTransport(repo: repo, token: token) }

    /// Removes the shared sync branch so a test about *joining a repository* actually starts from
    /// one nobody has joined yet.
    ///
    /// `SyncEngine.branch` is a fixed name, so unlike the transport tests these cannot each have
    /// their own — and the branch outlives the process, so without this every run pushes another
    /// starter workspace onto the last one's and the counts climb. The precondition is part of the
    /// test, so the test states it rather than hoping.
    func clearSyncBranch() async throws {
        var req = URLRequest(url: URL(string: "https://api.github.com/repos/\(repo.slug)/git/refs/heads/\(SyncEngine.branch)")!)
        req.httpMethod = "DELETE"
        req.setValue("Bearer \(token!)", forHTTPHeaderField: "Authorization")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        _ = try? await URLSession.shared.data(for: req)   // 422 when it was not there, which is fine
    }

    /// Each run works on its own branch, so a failed run never leaves the next one merging against
    /// wreckage — and two runs at once do not fight.
    lazy var branch = "live-test-\(UUID().uuidString.prefix(8).lowercased())"

    // MARK: the calls the fake cannot vouch for

    func testTheTokenIsAcceptedForARealCall() async throws {
        let who = try await transport.viewer()
        XCTAssertNotNil(who, "GET /user refused the token")
        XCTAssertFalse(who!.login.isEmpty)
        print("YANTRA-LIVE: viewer = \(who!.login)")
    }

    func testTheRepositoryIsReachableAndWritable() async {
        let check = await transport.check()
        guard case let .ok(canPush) = check else {
            return XCTFail("check() said \(check) for \(repo.slug)")
        }
        XCTAssertTrue(canPush, "the token cannot push to \(repo.slug)")
    }

    func testAMissingBranchIsNothingRatherThanAnError() async throws {
        // The first sync on a fresh repository takes this path, and a 404 read as a failure would
        // make sync impossible to ever start.
        let head = try await transport.head(branch: "no-such-branch-\(UUID().uuidString.prefix(6))")
        XCTAssertNil(head)
    }

    /// The whole push path in one: blobs written, a tree built, a commit made, the ref created, and
    /// then every byte read back through the API rather than assumed.
    func testACommitRoundTripsThroughGitHub() async throws {
        let page = Data("# Live\n\n- [ ] a task written by the test\n".utf8)
        let nested = Data("nested\n".utf8)

        let first = try await transport.commit(
            branch: branch, parent: nil, baseTree: nil,
            files: ["pages/live.md": page, "a/b/deep.md": nested],
            message: "live test: first commit")
        let firstSha = try XCTUnwrap(first, "the first commit was refused")
        print("YANTRA-LIVE: first commit \(firstSha) on \(branch)")

        let headOpt = try await transport.head(branch: branch)
        let head = try XCTUnwrap(headOpt, "the branch was not created by the first commit")
        XCTAssertEqual(head.commit, firstSha)
        // `recursive=1` is what makes a nested path appear at all; without it this is the entry
        // that goes missing and a whole subtree silently stops syncing.
        XCTAssertEqual(Set(head.tree.keys), ["pages/live.md", "a/b/deep.md"])

        // The ids GitHub gave back must be the ids this app computes, or a push builds a tree
        // pointing at blobs that do not exist under those names.
        XCTAssertEqual(head.tree["pages/live.md"], gitBlobSha(page),
                       "GitHub's blob id disagrees with gitBlobSha")

        let readBack = try await transport.blob(sha: try XCTUnwrap(head.tree["pages/live.md"]))
        XCTAssertEqual(readBack, page, "the bytes did not survive the round trip")

        // A second commit on top, including a delete.
        let second = try await transport.commit(
            branch: branch, parent: head.commit, baseTree: head.treeSha,
            files: ["pages/live.md": Data("# Live\n\n- [x] done now\n".utf8), "a/b/deep.md": Data?.none],
            message: "live test: edit and delete")
        XCTAssertNotNil(second, "the second commit was refused")

        let head2Opt = try await transport.head(branch: branch)
        let head2 = try XCTUnwrap(head2Opt)
        XCTAssertEqual(head2.tree.keys.sorted(), ["pages/live.md"], "the delete did not take")
        let edited = try await transport.blob(sha: try XCTUnwrap(head2.tree["pages/live.md"]))
        XCTAssertEqual(edited, Data("# Live\n\n- [x] done now\n".utf8))
    }

    /// A stale parent must be refused rather than forced — this is what stops one device's push
    /// erasing another's work, and it is the one case the retry loop exists for.
    func testAStaleParentIsRefusedRatherThanForced() async throws {
        let one = try await transport.commit(branch: branch, parent: nil, baseTree: nil,
                                             files: ["x.md": Data("one\n".utf8)], message: "one")
        let h1Opt = try await transport.head(branch: branch)
        let h1 = try XCTUnwrap(h1Opt)
        _ = try await transport.commit(branch: branch, parent: h1.commit, baseTree: h1.treeSha,
                                       files: ["x.md": Data("two\n".utf8)], message: "two")

        // Now commit again as if we had never seen "two" — which is exactly a second device.
        let stale = try await transport.commit(branch: branch, parent: one, baseTree: h1.treeSha,
                                               files: ["x.md": Data("three\n".utf8)], message: "stale")
        XCTAssertNil(stale, "a stale push was accepted — another device's commit would be lost")
        let afterOpt = try await transport.head(branch: branch)
        let after = try XCTUnwrap(afterOpt)
        let winner = try await transport.blob(sha: try XCTUnwrap(after.tree["x.md"]))
        XCTAssertEqual(winner, Data("two\n".utf8), "the losing push overwrote the winner")
    }

    // MARK: two devices, through the real remote

    /// The thing the user actually asked about: a workspace pushed from one device and picked up by
    /// another, then an edit on each merging without either being lost.
    func testTwoDevicesShareAWorkspace() async throws {
        try await clearSyncBranch()
        let (a, aEngine) = try device("phone")
        let (b, bEngine) = try device("laptop")

        // The first device writes a list and pushes.
        a.scaffold(name: "Live", now: 1)
        a.writePage(PageDoc(id: "groceries", type: NodeType.list, parent: nil, title: "Groceries",
                            modifiedAt: Date(timeIntervalSince1970: 1), device: "phone", blocks: [
            .task(TaskRef(id: "t1", title: "Milk")),
            .task(TaskRef(id: "t2", title: "Bread")),
        ]))
        let push = await aEngine.sync(message: "first push")
        XCTAssertNil(push.error, "the first push failed: \(push.error ?? "")")
        XCTAssertTrue(push.pushed, "nothing was pushed")
        print("YANTRA-LIVE: pushed from phone")

        // The second picks it up from nothing at all.
        let pull = await bEngine.sync(message: "first pull")
        XCTAssertNil(pull.error, "the first pull failed: \(pull.error ?? "")")
        XCTAssertTrue(pull.pulled, "nothing came down")
        let got = b.readPage("groceries")
        XCTAssertNotNil(got, "the page did not arrive on the second device")
        XCTAssertEqual(got?.title, "Groceries")
        XCTAssertEqual(got?.blocks.count, 2)
        print("YANTRA-LIVE: pulled onto laptop")

        // Each edits a different line, neither having seen the other.
        let wa = WorkspaceWriter(store: a, device: "phone")
        let wb = WorkspaceWriter(store: b, device: "laptop")
        try wa.setDone("t1", true)
        try wb.editTask("t2") { var t = $0; t.title = "Sourdough"; return t }

        let pushA = await aEngine.sync(message: "phone edit")
        XCTAssertNil(pushA.error, "phone could not push: \(pushA.error ?? "")")
        let pushB = await bEngine.sync(message: "laptop edit")
        XCTAssertNil(pushB.error, "laptop could not push: \(pushB.error ?? "")")

        // And the first sees the merged result: its own tick and the other's rename.
        let back = await aEngine.sync(message: "phone catches up")
        XCTAssertNil(back.error, "phone could not catch up: \(back.error ?? "")")
        let merged = a.readPage("groceries")
        let ix = WorkspaceIndex.read(a)
        XCTAssertEqual(merged?.blocks.count, 2, "a line was lost in the merge")
        XCTAssertEqual(ix.nodes["t1"]?.done, true, "the phone's own tick came back undone")
        XCTAssertEqual(ix.nodes["t2"].flatMap { $0.title }, "Sourdough",
                       "the laptop's rename was lost")
        print("YANTRA-LIVE: merged — t1 done, t2 renamed, both survived")
    }

    /// The second device joining, which is where this went wrong in practice.
    ///
    /// Reproduced on two simulators first: a phone pushed, an iPad signed in with the same account,
    /// and the iPad ended up with two Inboxes, two Todays, two High Priority and two Getting
    /// started — both Inboxes carrying `system_key: inbox`. The merge was behaving correctly; it had
    /// simply been handed two unrelated workspaces and asked to keep everything.
    func testAJoiningDeviceAdoptsTheRepositoryInsteadOfDoublingIt() async throws {
        try await clearSyncBranch()
        let (a, aEngine) = try device("first")
        a.scaffold(name: "Live", now: 1)
        WorkspaceSeeder.seed(a)
        let wa = WorkspaceWriter(store: a, device: "first")
        guard let inbox = WorkspaceIndex.read(a).node(systemKey: SystemKey.inbox) else {
            return XCTFail("the seed has no inbox")
        }
        _ = try wa.addBlock(to: inbox.id, type: NodeType.task, text: "Written on the first device")
        let push = await aEngine.sync(message: "first device")
        XCTAssertNil(push.error, "the first device could not push: \(push.error ?? "")")

        // The second device did what every device does on first launch: scaffolded and seeded.
        let (b, bEngine) = try device("second")
        b.scaffold(name: "Live", now: 2)
        WorkspaceSeeder.seed(b)
        XCTAssertTrue(WorkspaceLink.isPristine(b), "the starter set should read as untouched")

        let decision = try await bEngine.linkDecision()
        XCTAssertEqual(decision, .adopt, "a joining device must adopt, not merge")

        let adopted = try await bEngine.adoptRemote()
        XCTAssertNil(adopted.error, "adopting failed: \(adopted.error ?? "")")

        // The whole point: one of each, not two.
        let ix = WorkspaceIndex.read(b)
        let inboxes = ix.nodes.values.filter { $0.systemKey == SystemKey.inbox }
        let todays = ix.nodes.values.filter { $0.systemKey == SystemKey.today }
        XCTAssertEqual(inboxes.count, 1, "the second device has \(inboxes.count) Inboxes")
        XCTAssertEqual(todays.count, 1, "the second device has \(todays.count) Todays")

        let titles = ix.nodes.values.compactMap { $0.title }
        XCTAssertTrue(titles.contains("Written on the first device"),
                      "adopting lost the first device's task")
        print("YANTRA-LIVE: joined cleanly — one Inbox, one Today, the task is there")

        // And a normal pass afterwards has a common ancestor, so it is quiet rather than conflicted.
        let after = await bEngine.sync(message: "second device settles")
        XCTAssertNil(after.error, "the pass after adopting failed: \(after.error ?? "")")
        XCTAssertTrue(after.conflicts.isEmpty,
                      "adopting left \(after.conflicts.count) conflicts behind")
    }

    private func device(_ name: String) throws -> (WorkspaceStore, SyncEngine) {
        let root = scratch.appendingPathComponent(name)
        let state = scratch.appendingPathComponent("\(name)-state")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let store = WorkspaceStore(root: root, id: "")
        return (store, SyncEngine(store: store, transport: transport, device: name, stateDir: state))
    }
}
