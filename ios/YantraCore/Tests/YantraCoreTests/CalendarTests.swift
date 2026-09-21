import XCTest
@testable import YantraCore

/// The bucketer's arithmetic, which is where the off-by-one lives.
///
/// A multi-day event has to appear on every day it covers, an all-day event's exclusive end must
/// not add a phantom day, and a task due at midnight belongs to that day rather than the one
/// before. None of that needs a screen to be checked, and all of it is easy to get wrong once and
/// never notice.
final class CalendarTests: XCTestCase {

    let zone = TimeZone(identifier: "Europe/Dublin")!
    let from = LocalDate("2026-09-01")!
    let to = LocalDate("2026-10-01")!

    func day(_ s: String) -> LocalDate { LocalDate(s)! }
    func at(_ s: String) -> LocalDateTime { LocalDateTime(s)! }

    /// An event node, as the index would build one from a `@ ` line.
    func eventNode(_ id: String, _ title: String, _ when: String, workspace: String = "w1",
                   forTask: String? = nil, color: String? = nil, rrule: String? = nil,
                   cancelled: Bool = false, external: ExternalRef? = nil) -> Node {
        guard case let .event(e) = PageCodec.decodeBlock("@ \(when) \(title) ^\(id)") else {
            fatalError("not an event line: @ \(when) \(title)")
        }
        var ev = e
        ev.forTaskId = forTask; ev.color = color; ev.rrule = rrule; ev.cancelled = cancelled; ev.external = external
        return Node(id: id, workspaceId: workspace, parentId: "p1", type: NodeType.event, title: title, rank: "i",
                    done: false, inProgress: false, indent: 0, systemKey: nil, createdAt: 0, event: ev)
    }

    func taskNode(_ id: String, _ title: String, due: DueSpec?, done: Bool = false,
                  external: ExternalRef? = nil, workspace: String = "w1") -> Node {
        Node(id: id, workspaceId: workspace, parentId: "p1", type: NodeType.task, title: title, rank: "i",
             done: done, inProgress: false, indent: 0, systemKey: nil, createdAt: 0, due: due, external: external)
    }

    func run(_ nodes: [Node], device: [DeviceEvent] = [], workspaceTints: [String: String] = [:],
             listTints: [String: String] = [:]) -> CalendarDays {
        CalendarBucketer.bucket(nodes: nodes, device: device, workspaceTints: workspaceTints,
                                listTints: listTints, from: from, toExclusive: to, zone: zone)
    }

    // MARK: spans

    func testOneDayAllDayEventCoversOneDay() {
        let days = run([eventNode("e1", "Birthday", "2026-09-11")])
        XCTAssertEqual(days[day("2026-09-11")]?.count, 1)
        // The exclusive end is 00:00 on the 12th and must not add a phantom day.
        XCTAssertNil(days[day("2026-09-12")])
    }

    func testMultiDayAllDayEventCoversEveryDayInclusive() {
        let days = run([eventNode("e1", "Conference", "2026-09-11/2026-09-13")])
        for d in ["2026-09-11", "2026-09-12", "2026-09-13"] {
            XCTAssertEqual(days[day(d)]?.count, 1, "missing \(d)")
        }
        XCTAssertNil(days[day("2026-09-14")])
        XCTAssertNil(days[day("2026-09-10")])
    }

    func testMeetingEndingAtMidnightBelongsToTheDayItStarted() {
        let days = run([eventNode("e1", "Late", "2026-09-11T22:00/PT2H")])
        XCTAssertEqual(days[day("2026-09-11")]?.count, 1)
        XCTAssertNil(days[day("2026-09-12")])
    }

    func testMeetingCrossingMidnightCoversBothDays() {
        let days = run([eventNode("e1", "Longer", "2026-09-11T22:00/PT3H")])
        XCTAssertEqual(days[day("2026-09-11")]?.count, 1)
        XCTAssertEqual(days[day("2026-09-12")]?.count, 1)
    }

    func testEventsOutsideTheWindowAreDropped() {
        let days = run([eventNode("e1", "Before", "2026-08-30"), eventNode("e2", "After", "2026-10-05")])
        XCTAssertTrue(days.isEmpty)
    }

    func testCancelledOccurrenceIsAnAbsence() {
        let days = run([eventNode("e1", "Skipped", "2026-09-11T14:00/PT1H", cancelled: true)])
        XCTAssertTrue(days.isEmpty)
    }

    // MARK: tasks

    func testTaskDueAtMidnightBelongsToThatDay() {
        let midnight = at("2026-09-11T00:00").instant(in: zone)
        let days = run([taskNode("t1", "Thing", due: DueSpec(.at(midnight)))])
        XCTAssertEqual(days[day("2026-09-11")]?.count, 1)
        XCTAssertNil(days[day("2026-09-10")])
    }

    func testAllDayTaskSortsAfterEverythingTimed() {
        let nodes = [
            taskNode("t1", "Whenever", due: DueSpec(.allDay(day("2026-09-11")))),
            taskNode("t2", "At three", due: DueSpec(.at(at("2026-09-11T15:00").instant(in: zone)))),
        ]
        let items = run(nodes)[day("2026-09-11")] ?? []
        XCTAssertEqual(items.map(\.nodeId), ["t2", "t1"])
    }

    func testAllDayEventSortsBeforeEverythingTimed() {
        let nodes = [
            eventNode("e1", "Standup", "2026-09-11T09:00/PT15M"),
            eventNode("e2", "Birthday", "2026-09-11"),
        ]
        let items = run(nodes)[day("2026-09-11")] ?? []
        XCTAssertEqual(items.map(\.nodeId), ["e2", "e1"])
    }

    func testTaskDurationBecomesMinutes() {
        var due = DueSpec(.at(at("2026-09-11T14:00").instant(in: zone)))
        due.duration = .minutes(90)
        guard case let .task(t)? = run([taskNode("t1", "Blocked out", due: due)])[day("2026-09-11")]?.first else {
            return XCTFail("no task item")
        }
        XCTAssertEqual(t.durationMin, 90)
    }

    // MARK: colour

    func testSittingBorrowsItsTaskListColourOnlyWhenItsOwnLineSaysNothing() {
        let own = run([eventNode("e1", "Sitting", "2026-09-11T14:00/PT1H", forTask: "t1", color: "teal")],
                      listTints: ["t1": "coral"])
        guard case let .event(e)? = own[day("2026-09-11")]?.first else { return XCTFail("no event") }
        XCTAssertEqual(e.tint, "teal")

        let borrowed = run([eventNode("e2", "Sitting", "2026-09-11T14:00/PT1H", forTask: "t1")],
                           listTints: ["t1": "coral"])
        guard case let .event(b)? = borrowed[day("2026-09-11")]?.first else { return XCTFail("no event") }
        XCTAssertEqual(b.tint, "coral")

        // An appointment has no list to borrow from and stays uncoloured, which is what the accent means.
        let plain = run([eventNode("e3", "Appointment", "2026-09-11T14:00/PT1H")], listTints: ["t1": "coral"])
        guard case let .event(p)? = plain[day("2026-09-11")]?.first else { return XCTFail("no event") }
        XCTAssertNil(p.tint)
    }

    func testWorkspaceTintIsTheSpineAndNotTheBlocksColour() {
        let days = run([eventNode("e1", "Appointment", "2026-09-11T14:00/PT1H")], workspaceTints: ["w1": "indigo"])
        guard case let .event(e)? = days[day("2026-09-11")]?.first else { return XCTFail("no event") }
        XCTAssertEqual(e.workspaceTint, "indigo")
        XCTAssertNil(e.tint, "the repository is the spine, never the block's own colour")
    }

    // MARK: notes about somebody else's meetings

    func device(_ instance: String, uid: String?, _ start: String, _ end: String, title: String = "Standup",
                allDay: Bool = false) -> DeviceEvent {
        DeviceEvent(instanceId: instance, uid: uid, eventId: "ev-\(instance)", title: title,
                    beginUtc: at(start).instant(in: zone), endUtc: at(end).instant(in: zone), allDay: allDay)
    }

    func testAnnotatedLineStandsAsideSoOneMeetingIsOneBlock() {
        let note = taskNode("t1", "Prep the standup", due: DueSpec(.at(at("2026-09-11T09:00").instant(in: zone))),
                            external: ExternalRef("uid-1"))
        let days = run([note], device: [device("i1", uid: "uid-1", "2026-09-11T09:00", "2026-09-11T09:15")])
        let items = days[day("2026-09-11")] ?? []
        XCTAssertEqual(items.count, 1, "the note's own line must not draw beside the meeting")
        guard case let .device(d) = items[0] else { return XCTFail("expected the meeting's block") }
        XCTAssertEqual(d.taskId, "t1")
        XCTAssertEqual(d.taskTitle, "Prep the standup", "a renamed note says its own name")
    }

    func testANoteWhoseMeetingCannotBeReadStillDraws() {
        let note = taskNode("t1", "Prep", due: DueSpec(.at(at("2026-09-11T09:00").instant(in: zone))),
                            external: ExternalRef("uid-1"))
        let days = run([note], device: [])     // permission off, or the meeting is gone
        XCTAssertEqual(days[day("2026-09-11")]?.count, 1)
        guard case .task = (days[day("2026-09-11")] ?? [])[0] else { return XCTFail("expected the note's own line") }
    }

    func testALineClaimsTheNearestOccurrenceAndLeavesTheRestAlone() {
        // A weekly standup: one uid, four meetings. One line, written about the 16th.
        let theirs = (0..<4).map { i -> DeviceEvent in
            let d = LocalDate("2026-09-09")!.adding(days: i * 7)
            return device("i\(i)", uid: "uid-1", "\(d)T09:00", "\(d)T09:15")
        }
        let note = taskNode("t1", "About the sixteenth",
                            due: DueSpec(.at(at("2026-09-16T09:00").instant(in: zone))),
                            external: ExternalRef("uid-1"))
        let days = run([note], device: theirs)
        // Every Wednesday draws exactly one block, and only the 16th's carries the note.
        for (i, d) in ["2026-09-09", "2026-09-16", "2026-09-23", "2026-09-30"].enumerated() {
            let items = days[day(d)] ?? []
            XCTAssertEqual(items.count, 1, "\(d)")
            guard case let .device(dev) = items[0] else { return XCTFail("\(d) is not a meeting") }
            XCTAssertEqual(dev.taskId, i == 1 ? "t1" : nil, "the note belongs to the 16th only, not \(d)")
            XCTAssertTrue(dev.repeating, "four occurrences of one uid is a rule the provider expanded")
        }
    }

    func testAMeetingThatMovedKeepsThePageWrittenForIt() {
        // The line remembers 09:00; the meeting is now at 11:00 the same day. Nearest still wins.
        let note = taskNode("t1", "Prep", due: DueSpec(.at(at("2026-09-11T09:00").instant(in: zone))),
                            external: ExternalRef("uid-1"))
        let days = run([note], device: [device("i1", uid: "uid-1", "2026-09-11T11:00", "2026-09-11T12:00")])
        let items = days[day("2026-09-11")] ?? []
        XCTAssertEqual(items.count, 1)
        guard case let .device(d) = items[0] else { return XCTFail("expected the meeting") }
        XCTAssertEqual(d.taskId, "t1")
        XCTAssertEqual(d.start, at("2026-09-11T11:00"), "the times drawn are theirs, not the line's")
    }

    func testTwoLinesWantingOneInstanceGiveItToTheNearer() {
        let near = taskNode("t1", "Nearer", due: DueSpec(.at(at("2026-09-11T09:30").instant(in: zone))),
                            external: ExternalRef("uid-1"))
        let far = taskNode("t2", "Further", due: DueSpec(.at(at("2026-09-11T15:00").instant(in: zone))),
                           external: ExternalRef("uid-1"))
        let days = run([near, far], device: [device("i1", uid: "uid-1", "2026-09-11T09:00", "2026-09-11T09:15")])
        let items = days[day("2026-09-11")] ?? []
        // The meeting, carrying the nearer line; and the further line falling back to its own time.
        XCTAssertEqual(items.count, 2)
        guard case let .device(d) = items.first(where: { if case .device = $0 { return true }; return false })! else { return XCTFail() }
        XCTAssertEqual(d.taskId, "t1")
        XCTAssertTrue(items.contains { $0.nodeId == "t2" }, "the loser draws its own remembered time")
    }

    func testAllDayDeviceEventsAreReadInUTC() {
        // The provider stores an all-day event as UTC midnight to UTC midnight. Resolving it locally
        // would put a birthday on the wrong day either side of the meridian.
        let utc = TimeZone(identifier: "UTC")!
        let d = DeviceEvent(instanceId: "i1", uid: nil, eventId: "e", title: "Birthday",
                            beginUtc: LocalDateTime("2026-09-11T00:00")!.instant(in: utc),
                            endUtc: LocalDateTime("2026-09-12T00:00")!.instant(in: utc), allDay: true)
        for name in ["Asia/Tokyo", "America/New_York", "Europe/Dublin"] {
            let z = TimeZone(identifier: name)!
            let days = CalendarBucketer.bucket(nodes: [], device: [d], from: from, toExclusive: to, zone: z)
            XCTAssertEqual(days.keys.map(\.description), ["2026-09-11"], "in \(name)")
        }
    }

    // MARK: the month grid

    func testMonthGridStartsOnTheMondayOnOrBeforeTheFirst() {
        // 1 September 2026 is a Tuesday, so the grid opens on 31 August.
        let g = monthGrid(day("2026-09-01"))
        XCTAssertEqual(g.count, 42)
        XCTAssertEqual(g.first?.description, "2026-08-31")
        XCTAssertEqual(g.last?.description, "2026-10-11")
    }

    func testMonthGridIsANoOpWhenTheFirstIsAMonday() {
        // 1 June 2026 is a Monday.
        XCTAssertEqual(monthGrid(day("2026-06-01")).first?.description, "2026-06-01")
    }
}

/// What one render of the calendar widget is given.
final class CalendarWidgetTests: XCTestCase {

    let zone = TimeZone(identifier: "Europe/Dublin")!
    let today = LocalDate("2026-09-11")!

    func index(_ nodes: [Node]) -> WorkspaceIndex {
        var ix = WorkspaceIndex()
        for n in nodes { ix.nodes[n.id] = n }
        return ix
    }

    func eventNode(_ id: String, _ title: String, _ when: String) -> Node {
        guard case let .event(e) = PageCodec.decodeBlock("@ \(when) \(title) ^\(id)") else { fatalError() }
        return Node(id: id, workspaceId: "w1", parentId: "p1", type: NodeType.event, title: title, rank: "i",
                    done: false, inProgress: false, indent: 0, systemKey: nil, createdAt: 0, event: e)
    }

    func build(_ shape: CalendarWidgetShape, _ nodes: [Node], rowLimit: Int = 4) -> CalendarWidgetData {
        CalendarWidgetBuilder.build(shape: shape, anchor: today, index: index(nodes), rowLimit: rowLimit,
                                    today: today, locale: Locale(identifier: "en_GB"), zone: zone)
    }

    func testDayShowsOneColumnAndThreeDayShowsThree() {
        XCTAssertEqual(build(.day, []).columns.count, 1)
        XCTAssertEqual(build(.threeDay, []).columns.count, 3)
        // The month carries the grid and the selected day beneath it.
        let m = build(.month, [])
        XCTAssertEqual(m.cells.count, 42)
        XCTAssertEqual(m.columns.count, 1)
    }

    func testRowsBeyondTheLimitAreCountedNotDropped() {
        let nodes = (0..<7).map { eventNode("e\($0)", "Thing\($0)", "2026-09-11T\(String(format: "%02d", 9 + $0)):00/PT30M") }
        let col = build(.day, nodes, rowLimit: 4).columns[0]
        XCTAssertEqual(col.rows.count, 4)
        XCTAssertEqual(col.more, 3, "a column says +3 rather than quietly showing four of seven")
    }

    func testAnEmptyDaySaysWhenTheQuietEnds() {
        // Nothing today; something on the 15th. The useful line is not "nothing on".
        let d = build(.day, [eventNode("e1", "Dentist", "2026-09-15T10:00/PT1H")])
        XCTAssertTrue(d.columns[0].rows.isEmpty)
        XCTAssertEqual(d.nextUp?.date, LocalDate("2026-09-15"))
        XCTAssertEqual(d.nextUp?.title, "Dentist")
    }

    func testNextUpIsOnlyOfferedWhenThereIsNothingOnScreen() {
        let d = build(.day, [eventNode("e1", "Standup", "2026-09-11T09:00/PT30M"),
                             eventNode("e2", "Dentist", "2026-09-15T10:00/PT1H")])
        XCTAssertFalse(d.columns[0].rows.isEmpty)
        XCTAssertNil(d.nextUp, "a day with something on it has no line to spare")
    }

    func testFinishedTasksSortLastRatherThanVanish() {
        let done = Node(id: "t1", workspaceId: "w1", parentId: "p", type: NodeType.task, title: "Done thing",
                        rank: "i", done: true, inProgress: false, indent: 0, systemKey: nil, createdAt: 0,
                        due: DueSpec(.allDay(today)))
        let open = Node(id: "t2", workspaceId: "w1", parentId: "p", type: NodeType.task, title: "Open thing",
                        rank: "j", done: false, inProgress: false, indent: 0, systemKey: nil, createdAt: 0,
                        due: DueSpec(.allDay(today)))
        let rows = build(.day, [done, open]).columns[0].rows
        XCTAssertEqual(rows.map(\.nodeId), ["t2", "t1"])
        XCTAssertTrue(rows[1].done)
    }

    func testTimesArePrintedAsAListingNotAsAClock() {
        let gb = Locale(identifier: "en_GB"), us = Locale(identifier: "en_US")
        let nine = LocalDateTime("2026-09-11T09:30")!, two = LocalDateTime("2026-09-11T14:00")!
        // No leading zero: the gutter already carries the column.
        XCTAssertEqual(CalendarWidgetBuilder.listingTime(nine, locale: gb), "9:30")
        XCTAssertEqual(CalendarWidgetBuilder.listingTime(two, locale: gb), "14:00")
        // One character for the half-day, not three.
        XCTAssertEqual(CalendarWidgetBuilder.listingTime(nine, locale: us), "9:30a")
        XCTAssertEqual(CalendarWidgetBuilder.listingTime(two, locale: us), "2:00p")
        XCTAssertEqual(CalendarWidgetBuilder.listingTime(LocalDateTime("2026-09-11T00:15")!, locale: us), "12:15a")
    }

    func testAMeetingWithNoNoteCarriesNoNodeToOpen() {
        let d = DeviceEvent(instanceId: "i1", uid: "u1", eventId: "e", title: "Theirs",
                            beginUtc: LocalDateTime("2026-09-11T09:00")!.instant(in: zone),
                            endUtc: LocalDateTime("2026-09-11T10:00")!.instant(in: zone), allDay: false)
        let data = CalendarWidgetBuilder.build(shape: .day, anchor: today, index: index([]), device: [d],
                                               rowLimit: 4, today: today, locale: Locale(identifier: "en_GB"), zone: zone)
        let row = data.columns[0].rows[0]
        XCTAssertEqual(row.title, "Theirs")
        XCTAssertNil(row.nodeId, "we can show their meeting and we cannot open it")
    }

    func testPagingStepsByWhatYouAreLookingAt() {
        XCTAssertEqual(CalendarWidgetShape.day.step(from: today, forward: 1).description, "2026-09-12")
        XCTAssertEqual(CalendarWidgetShape.threeDay.step(from: today, forward: 1).description, "2026-09-14")
        XCTAssertEqual(CalendarWidgetShape.month.step(from: today, forward: 1).description, "2026-10-01")
        XCTAssertEqual(CalendarWidgetShape.month.step(from: today, forward: -1).description, "2026-08-31")
    }
}
