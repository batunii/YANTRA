import XCTest
@testable import YantraCore

/// How a day's items become blocks that do not sit on top of each other.
final class TimelineTests: XCTestCase {

    let day = LocalDate("2026-09-11")!

    func event(_ id: String, _ from: String, _ to: String, allDay: Bool = false) -> DayItem {
        .event(DayItem.EventItem(nodeId: id, title: id,
                                 start: LocalDateTime("2026-09-11T\(from)")!,
                                 end: LocalDateTime(to.contains("-") ? to : "2026-09-11T\(to)")!,
                                 allDay: allDay, location: nil, repeating: false, forTaskId: nil,
                                 tint: nil, workspaceTint: nil, sortKey: 0))
    }

    func layout(_ items: [DayItem]) -> TimelineDay { TimelineLayout.forDay(items, day: day) }

    func testOneBlockTakesTheWholeWidth() {
        let d = layout([event("a", "09:00", "10:00")])
        XCTAssertEqual(d.blocks.count, 1)
        XCTAssertEqual(d.blocks[0].columns, 1)
        XCTAssertEqual(d.blocks[0].startMinute, 540)
        XCTAssertEqual(d.blocks[0].endMinute, 600)
    }

    func testTwoAtOnceShareTheWidth() {
        let d = layout([event("a", "09:00", "10:00"), event("b", "09:30", "10:30")])
        XCTAssertEqual(d.blocks.map(\.columns), [2, 2])
        XCTAssertEqual(d.blocks.map(\.column).sorted(), [0, 1])
    }

    func testBackToBackBlocksDoNotShare() {
        // 09:00–10:00 then 10:00–11:00 touch but do not overlap, so each takes the full width.
        let d = layout([event("a", "09:00", "10:00"), event("b", "10:00", "11:00")])
        XCTAssertEqual(d.blocks.map(\.columns), [1, 1])
    }

    func testWidthIsDecidedPerClusterNotPerPair() {
        // a and c never touch, but b overlaps both, so all three are one cluster and share one
        // width. Deciding pairwise would give a and b a width of 2 and c a width of 1, so c would
        // jump from half the column to all of it as you scrolled past the middle item.
        let d = layout([event("a", "09:00", "10:00"), event("b", "09:30", "11:30"), event("c", "11:00", "12:00")])
        XCTAssertEqual(Set(d.blocks.map(\.columns)), [2], "one cluster, one width")
        // And the width is the fewest that fit: two things overlap at most, so c reuses a's column
        // rather than opening a third nobody needs.
        XCTAssertEqual(d.blocks.map(\.column), [0, 1, 0])
    }

    func testAllDayGoesToTheBar() {
        let d = layout([event("a", "00:00", "2026-09-12T00:00", allDay: true)])
        XCTAssertEqual(d.allDay.count, 1)
        XCTAssertTrue(d.blocks.isEmpty)
    }

    func testABlockThatBeganYesterdayHasNoHonestTopEdge() {
        let crossing = DayItem.event(DayItem.EventItem(
            nodeId: "x", title: "x", start: LocalDateTime("2026-09-10T22:00")!,
            end: LocalDateTime("2026-09-11T02:00")!, allDay: false, location: nil, repeating: false,
            forTaskId: nil, tint: nil, workspaceTint: nil, sortKey: 0))
        let d = layout([crossing])
        XCTAssertEqual(d.allDay.count, 1)
        XCTAssertTrue(d.blocks.isEmpty)
    }

    func testEndingAtMidnightIsThisDaysLastMinute() {
        let d = layout([event("a", "22:00", "2026-09-12T00:00")])
        XCTAssertEqual(d.blocks.count, 1)
        XCTAssertEqual(d.blocks[0].endMinute, TimelineLayout.minutesInDay)
    }

    func testAMomentStillGetsATouchableBlock() {
        let t = DayItem.task(DayItem.TaskItem(nodeId: "t", title: "t", at: LocalDateTime("2026-09-11T14:00")!,
                                              hasTime: true, done: false, durationMin: nil, sortKey: 0))
        let d = layout([t])
        XCTAssertEqual(d.blocks[0].endMinute - d.blocks[0].startMinute, TimelineLayout.minBlockMinutes)
    }

    func testUndatedTaskGoesToTheBar() {
        let t = DayItem.task(DayItem.TaskItem(nodeId: "t", title: "t", at: LocalDateTime("2026-09-11T00:00")!,
                                              hasTime: false, done: false, durationMin: nil, sortKey: 0))
        XCTAssertEqual(layout([t]).allDay.count, 1)
    }

    // MARK: the span on screen

    func testSevenDaysSnapsToMonday() {
        // 11 September 2026 is a Friday.
        let s = TimelineLayout.span(LocalDate("2026-09-11")!, 7)
        XCTAssertEqual(s.first?.description, "2026-09-07")
        XCTAssertEqual(s.count, 7)
    }

    func testThreeDaysDoesNotSnap() {
        // A window you push along, not a unit with a start.
        let s = TimelineLayout.span(LocalDate("2026-09-11")!, 3)
        XCTAssertEqual(s.map(\.description), ["2026-09-11", "2026-09-12", "2026-09-13"])
    }

    // MARK: the rail

    func testTodayBeatsSoonAndSoonBeatsEverything() {
        let today = LocalDate("2026-09-11")!
        // Due today with a deadline on Friday appears once, under Today.
        XCTAssertEqual(railBucket(due: today, deadline: today.adding(days: 2), today: today), .today)
        XCTAssertEqual(railBucket(due: nil, deadline: today.adding(days: 2), today: today), .soon)
        XCTAssertEqual(railBucket(due: nil, deadline: nil, today: today), .undated)
        XCTAssertEqual(railBucket(due: today.adding(days: 30), deadline: nil, today: today), .other)
        // Overdue is not today's work, whatever it feels like.
        XCTAssertEqual(railBucket(due: today.adding(days: -7), deadline: nil, today: today), .other)
        // A deadline further out than the window is not soon.
        XCTAssertEqual(railBucket(due: nil, deadline: today.adding(days: soonDays + 1), today: today), .other)
    }

    func testEmptyShelvesAreKeptSoTheTabsDoNotMove() {
        let shelves = railShelves([], today: LocalDate("2026-09-11")!)
        XCTAssertEqual(Set(shelves.keys), Set(RailBucket.allCases))
    }
}
