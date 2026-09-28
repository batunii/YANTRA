import XCTest
@testable import YantraCore

final class EventWordsTests: XCTestCase {
    private let en = Locale(identifier: "en_US_POSIX")
    private func at(_ d: Int, _ h: Int, _ m: Int = 0) -> LocalDateTime {
        LocalDateTime(date: LocalDate(year: 2026, month: 9, day: d), hour: h, minute: m)
    }

    func testASpanOnOneDayNeedsOnlyItsClockAtTheEnd() {
        XCTAssertEqual(EventWords.when(start: at(24, 10), end: at(24, 11, 30), allDay: false, sitting: false, locale: en),
                       "Thu 24 Sep · 10:00–11:30")
    }

    func testAMomentHasNoTail() {
        XCTAssertEqual(EventWords.when(start: at(24, 10), end: at(24, 10), allDay: false, sitting: false, locale: en),
                       "Thu 24 Sep · 10:00")
    }

    func testASpanAcrossDaysNamesTheEndDay() {
        XCTAssertEqual(EventWords.when(start: at(24, 22), end: at(25, 1), allDay: false, sitting: false, locale: en),
                       "Thu 24 Sep · 22:00 → Fri 25 Sep 01:00")
    }

    func testAllDayAndSittings() {
        XCTAssertEqual(EventWords.when(start: at(24, 0), end: at(25, 0), allDay: true, sitting: false, locale: en),
                       "Thu 24 Sep · all day")
        XCTAssertEqual(EventWords.when(start: at(24, 9), end: at(24, 10), allDay: false, sitting: true, locale: en),
                       "for Thu 24 Sep · 09:00–10:00")
        XCTAssertEqual(EventWords.when(start: nil, end: nil, allDay: false, sitting: false), "Sometime")
    }
}
