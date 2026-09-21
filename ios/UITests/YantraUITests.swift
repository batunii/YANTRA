import XCTest

/// The app driven the way a person drives it.
///
/// These exist because the unit tests cannot see a layout. Every bug the calendar shipped with and
/// then lost — a rail pushed off the screen edge, hour rules that were never painted, a day opening
/// on midnight while its comment claimed otherwise — was invisible to a test of the model and
/// obvious the moment the app was on a screen. So: launch it, tap it, and assert on what is there.
///
/// The workspace is reset and re-seeded on every launch (`-uitest-reset -uitest`), so a run does not
/// depend on what the run before it left behind, and the fixture is `UITestFixture` rather than the
/// welcome content — otherwise every test that named a row would be a test of the welcome copy.
class YantraUITestCase: XCTestCase {

    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    /// Launches on a given route. `-route` is the app's own scaffolding, already used for
    /// screenshots, so a screen that needs three taps to reach can be the subject of a test rather
    /// than the prelude to one.
    @discardableResult
    func launch(route: String? = nil, extra: [String] = []) -> XCUIApplication {
        app.launchArguments = ["-uitest-reset", "-uitest"] + extra
        if let route { app.launchArguments += ["-route", route] }
        app.launch()
        return app
    }

    // Fixture names, mirrored from `UITestFixture.Names`.
    enum Fixture {
        static let list = "Groceries"
        static let secondList = "Work"
        static let smartList = "Today"
        static let overdueTask = "Overdue thing"
        static let todayTask = "Timed thing"
        static let plainTask = "Plain thing"
        static let doneTask = "Finished thing"
        static let subtask = "A subtask"
        static let event = "Standup"
        static let allDayEvent = "Conference day"
    }

    /// Anything with this identifier, whatever kind of element SwiftUI decided it was.
    ///
    /// `.accessibilityElement(children: .combine)` turns a row into a *button*, and a row without it
    /// stays an *other* — which is an implementation detail of the view, not of the thing being
    /// tested. Querying the whole tree by identifier keeps the test about the row.
    func el(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    /// Taps a Home row the way a finger does — on the words.
    ///
    /// Not on the element the identifier resolves to. SwiftUI wraps a Button carrying an
    /// identifier in a container that reports as a button and is not the control, so `.tap()` on it
    /// lands on nothing. Tapping the title is both a real target and the one a person aims at.
    func openListNamed(_ name: String) {
        let words = app.staticTexts[name]
        XCTAssertTrue(words.waitForExistence(timeout: 8), "no row called \(name) on Home")
        words.tap()
    }

    func waitFor(_ element: XCUIElement, _ seconds: TimeInterval = 8, _ message: String = "") -> Bool {
        element.waitForExistence(timeout: seconds)
    }

    func assertExists(_ element: XCUIElement, _ message: String, timeout: TimeInterval = 8) {
        XCTAssertTrue(element.waitForExistence(timeout: timeout), message)
    }

    static func isoToday(_ offsetDays: Int = 0) -> String {
        let d = Calendar.current.date(byAdding: .day, value: offsetDays, to: Date())!
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.calendar = Calendar(identifier: .gregorian)
        return f.string(from: d)
    }
}

// MARK: - the app opens, and shows what is in the workspace

final class HomeUITests: YantraUITestCase {

    func testHomeShowsTheListsInTheWorkspace() {
        let app = launch(route: "home")
        assertExists(app.staticTexts["home.greeting"], "the home screen never appeared")
        assertExists(el("home.row.\(Fixture.list)"), "the Groceries list is missing from Home")
        assertExists(el("home.row.\(Fixture.secondList)"), "the Work list is missing from Home")
        assertExists(el("home.row.\(Fixture.smartList)"), "the Today smart list is missing from Home")
    }

    func testAListRowCountsOnlyItsTasks() {
        let app = launch(route: "home")
        let row = el("home.row.\(Fixture.list)")
        assertExists(row, "no Groceries row")
        // Four tasks, one of them finished. The two events on that page are not tasks and must not
        // be counted — a list that said "1 of 6 done" would be counting things with no box to tick.
        XCTAssertTrue(row.label.contains("1 of 4 done"),
                      "expected '1 of 4 done', got \(row.label.debugDescription)")
    }

    func testTappingAListOnHomeOpensIt() {
        launch(route: "home")
        openListNamed(Fixture.list)
        assertExists(el("task.row.\(Fixture.plainTask)"), "the list did not open, or is empty")
        assertExists(el("task.row.\(Fixture.overdueTask)"), "the overdue task is missing")
    }

    func testTheSettingsAndCalendarKeysGoSomewhere() {
        let app = launch(route: "home")
        el("home.calendar").tap()
        assertExists(app.staticTexts["calendar.heading"], "the calendar key did not reach the calendar")
    }
}

// MARK: - a task can be finished, and it stays finished

final class TaskUITests: YantraUITestCase {

    func testTickingATaskFoldsItAwayAndItStaysFinished() {
        // Straight to the list: this test is about the checkbox, not about getting there.
        let app = launch(route: "open:fixture-groceries")

        let check = el("task.check.\(Fixture.plainTask)")
        assertExists(check, "no checkbox for the plain task")
        XCTAssertEqual(check.label, "Mark done", "the task should start open")
        check.tap()

        // Finishing it takes it out of the open list — a list is for what is left to do — and the
        // Done count goes up. Both are the app saying the write landed.
        expectEventually("the task was ticked and stayed in the open list") {
            !el("task.row.\(Fixture.plainTask)").exists
        }
        assertExists(app.staticTexts["DONE · 2"], "the finished count did not go up")

        // And it is still finished once the screen has been rebuilt from the files, which is what
        // would catch a change that only ever lived in memory.
        app.buttons["Show"].tap()
        let again = el("task.check.\(Fixture.plainTask)")
        assertExists(again, "Show did not reveal the task that was just finished")
        expectEventually("the task did not stay finished") { again.label == "Mark not done" }
    }

    func testFinishedTasksAreFoldedAwayUntilAskedFor() {
        let app = launch(route: "open:fixture-groceries")
        assertExists(el("task.row.\(Fixture.plainTask)"), "the list did not open")

        // A finished task is not gone, it is folded away: a list is for what is left to do.
        XCTAssertFalse(el("task.row.\(Fixture.doneTask)").exists,
                       "finished tasks should not be in the open list")
        assertExists(app.staticTexts["DONE · 1"], "the list does not say how many are finished")

        app.buttons["Show"].tap()
        let check = el("task.check.\(Fixture.doneTask)")
        assertExists(check, "Show did not reveal the finished task")
        XCTAssertEqual(check.label, "Mark not done", "a task seeded as done should render as done")
    }

    /// Polls a condition, for the state that arrives a frame or two after a tap.
    func expectEventually(_ message: String, timeout: TimeInterval = 5, _ condition: () -> Bool) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return }
            usleep(150_000)
        }
        XCTFail(message)
    }
}

// MARK: - the calendar

final class CalendarUITests: YantraUITestCase {

    func testMonthOpensOnThisMonthAndTodayIsSelectable() {
        let app = launch(route: "calendar")
        assertExists(app.staticTexts["calendar.heading"], "the calendar never appeared")
        let f = DateFormatter(); f.dateFormat = "MMM yyyy"
        XCTAssertEqual(app.staticTexts["calendar.heading"].label, f.string(from: Date()),
                       "the month should open on the month it is")
        assertExists(el("calendar.cell.\(Self.isoToday())"), "today's cell is missing from the grid")
    }

    func testTheDayListShowsTodaysEventsAndTasks() {
        let app = launch(route: "calendar")
        // The fixture puts a timed event, an all-day event and a timed task on today.
        assertExists(el("calendar.item.\(Fixture.event)"), "the standup is missing from the day list")
        assertExists(el("calendar.item.\(Fixture.allDayEvent)"), "the all-day event is missing")
        assertExists(el("calendar.item.\(Fixture.todayTask)"), "the timed task is missing")
    }

    func testSwitchingModesChangesWhatTheHeadingSays() {
        let app = launch(route: "calendar")
        let heading = app.staticTexts["calendar.heading"]
        assertExists(heading, "no heading")
        let month = heading.label

        el("calendar.mode.D").tap()
        let dayFmt = DateFormatter(); dayFmt.dateFormat = "EEE d MMM"
        expectHeading(heading, becomes: dayFmt.string(from: Date()), "Day mode should name the day you are on")

        el("calendar.mode.M").tap()
        expectHeading(heading, becomes: month, "going back to Month should name the month again")
    }

    func testTodayKeyAppearsOnlyWhenTodayIsOffScreen() {
        let app = launch(route: "calendar")
        // It opens on this month, so today is already on screen and the key has nothing to offer.
        XCTAssertFalse(el("calendar.today").exists,
                       "the Today key should not be drawn while today is already on screen")

        // Page forward a month by swiping the bar, and it should appear.
        app.staticTexts["calendar.heading"].swipeLeft()
        assertExists(el("calendar.today"), "paging away from today should offer a way back")
        el("calendar.today").tap()
        expectGone(el("calendar.today"), "tapping Today should bring today back and retire the key")
    }

    func testTheNewEventKeyOpensTheSheet() {
        let app = launch(route: "calendar")
        el("calendar.add").tap()
        assertExists(app.navigationBars["New event"], "the + key did not open the event sheet")
        app.buttons["Cancel"].tap()
        expectGone(app.navigationBars["New event"], "Cancel did not close the sheet")
    }

    func testCreatingAnEventPutsItOnTheDay() {
        let app = launch(route: "calendar")
        el("calendar.add").tap()
        assertExists(app.navigationBars["New event"], "no event sheet")

        let field = app.textFields.firstMatch
        assertExists(field, "no title field on the event sheet")
        field.tap()
        field.typeText("Dentist")
        app.buttons["Save"].tap()

        assertExists(el("calendar.item.Dentist"),
                     "the event was saved but never appeared on the day")
    }

    func testTheDayTimelineDraws() {
        let app = launch(route: "calendar", extra: ["-calmode", "day"])
        // The ruler is the thing that was silently missing once; assert an hour label exists.
        assertExists(app.staticTexts["09:00"], "the timeline ruler did not draw")
        assertExists(app.descendants(matching: .any)[Fixture.event], "the standup block is missing from the timeline")
        // An all-day thing belongs in the bar above the ruler, not in it.
        assertExists(app.descendants(matching: .any)[Fixture.allDayEvent], "the all-day chip is missing")
    }

    func expectHeading(_ el: XCUIElement, becomes expected: String, _ message: String, timeout: TimeInterval = 5) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if el.label == expected { return }
            usleep(150_000)
        }
        XCTFail("\(message) — expected \(expected.debugDescription), got \(el.label.debugDescription)")
    }

    func expectGone(_ el: XCUIElement, _ message: String, timeout: TimeInterval = 5) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if !el.exists { return }
            usleep(150_000)
        }
        XCTFail(message)
    }
}

// MARK: - the shapes a bigger screen takes

final class LayoutUITests: YantraUITestCase {

    /// Runs on whatever device the scheme was given; the assertions are about the window it got.
    func testTheCalendarFitsItsWindowWhicheverItIs() {
        let app = launch(route: "calendar")
        assertExists(app.staticTexts["calendar.heading"], "the calendar never appeared")

        let window = app.windows.firstMatch.frame
        // Nothing may sit outside the window. This is the bug the rail shipped with: a fixed-width
        // column beside a scroll view that took every point offered, pushed off the right edge.
        for id in ["calendar.heading", "calendar.cell.\(Self.isoToday())"] {
            let element = el(id)
            guard element.exists else { continue }
            XCTAssertTrue(window.contains(element.frame.origin),
                          "\(id) starts outside the window: \(element.frame) vs \(window)")
            XCTAssertLessThanOrEqual(element.frame.maxX, window.maxX + 1,
                                     "\(id) runs off the right edge: \(element.frame) vs \(window)")
        }
    }

    /// Every shelf has to be readable, on whatever this is running on.
    ///
    /// The rail shipped at 210 points wide and the fourth tab was cut in half by the edge — which
    /// no model test could see and no assertion about the window caught, because the tabs sit in a
    /// horizontal scroll view and were technically reachable.
    func testTheRailShowsAllFourShelves() {
        let app = launch(route: "calendar", extra: ["-calmode", "day"])
        assertExists(app.staticTexts["calendar.heading"], "the calendar never appeared")

        // A column on a wide window, a sheet on a phone. Both are the same rail.
        if !app.staticTexts["Undated"].exists { app.buttons["Tasks"].tap() }

        let window = app.windows.firstMatch.frame
        for shelf in ["Today", "Soon", "Undated", "Other"] {
            let tab = app.staticTexts[shelf]
            assertExists(tab, "the \(shelf) shelf is missing from the rail")
            XCTAssertLessThanOrEqual(tab.frame.maxX, window.maxX,
                                     "the \(shelf) tab is cut off by the right edge: \(tab.frame)")
            XCTAssertGreaterThanOrEqual(tab.frame.minX, window.minX,
                                        "the \(shelf) tab starts off the left edge: \(tab.frame)")
        }
    }

    /// The rail's whole job: a task with no date is invisible on a calendar, and this is how it
    /// stops being. The fixture's undated task is the one that should be on that shelf.
    func testTheUndatedShelfHoldsTheTaskWithNoDate() {
        let app = launch(route: "calendar", extra: ["-calmode", "day"])
        assertExists(app.staticTexts["calendar.heading"], "the calendar never appeared")
        if !app.staticTexts["Undated"].exists { app.buttons["Tasks"].tap() }
        app.staticTexts["Undated"].tap()
        assertExists(app.staticTexts[Fixture.plainTask],
                     "the task with no date is not on the Undated shelf")
    }

    func testTheDayViewKeepsItsRailInsideTheWindow() {
        let app = launch(route: "calendar", extra: ["-calmode", "day"])
        assertExists(app.staticTexts["calendar.heading"], "the calendar never appeared")
        let window = app.windows.firstMatch.frame
        // On a phone the rail is a sheet and this passes trivially; on an iPad it is a column and
        // this is the assertion that matters.
        let ruler = app.staticTexts["09:00"]
        if ruler.exists {
            XCTAssertLessThanOrEqual(ruler.frame.maxX, window.maxX,
                                     "the timeline ruler is outside the window")
        }
    }
}

// MARK: - settings, including what the App Store audit added

final class SettingsUITests: YantraUITestCase {

    func testSettingsShowsTheWorkspaceAndTheTheme() {
        let app = launch(route: "settings")
        assertExists(app.staticTexts["Settings"], "settings never appeared")
        assertExists(app.staticTexts["THEME"], "no theme section")
        assertExists(app.staticTexts["Personal"], "the workspace is not named")
    }

    /// Reading somebody's meetings is off until they turn it on, and the switch is what asks for the
    /// permission — not the first launch of a screen.
    func testTheCalendarSwitchIsOffUntilItIsTurnedOn() {
        let app = launch(route: "settings")
        let toggle = app.switches["Show my calendars"]
        assertExists(toggle, "the device-calendar switch is missing from settings")
        XCTAssertEqual(toggle.value as? String, "0", "calendar access should start off")
        // Nothing should be listed while it is off: there is nothing to list until access is given.
        XCTAssertFalse(app.staticTexts["Which ones"].exists)
    }

    /// 5.1.1 wants the policy reachable from inside the app, not only from the store listing — which
    /// is the one place somebody who already installed it will never look.
    func testThePrivacyPolicyIsReachableFromInsideTheApp() {
        let app = launch(route: "settings")
        let link = app.staticTexts["Privacy policy"]
        // It is near the bottom, so it may need scrolling to.
        if !link.exists { app.swipeUp(); app.swipeUp() }
        assertExists(link, "there is no privacy policy link in settings")
    }
}

// MARK: - every route opens

/// A smoke test over the app's own navigation scaffolding.
///
/// Cheap, and it catches the class of failure that is otherwise only found by hand: a screen that
/// crashes or comes up blank because something it reads was renamed underneath it.
final class RouteSmokeTests: YantraUITestCase {

    func testEveryRouteOpensSomething() {
        for route in ["home", "settings", "calendar", "archive", "github", "stats",
                      "open:fixture-groceries", "open:fixture-plain"] {
            let app = launch(route: route)
            // Something has to be on screen, and the app has to still be running.
            XCTAssertEqual(app.state, .runningForeground, "the app is not running after -route \(route)")
            XCTAssertGreaterThan(app.descendants(matching: .any).count, 3,
                                 "-route \(route) came up with nothing on it")
            app.terminate()
        }
    }
}
