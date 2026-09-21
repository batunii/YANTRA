import XCTest

/// One account, two devices — the thing sync is for.
///
/// Driven through the app's own screens on two simulators: a phone writes a task and pushes, an iPad
/// signed in with the same credential pulls, and the task is there. Nothing here reaches into the
/// store or calls the engine directly, because the question is not whether `SyncEngine` merges — 147
/// offline tests and six live ones already answer that — but whether a person doing the ordinary
/// thing on two devices ends up with the same tasks on both.
///
/// The two halves run as separate invocations against separate destinations, phone first:
///
///     -only-testing:YantraUITests/TwoDeviceSyncTests/testAPhoneWritesATaskAndPushesIt   (iPhone)
///     -only-testing:YantraUITests/TwoDeviceSyncTests/testAnIPadSignedInTheSameWaySeesIt (iPad)
///
/// `YANTRA_LIVE_REPO` and `YANTRA_LIVE_TOKEN` come in through the test runner's environment and are
/// handed to the app as a debug-only launch argument, because the device flow needs a human and
/// "two devices agreeing" is exactly the property one device cannot check.
final class TwoDeviceSyncTests: XCTestCase {

    /// The task the phone writes and the iPad must find. Fixed, so the two halves agree without
    /// sharing anything but the repository.
    static let subject = "Ordered by the phone"

    var app: XCUIApplication!

    override func setUpWithError() throws {
        let env = ProcessInfo.processInfo.environment
        guard let repo = env["YANTRA_LIVE_REPO"], let token = env["YANTRA_LIVE_TOKEN"], !token.isEmpty else {
            throw XCTSkip("set YANTRA_LIVE_REPO and YANTRA_LIVE_TOKEN to run the two-device sync test")
        }
        continueAfterFailure = false
        app = XCUIApplication()
        // A real workspace, not the fixture: the point is what one device's own tasks look like when
        // they arrive on another.
        app.launchArguments = ["-uitest-reset", "-uitest-connect", repo, token, deviceName]
    }

    var deviceName: String {
        UIDevice.current.userInterfaceIdiom == .pad ? "ipad-sim" : "iphone-sim"
    }

    private func say(_ s: String) { print("YANTRA-2DEV: \(s)") }

    private func el(_ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// Presses Sync now and waits for the screen to say what happened, rather than sleeping.
    ///
    /// **Drops `-uitest-reset` first.** It is a launch argument, so it fires on *every* launch, not
    /// just the first — leaving it in wiped the task that had just been written and pushed a freshly
    /// seeded workspace instead, while the screen still said "Synced". Only looking in the repository
    /// caught it.
    private func syncThroughTheUI(_ expecting: String) {
        app.launchArguments.removeAll { $0 == "-uitest-reset" }
        app.launchArguments += ["-route", "github"]
        app.terminate()
        app.launch()
        let button = app.buttons["Sync now"]
        XCTAssertTrue(button.waitForExistence(timeout: 20),
                      "the GitHub screen is not signed in — the credential did not take")
        button.tap()

        // The status line is the app reporting the outcome of a real push or pull.
        let status = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Synced' OR label BEGINSWITH 'Nothing to sync' OR label BEGINSWITH 'Not synced'")).firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 60), "the sync never reported an outcome")
        say("\(expecting): \(status.label)")
        XCTAssertFalse(status.label.hasPrefix("Not synced"), "sync failed: \(status.label)")
    }

    /// The phone writes a task the ordinary way — typed into the capture field — and pushes it.
    func testAPhoneWritesATaskAndPushesIt() {
        // No route: the app opens on Today, whose quick-add writes into Inbox — the ordinary way a
        // task gets written on a phone.
        app.launch()

        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 20), "no capture field to type into")
        field.tap()
        // The return key is the field's own submit, which is what a person presses.
        field.typeText(Self.subject + "\n")

        XCTAssertTrue(app.staticTexts[Self.subject].waitForExistence(timeout: 10),
                      "the task was not written on the phone")
        say("wrote “\(Self.subject)” on the phone")

        syncThroughTheUI("phone push")
    }

    /// The iPad, signed in with the same account, finds it.
    func testAnIPadSignedInTheSameWaySeesIt() {
        syncThroughTheUI("ipad pull")

        // Back to the workspace to look for the phone's task. No reset — the pull that just
        // happened is the state under test.
        app.launchArguments.removeAll { $0 == "github" || $0 == "-route" }
        app.terminate()
        app.launch()

        let found = app.staticTexts[Self.subject]
        XCTAssertTrue(found.waitForExistence(timeout: 20),
                      "the phone's task never arrived on the iPad")
        say("the iPad sees “\(Self.subject)”")
    }
}
