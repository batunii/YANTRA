import XCTest
@testable import YantraCore

/// Several workspaces at once: the list on disk, and the one index the screens read.
final class WorkspaceRegistryTests: XCTestCase {

    var root: URL!
    override func setUpWithError() throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("reg-\(UUID())")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: root) }

    func testAnEntrySurvivesBeingWrittenAndReadBack() {
        let registry = WorkspaceRegistry(root: root)
        XCTAssertTrue(registry.entries().isEmpty)
        registry.add(WorkspaceEntry(id: "a", name: "Team", slug: "you/team-tasks"))
        XCTAssertEqual(WorkspaceRegistry(root: root).entry("a")?.slug, "you/team-tasks")
    }

    func testAddingTheSameIdReplacesRatherThanDuplicates() {
        let registry = WorkspaceRegistry(root: root)
        registry.add(WorkspaceEntry(id: "a", name: "Team", slug: "you/team"))
        registry.add(WorkspaceEntry(id: "a", name: "Team renamed", slug: "you/team"))
        XCTAssertEqual(registry.entries().count, 1)
        XCTAssertEqual(registry.entries().first?.name, "Team renamed")
    }

    /// The local workspace is not in the list, and its directory is still spelled out: an empty id
    /// resolving to the parent would put one workspace's pages beside every other's directory.
    func testTheLocalWorkspaceHasItsOwnDirectoryAndNoEntry() {
        let registry = WorkspaceRegistry(root: root)
        XCTAssertEqual(registry.dir(for: "").lastPathComponent, "local")
        XCTAssertEqual(registry.dir(for: "a").lastPathComponent, "a")
        XCTAssertTrue(registry.entries().isEmpty)
    }

    func testForgettingOneLeavesTheOthers() {
        let registry = WorkspaceRegistry(root: root)
        registry.add(WorkspaceEntry(id: "a", name: "A"))
        registry.add(WorkspaceEntry(id: "b", name: "B"))
        registry.remove("a")
        XCTAssertEqual(registry.entries().map(\.id), ["b"])
    }

    // MARK: the merged index

    private func seeded(_ id: String, _ name: String, task: String) -> WorkspaceStore {
        let store = WorkspaceStore(root: root.appendingPathComponent(name), id: id)
        store.scaffold(name: name, now: 1)
        let writer = WorkspaceWriter(store: store, device: "test")
        let page = try! writer.createTopLevel(type: NodeType.list, title: name + " list")
        _ = try! writer.addBlock(to: page, type: NodeType.task, text: task)
        return store
    }

    func testTwoWorkspacesReadAsOneIndexWithEachNodeKnowingWhereItLives() {
        let local = seeded("", "Personal", task: "mine")
        let team = seeded("team-id", "Team", task: "theirs")

        let ix = WorkspaceIndex.read([local, team])
        let mine = ix.nodes.values.first { $0.title == "mine" }
        let theirs = ix.nodes.values.first { $0.title == "theirs" }
        XCTAssertEqual(mine?.workspaceId, "")
        XCTAssertEqual(theirs?.workspaceId, "team-id")
        // Both lists are on Home, which is the whole point of reading them together.
        XCTAssertEqual(ix.children(of: nil).filter { $0.type == NodeType.list }.count, 2)
    }

    /// A writer owns one store, so a destination in another workspace is refused rather than
    /// attempted — `editPage` would otherwise invent the page here and leave the task in neither
    /// place a person can reach.
    func testATaskCannotBeMovedIntoAnotherWorkspacesList() throws {
        let local = seeded("", "Personal", task: "mine")
        let team = seeded("team-id", "Team", task: "theirs")
        let ix = WorkspaceIndex.read([local, team])
        let task = try XCTUnwrap(ix.nodes.values.first { $0.title == "mine" })
        let theirList = try XCTUnwrap(ix.nodes.values.first { $0.workspaceId == "team-id" && $0.type == NodeType.list })

        XCTAssertThrowsError(try WorkspaceWriter(store: local, device: "test").moveTask(task.id, toList: theirList.id)) {
            XCTAssertTrue($0 is WorkspaceWriter.MoveRefused, "got \($0)")
        }
        // And the task is exactly where it was.
        XCTAssertNotNil(WorkspaceIndex.read([local, team]).nodes[task.id])
    }

    /// A smart list is a view *over* repositories rather than a thing inside one, so only the local
    /// workspace's are read. One cloned into a shared repository would name workspaces the person
    /// cloning it does not have.
    func testSmartListsComeFromTheLocalWorkspaceAlone() {
        let local = seeded("", "Personal", task: "mine")
        let team = seeded("team-id", "Team", task: "theirs")
        let teamWriter = WorkspaceWriter(store: team, device: "test")
        let smart = try! teamWriter.createTopLevel(type: NodeType.smartList, title: "Theirs")
        try! teamWriter.writeSmartList(SmartListDef(nodeId: smart, filterJson: "{}"))

        let ix = WorkspaceIndex.read([local, team])
        XCTAssertTrue(ix.smartLists.isEmpty)
    }
}

/// Moving a node from one workspace to another — the thing a single writer cannot do.
final class WorkspaceMoveTests: XCTestCase {

    var root: URL!
    override func setUpWithError() throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("move-\(UUID())")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: root) }

    private func workspace(_ id: String, _ name: String) -> (WorkspaceStore, WorkspaceWriter) {
        let store = WorkspaceStore(root: root.appendingPathComponent(name), id: id)
        store.scaffold(name: name, now: 1)
        return (store, WorkspaceWriter(store: store, device: "test"))
    }

    func testATaskMovesToAListInAnotherWorkspaceAndLeavesNothingBehind() throws {
        let (mineStore, mine) = workspace("", "Personal")
        let (theirsStore, theirs) = workspace("team", "Team")
        let myList = try mine.createTopLevel(type: NodeType.list, title: "Mine")
        let theirList = try theirs.createTopLevel(type: NodeType.list, title: "Theirs")
        let task = try mine.addBlock(to: myList, type: NodeType.task, text: "Move me")

        try WorkspaceMove.move(nodeId: task, toList: theirList, from: mine, to: theirs)

        let after = WorkspaceIndex.read([mineStore, theirsStore])
        let moved = try XCTUnwrap(after.nodes[task], "the task is in neither workspace")
        XCTAssertEqual(moved.workspaceId, "team", "it did not end up in the other workspace")
        XCTAssertEqual(moved.parentId, theirList)
        // And it is gone from where it was, rather than being in both.
        XCTAssertFalse(WorkspaceIndex.read([mineStore]).nodes.values.contains { $0.title == "Move me" },
                       "the original was left behind, so the task now exists twice")
    }

    /// Subtasks and the pages under them come too. A move that took the line and left the page would
    /// be a task whose contents had silently stayed in a repository it no longer belongs to.
    func testWhatIsFiledUnderTheTaskComesWithIt() throws {
        let (mineStore, mine) = workspace("", "Personal")
        let (theirsStore, theirs) = workspace("team", "Team")
        let myList = try mine.createTopLevel(type: NodeType.list, title: "Mine")
        let theirList = try theirs.createTopLevel(type: NodeType.list, title: "Theirs")
        let parent = try mine.addBlock(to: myList, type: NodeType.task, text: "Parent")
        let child = try mine.addBlock(to: parent, type: NodeType.task, text: "Child")

        try WorkspaceMove.move(nodeId: parent, toList: theirList, from: mine, to: theirs)

        let after = WorkspaceIndex.read([mineStore, theirsStore])
        XCTAssertEqual(after.nodes[child]?.workspaceId, "team", "the subtask did not come with its parent")
        XCTAssertEqual(after.nodes[child]?.parentId, parent)
    }

    /// A destination the writer does not hold is refused rather than invented — the failure this
    /// whole type exists to replace.
    func testAListThatIsNotInTheDestinationIsRefused() throws {
        let (_, mine) = workspace("", "Personal")
        let (_, theirs) = workspace("team", "Team")
        let myList = try mine.createTopLevel(type: NodeType.list, title: "Mine")
        let task = try mine.addBlock(to: myList, type: NodeType.task, text: "Move me")

        XCTAssertThrowsError(try WorkspaceMove.move(nodeId: task, toList: myList, from: mine, to: theirs)) {
            XCTAssertTrue($0 is WorkspaceMove.Refused, "got \($0)")
        }
    }

    /// Within one workspace it is the ordinary move, not a copy-and-delete across stores.
    func testAMoveInsideOneWorkspaceIsStillJustAMove() throws {
        let (store, writer) = workspace("", "Personal")
        let a = try writer.createTopLevel(type: NodeType.list, title: "A")
        let b = try writer.createTopLevel(type: NodeType.list, title: "B")
        let task = try writer.addBlock(to: a, type: NodeType.task, text: "Move me")

        try WorkspaceMove.move(nodeId: task, toList: b, from: writer, to: writer)

        XCTAssertEqual(WorkspaceIndex.read([store]).nodes[task]?.parentId, b)
    }
}

/// Typing a link, and reading the ones already written.
final class LinkDraftTests: XCTestCase {

    func testACaretInsideAnOpenBracketIsADraft() {
        let (range, typed) = Links.draft("See [[Call", caret: 10)!
        XCTAssertEqual(typed, "Call")
        XCTAssertEqual(NSString(string: "See [[Call").substring(with: range), "[[Call")
    }

    func testTheDraftIsEmptyTheMomentTheBracketsOpen() {
        let (_, typed) = Links.draft("See [[", caret: 6)!
        XCTAssertEqual(typed, "", "an empty draft still offers a picker — that is the point of it")
    }

    /// Anything that ends the token ends the draft: a newline or a bracket means the `[[` was never
    /// the start of a link, and a `]]` means it was one and is finished.
    func testAFinishedOrBrokenLinkIsNotADraft() {
        XCTAssertNil(Links.draft("See [[Call Bob|^abc]]", caret: 21))
        XCTAssertNil(Links.draft("See [[Call\nBob", caret: 14))
        XCTAssertNil(Links.draft("no brackets here", caret: 16))
        XCTAssertNil(Links.draft("", caret: 0))
    }

    /// The caret behind the brackets is not inside them.
    func testACaretBeforeTheBracketsIsNotADraft() {
        XCTAssertNil(Links.draft("See [[Call", caret: 2))
    }

    func testTargetsAreEveryTaskPointedAtOnceEach() {
        let text = "[[A|^one]] and [[B|^two]] and [[A again|^one]]"
        XCTAssertEqual(Links.targets(text), ["one", "two"])
    }

    /// A link renders as its target's *current* title, and falls back to the stored label when the
    /// id resolves to nothing — the task was deleted, or lives in a workspace this device has not
    /// added. That is why the label is worth storing even though the index could supply it.
    func testRenderingPrefersTheLiveTitleAndFallsBackToTheLabel() {
        let text = "See [[Call Bob|^abc]] and [[Gone|^zzz]]"
        XCTAssertEqual(Links.plain(text) { $0 == "abc" ? "Ring Robert" : nil },
                       "See Ring Robert and Gone")
    }
}

/// What happens to time set aside for a task when the task goes.
final class DeletingATaskWithASittingTests: XCTestCase {

    var root: URL!
    override func setUpWithError() throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("sit-\(UUID())")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: root) }

    /// A sitting is time set aside **for** a task. With the task deleted it means nothing, cannot
    /// even draw its own name — its words are the task's — and yet it stayed on the day as a block
    /// with no words in it, which is what "I deleted it and it is still on my calendar" looks like.
    func testDeletingATaskTakesItsSittingsOffTheCalendar() throws {
        let store = WorkspaceStore(root: root.appendingPathComponent("ws"), id: "")
        store.scaffold(name: "Personal", now: 1)
        let writer = WorkspaceWriter(store: store, device: "test")

        let list = try writer.createTopLevel(type: NodeType.list, title: "Work")
        let task = try writer.addBlock(to: list, type: NodeType.task, text: "Write the thing")
        let day = LocalDate.today()
        _ = try writer.addEvent(to: list, title: "", time: EventTime(start: LocalDateTime(date: day, hour: 10),
                                                                    end: LocalDateTime(date: day, hour: 11)),
                                forTaskId: task)

        // It is on the day, drawn with the task's words.
        func onTheDay() -> [DayItem] {
            CalendarBucketer.bucket(nodes: Array(WorkspaceIndex.read([store]).nodes.values),
                                    from: day, toExclusive: day.adding(days: 1))[day] ?? []
        }
        let before = onTheDay()
        XCTAssertTrue(before.contains { if case let .event(e) = $0 { return e.forTaskId == task }; return false },
                      "the sitting was not on the day to begin with")

        // Delete the task the way the page does.
        let (home, index) = try XCTUnwrap(writer.locate(taskId: task))
        try writer.removeBlock(pageId: home, index: index)

        let after = onTheDay()
        XCTAssertFalse(after.contains { if case let .event(e) = $0 { return e.forTaskId == task }; return false },
                       "the task was deleted and its sitting is still on the calendar")
    }
}

/// Writing to a task the index has not seen yet.
final class WritingToAFreshTaskTests: XCTestCase {

    var root: URL!
    override func setUpWithError() throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("fresh-\(UUID())")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: root) }

    private func workspace(_ id: String, _ name: String) -> (WorkspaceStore, WorkspaceWriter) {
        let store = WorkspaceStore(root: root.appendingPathComponent(name), id: id)
        store.scaffold(name: name, now: 1)
        return (store, WorkspaceWriter(store: store, device: "test"))
    }

    /// The bug this pins: a task is created in one workspace and the assignee is written through a
    /// writer chosen by looking the task up in the index — which has not been rebuilt yet, so the
    /// lookup misses and the write lands in the local workspace instead. It found nothing there and
    /// said nothing, so the name simply vanished.
    func testAWriteAimedAtAnotherWorkspacesTaskIsRefusedRatherThanLost() throws {
        let (_, mine) = workspace("", "Personal")
        let (theirsStore, theirs) = workspace("team", "Team")
        let list = try theirs.createTopLevel(type: NodeType.list, title: "Team list")
        let task = try theirs.addBlock(to: list, type: NodeType.task, text: "Send the invoice")

        // The wrong writer — what the fall-back used to pick — must not silently do nothing.
        XCTAssertThrowsError(try mine.setAssignee(task, "batunii")) {
            XCTAssertTrue($0 is WorkspaceWriter.NotHere, "got \($0)")
        }
        // And the right one writes it where it belongs.
        try theirs.setAssignee(task, "batunii")
        XCTAssertEqual(WorkspaceIndex.read([theirsStore]).nodes[task]?.assignee, "batunii")
    }
}

/// The order workspaces are shown in.
final class WorkspaceOrderTests: XCTestCase {

    var root: URL!
    override func setUpWithError() throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("order-\(UUID())")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: root) }

    func testTheNewestWorkspaceIsShownFirst() {
        let registry = WorkspaceRegistry(root: root)
        registry.add(WorkspaceEntry(id: "old", name: "Older", addedAt: 1_000))
        registry.add(WorkspaceEntry(id: "new", name: "Newer", addedAt: 2_000))
        XCTAssertEqual(registry.newestFirst().map(\.id), ["new", "old"])
    }

    /// Renaming one, or giving it a colour, is not adding it again.
    func testEditingAWorkspaceDoesNotJumpItToTheTop() {
        let registry = WorkspaceRegistry(root: root)
        registry.add(WorkspaceEntry(id: "old", name: "Older", addedAt: 1_000))
        registry.add(WorkspaceEntry(id: "new", name: "Newer", addedAt: 2_000))
        registry.add(WorkspaceEntry(id: "old", name: "Older, renamed", color: "Teal"))
        XCTAssertEqual(registry.newestFirst().map(\.id), ["new", "old"], "an edit re-dated the entry")
        XCTAssertEqual(registry.entry("old")?.addedAt, 1_000, "the original moment was lost")
    }

    /// An entry written before this existed has no moment, and is as old as it looks.
    func testAnUndatedEntrySortsAsOldest() {
        let registry = WorkspaceRegistry(root: root)
        registry.add(WorkspaceEntry(id: "dated", name: "Dated", addedAt: 5_000))
        // Written the way an older build would have: straight to the file, with no date.
        let older = WorkspaceEntry(id: "undated", name: "Undated")
        let encoder = JSONEncoder()
        let both = registry.entries().filter { $0.id != older.id } + [older]
        try? encoder.encode(both).write(to: root.appendingPathComponent("registry.json"))
        XCTAssertEqual(registry.newestFirst().map(\.id), ["dated", "undated"])
    }
}
