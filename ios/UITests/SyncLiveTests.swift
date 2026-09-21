import XCTest

/// A live GitHub sign-in, driven through the app's own screen.
///
/// Deliberately **not** part of the suite: it talks to github.com, it needs a human to approve a
/// code, and it writes to a real repository. It exists so the half of sync that the fake remote in
/// `SyncEngineTests` cannot reach — the device flow, the auth header, the request shapes — is
/// exercised at least once against the real thing rather than only against a stand-in.
///
/// Run it by name:
///     xcodebuild ... -only-testing:YantraUITests/SyncLiveTests/testSignInWithGitHub
final class SyncLiveTests: XCTestCase {

    /// Everything this test learns is printed, because the person running it has to act on the code
    /// while it waits.
    private func say(_ s: String) { print("YANTRA-SYNC: \(s)") }

    func testSignInWithGitHub() {
        let app = XCUIApplication()
        // No `-uitest`: this runs against the real workspace and the real keychain, which is the
        // point — a fixture workspace signed into a real account would prove nothing about either.
        app.launchArguments = ["-route", "github"]
        app.launch()

        let signIn = app.buttons["Sign in with GitHub"]
        XCTAssertTrue(signIn.waitForExistence(timeout: 20), "the GitHub screen did not come up")
        say("tapping sign in")
        signIn.tap()

        // Requesting the code opens GitHub, which puts Safari in front. Bring Yantra back so its
        // poll keeps running and its screen can be read.
        let code = app.staticTexts.matching(NSPredicate(format: "label MATCHES %@", "[A-Z0-9]{4}-[A-Z0-9]{4}")).firstMatch
        for _ in 0..<20 {
            if code.exists { break }
            app.activate()
            _ = code.waitForExistence(timeout: 3)
        }
        XCTAssertTrue(code.waitForExistence(timeout: 30), "no device code appeared — the request failed")
        say("CODE \(code.label)")
        say("open https://github.com/login/device and enter it")

        // Then wait for the app to notice by itself, which is the behaviour the screen promises.
        let signedIn = app.staticTexts["The name on your commits, and who a task is assigned to"]
        var waited = 0
        // GitHub's codes last fifteen minutes; waiting less than that fails a person who
        // was simply still typing rather than an app that was not listening.
        while waited < 870, !signedIn.exists {
            app.activate()
            _ = signedIn.waitForExistence(timeout: 10)
            waited += 10
            if waited % 60 == 0 { say("still waiting (\(waited)s)") }
        }
        XCTAssertTrue(signedIn.exists, "the app never noticed the approval")

        // The login is read back from GitHub with the token it was just given, so this line
        // existing means the token was accepted for a real API call.
        let name = signedIn.firstMatch
        say("SIGNED IN — \(name.exists ? "ok" : "?")")
        for t in app.staticTexts.allElementsBoundByIndex.prefix(12) where !t.label.isEmpty {
            say("screen: \(t.label)")
        }
    }
}
