import XCTest
@testable import YantraCore

/// The device flow against the real github.com.
///
/// Every path here is reachable without anybody approving anything, which is the point: the one
/// step that needs a human is the final exchange, and everything leading to it can be — and now is
/// — checked against what GitHub actually sends rather than against what this code hopes it sends.
///
///     YANTRA_LIVE_AUTH=1 swift test --filter LiveAuthTests
///
/// Opt-in because it talks to the network. It needs no credential: asking for a device code and
/// polling an unapproved one are anonymous, and nothing here can authorise anything.
final class LiveAuthTests: XCTestCase {

    override func setUpWithError() throws {
        guard ProcessInfo.processInfo.environment["YANTRA_LIVE_AUTH"] == "1" else {
            throw XCTSkip("set YANTRA_LIVE_AUTH=1 to run the live device-flow tests")
        }
    }

    /// The registration is live and device flow is switched on for it — if either were untrue this
    /// is where sign-in would die, and it would die for everybody at once.
    func testGitHubIssuesADeviceCodeForThisApp() async throws {
        let code = try await GitHubAuth.requestCode()
        XCTAssertFalse(code.deviceCode.isEmpty)
        XCTAssertEqual(code.verificationUri, "https://github.com/login/device")
        // The shape the screen prints in a 30pt mono face; a change here is a visual bug too.
        XCTAssertEqual(code.userCode.count, 9, "the code is not ABCD-1234 shaped: \(code.userCode)")
        XCTAssertGreaterThan(code.expiresIn, 60)
        XCTAssertGreaterThan(code.interval, 0)
        print("YANTRA-LIVE-AUTH: issued \(code.userCode), \(code.expiresIn)s at \(code.interval)s")
    }

    /// The state the screen sits in for as long as somebody is typing. It has to read as "keep
    /// waiting" and never as an error, or sign-in would abandon itself mid-approval.
    func testAnUnapprovedCodeIsPendingRatherThanFailed() async throws {
        let code = try await GitHubAuth.requestCode()
        let first = await GitHubAuth.poll(code)
        XCTAssertEqual(first, .pending, "an unapproved code did not read as pending")

        // Polling again straight away is what GitHub rate-limits, and it answers with a new interval
        // rather than a refusal. Read as anything else, sign-in either spins or gives up.
        switch await GitHubAuth.poll(code) {
        case .pending: break
        case let .slowDown(interval):
            XCTAssertGreaterThan(interval, 0, "slow_down gave an unusable interval")
            print("YANTRA-LIVE-AUTH: slow_down -> \(interval)s")
        case let other: XCTFail("polling twice quickly gave \(other)")
        }
    }

    func testANonsenseDeviceCodeFailsWithGitHubsOwnWords() async {
        let fake = GitHubAuth.DeviceCode(deviceCode: "not-a-real-device-code", userCode: "XXXX-XXXX",
                                         verificationUri: "https://github.com/login/device",
                                         expiresIn: 900, interval: 5)
        guard case let .failed(why) = await GitHubAuth.poll(fake) else {
            return XCTFail("a bogus device code did not fail")
        }
        // GitHub's sentence, not its slug — the screen shows this to a person. (The sentence may
        // well quote the slug, as this one quotes device_code; what must not happen is the bare
        // token "incorrect_device_code" being printed as if it were prose.)
        XCTAssertNotEqual(why, "incorrect_device_code", "a raw error slug reached the screen")
        XCTAssertTrue(why.contains(" "), "not a sentence: \(why)")
        print("YANTRA-LIVE-AUTH: bogus code -> \(why)")
    }

    /// The distinction the renewal path turns on: a refusal must not be mistaken for being offline,
    /// or a dead sign-in is presented forever and nothing ever says to sign in again.
    func testARefusedRefreshSaysSignInAgainRatherThanCouldNotAsk() async {
        let dead = GitHubAuth.Token(accessToken: "gho_dead", refreshToken: "ghr_definitely_not_valid",
                                    expiresAt: Date().timeIntervalSince1970 - 1)
        switch await GitHubAuth.refresh(dead) {
        case let .needsSignIn(why):
            XCTAssertFalse(why.isEmpty)
            print("YANTRA-LIVE-AUTH: refused refresh -> \(why)")
        case let other:
            XCTFail("a refused refresh reported \(other) — a scrap credential would be kept and reused")
        }
    }

    func testATokenWithNoRefreshTokenIsNotEvenAskedAbout() async {
        // No network call at all: this is the common case and it must stay free.
        let plain = GitHubAuth.Token(accessToken: "gho_x", refreshToken: nil, expiresAt: nil)
        let outcome = await GitHubAuth.refresh(plain)
        XCTAssertEqual(outcome, .nothingToDo)
    }
}
