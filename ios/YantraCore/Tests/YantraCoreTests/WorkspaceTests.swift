import XCTest
@testable import YantraCore

/// The store, the writer and the index over a real directory.
///
/// A temporary directory rather than a mock, because the thing being tested *is* the files: every
/// write goes to disk first and the index is rebuilt from what is there. A fake filesystem would
/// pass while the real one lost a line.
final class WorkspaceTests: XCTestCase {

    var dir: URL!
    var store: WorkspaceStore!
    var writer: WorkspaceWriter!

    override func setUpWithError() throws {
        dir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("yantra-tests-\(UUID().uuidString)")
        store = WorkspaceStore(root: dir, id: "")
        store.scaffold(name: "Test", now: 0)
        writer = WorkspaceWriter(store: store, device: "test")
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    var index: WorkspaceIndex { WorkspaceIndex.read(store) }

    @discardableResult
    func list(_ title: String) throws -> String {
        try writer.createTopLevel(type: NodeType.list, title: title)
    }

    // MARK: the store

    func testScaffoldMakesAReadableWorkspace() {
        XCTAssertTrue(store.exists)
        XCTAssertEqual(store.readManifest()?.name, "Test")
        XCTAssertEqual(store.readManifest()?.formatVersion, WorkspaceStore.formatVersion)
        XCTAssertFalse(store.isReadOnly)
        // The built-in properties are the fixed fields every workspace has.
        XCTAssertEqual(Set(store.readProperties().map(\.id)),
                       [BuiltIns.priority, BuiltIns.due, BuiltIns.deadline, BuiltIns.assignee])
    }

    func testAWorkspaceFromANewerAppIsReadOnly() throws {
        var m = store.readManifest()!
        m.formatVersion = WorkspaceStore.formatVersion + 1
        store.writeManifest(m)
        XCTAssertTrue(store.isReadOnly)
        // And the writer refuses rather than corrupting it.
        XCTAssertThrowsError(try writer.createTopLevel(type: NodeType.list, title: "Nope")) { e in
            XCTAssertTrue(e is WorkspaceWriter.ReadOnly)
        }
    }

    func testAPageRoundTripsThroughTheFile() throws {
        let id = try list("Groceries")
        _ = try writer.addBlock(to: id, type: NodeType.task, text: "Milk")
        let page = store.readPage(id)
        XCTAssertEqual(page?.title, "Groceries")
        XCTAssertEqual(page?.blocks.count, 1)
        XCTAssertEqual(page?.blocks.first?.text, "Milk")
    }

    // MARK: the writer

    func testAddingATaskMintsAnIdAndIndexesIt() throws {
        let l = try list("L")
        let t = try writer.addBlock(to: l, type: NodeType.task, text: "Thing")
        XCTAssertFalse(t.isEmpty)
        let ix = index
        XCTAssertEqual(ix.nodes[t]?.title, "Thing")
        XCTAssertEqual(ix.nodes[t]?.parentId, l)
        XCTAssertEqual(ix.children(of: l).map(\.id), [t])
    }

    func testFinishingATaskStampsTheDayAndUnfinishingClearsIt() throws {
        let l = try list("L")
        let t = try writer.addBlock(to: l, type: NodeType.task, text: "Thing")

        try writer.setDone(t, true)
        XCTAssertTrue(index.nodes[t]!.done)
        XCTAssertEqual(index.nodes[t]?.doneAt, .today())

        // A task cannot claim to have been completed on a day it was not.
        try writer.setDone(t, false)
        XCTAssertFalse(index.nodes[t]!.done)
        XCTAssertNil(index.nodes[t]?.doneAt, "un-finishing must clear the completion day")
    }

    func testStartingATaskIsNotFinishingIt() throws {
        let l = try list("L")
        let t = try writer.addBlock(to: l, type: NodeType.task, text: "Thing")
        try writer.setInProgress(t, true)
        XCTAssertTrue(index.nodes[t]!.inProgress)
        XCTAssertFalse(index.nodes[t]!.done)

        // A finished task does not go back to being started.
        try writer.setDone(t, true)
        try writer.setInProgress(t, true)
        XCTAssertTrue(index.nodes[t]!.done)
        XCTAssertFalse(index.nodes[t]!.inProgress)
    }

    func testEditingOneBlockLeavesItsNeighboursBytesAlone() throws {
        let l = try list("L")
        // A hand-written paragraph with spacing the emitter would not choose for itself.
        var page = store.readPage(l)!
        page.blocks = [
            .prose("Some  prose   with odd spacing", indent: 0, raw: "Some  prose   with odd spacing"),
            .task(TaskRef(id: "t1", title: "One", raw: "- [ ] One ^t1")),
        ]
        store.writePage(page)

        try writer.setTitle("t1", "One, renamed")

        let after = store.readPage(l)!
        XCTAssertEqual(after.blocks[0].raw, "Some  prose   with odd spacing",
                       "touching a task rewrote the paragraph above it")
        XCTAssertEqual(after.blocks[1].text, "One, renamed")
    }

    func testMovingATaskToAnotherListTakesItsPageWithIt() throws {
        let a = try list("A"), b = try list("B")
        let t = try writer.addBlock(to: a, type: NodeType.task, text: "Thing")
        // Give it a page of its own.
        _ = try writer.addBlock(to: t, type: NodeType.paragraph, text: "A note")
        XCTAssertEqual(store.readPage(t)?.parent, a)

        try writer.moveTask(t, toList: b)
        XCTAssertEqual(index.nodes[t]?.parentId, b)
        XCTAssertEqual(store.readPage(t)?.parent, b, "the task's own page still points at the old list")
        XCTAssertTrue(index.children(of: a).isEmpty)
    }

    func testRemovingATaskTakesItsPageAway() throws {
        let l = try list("L")
        let t = try writer.addBlock(to: l, type: NodeType.task, text: "Thing")
        _ = try writer.addBlock(to: t, type: NodeType.paragraph, text: "A note")
        XCTAssertNotNil(store.readPage(t))

        try writer.removeBlock(pageId: l, index: 0)
        XCTAssertNil(store.readPage(t), "the task's page outlived its line")
        XCTAssertNil(index.nodes[t])
    }

    func testMovingABlockReordersThePage() throws {
        let l = try list("L")
        let a = try writer.addBlock(to: l, type: NodeType.task, text: "First")
        let b = try writer.addBlock(to: l, type: NodeType.task, text: "Second")
        XCTAssertEqual(index.children(of: l).map(\.id), [a, b])

        try writer.moveBlock(pageId: l, from: 1, to: 0)
        XCTAssertEqual(index.children(of: l).map(\.id), [b, a])
    }

    // MARK: events

    func testAnEventLineRoundTripsThroughTheWriterAndTheIndex() throws {
        let l = try list("L")
        let time = EventTime(start: LocalDateTime("2026-09-11T09:00")!,
                             end: LocalDateTime("2026-09-11T09:30")!)
        let e = try writer.addEvent(to: l, title: "Standup", time: time, location: "Room 3", color: "Teal")

        let node = index.nodes[e]
        XCTAssertEqual(node?.type, NodeType.event)
        XCTAssertEqual(node?.event?.title, "Standup")
        XCTAssertEqual(node?.event?.location, "Room 3", "a value with a space must survive the token encoding")
        XCTAssertEqual(node?.event?.color, "Teal")
        XCTAssertEqual(node?.event?.time.duration, .minutes(30))
    }

    func testMovingAnEventKeepsEverythingElseAboutIt() throws {
        let l = try list("L")
        let e = try writer.addEvent(to: l, title: "Standup",
                                    time: EventTime(start: LocalDateTime("2026-09-11T09:00")!,
                                                    end: LocalDateTime("2026-09-11T09:30")!),
                                    location: "Room 3", color: "Teal")
        try writer.setEventTime(e, EventTime(start: LocalDateTime("2026-09-12T11:00")!,
                                             end: LocalDateTime("2026-09-12T11:30")!))
        let ev = index.nodes[e]?.event
        XCTAssertEqual(ev?.time.start, LocalDateTime("2026-09-12T11:00"))
        XCTAssertEqual(ev?.location, "Room 3")
        XCTAssertEqual(ev?.color, "Teal")
    }

    func testDeletingASittingLeavesItsTaskAlone() throws {
        let l = try list("L")
        let t = try writer.addBlock(to: l, type: NodeType.task, text: "Write it")
        let e = try writer.addEvent(to: l, title: "", time: EventTime(start: LocalDateTime("2026-09-11T09:00")!,
                                                                     end: LocalDateTime("2026-09-11T10:00")!),
                                    forTaskId: t)
        try writer.deleteEvent(e)
        XCTAssertNil(index.nodes[e])
        XCTAssertNotNil(index.nodes[t], "deleting time set aside for a task deleted the task")
    }

    func testCancellingOneOccurrenceLeavesTheSeriesLineAlone() throws {
        let l = try list("L")
        let series = try writer.addEvent(to: l, title: "Standup",
                                         time: EventTime(start: LocalDateTime("2026-09-07T09:00")!,
                                                         end: LocalDateTime("2026-09-07T09:15")!),
                                         rrule: "FREQ=WEEKLY;BYDAY=MO")
        let tomb = try writer.cancelOccurrence(of: series, at: LocalDateTime("2026-09-14T09:00")!)
        XCTAssertNotNil(tomb)
        // The series is untouched; the cancellation is a line of its own, which is what two devices
        // cancelling two different days must not collide on.
        XCTAssertEqual(index.nodes[series]?.event?.rrule, "FREQ=WEEKLY;BYDAY=MO")
        XCTAssertFalse(index.nodes[series]?.event?.cancelled ?? true)
        let t = index.nodes[tomb!]?.event
        XCTAssertTrue(t?.cancelled ?? false)
        XCTAssertEqual(t?.series?.id, index.nodes[series]?.event?.id)
        // The occurrence it replaces is its own start, so the bare `series:<id>` form is what gets
        // written — "the occurrence at this line's own start" — and `originalStart` is absent.
        // Writing the start twice would be two places for two devices to disagree.
        XCTAssertNil(t?.series?.originalStart, "a cancellation sitting on its own start names it once")
        let occurrence = t?.series?.originalStart ?? t?.time.start
        XCTAssertEqual(occurrence, LocalDateTime("2026-09-14T09:00"))
    }

    // MARK: the page's colour

    func testAListsColourLivesInItsFile() throws {
        let l = try list("L")
        try writer.setPageColor(l, "Teal")
        XCTAssertEqual(store.readPage(l)?.color, "Teal")
        try writer.setPageColor(l, nil)
        XCTAssertNil(store.readPage(l)?.color)
    }

    // MARK: smart lists

    func testTodaysRuleFindsWhatIsDueAndNothingFinished() throws {
        let l = try list("L")
        let today = try writer.addBlock(to: l, type: NodeType.task, text: "Due today",
                                        due: DueSpec(.allDay(.today())))
        let overdue = try writer.addBlock(to: l, type: NodeType.task, text: "Overdue",
                                          due: DueSpec(.allDay(LocalDate.today().adding(days: -2))))
        let later = try writer.addBlock(to: l, type: NodeType.task, text: "Next week",
                                        due: DueSpec(.allDay(LocalDate.today().adding(days: 7))))
        let done = try writer.addBlock(to: l, type: NodeType.task, text: "Already done",
                                       due: DueSpec(.allDay(.today())))
        try writer.setDone(done, true)

        let filter: Filter = .all([.type(NodeType.task), .done(false), .anyOf([
            .prop(defId: BuiltIns.due, op: .lte, dateRel: .todayEnd),
            .prop(defId: BuiltIns.deadline, op: .lte, dateRel: .todayEnd),
        ])])
        let def = SmartListDef(nodeId: "s", filterJson: FilterJSON.encode(filter))
        let rows = SmartListQuery.run(def, in: index).map(\.id)

        XCTAssertTrue(rows.contains(today))
        XCTAssertTrue(rows.contains(overdue), "overdue is still due")
        XCTAssertFalse(rows.contains(later))
        XCTAssertFalse(rows.contains(done))
    }

    func testAStartedTaskSortsFirstWhateverTheSortSays() throws {
        let l = try list("L")
        let a = try writer.addBlock(to: l, type: NodeType.task, text: "A", due: DueSpec(.allDay(.today())))
        let b = try writer.addBlock(to: l, type: NodeType.task, text: "B",
                                    due: DueSpec(.allDay(LocalDate.today().adding(days: 3))))
        try writer.setInProgress(b, true)

        let def = SmartListDef(nodeId: "s",
                               filterJson: FilterJSON.encode(Filter.all([.type(NodeType.task), .done(false)])),
                               sortJson: FilterJSON.encode([SortSpec(by: .propDate, defId: BuiltIns.due)]))
        XCTAssertEqual(SmartListQuery.run(def, in: index).map(\.id), [b, a],
                       "the task you have started belongs at the top")
    }

    func testAPriorityRuleMatchesOnTheValueOnTheLine() throws {
        let l = try list("L")
        let high = try writer.addBlock(to: l, type: NodeType.task, text: "Important", priority: "High")
        _ = try writer.addBlock(to: l, type: NodeType.task, text: "Ordinary")
        let def = SmartListDef(nodeId: "s", filterJson: FilterJSON.encode(
            Filter.all([.type(NodeType.task), .prop(defId: BuiltIns.priority, op: .eq, text: "High")])))
        XCTAssertEqual(SmartListQuery.run(def, in: index).map(\.id), [high])
    }

    func testAScopedRuleOnlyLooksInsideItsBranch() throws {
        let a = try list("A"), b = try list("B")
        let inA = try writer.addBlock(to: a, type: NodeType.task, text: "Inside")
        _ = try writer.addBlock(to: b, type: NodeType.task, text: "Outside")
        let def = SmartListDef(nodeId: "s", scopeRootId: a,
                               filterJson: FilterJSON.encode(Filter.all([.type(NodeType.task)])))
        XCTAssertEqual(SmartListQuery.run(def, in: index).map(\.id), [inA])
    }

    // MARK: the archive

    func testFinishedTasksLeaveOnTheThresholdAndComeBack() throws {
        let l = try list("L")
        let old = try writer.addBlock(to: l, type: NodeType.task, text: "Long done")
        let recent = try writer.addBlock(to: l, type: NodeType.task, text: "Just done")
        try writer.setDone(old, true)
        try writer.setDone(recent, true)
        // Backdate one of them.
        try writer.editTask(old) { var t = $0; t.doneAt = LocalDate.today().adding(days: -40); return t }

        let moved = try writer.archiveFinished(before: LocalDate.today().adding(days: -30)) { _ in false }
        XCTAssertEqual(moved, 1)
        XCTAssertNil(index.nodes[old], "the old task did not leave the list")
        XCTAssertNotNil(index.nodes[recent], "a recently finished task was archived too early")
        XCTAssertEqual(writer.archivedCount(), 1)

        let back = try writer.restoreArchived(pageId: l, taskIds: [old])
        XCTAssertEqual(back, 1)
        XCTAssertNotNil(index.nodes[old], "restoring did not put it back on the list")
        XCTAssertEqual(writer.archivedCount(), 0)
    }

    func testATaskWithUnfinishedChildrenStays() throws {
        let l = try list("L")
        let parent = try writer.addBlock(to: l, type: NodeType.task, text: "Parent")
        try writer.setDone(parent, true)
        try writer.editTask(parent) { var t = $0; t.doneAt = LocalDate.today().adding(days: -40); return t }

        let moved = try writer.archiveFinished(before: LocalDate.today().adding(days: -30)) { id in id == parent }
        XCTAssertEqual(moved, 0, "a finished task with unfinished children took them out of sight")
        XCTAssertNotNil(index.nodes[parent])
    }

    // MARK: ink

    func testInkIsWrittenBesideThePageAndReadBack() throws {
        let l = try list("L")
        let ink = try writer.addBlock(to: l, type: NodeType.ink, text: "")
        let stroke = StrokeEnvelope.encode(.init(
            header: .init(family: "pressure_pen", color: 0xFF000000, size: 3, epsilon: 0.1),
            tool: StrokeEnvelope.toolStylus,
            points: [.init(x: 1, y: 2, elapsedMillis: 0, pressure: 0.5, tiltRadians: -1, orientationRadians: -1, strokeUnitLengthCm: 0)]))
        try writer.writeInk(ink, [stroke])

        XCTAssertEqual(store.readInk(ink).count, 1)
        XCTAssertEqual(store.readInk(ink).first, stroke, "the sidecar did not return the bytes it was given")

        // An empty sketch leaves no file behind.
        try writer.writeInk(ink, [])
        XCTAssertTrue(store.readInk(ink).isEmpty)
    }
}

// MARK: - quick capture

/// The capture grammar. Two invariants: nothing is consumed silently, and if stripping everything
/// would leave a blank title, nothing is stripped.
final class CaptureTests: XCTestCase {

    let today = LocalDate("2026-09-11")!   // a Friday

    func parse(_ s: String, lists: [String] = [], people: [String] = []) -> Captured {
        CaptureParse.parse(s, today: today, lists: lists, people: people)
    }

    func testAPlainLineIsJustATitle() {
        let c = parse("Buy milk")
        XCTAssertEqual(c.title, "Buy milk")
        XCTAssertFalse(c.hasAnything)
    }

    func testLabelsAndPriorityComeOffTheLine() {
        let c = parse("Buy milk #groceries !High")
        XCTAssertEqual(c.title, "Buy milk")
        XCTAssertEqual(c.labels, ["groceries"])
        XCTAssertEqual(c.priority, "High")
    }

    /// The assignee is a **closed list**: a name nobody has is not an assignee, it is words.
    ///
    /// Handing a task to somebody who does not exist is worse than not handing it over at all, so
    /// the parser reads what was typed and only the caller knows whether it names anyone.
    func testOnlyAKnownPersonBecomesAnAssignee() {
        let stranger = parse("Buy milk @sam")
        XCTAssertEqual(stranger.title, "Buy milk @sam", "an unknown name belongs in the title")
        XCTAssertNil(stranger.assignee)

        let known = parse("Buy milk @sam", people: ["sam"])
        XCTAssertEqual(known.title, "Buy milk")
        XCTAssertEqual(known.assignee, "sam")
    }

    func testAnIsoDateIsRead() {
        let c = parse("Renew the passport 2026-10-02")
        XCTAssertEqual(c.title, "Renew the passport")
        XCTAssertEqual(c.date, LocalDate("2026-10-02"))
    }

    func testATimeMakesTheDueAnInstantRatherThanADay() {
        let c = parse("Call the dentist 2026-10-02 2pm")
        XCTAssertEqual(c.time?.hour, 14)
        guard case .at? = c.due()?.value else { return XCTFail("a time should give an instant, not a day") }
    }

    func testADateWithNoTimeStaysAllDay() {
        let c = parse("Renew the passport 2026-10-02")
        guard case .allDay? = c.due()?.value else { return XCTFail("no time means the whole day") }
    }

    func testTheListMarkPicksAKnownListAndFlagsANewOne() {
        let known = parse("Milk ~Groceries", lists: ["Groceries"])
        XCTAssertEqual(known.list, "Groceries")
        XCTAssertFalse(known.listIsNew)

        let fresh = parse("Milk ~Sundries", lists: ["Groceries"])
        XCTAssertEqual(fresh.list, "Sundries")
        XCTAssertTrue(fresh.listIsNew, "a list nobody has yet has to be announced as new")
    }

    func testNothingIsStrippedWhenStrippingWouldLeaveNothing() {
        // The whole line is a label. Consuming it would leave a task with no words at all.
        let c = parse("#groceries")
        XCTAssertEqual(c.title, "#groceries")
        XCTAssertTrue(c.labels.isEmpty, "the line was consumed into nothing")
    }

    /// A hash has to be on its own to be a tag.
    ///
    /// Capture is a *different grammar* from the file format, and deliberately: the file scans a
    /// line right to left and stops at the first word that is not a token, so `- [ ] Buy #2 pencils`
    /// keeps its `#2`. Capture scans with regexes, so a standalone `#2` is a label. What both agree
    /// on is that a hash inside a word is part of the word.
    func testAHashInsideAWordIsNotALabel() {
        let c = parse("issue C#100 is open")
        XCTAssertEqual(c.title, "issue C#100 is open")
        XCTAssertTrue(c.labels.isEmpty)
    }

    func testAStandaloneHashIsALabelEvenWhenItIsANumber() {
        let c = parse("Buy #2 pencils")
        XCTAssertEqual(c.labels, ["2"])
        XCTAssertEqual(c.title, "Buy pencils")
    }
}

// MARK: - the focus ledger's rules

final class FocusRuleTests: XCTestCase {

    func testAShortSessionIsNotWorthKeeping() {
        // A minute is the floor: below it, a session is a mis-tap rather than work.
        XCTAssertFalse(FocusOutcome.wouldBeKept(elapsed: 30, planned: 1500))
        XCTAssertTrue(FocusOutcome.wouldBeKept(elapsed: FocusOutcome.minKeptSecs, planned: 1500))
    }

    func testOnlyADiscardedSessionIsNotTime() {
        XCTAssertTrue(FocusOutcome.countsAsTime(FocusOutcome.ranOut))
        XCTAssertTrue(FocusOutcome.countsAsTime(FocusOutcome.stopped))
        XCTAssertTrue(FocusOutcome.countsAsTime(FocusOutcome.interrupted))
        XCTAssertFalse(FocusOutcome.countsAsTime(FocusOutcome.discarded))
    }

    func testAnOpenStopwatchMakesNoPromiseToKeep() {
        // With no planned length there is nothing to have kept or broken.
        XCTAssertFalse(FocusOutcome.keptItsPromise(FocusOutcome.stopped, planned: 0))
        XCTAssertTrue(FocusOutcome.keptItsPromise(FocusOutcome.ranOut, planned: 1500))
    }
}
