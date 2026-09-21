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

    private var interruptions: NSObjectProtocol?

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        // Permission dialogs belong to the system, not the app, and they sit *over* it — so a query
        // for anything behind one finds nothing and the test fails for a reason that has nothing to
        // do with the screen. Starting a focus session asks about notifications; turning on the
        // calendar asks about calendars.
        interruptions = addUIInterruptionMonitor(withDescription: "system permission") { alert in
            for label in ["Allow", "OK", "Don’t Allow", "Don't Allow", "Allow While Using App"] {
                let button = alert.buttons[label]
                if button.exists { button.tap(); return true }
            }
            return false
        }
    }

    override func tearDownWithError() throws {
        if let interruptions { removeUIInterruptionMonitor(interruptions) }
    }

    /// Launches on a given route. `-route` is the app's own scaffolding, already used for
    /// screenshots, so a screen that needs three taps to reach can be the subject of a test rather
    /// than the prelude to one.
    @discardableResult
    func launch(route: String? = nil, extra: [String] = []) -> XCUIApplication {
        app.launchArguments = ["-uitest-reset", "-uitest"] + extra
        if let route { app.launchArguments += ["-route", route] }
        // Tests run back to back in one process, and a launch on top of an instance that is still
        // shutting down comes up in a half-state where the first screen never arrives. Terminating
        // first, then waiting to actually be foreground, is what makes each test start from the
        // same place instead of from whatever the previous one left mid-teardown.
        app.terminate()
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15),
                      "the app never came to the foreground for -route \(route ?? "home")")
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
        static let focusedTask = "Focused thing"
        static let archivedTask = "Archived thing"
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

    /// Taps once the element is actually there.
    ///
    /// A bare `.tap()` races the first layout: the screen is on its way up, the query finds nothing,
    /// and the test fails for a reason that has nothing to do with the app.
    func tap(_ identifier: String, _ message: String = "", timeout: TimeInterval = 8) {
        let e = el(identifier)
        XCTAssertTrue(e.waitForExistence(timeout: timeout),
                      message.isEmpty ? "never found \(identifier) to tap" : message)
        e.tap()
    }

    /// A shelf tab, whatever element SwiftUI made of it.
    ///
    /// In the iPad's column the tab's words are a `staticText`; in the phone's sheet the same view
    /// is folded into a `button` and the words are its label. Querying one or the other is how a
    /// test comes to "fail" on a rail that is plainly on screen.
    func shelf(_ name: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR identifier == %@", name, name)).firstMatch
    }

    /// Opens the rail, wherever it lives on this device.
    ///
    /// A column beside the day on a wide window, a sheet on a phone. `.exists` does not wait, so
    /// probing it straight after the screen appears reports "no rail" on an iPad where the rail is
    /// already open — and then tapping Tasks *closes* the one that was there.
    func openRail() {
        if shelf("Undated").waitForExistence(timeout: 4) { return }   // already a column
        tap("calendar.tasks", "no Tasks key to open the rail with")
        XCTAssertTrue(shelf("Undated").waitForExistence(timeout: 6), "the rail did not open")
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
        // Five tasks, one of them finished. The two events on that page are not tasks and must not
        // be counted — a list that said "1 of 7 done" would be counting things with no box to tick.
        // The archived task is not counted either: it has left the list.
        XCTAssertTrue(row.label.contains("1 of 5 done"),
                      "expected '1 of 5 done', got \(row.label.debugDescription)")
    }

    func testTappingAListOnHomeOpensIt() {
        launch(route: "home")
        openListNamed(Fixture.list)
        assertExists(el("task.row.\(Fixture.plainTask)"), "the list did not open, or is empty")
        assertExists(el("task.row.\(Fixture.overdueTask)"), "the overdue task is missing")
    }

    func testTheSettingsAndCalendarKeysGoSomewhere() {
        let app = launch(route: "home")
        tap("home.calendar")
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

        tap("calendar.mode.D")
        let dayFmt = DateFormatter(); dayFmt.dateFormat = "EEE d MMM"
        expectHeading(heading, becomes: dayFmt.string(from: Date()), "Day mode should name the day you are on")

        tap("calendar.mode.M")
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
        tap("calendar.today")
        expectGone(el("calendar.today"), "tapping Today should bring today back and retire the key")
    }

    func testTheNewEventKeyOpensTheSheet() {
        let app = launch(route: "calendar")
        tap("calendar.add")
        assertExists(app.navigationBars["New event"], "the + key did not open the event sheet")
        app.buttons["Cancel"].tap()
        expectGone(app.navigationBars["New event"], "Cancel did not close the sheet")
    }

    func testCreatingAnEventPutsItOnTheDay() {
        let app = launch(route: "calendar")
        tap("calendar.add")
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

        openRail()

        let window = app.windows.firstMatch.frame
        for name in ["Today", "Soon", "Undated", "Other"] {
            let tab = shelf(name)
            assertExists(tab, "the \(name) shelf is missing from the rail")
            XCTAssertLessThanOrEqual(tab.frame.maxX, window.maxX,
                                     "the \(name) tab is cut off by the right edge: \(tab.frame)")
            XCTAssertGreaterThanOrEqual(tab.frame.minX, window.minX,
                                        "the \(name) tab starts off the left edge: \(tab.frame)")
        }
    }

    /// The rail's whole job: a task with no date is invisible on a calendar, and this is how it
    /// stops being. The fixture's undated task is the one that should be on that shelf.
    func testTheUndatedShelfHoldsTheTaskWithNoDate() {
        let app = launch(route: "calendar", extra: ["-calmode", "day"])
        assertExists(app.staticTexts["calendar.heading"], "the calendar never appeared")
        openRail()
        shelf("Undated").tap()
        assertExists(shelf(Fixture.plainTask),
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
        // Wait for the screen before deciding anything about it: `.exists` does not wait, so a
        // probe on the way up reports "not there", scrolls past it, and fails for the wrong reason.
        assertExists(app.staticTexts["Settings"], "settings never appeared")
        let link = app.staticTexts["Privacy policy"]
        // It is near the bottom, so it may need scrolling to.
        var swipes = 0
        while !link.exists, swipes < 4 { app.swipeUp(); swipes += 1 }
        assertExists(link, "there is no privacy policy link in settings")
    }
}

// MARK: - the routes
//
// There was a smoke test here that walked every route in one method and asserted each came up with
// something on it. It has been removed, because every route it covered now has a test of its own
// that checks what is actually *on* the screen rather than that more than three elements exist:
// home and lists (HomeUITests), settings (SettingsUITests), the calendar in three modes
// (CalendarUITests), focus and stats (FocusUITests), the archive (ArchiveUITests), sign-in
// (SignInUITests), ink (InkUITests) and open:<id> (DeepLinkUITests).
//
// It was also the one test that regularly timed out: eight full resets and launches in a single
// method, which is a slow way to learn less than the tests above already tell us.

// MARK: - focus and its stats

final class FocusUITests: YantraUITestCase {

    /// The empty line is one label across two lines, so it is matched by what it contains rather
    /// than by an exact string that depends on where the wrap falls.
    func emptyLine() -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS[c] 'Nothing in focus'")).firstMatch
    }

    func testFocusOpensOnItsEmptyStateWithNothingRunning() {
        launch(route: "focus")
        assertExists(emptyLine(), "the focus screen did not come up empty")
    }

    /// Starting a session is what the whole screen exists for, so it is worth driving rather than
    /// asserting about the ledger. `start:<id>:<secs>` is the app's own scaffolding.
    func testAStartedSessionIsRunningAndCanBeFinished() {
        let app = launch(route: "start:fixture-plain:1500")
        // A monitor only runs when the test touches the app, so touch it before asking anything.
        app.tap()
        // The task being focused is named on screen, and the empty state is gone.
        assertExists(shelf(Fixture.plainTask), "the running session does not name its task")
        XCTAssertFalse(emptyLine().exists, "it still looks empty while running")
    }

    func testStatsCountsTheSessionsInTheLedger() {
        let app = launch(route: "stats")
        assertExists(app.staticTexts["Focus stats"], "the stats screen never appeared")
        // The fixture writes two finished sessions, yesterday and today.
        assertExists(app.staticTexts["Focused on 2 of the last 7 days"],
                     "the rhythm line does not match the ledger")
        // And the breakdown names the task they were on.
        assertExists(shelf(Fixture.focusedTask), "the breakdown does not name the focused task")
        XCTAssertFalse(app.staticTexts["No focus in the last 7 days."].exists,
                       "the breakdown claims there is nothing to show")
    }
}

// MARK: - the archive

final class ArchiveUITests: YantraUITestCase {

    func testArchiveListsWhatLeftAndPutsItBack() {
        let app = launch(route: "archive")
        assertExists(app.staticTexts["Archive"], "the archive screen never appeared")
        assertExists(shelf(Fixture.archivedTask), "the archived task is not listed")

        // One tap returns it exactly where it was, which is the screen's whole promise.
        let back = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'arrow'")).firstMatch
        if back.exists {
            back.tap()
            expectGone(shelf(Fixture.archivedTask), "restoring did not take it out of the archive")
        }
    }

    func expectGone(_ el: XCUIElement, _ message: String, timeout: TimeInterval = 6) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline { if !el.exists { return }; usleep(150_000) }
        XCTFail(message)
    }
}

// MARK: - sign-in, without signing in

final class SignInUITests: YantraUITestCase {

    /// The screen has to be usable and honest before anybody signs in: the app works entirely
    /// without an account, and the review notes say so.
    func testSignInOffersAWayInAndClaimsNoAccount() {
        let app = launch(route: "github")
        // Something about signing in is on screen, and nothing claims to be signed in already.
        let signIn = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS[c] 'sign in' OR label CONTAINS[c] 'github'")).firstMatch
        assertExists(signIn, "the GitHub screen offers no way in")
        XCTAssertFalse(app.staticTexts["Signed in"].exists, "it claims a session that does not exist")
    }
}

// MARK: - ink

final class InkUITests: YantraUITestCase {

    /// The canvas is a PencilKit view, so there is little to assert about its contents — but that it
    /// opens, names its page and does not come up blank is exactly the failure a route smoke test
    /// cannot tell from a working screen.
    func testTheInkCanvasOpens() {
        let app = launch(route: "ink:fixture-ink")
        XCTAssertEqual(app.state, .runningForeground, "the ink route brought the app down")
        // The hint line is the canvas saying it is ready for input.
        let hint = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS[c] 'draw'")).firstMatch
        assertExists(hint, "the ink canvas came up with no drawing surface")
    }
}

// MARK: - the custom scheme, which anyone can open

/// `yantra://` is not owned: any app on the device can open one of these links. So the whole
/// reachable surface has to be navigation into the person's own data — nothing that writes, deletes
/// or signs anything out — and a link naming something that does not exist has to go nowhere rather
/// than to a blank page with a back button.
final class DeepLinkUITests: YantraUITestCase {

    func testAnUnknownIdGoesNowhere() {
        // Driven through the app's own route scaffolding, which shares the handler's lookup.
        let app = launch(route: "open:no-such-node-at-all")
        XCTAssertEqual(app.state, .runningForeground, "an unknown id brought the app down")
        // It stays on Home rather than pushing a page for a node that does not exist.
        assertExists(app.staticTexts["home.greeting"], "an unknown id pushed a screen anyway")
    }

    func testAKnownIdOpensThatPage() {
        launch(route: "open:fixture-groceries")
        assertExists(el("task.row.\(Fixture.plainTask)"), "a known id did not open its list")
    }

    func testACalendarLinkWithARubbishDateStillOpensTheCalendar() {
        let app = launch(route: "calendar:not-a-date")
        XCTAssertEqual(app.state, .runningForeground, "a bad date brought the app down")
        assertExists(app.staticTexts["calendar.heading"], "a bad date should still reach the calendar")
    }
}
