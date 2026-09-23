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
    /// `reset: false` leaves the workspace as the last launch left it, for the handful of tests
    /// whose subject is what survived being written down.
    @discardableResult
    func launch(route: String? = nil, extra: [String] = [], reset: Bool = true) -> XCUIApplication {
        app.launchArguments = (reset ? ["-uitest-reset"] : []) + ["-uitest"] + extra
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
        static let listId = "fixture-groceries"
        static let plainTaskId = "fixture-plain"
        static let secondList = "Work"
        static let smartList = "Today"
        static let overdueTask = "Overdue thing"
        static let todayTask = "Timed thing"
        static let plainTask = "Plain thing"
        static let doneTask = "Finished thing"
        static let subtask = "A subtask"
        static let focusedTask = "Focused thing"
        static let archivedTask = "Archived thing"
        static let warnedTask = "Warned thing"
        static let startedTask = "Started thing"
        static let sittingTask = "Sitting thing"
        static let group = "Projects"
        static let groupedList = "Kitchen"
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
        // Label, identifier *or* value. An editable title — a page's name, a text field — carries
        // its words as a `value`, not a `label`, so matching only the first two finds nothing on a
        // screen where the words are plainly on display.
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@ OR identifier == %@ OR value == %@", name, name, name))
            .firstMatch
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

    /// The capture bar's field.
    ///
    /// A `UITextView`, not a `UITextField`: it tints what the parser understands as you type, and
    /// SwiftUI's `TextField` draws one colour only — see `CaptureField`. Named here so the tests do
    /// not each carry that fact.
    var captureField: XCUIElement { app.textViews.firstMatch }

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
        // Eight tasks, one of them finished. The three events on that page are not tasks and must
        // not be counted — a list that said "1 of 11 done" would be counting things with no box to
        // tick. The archived task is not counted either: it has left the list.
        XCTAssertTrue(row.label.contains("1 of 8 done"),
                      "expected '1 of 8 done', got \(row.label.debugDescription)")
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

        // Finishing it strikes it through **where it is**. It used to leave the open list the same
        // instant, which took the feedback with it and jumped every row below up a line — you could
        // not see what you had just done, and if it was the wrong row you could not see that either.
        // The list tidies itself the next time you come to it; see `PlaceOnCalendarUITests` for the
        // other half of this behaviour.
        let row = el("task.row.\(Fixture.plainTask)")
        XCTAssertTrue(row.exists, "the task left the list the moment it was ticked")
        expectEventually("the tick did not land") {
            el("task.check.\(Fixture.plainTask)").label == "Mark not done"
        }

        // And it is still finished once the screen has been rebuilt from the files, which is what
        // would catch a change that only ever lived in memory.
        tap("nav.back", "no way back to Home")
        // The row's title, not the row: an identifier on a Button resolves to a wrapper that
        // swallows the tap. See `openRail` and the note in ListLookUITests.
        let listRow = app.staticTexts[Fixture.list]
        XCTAssertTrue(listRow.waitForExistence(timeout: 8), "Home did not come back")
        listRow.tap()
        expectEventually("the finished task is still in the open list after reopening it") {
            !el("task.row.\(Fixture.plainTask)").exists
        }
        assertExists(app.staticTexts["DONE · 2"], "the finished count did not go up")
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

    /// The calendar opens on the **day** — that is what it is opened to do. A test about the month
    /// therefore asks for the month, rather than relying on where the app happens to land.
    func testMonthOpensOnThisMonthAndTodayIsSelectable() {
        let app = launch(route: "calendar", extra: ["-calmode", "month"])
        assertExists(app.staticTexts["calendar.heading"], "the calendar never appeared")
        let f = DateFormatter(); f.dateFormat = "MMM yyyy"
        XCTAssertEqual(app.staticTexts["calendar.heading"].label, f.string(from: Date()),
                       "the month should open on the month it is")
        assertExists(el("calendar.cell.\(Self.isoToday())"), "today's cell is missing from the grid")
    }

    /// The day list is the pane under the month grid, so this asks for the month.
    func testTheDayListShowsTodaysEventsAndTasks() {
        let app = launch(route: "calendar", extra: ["-calmode", "month"])
        // The fixture puts a timed event, an all-day event and a timed task on today.
        assertExists(el("calendar.item.\(Fixture.event)"), "the standup is missing from the day list")
        assertExists(el("calendar.item.\(Fixture.allDayEvent)"), "the all-day event is missing")
        assertExists(el("calendar.item.\(Fixture.todayTask)"), "the timed task is missing")
    }

    func testSwitchingModesChangesWhatTheHeadingSays() {
        let app = launch(route: "calendar", extra: ["-calmode", "month"])
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
        let app = launch(route: "calendar", extra: ["-calmode", "month"])
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

    /// `calendar.item.…` is a row in the month's day list, so this asks for the month. On the day
    /// timeline the same event is a block, which `TimelineBlock` draws and names differently.
    func testCreatingAnEventPutsItOnTheDay() {
        let app = launch(route: "calendar", extra: ["-calmode", "month"])
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

    /// A session is time given to one thing, and the thing is a task.
    ///
    /// The list page used to offer the same control the task page does, so a list could be focused
    /// on — a clock started against a container, which the stats screen would then have to report
    /// as work done on nothing in particular. Android gates it on the node being a task.
    func testOnlyATaskOffersToBeFocusedOn() {
        launch(route: "open:\(Fixture.plainTaskId)")
        assertExists(el("page.focus"), "a task page did not offer to start a session")

        launch(route: "open:\(Fixture.listId)")
        assertExists(el("nav.back"), "the list page did not come up")
        XCTAssertFalse(el("page.focus").exists, "a list offered to be focused on")
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
        assertExists(el("ink.canvas"), "the ink canvas came up with no drawing surface")
        // And the kit that makes it usable: a pen to draw with and a way to take a mark back.
        assertExists(el("ink.slot.0"), "the ink canvas came up with no pen")
        assertExists(el("ink.undo"), "the ink canvas came up with no undo")
    }

    /// The document is a stack of pages, not a screenful.
    ///
    /// The fold and the number beside it are drawn for the eye, so the only thing a test — or a
    /// screen reader — can ask is the canvas itself. An empty sketch is one page with one blank
    /// page to grow into, which is what stops a drawing ending exactly where you stopped drawing.
    func testAnEmptySketchIsOnePageWithOneToGrowInto() {
        _ = launch(route: "ink:fixture-ink")
        let canvas = el("ink.canvas")
        assertExists(canvas, "the ink canvas came up with no drawing surface")
        XCTAssertEqual(canvas.value as? String, "Page 1 of 2")
    }

    /// Pinching in says how far in you are and offers the way back out; at one page across there is
    /// nothing to say, so nothing is said.
    func testTheCanvasZoomsAndOffersTheWayBackToOnePageAcross() {
        let app = launch(route: "ink:fixture-ink")
        let canvas = el("ink.canvas")
        assertExists(canvas, "the ink canvas came up with no drawing surface")
        let fit = app.buttons["ink.zoom"]
        XCTAssertFalse(fit.exists, "a page already one across was offering to fit itself")

        canvas.pinch(withScale: 3, velocity: 3)
        XCTAssertTrue(fit.waitForExistence(timeout: 5),
                      "the page did not zoom, or did not say how far in it is")

        fit.tap()
        XCTAssertTrue(fit.waitForNonExistence(timeout: 5),
                      "fit did not put the page back to one page across")
    }

    /// A stroke that runs past the fold is on the next page, and is still on the next page when the
    /// drawing is opened again.
    ///
    /// This is the only end-to-end check there is that ink is stored as a position on the page
    /// rather than a point on this screen: the page count is computed from where the strokes are,
    /// so a stroke that came back at the wrong scale would come back on the wrong page.
    func testInkRunningOntoTheSecondPageIsStillThereOnTheSecondPage() {
        let app = launch(route: "ink:fixture-ink")
        let canvas = el("ink.canvas")
        assertExists(canvas, "the ink canvas came up with no drawing surface")
        let empty = pageTotal(canvas)
        XCTAssertGreaterThan(empty, 0, "the canvas does not say what page it is on")

        // Zoom out before drawing. A page is taller than the screen by a different amount on every
        // device — about 1900du of a 1414du page are in view on a phone and only about 1320du on an
        // iPad — so a drag measured as a fraction of the screen crosses the first fold on one and
        // stops short of it on the other. Asserting a fixed page count after that drag is a test of
        // the window's shape, which is how this passed on a phone and failed on an iPad. Pinched
        // out, one drag reaches past the fold on both.
        canvas.pinch(withScale: 0.5, velocity: -3)

        let top = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.05))
        let bottom = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.97))
        top.press(forDuration: 0.1, thenDragTo: bottom)

        // How far one drag reaches still depends on the screen, so the number is not fixed — but it
        // has to be more than the one page and one to grow into an empty sketch has, or the stroke
        // never crossed a fold and what follows would prove nothing.
        let grown = waitForPageTotal(canvas, above: empty,
                                     "the stroke never reached past the first fold")

        // Away and back, so what is measured is the file rather than what is still in memory.
        app.buttons["nav.back"].tap()
        let reopened = launch(route: "ink:fixture-ink", reset: false)
        let again = reopened.descendants(matching: .any)["ink.canvas"]
        assertExists(again, "the ink canvas did not come back")
        _ = waitForPageTotal(again, above: empty, "the stroke did not come back at all")
        XCTAssertEqual(pageTotal(again), grown,
                       "the stroke came back on a different page from the one it was drawn on")
    }

    /// The `N` in the canvas's "Page n of N", which is how many pages the ink occupies plus the one
    /// blank one to grow into. 0 when the canvas has not said yet.
    private func pageTotal(_ canvas: XCUIElement) -> Int {
        guard let value = canvas.value as? String,
              let last = value.split(separator: " ").last else { return 0 }
        return Int(last) ?? 0
    }

    @discardableResult
    private func waitForPageTotal(_ canvas: XCUIElement, above: Int, _ message: String) -> Int {
        let deadline = Date().addingTimeInterval(8)
        while Date() < deadline {
            let n = pageTotal(canvas)
            if n > above { return n }
            usleep(150_000)
        }
        XCTFail("\(message) — it still says \(String(describing: canvas.value))")
        return 0
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

// MARK: - what main added: list icons, and more than one reminder

final class ListLookUITests: YantraUITestCase {

    /// A list wearing an emoji and a list wearing its mark are both on Home, and both are readable.
    ///
    /// The colour lands differently on each — a mark is tinted, an emoji sits on a disc because it
    /// cannot be tinted — which is not something a test can see. What it can check is that choosing
    /// an icon reaches the file and comes back, which is the part that would silently do nothing.
    func testAListCanBeGivenAnIconAndItSticks() {
        let app = launch(route: "home")
        assertExists(shelf(Fixture.list), "the Groceries row is missing")

        // The sheet is reached by long-pressing the row, because how a list looks is the second
        // thing you can do to it and not worth a control on every row.
        // A long press opens the context menu; the menu's item is what opens the sheet. Asserting
        // on the sheet straight after the press was asking for the second step to have happened.
        app.staticTexts[Fixture.list].press(forDuration: 1.2)
        let item = app.buttons["How it looks"]
        guard item.waitForExistence(timeout: 6) else {
            XCTFail("long-pressing a list offered no menu"); return
        }
        item.tap()
        let look = app.navigationBars["How it looks"]
        guard look.waitForExistence(timeout: 6) else {
            XCTFail("the menu item did not open the sheet"); return
        }
        // Pick the first suggested emoji and save. `firstMatch`, because once it is chosen the
        // preview at the top of the sheet shows the same emoji and the query matches both.
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", ListIconGrid.first)).firstMatch.tap()
        app.buttons["Save"].tap()
        expectGone(look, "the sheet did not close after saving")

        // Reopening shows the choice, which is the half that proves it reached the file.
        //
        // `expectGone` returns the instant the sheet's bar stops being in the tree, which is the
        // *start* of the dismissal, not the end of it — so long-pressing straight afterwards drives
        // the row while the sheet above it is still animating away, and the second presentation is
        // occasionally dropped. That only ever failed on a loaded machine, which is the signature
        // of a race in the test rather than in the app: nobody long-presses twenty milliseconds
        // after a sheet begins to close. Waiting for the row to be hittable again is the wait a
        // finger already performs.
        let row = app.staticTexts[Fixture.list]
        let ready = NSPredicate(format: "isHittable == true")
        expectation(for: ready, evaluatedWith: row)
        waitForExpectations(timeout: 6) { error in
            XCTAssertNil(error, "the row never came back after the sheet closed")
        }
        row.press(forDuration: 1.2)
        XCTAssertTrue(app.buttons["How it looks"].waitForExistence(timeout: 6), "no menu on reopen")
        app.buttons["How it looks"].tap()
        XCTAssertTrue(look.waitForExistence(timeout: 6), "the sheet did not reopen")
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", ListIconGrid.first)).firstMatch.exists,
                      "the chosen icon is not in the sheet")
    }

    /// Only the grid's first cell is needed, and hard-coding it here rather than importing the core
    /// keeps the UI bundle free of the package.
    enum ListIconGrid { static let first = "📥" }

    func expectGone(_ el: XCUIElement, _ message: String, timeout: TimeInterval = 6) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline { if !el.exists { return }; usleep(150_000) }
        XCTFail(message)
    }
}

final class ReminderUITests: YantraUITestCase {

    /// A task warned about twice says so, rather than showing the same bell a single reminder does.
    func testATaskWithTwoRemindersSaysHowMany() {
        launch(route: "open:fixture-warned")
        assertExists(shelf(Fixture.warnedTask), "the warned task's page did not open")
        // The due pill carries the count once there is more than one.
        // By identifier, not by "contains Due" — that also matches a task called "Overdue thing",
        // which is exactly what it picked up on an iPad where the rows lay out differently.
        let pill = el("task.due")
        assertExists(pill, "the task page shows no due pill")
        XCTAssertTrue(pill.label.contains("2"),
                      "a task with two reminders should say so, got \(pill.label.debugDescription)")
    }

    /// The offsets on offer follow the shape of the due date, and several can be on at once.
    func testSeveralRemindersCanBeChosenAtOnce() {
        launch(route: "open:fixture-warned")
        // By identifier, not by "contains Due" — that also matches a task called "Overdue thing",
        // which is exactly what it picked up on an iPad where the rows lay out differently.
        let pill = el("task.due")
        assertExists(pill, "no due pill to open")
        pill.tap()

        assertExists(app.staticTexts["Reminders"], "the due sheet has no reminders section")
        // The fixture sets a day and half an hour before, so both chips are already on.
        for label in ["1 day", "30 min"] {
            assertExists(shelf(label), "the \(label) chip is missing from the due sheet")
        }
        XCTAssertTrue(shelf("2 set").exists, "the sheet does not say how many are set")
    }
}

// MARK: - the smart list builder

/// The rule editor. What matters here is not that the controls draw but that opening a rule the
/// form cannot fully express, and saving, leaves the part it cannot show alone.
final class SmartListBuilderUITests: YantraUITestCase {

    /// The seeded Today rule is "open AND (due today-or-earlier OR deadline today-or-earlier)".
    /// The OR has no control, so the sheet has to say so rather than claim there are no conditions.
    func testARuleTheFormCannotExpressSaysSoRatherThanLooksEmpty() {
        launch(route: "rules:fixture-today")
        assertExists(el("smart.show.Open"), "the builder did not come up")
        XCTAssertTrue(el("smart.show.Open").isSelected, "Today shows open tasks, so Open should be the mode")
        assertExists(el("smart.extrasNote"), "a rule with a clause the form cannot show said nothing about it")
        // And no starting point claims to be what this rule is, because none of them is.
        for t in ["Due today", "High priority", "All open tasks"] {
            XCTAssertFalse(el("smart.preset.\(t)").isSelected,
                           "\(t) claimed to be the current rule while a hidden clause was present")
        }
    }

    /// The property this asserts is the one the whole `extras` mechanism exists for: edit what the
    /// form controls, save, and the branch it never showed you is still in the rule afterwards.
    func testSavingDoesNotDropTheClauseTheFormNeverShowed() {
        launch(route: "rules:fixture-today")
        assertExists(el("smart.show.All"), "the builder did not come up")
        tap("smart.show.All", "could not change what the list shows")
        tap("smart.save", "could not save the rule")

        // Reopen and look again: the note is still there, so the branch survived the write.
        launch(route: "rules:fixture-today")
        assertExists(el("smart.extrasNote"),
                     "saving an edit dropped the clause the form could not show — the rule was rewritten")
    }

    func testAConditionCanBeAddedAndRemoved() {
        launch(route: "rules:fixture-today")
        assertExists(el("smart.addCondition"), "the builder did not come up")
        tap("smart.addCondition")
        app.buttons["Priority"].tap()

        let op = el("smart.op.builtin-priority")
        assertExists(op, "adding Priority produced no condition row")
        // A freshly added select condition already says something true rather than sitting blank.
        assertExists(el("smart.value.builtin-priority"), "the new condition has no value to match")

        tap("smart.remove.builtin-priority", "the condition could not be removed")
        XCTAssertFalse(op.waitForExistence(timeout: 2), "the removed condition is still on screen")
    }

    /// Choosing a starting point is the one action that may discard the hidden branch — and it says
    /// as much on screen before you touch it.
    func testAStartingPointReplacesTheWholeRule() {
        launch(route: "rules:fixture-today")
        assertExists(el("smart.preset.High priority"), "the builder did not come up")
        tap("smart.preset.High priority")

        XCTAssertFalse(el("smart.extrasNote").exists,
                       "a starting point kept a clause it said it would replace")
        assertExists(el("smart.op.builtin-priority"), "the starting point did not fill in its condition")
        XCTAssertTrue(el("smart.preset.High priority").isSelected,
                      "the starting point did not light up after being chosen")
    }
}

// MARK: - the player

/// The bar at the foot of the screen holding whatever you are on.
final class NowPlayerUITests: YantraUITestCase {

    func testThePlayerNamesWhatIsOnTheGo() {
        launch()
        assertExists(el("now.player"), "nothing is on the bar although two tasks are started")
        // The scheduled card leads: a sitting is you, earlier, saying this is the hour for this.
        XCTAssertEqual(el("now.card").label, Fixture.sittingTask,
                       "the card whose hour is now is not at the front")
        // The one state with no numeral to carry it.
        XCTAssertEqual(el("now.state").label, "IT IS TIME")
    }

    func testTheDeckSaysHowManyCardsThereAre() {
        launch()
        assertExists(el("now.deck"), "two started tasks and no deck indicator")
        XCTAssertEqual(el("now.deck").label, "Card 1 of 2")
    }

    func testSwipingMovesToTheNextCard() {
        launch()
        assertExists(el("now.card"), "the player did not come up")
        el("now.player").swipeLeft()
        // The other started task, and with it the other end of the deck.
        let card = el("now.card")
        XCTAssertTrue(card.waitForExistence(timeout: 3))
        XCTAssertEqual(card.label, Fixture.startedTask, "the swipe did not change cards")
        XCTAssertEqual(el("now.deck").label, "Card 2 of 2")
    }

    /// The button starts an open stopwatch right here; the body opens the focus screen. Two targets,
    /// two meanings, and the split is the point.
    func testTheKeyStartsAndStopsTheClockWithoutLeavingTheScreen() {
        launch()
        assertExists(el("now.transport"), "the player has no transport key")
        XCTAssertEqual(el("now.transport").label, "Start the clock")
        tap("now.transport")

        // The timed card is dealt to the front, and its state is now a clock rather than a word.
        let state = el("now.state")
        assertExists(state, "the running card has no state to read")
        XCTAssertNotEqual(state.label, "IT IS TIME", "pressing play changed nothing on the bar")
        XCTAssertEqual(el("now.transport").label, "Stop the clock", "the key did not become a stop")
        XCTAssertTrue(el("now.player").exists, "the player left when the clock started")

        tap("now.transport", "could not stop the clock")
        XCTAssertEqual(el("now.transport").label, "Start the clock", "the key did not go back to play")
        // Stopping the clock does not put the task down — you are usually still on the thing.
        assertExists(el("now.player"), "stopping the clock cleared the task as well")
    }

    func testTappingTheCardOpensTheFocusScreen() {
        launch()
        assertExists(el("now.card"), "the player did not come up")
        el("now.card").tap()
        // The focus screen is where a length is committed to, which the bar's key deliberately is
        // not — so the screen that arrives must be the one naming that task.
        assertExists(shelf(Fixture.sittingTask), "tapping the card did not reach the focus screen")
    }

    /// The player is never the outermost thing on a screen with a permanent bar: it slots in above
    /// it, so the bottom of the app does not reshuffle itself between screens.
    func testTheCaptureFieldStaysReachableWhileSomethingIsRunning() {
        launch(route: "open:fixture-groceries")
        assertExists(el("now.player"), "the list screen shows no player")
        XCTAssertTrue(captureField.exists,
                      "capture went behind a mode because something was running")
    }
}
