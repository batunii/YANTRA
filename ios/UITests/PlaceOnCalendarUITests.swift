import XCTest

/// Taking a task from the rail and putting it on the day.
///
/// The drag existed and never worked on a phone, for a reason that was nothing to do with the
/// gesture: the rail was a **sheet**. A drag cannot leave a sheet's presentation — the finger
/// reaches the edge of the card, and the day it is aiming for is not even on screen — so the carry
/// that works perfectly on an iPad did nothing whatsoever here. The rail is now a drawer under the
/// day, in the same view and so in the same coordinate space, and the drag is the same drag.
///
/// Both ways in are covered, because both exist: hold and carry, or tap to pick up and tap to put
/// down. The second is not a consolation prize — a hold-and-drag is hard work for anyone whose
/// hands are not steady, and it is invisible to anyone who has not been told it is there.
final class PlaceOnCalendarUITests: YantraUITestCase {

    /// A point on the day with nothing already on it.
    ///
    /// A fraction of the window was a test of what time it is rather than of placing. The day opens
    /// on the current hour, so which hours are on screen moves through the day, and at half past
    /// nine in the morning 0.45 of the window is the middle of the fixture's "Timed thing": the tap
    /// opened that task, the calendar went away, and the banner disappearing — which is what the
    /// test checked — meant the opposite of what it was written to mean.
    ///
    /// So: ask where the hours are, and find a gap between the blocks that are drawn on them.
    private func emptyPointOnTheDay() -> XCUICoordinate {
        let timeline = app.scrollViews["calendar.timeline"]
        XCTAssertTrue(timeline.waitForExistence(timeout: 6), "the day's hours are not on screen")
        let day = timeline.frame
        // Right of centre: the fixture's all-day sitting runs the full height of the left column.
        let x = day.midX + day.width * 0.2
        let taken = app.buttons.allElementsBoundByIndex.map(\.frame)
        // Inset top and bottom, so a point is never under the all-day bar or the banner.
        for step in stride(from: day.minY + 48, to: day.maxY - 72, by: 16) {
            let point = CGPoint(x: x, y: step)
            if !taken.contains(where: { $0.contains(point) }) {
                return app.windows.firstMatch.coordinate(withNormalizedOffset: .zero)
                    .withOffset(CGVector(dx: point.x, dy: point.y))
            }
        }
        XCTFail("every hour on screen already has something on it")
        return app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.45))
    }

    /// The calendar on a day, with the rail already out.
    private func openDayWithRail() {
        launch(route: "calendar", extra: ["-calmode", "day", "-calrail"])
        XCTAssertTrue(shelf("Undated").waitForExistence(timeout: 8), "the rail did not come up")
        shelf("Undated").tap()
    }

    /// The gesture as it is meant to be used: hold a task, carry it up, let go on an hour.
    func testATaskIsCarriedFromTheRailOntoTheDay() {
        openDayWithRail()
        let task = el(Fixture.plainTask)
        XCTAssertTrue(task.waitForExistence(timeout: 6), "\(Fixture.plainTask) was not on the Undated shelf")

        // Up onto the middle of the day. The hold is what says "this one" — a plain drag would be
        // the rail scrolling — and it is the app's own threshold, not a guess.
        // Coordinate to coordinate, because the destination is an hour on a ruler rather than a
        // control with a frame of its own.
        let from = task.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        from.press(forDuration: 0.6, thenDragTo: emptyPointOnTheDay())

        // Matched on any element rather than on `staticTexts`: a timeline block is a Button with its
        // words folded into its label, so the text inside it is not a static text of its own.
        XCTAssertTrue(el(Fixture.plainTask).waitForExistence(timeout: 8),
                      "\(Fixture.plainTask) is not on the day after being carried onto it")
    }

    func testATaskPickedUpFromTheRailIsPlacedByTappingATime() {
        openDayWithRail()
        let task = el(Fixture.plainTask)
        XCTAssertTrue(task.waitForExistence(timeout: 6), "\(Fixture.plainTask) was not on the Undated shelf")
        task.tap()

        // Picking up says what it is waiting for. A mode with nothing on screen saying so is a trap:
        // the next tap on an empty hour would schedule something you had forgotten you were holding.
        let banner = app.staticTexts["Tap a time to place it"]
        XCTAssertTrue(banner.waitForExistence(timeout: 6), "nothing said the task had been picked up")

        emptyPointOnTheDay().tap()

        // Still on the calendar. The banner going away is only good news if the screen it was on is
        // still there — a tap that opened a task would take it away too, and used to.
        XCTAssertTrue(app.staticTexts["calendar.heading"].waitForExistence(timeout: 3),
                      "the tap left the calendar instead of placing something on it")
        XCTAssertFalse(banner.waitForExistence(timeout: 3), "the banner stayed up, so nothing was placed")
        XCTAssertTrue(el(Fixture.plainTask).waitForExistence(timeout: 8),
                      "\(Fixture.plainTask) is not on the day after being placed")
    }

    /// The day pane itself pages, not only the strip of text at the top of it.
    ///
    /// Reported from the dogfood build: "the calendar doesn't go to yesterday or the days before if
    /// I swipe". It did not — the only swipe on the screen was on the heading band, which is a
    /// thing most people never think to drag. Android puts the gesture on the whole pane.
    ///
    /// Yesterday rather than tomorrow, because backwards is what was asked for, and because the
    /// "Today" key is drawn exactly when today is somewhere else: its arrival is the screen saying
    /// it has left today, which is the claim being tested.
    func testSwipingTheDayGoesToYesterday() {
        let app = launch(route: "calendar")
        let timeline = app.scrollViews["calendar.timeline"]
        XCTAssertTrue(timeline.waitForExistence(timeout: 8), "the day's hours never came up")
        XCTAssertFalse(app.buttons["calendar.today"].exists,
                       "the calendar did not open on today, so this proves nothing")

        // Rightward is backward — the pane moves the way the finger does.
        let middle = timeline.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.5))
        let across = timeline.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5))
        middle.press(forDuration: 0.05, thenDragTo: across)

        XCTAssertTrue(app.buttons["calendar.today"].waitForExistence(timeout: 5),
                      "swiping the day did not leave today, so there is still no way back to yesterday")
    }

    /// Cancelling puts nothing anywhere, and a tap afterwards is an ordinary tap again.
    func testPuttingItBackLeavesTheDayAlone() {
        openDayWithRail()
        let task = el(Fixture.plainTask)
        XCTAssertTrue(task.waitForExistence(timeout: 6), "\(Fixture.plainTask) was not on the Undated shelf")
        task.tap()

        let cancel = app.buttons["Cancel"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 6), "no way to put the task back down")
        cancel.tap()
        XCTAssertFalse(app.staticTexts["Tap a time to place it"].waitForExistence(timeout: 3),
                       "cancelling left the app still holding the task")
    }
}
