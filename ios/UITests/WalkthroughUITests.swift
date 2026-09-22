import XCTest

/// Using the app the way a person does, and checking the controls actually do something.
///
/// Written after a report that "so many buttons do not work". The cause found was not a missing
/// action anywhere — it was two `.sheet` modifiers on one view. SwiftUI honours one and drops the
/// rest silently, so a perfectly wired control opened nothing. These walk the controls a person
/// meets in the first minute and assert that each one *arrives* somewhere.
final class WalkthroughUITests: YantraUITestCase {

    /// Opens the task page of a fixture task by name.
    private func openTaskPage(_ name: String) {
        launch(route: "open:\(Fixture.listId)")
        let task = shelf(name)
        assertExists(task, "the list did not come up")
        task.tap()
    }

    /// The due pill opens the due sheet.
    func testTheDuePillOpensTheDueSheet() {
        openTaskPage(Fixture.overdueTask)   // this one has a due date, so the set pill is drawn
        assertExists(el("task.due"), "the task page has no due pill")
        el("task.due").tap()
        assertExists(app.staticTexts["Reminders"], "the due pill opened nothing")
    }

    /// And the label pill opens the label picker.
    ///
    /// A separate launch rather than a second tap in the same test: these are the two sheets that
    /// were fighting, and the point is that **each** opens from a clean screen. Dismissing one to
    /// reach the other also leaves the view underneath unhittable for a moment, which fails for a
    /// reason that has nothing to do with what is being asked.
    func testTheLabelPillOpensTheLabelPicker() {
        openTaskPage(Fixture.overdueTask)
        let label = shelf("+ Label")
        assertExists(label, "the task page has no label pill")
        label.tap()
        XCTAssertTrue(app.staticTexts["Labels"].waitForExistence(timeout: 5)
                      || app.textFields.firstMatch.waitForExistence(timeout: 5),
                      "the label pill opened nothing — a second sheet on one view is dropped")
    }

    /// Home's create key opens its sheet.
    ///
    /// The row's context menu is the *other* sheet on this screen, and `ListLookUITests` already
    /// drives it end to end — asserting it twice here only adds a second thing to keep working.
    func testHomesCreateKeyOpens() {
        launch(route: "home")
        tap("tab.create", "the create key did nothing")
        XCTAssertTrue(app.textFields.firstMatch.waitForExistence(timeout: 5),
                      "the create sheet did not come up")
    }

    /// The three keys along the bottom of Home each have somewhere to go.
    func testEveryKeyOnHomeArrivesSomewhere() {
        launch(route: "home")
        tap("home.calendar", "the calendar key did nothing")
        assertExists(el("calendar.heading"), "the calendar key went nowhere")

        launch(route: "home")
        tap("tab.stats", "the stats key did nothing")
        assertExists(app.staticTexts["Focus stats"], "the stats key went nowhere")
    }
}

/// The focus timer, driven the way a person does: pick a task, start a clock, watch it move, pause
/// it, and finish.
final class TimerUITests: YantraUITestCase {

    /// A clock that does not advance is not a timer. This reads it twice, a few seconds apart, and
    /// fails if the number is the same — which is the one thing a screenshot cannot tell you.
    func testTheClockActuallyRuns() {
        let app = launch(route: "start:\(Fixture.plainTaskId):1500")
        app.tap()   // let the interruption monitor fire before anything is asked

        // The reading is a mono clock somewhere on the screen: mm:ss, or h:mm:ss past an hour.
        let clock = app.staticTexts.matching(NSPredicate(format: "label MATCHES %@", "\\d{1,2}:\\d{2}(:\\d{2})?")).firstMatch
        XCTAssertTrue(clock.waitForExistence(timeout: 10), "the focus screen shows no clock")
        let first = clock.label

        // Long enough that a seconds-resolution clock must have moved.
        let moved = NSPredicate(format: "label != %@", first)
        expectation(for: moved, evaluatedWith: clock)
        waitForExpectations(timeout: 12) { error in
            XCTAssertNil(error, "the clock read \(first) and never changed — the timer is not running")
        }
    }

    /// Pause stops it, and resume starts it again. A pause that keeps counting is worse than none.
    func testPauseHoldsTheClockAndResumeReleasesIt() {
        let app = launch(route: "start:\(Fixture.plainTaskId):1500")
        app.tap()
        let clock = app.staticTexts.matching(NSPredicate(format: "label MATCHES %@", "\\d{1,2}:\\d{2}(:\\d{2})?")).firstMatch
        XCTAssertTrue(clock.waitForExistence(timeout: 10), "the focus screen shows no clock")

        assertExists(el("focus.playpause"), "the session has no pause control")
        el("focus.playpause").tap()
        let held = clock.label
        // Nothing should move while it is held.
        Thread.sleep(forTimeInterval: 3)
        XCTAssertEqual(clock.label, held, "the clock kept running while paused")

        el("focus.playpause").tap()
        let running = NSPredicate(format: "label != %@", held)
        expectation(for: running, evaluatedWith: clock)
        waitForExpectations(timeout: 12) { error in
            XCTAssertNil(error, "the clock never restarted after resuming")
        }
    }

    /// Finishing writes the session and leaves the screen, rather than leaving a clock nobody can stop.
    func testFinishingEndsTheSession() {
        let app = launch(route: "start:\(Fixture.plainTaskId):1500")
        app.tap()
        assertExists(shelf("Finish"), "a running session offers no way to finish")
        shelf("Finish").tap()
        // Either it asks first (a short session) or it ends; both end with no clock running.
        if app.buttons["End anyway"].waitForExistence(timeout: 3) { app.buttons["End anyway"].tap() }
        let empty = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS[c] 'Nothing in focus'")).firstMatch
        XCTAssertTrue(empty.waitForExistence(timeout: 8) || app.staticTexts["Start another"].exists,
                      "the session did not end")
    }
}
