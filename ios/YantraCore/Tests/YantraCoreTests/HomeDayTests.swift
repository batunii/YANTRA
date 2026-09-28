import XCTest
@testable import YantraCore

/// Home's byline and its Next row — `HomeViewModel.bookedMinutes` / `nextToday` at 17d0beb.
final class HomeDayTests: XCTestCase {
    let zone = TimeZone(identifier: "Europe/Dublin")!
    let today = LocalDate(year: 2026, month: 9, day: 29)

    func item(_ id: String, _ h0: Int, _ m0: Int, _ h1: Int, _ m1: Int, day: LocalDate? = nil, endDay: LocalDate? = nil,
              allDay: Bool = false) -> DayItem.EventItem {
        let d = day ?? today
        return .init(nodeId: id, title: id, start: LocalDateTime(date: d, hour: h0, minute: m0),
                     end: LocalDateTime(date: endDay ?? d, hour: h1, minute: m1), allDay: allDay, location: nil,
                     repeating: false, forTaskId: nil, forTitle: nil, tint: nil, workspaceTint: nil, sortKey: 0)
    }
    func at(_ h: Int, _ m: Int) -> Date { LocalDateTime(date: today, hour: h, minute: m).instant(in: zone) }

    func testBookedIsTimedEventsOnlyAndClippedToToday() {
        let events = [
            item("standup", 9, 0, 9, 30),
            item("review", 14, 0, 15, 20),
            item("passport", 0, 0, 0, 0, endDay: today.adding(days: 1), allDay: true),
            // Started last night: only the part that is today counts.
            item("overnight", 23, 0, 1, 0, day: today.adding(days: -1), endDay: today),
            // Runs past midnight tonight.
            item("late", 23, 30, 0, 45, endDay: today.adding(days: 1)),
        ]
        XCTAssertEqual(HomeDay.bookedMinutes(events, today: today, zone: zone), 30 + 80 + 60 + 30)
    }

    func testBookedWords() {
        XCTAssertEqual(HomeDay.bookedWords(45), "45m")
        XCTAssertEqual(HomeDay.bookedWords(140), "2h 20m")
        XCTAssertEqual(HomeDay.bookedWords(180), "3h")
    }

    func testNextIsTheEarliestNotYetEndedAndNeverAllDay() {
        let events = [item("standup", 9, 0, 9, 30), item("review", 14, 0, 15, 0), item("lunch", 12, 0, 13, 0),
                      item("holiday", 0, 0, 0, 0, endDay: today.adding(days: 1), allDay: true)]
        XCTAssertEqual(HomeDay.next(events, now: at(8, 0), zone: zone)?.nodeId, "standup")
        // One already running is still the thing you are next expected at.
        XCTAssertEqual(HomeDay.next(events, now: at(9, 10), zone: zone)?.nodeId, "standup")
        XCTAssertEqual(HomeDay.next(events, now: at(9, 30), zone: zone)?.nodeId, "lunch")
        XCTAssertNil(HomeDay.next(events, now: at(15, 0), zone: zone), "evenings are quiet")
    }

    func testTheCaptionSaysHowLongOnlyInsideTheWindow() {
        let e = item("review", 14, 0, 15, 0)
        XCTAssertEqual(HomeDay.nextCaption(e, now: at(13, 0), zone: zone), "14:00–15:00")
        XCTAssertEqual(HomeDay.nextCaption(e, now: at(13, 48), zone: zone), "14:00–15:00  ·  in 12m")
        XCTAssertEqual(HomeDay.nextCaption(e, now: at(13, 30), zone: zone), "14:00–15:00  ·  in 30m")
        XCTAssertEqual(HomeDay.nextCaption(e, now: at(14, 5), zone: zone), "14:00–15:00  ·  now")
    }
}
