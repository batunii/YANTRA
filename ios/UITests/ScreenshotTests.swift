import XCTest

/// Screenshots for the website, taken by driving the real app.
///
/// Not part of the ordinary suite — it asserts almost nothing and exists to produce pictures — so it
/// is skipped unless `-shots` is passed. Run it with:
///
///     xcodebuild … -only-testing:YantraUITests/ScreenshotTests SHOTS=1
///
/// Why a UI test rather than `simctl io screenshot` against a launch argument: a route gets you to a
/// screen, not to a screen worth looking at. The day opens on the current hour, so a run after
/// midnight photographs eight empty hours; the rail is closed until something opens it; a focus
/// session has to have been started to be running. Each of these drives the screen to the state a
/// person would actually be looking at, using the same helpers the real tests use.
///
/// The PNGs are written into the *runner's* Documents directory, which is the one place both the
/// simulator and the host can reach:
///
///     xcrun simctl get_app_container <sim> ie.shoonya.yantra.uitests.xctrunner data
final class ScreenshotTests: YantraUITestCase {

    override func setUpWithError() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["SHOTS"] == "1",
                          "screenshots are taken deliberately, not on every run")
        try super.setUpWithError()
    }

    private func keep(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? shot.pngRepresentation.write(to: dir.appendingPathComponent("\(name).png"))
    }

    /// Home, with a list part-finished and the now-playing bar along the bottom.
    func testShotHome() {
        _ = launch(route: "home", extra: ["-demo"])
        XCTAssertTrue(app.staticTexts["home.greeting"].waitForExistence(timeout: 8))
        keep("home")
    }

    /// A list: tasks with dates, a label, a subtask, one finished.
    func testShotList() {
        _ = launch(route: "open:demo-writing", extra: ["-demo"])
        XCTAssertTrue(el("task.row.Sketch the empty states").waitForExistence(timeout: 8))
        keep("list")
    }

    /// The day, scrolled off midnight and with the rail of undated tasks open beside it.
    ///
    /// Both of those are the difference between a picture of a calendar and a picture of a calendar
    /// being used: the hours worth seeing are the working ones, and the rail is where the tasks you
    /// have not placed yet are waiting.
    func testShotCalendar() {
        _ = launch(route: "calendar", extra: ["-demo"])
        let timeline = app.scrollViews["calendar.timeline"]
        XCTAssertTrue(timeline.waitForExistence(timeout: 8))
        // Onto the working hours, whatever time the machine happens to say it is. The rail is
        // left closed: on a phone it is a sheet over half the day, which photographs as a list.
        //
        // A drag rather than `swipeUp`, which carries momentum and overshot the whole working day
        // when this was run after midnight — it opened on 00:00 and landed on the empty evening.
        // A press-and-drag moves exactly as far as the finger does.
        func drag(_ from: CGFloat, _ to: CGFloat) {
            timeline.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: from))
                .press(forDuration: 0.1,
                       thenDragTo: timeline.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: to)))
        }
        // The day opens on the current hour, so a run in the small hours starts on midnight and
        // photographs an empty night. One drag of this length moves about six and a half hours —
        // measured, not guessed — which lands on the working day from anywhere before it.
        //
        // Not `swipeUp` (momentum overshoots by half a day) and not "scroll until the morning is
        // hittable" — an element scrolled out of view inside a scroll view still reports as
        // hittable, so that loop exits immediately and proves nothing.
        if Calendar.current.component(.hour, from: Date()) < 8 { drag(0.78, 0.38) }

        keep("calendar")
    }

    /// A session running: the clock, the task it is against, and the way to stop it.
    func testShotFocus() {
        _ = launch(route: "start:demo-chapter:1500", extra: ["-demo"])
        app.tap()
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label MATCHES %@", "\\d{1,2}:\\d{2}(:\\d{2})?")).firstMatch
            .waitForExistence(timeout: 10))
        keep("focus")
    }

    /// The canvas with something drawn on it, rather than a blank page.
    func testShotInk() {
        _ = launch(route: "ink:demo-ink", extra: ["-demo"])
        let canvas = el("ink.canvas")
        XCTAssertTrue(canvas.waitForExistence(timeout: 8))
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.25))
            .press(forDuration: 0.1,
                   thenDragTo: canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.42)))
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.55))
            .press(forDuration: 0.1,
                   thenDragTo: canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.66)))
        keep("ink")
    }

    /// What the ledger adds up to.
    func testShotStats() {
        _ = launch(route: "stats", extra: ["-demo"])
        XCTAssertTrue(app.staticTexts["Focus stats"].waitForExistence(timeout: 8))
        keep("stats")
    }
}
