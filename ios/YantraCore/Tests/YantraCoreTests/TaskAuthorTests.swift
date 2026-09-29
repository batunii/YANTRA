import XCTest
@testable import YantraCore

/// A task made in a shared workspace says who made it — `by:<login>` straight after the title, as
/// newer Android builds write it (`- [~] Draft the doc on:sai by:sai ^t1 …`). Never in Personal.
final class TaskAuthorTests: XCTestCase {
    var dir: URL!

    override func setUpWithError() throws {
        dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("yantra-author-\(UUID().uuidString)")
    }
    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: dir) }

    private func writer(_ id: String) -> (WorkspaceStore, WorkspaceWriter, String) {
        let store = WorkspaceStore(root: dir.appendingPathComponent(id.isEmpty ? "local" : id), id: id)
        store.scaffold(name: "W", now: 0)
        let w = WorkspaceWriter(store: store, device: "test")
        let list = try! w.createTopLevel(type: NodeType.list, title: "L")
        return (store, w, list)
    }

    private func line(_ store: WorkspaceStore, _ page: String, _ id: String) -> String {
        let text = String(decoding: try! Data(contentsOf: store.pageFile(page)), as: UTF8.self)
        return text.split(separator: "\n").first { $0.contains("^\(id)") }.map(String.init) ?? ""
    }

    func testANewTaskInASharedWorkspaceIsStampedStraightAfterItsTitle() throws {
        let (store, w, list) = writer("shared-1")
        w.author = { "saieeshward" }
        let id = try w.addBlock(to: list, type: NodeType.task, text: "Write the brief", due: nil)
        XCTAssertEqual(line(store, list, id), "- [ ] Write the brief by:saieeshward ^\(id)")
        // And it reads back as the author, not as title text.
        let n = WorkspaceIndex.read([store]).nodes[id]
        XCTAssertEqual(n?.title, "Write the brief")
        XCTAssertEqual(n?.addedBy, "saieeshward")
    }

    func testTheStampSurvivesAnEdit() throws {
        let (store, w, list) = writer("shared-2")
        w.author = { "saieeshward" }
        let id = try w.addBlock(to: list, type: NodeType.task, text: "Write the brief", due: nil)
        try w.setDone(id, true)
        XCTAssertTrue(line(store, list, id).contains("by:saieeshward"), line(store, list, id))
        XCTAssertEqual(WorkspaceIndex.read([store]).nodes[id]?.addedBy, "saieeshward")
    }

    func testPersonalIsNeverStamped() throws {
        let (store, w, list) = writer("")
        let id = try w.addBlock(to: list, type: NodeType.task, text: "Mine", due: nil)
        XCTAssertEqual(line(store, list, id), "- [ ] Mine ^\(id)")
    }
}
