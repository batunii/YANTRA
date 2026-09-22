import XCTest
@testable import YantraCore

/// Placing a task on a day by carrying it there.
///
/// Dropping a task on two o'clock says "I will do this then", which is a claim about attention — a
/// **sitting**. A due date is a claim about a deadline. They are different sentences, which is why
/// the rail's tap still sets one and the carry sets the other.
final class DropToScheduleTests: XCTestCase {

    let hour: Double = 60   // a point per minute, so offsets read as minutes

    func testADropLandsOnTheQuarterHourItIsNearest() {
        // Snapped down, like every other time in the app. To the minute would be a precision nobody
        // wants to read back off a block.
        XCTAssertEqual(TimelineLayout.dropMinute(offset: 0, hourHeight: hour), 0)
        XCTAssertEqual(TimelineLayout.dropMinute(offset: 14, hourHeight: hour), 0)
        XCTAssertEqual(TimelineLayout.dropMinute(offset: 15, hourHeight: hour), 15)
        XCTAssertEqual(TimelineLayout.dropMinute(offset: 29, hourHeight: hour), 15)
        XCTAssertEqual(TimelineLayout.dropMinute(offset: 8 * 60 + 5, hourHeight: hour), 8 * 60)
        XCTAssertEqual(TimelineLayout.dropMinute(offset: 14 * 60 + 50, hourHeight: hour), 14 * 60 + 45)
    }

    func testTheHourHeightIsHonoured() {
        // Zoomed in, the same finger position is a different time — the offset is in points, and
        // the only thing that turns points into minutes is how tall an hour currently is.
        XCTAssertEqual(TimelineLayout.dropMinute(offset: 120, hourHeight: 120), 60)
        XCTAssertEqual(TimelineLayout.dropMinute(offset: 120, hourHeight: 30), 4 * 60)
    }

    /// A drop near the bottom gives a sitting that fits inside the day rather than one running off
    /// the end of it.
    func testADropAtTheFootOfTheDayStillFits() {
        let last = TimelineLayout.dropMinute(offset: 24 * 60, hourHeight: hour)
        XCTAssertEqual(last, TimelineLayout.minutesInDay - TimelineLayout.defaultSittingMinutes)
        XCTAssertLessThanOrEqual(last + TimelineLayout.defaultSittingMinutes, TimelineLayout.minutesInDay)
        // Far past the end is still the last slot, not an hour in the next day.
        XCTAssertEqual(TimelineLayout.dropMinute(offset: 99_999, hourHeight: hour), last)
    }

    func testADropAboveTheTopIsMidnightRatherThanNegative() {
        XCTAssertEqual(TimelineLayout.dropMinute(offset: -40, hourHeight: hour), 0)
    }

    func testAZeroHeightColumnDoesNotDivideByIt() {
        // It can be zero for a frame on the way in; answering midnight is better than a crash.
        XCTAssertEqual(TimelineLayout.dropMinute(offset: 100, hourHeight: 0), 0)
    }

    /// A sitting is an hour by default — a block you drag onto a day is a claim about attention, and
    /// an hour is the unit people think in.
    func testTheDefaultSittingIsAnHour() {
        XCTAssertEqual(TimelineLayout.defaultSittingMinutes, 60)
    }

    /// End to end on the format: the block written carries the task and no words of its own, and
    /// reads back as a sitting that the calendar titles with the task's name.
    func testAPlacedSittingIsWrittenAsTimeForTheTaskAndNothingElse() throws {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("drop-\(UUID())")
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = WorkspaceStore(root: dir, id: "")
        store.scaffold(name: "Drop", now: 1)
        store.writePage(PageDoc(id: "list", type: NodeType.list, parent: nil, title: "Work",
                                modifiedAt: Date(timeIntervalSince1970: 1), device: nil,
                                blocks: [.task(TaskRef(id: "t1", title: "Draft the deck"))]))

        let w = WorkspaceWriter(store: store, device: "phone")
        let day = LocalDate.today()
        let minute = TimelineLayout.dropMinute(offset: 14 * 60, hourHeight: 60)
        let start = LocalDateTime(date: day, hour: minute / 60, minute: minute % 60)
        let end = LocalDateTime(date: day, hour: (minute + 60) / 60, minute: (minute + 60) % 60)
        _ = try w.addEvent(to: "list", title: "", time: EventTime(start: start, end: end), forTaskId: "t1")

        let ix = WorkspaceIndex.read(store)
        let sitting = try XCTUnwrap(ix.nodes.values.first { $0.event?.forTaskId == "t1" },
                                    "no sitting was written")
        XCTAssertEqual(sitting.event?.title, "", "a sitting must carry no words of its own")
        XCTAssertEqual(sitting.event?.time.start.hour, 14)

        // And the calendar draws it as the task, not as "Event".
        let days = CalendarBucketer.bucket(nodes: Array(ix.nodes.values), from: day, toExclusive: day.adding(days: 1))
        let item = try XCTUnwrap(days[day]?.first { if case let .event(e) = $0 { return e.forTaskId == "t1" }; return false })
        XCTAssertEqual(item.title, "Draft the deck", "the placed block does not wear its task's words")
    }
}
