import XCTest
@testable import YantraCore

/// Where a day's ruler opens. It opened at seven whatever the time was, so a calendar checked in the
/// afternoon began with nine hours of morning and the answer had scrolled off the bottom.
final class OpeningHourTests: XCTestCase {

    let today = LocalDate.today()
    func at(_ h: Int, _ m: Int = 0, on day: LocalDate? = nil) -> LocalDateTime {
        LocalDateTime(date: day ?? today, hour: h, minute: m)
    }

    func testTodayOpensAnHourBeforeNow() {
        // The thing you are most likely looking for is what you are twenty minutes into, not what
        // is next — so the line does not land at the very top.
        XCTAssertEqual(OpeningHour.forDays([today], now: at(16)), 15, accuracy: 0.001)
        XCTAssertEqual(OpeningHour.forDays([today], now: at(9)), 8, accuracy: 0.001)
    }

    func testTheFractionIsKeptSoTheLineLandsInTheSamePlace() {
        // Half past two opens at half past one, not at one.
        XCTAssertEqual(OpeningHour.forDays([today], now: at(14, 30)), 13.5, accuracy: 0.001)
        XCTAssertEqual(OpeningHour.forDays([today], now: at(14, 45)), 13.75, accuracy: 0.001)
    }

    func testAnotherDayStillOpensAtSeven() {
        // It has no "now" to show, and the old guess was always right for that case.
        let other = today.adding(days: 3)
        XCTAssertEqual(OpeningHour.forDays([other], now: at(16)), 7, accuracy: 0.001)
    }

    /// Three days share one scroll, so the question is whether *any* of them is today — asking only
    /// about the first would open at seven for two days out of three, including the one where now is.
    func testAnyOfTheDaysOnScreenBeingTodayCounts() {
        let days = [today.adding(days: -1), today, today.adding(days: 1)]
        XCTAssertEqual(OpeningHour.forDays(days, now: at(16)), 15, accuracy: 0.001)
        let without = [today.adding(days: 5), today.adding(days: 6)]
        XCTAssertEqual(OpeningHour.forDays(without, now: at(16)), 7, accuracy: 0.001)
    }

    func testTheSmallHoursStillShowMidnight() {
        // Half past midnight has no hour before it to lead with, and scrolling to -1 is nowhere.
        XCTAssertEqual(OpeningHour.forDays([today], now: at(0, 30)), 0, accuracy: 0.001)
        XCTAssertEqual(OpeningHour.forDays([today], now: at(1)), 0, accuracy: 0.001)
    }

    func testAnEmptyWindowFallsBackRatherThanCrashing() {
        XCTAssertEqual(OpeningHour.forDays([], now: at(16)), 7, accuracy: 0.001)
    }
}
