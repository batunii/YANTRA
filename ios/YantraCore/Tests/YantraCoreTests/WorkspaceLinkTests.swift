import XCTest
@testable import YantraCore

/// Joining a repository that may already hold a workspace.
///
/// The bug these pin down was reproduced on two simulators against a real repository: a phone wrote
/// a task and pushed; an iPad signed in with the same account and pulled; and the iPad ended up with
/// **two** Inboxes, two Todays, two High Priority and two Getting started — both Inboxes carrying
/// `system_key: inbox`, so `node(systemKey:)` picks one arbitrarily and quick-add can land in
/// either. Sync called it `Synced · 2 conflicts resolved`.
final class WorkspaceLinkTests: XCTestCase {

    var dir: URL!
    override func setUpWithError() throws {
        dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("link-\(UUID())")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: dir) }

    func store(_ name: String) -> WorkspaceStore {
        let s = WorkspaceStore(root: dir.appendingPathComponent(name), id: "")
        s.scaffold(name: name, now: 1)
        return s
    }

    // MARK: pristine — the signal the whole decision rests on

    func testAFreshlySeededWorkspaceIsPristine() {
        let s = store("fresh")
        WorkspaceSeeder.seed(s)
        XCTAssertTrue(WorkspaceLink.isPristine(s),
                      "the starter set counts as untouched — nobody has written anything yet")
    }

    func testAnEmptyWorkspaceIsPristine() {
        XCTAssertTrue(WorkspaceLink.isPristine(store("empty")))
    }

    /// The `device:` stamp is exact, not a heuristic: the seeder writes none and the writer stamps
    /// one on every write. So one edit — anywhere — ends pristine.
    func testOneEditMakesItNoLongerPristine() throws {
        let s = store("edited")
        WorkspaceSeeder.seed(s)
        let w = WorkspaceWriter(store: s, device: "phone")
        guard let inbox = WorkspaceIndex.read(s).node(systemKey: SystemKey.inbox) else {
            return XCTFail("no inbox to write into")
        }
        _ = try w.addBlock(to: inbox.id, type: NodeType.task, text: "something of mine")
        XCTAssertFalse(WorkspaceLink.isPristine(s),
                       "a task somebody typed was treated as a discardable starter set")
    }

    /// Running a timer is doing something, even with no page edited.
    func testAFocusSessionMakesItNoLongerPristine() {
        let s = store("focused")
        WorkspaceSeeder.seed(s)
        let session = FocusSession(id: "f1", nodeId: "n1", startedAt: 1000, endedAt: 2000,
                                   plannedSecs: 1500, actualSecs: 1000, outcome: FocusOutcome.ranOut)
        s.appendFocus(session.line, month: session.monthKey)
        XCTAssertFalse(WorkspaceLink.isPristine(s),
                       "a session in the ledger is work this device would be throwing away")
    }

    // MARK: the decision

    func testAJoiningDeviceAdoptsInsteadOfMerging() {
        // Exactly the iPad's situation. Adopting is what stops the second Inbox existing.
        XCTAssertEqual(WorkspaceLink.decide(localIsPristine: true, remoteHasWorkspace: true), .adopt)
    }

    func testTheFirstDevicePushesItsOwn() {
        XCTAssertEqual(WorkspaceLink.decide(localIsPristine: true, remoteHasWorkspace: false), .push)
        // Real work and an empty repository is still a push: there is nothing to lose up there.
        XCTAssertEqual(WorkspaceLink.decide(localIsPristine: false, remoteHasWorkspace: false), .push)
    }

    /// Two real workspaces is the one case with no right answer, so it must not be guessed. They may
    /// be two genuine sets of work, or the same work seen twice after a reinstall — identical from
    /// here, opposite in what they want.
    func testTwoRealWorkspacesAreHandedBackToThePerson() {
        XCTAssertEqual(WorkspaceLink.decide(localIsPristine: false, remoteHasWorkspace: true), .ask)
    }

    // MARK: what counts as a workspace up there

    func testAManifestIsWhatMakesItAWorkspace() {
        XCTAssertTrue(WorkspaceLink.looksLikeAWorkspace([WorkspaceStore.manifestPath, "pages/a.md"]))
        // A branch with unrelated content is not one, and joining it should not adopt.
        XCTAssertFalse(WorkspaceLink.looksLikeAWorkspace(["README.md", "src/main.swift"]))
        XCTAssertFalse(WorkspaceLink.looksLikeAWorkspace([String]()))
    }
}
