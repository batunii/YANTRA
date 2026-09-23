import XCTest
@testable import YantraCore

/// What an invitation actually contains, and what of it is worth showing.
final class MeetingTextTests: XCTestCase {

    // MARK: the call you came for

    func testTheLocationIsPreferredOverTheDescription() {
        // A provider that knows the meeting is a video call puts it in the location. The
        // description is where it is one line among thirty of dial-in numbers.
        let c = MeetingText.conference(location: "https://meet.google.com/abc-defg-hij",
                                       description: "Or join on https://zoom.us/j/999")
        XCTAssertEqual(c?.name, "Google Meet")
        XCTAssertEqual(c?.url, "https://meet.google.com/abc-defg-hij")
    }

    func testTheKnownServicesAreNamed() {
        XCTAssertEqual(MeetingText.hostName("https://zoom.us/j/123"), "Zoom")
        XCTAssertEqual(MeetingText.hostName("https://acme.zoom.us/j/123"), "Zoom")
        XCTAssertEqual(MeetingText.hostName("https://teams.microsoft.com/l/meetup-join/x"), "Microsoft Teams")
        XCTAssertEqual(MeetingText.hostName("https://whereby.com/yantra"), "Whereby")
        XCTAssertEqual(MeetingText.hostName("https://acme.slack.com/huddle/T1/C2"), "Slack huddle")
    }

    /// "Join meeting" on a link to a shared document is worse than no button at all, because it is
    /// a promise about what pressing it will do.
    func testAnOrdinaryLinkIsNotACall() {
        XCTAssertNil(MeetingText.hostName("https://docs.google.com/document/d/1/edit"))
        XCTAssertNil(MeetingText.conference(location: "Meeting room 2",
                                            description: "Agenda: https://example.com/agenda"))
        // Slack without a huddle is a message, not a call.
        XCTAssertNil(MeetingText.hostName("https://acme.slack.com/archives/C123"))
    }

    // MARK: links

    func testLinksKeepTheirOrderAndLoseTheirPunctuation() {
        let text = "See https://example.com/a, then https://example.com/b. Again https://example.com/a"
        XCTAssertEqual(MeetingText.links(in: text), ["https://example.com/a", "https://example.com/b"])
    }

    func testProseIsNotALink() {
        XCTAssertTrue(MeetingText.links(in: "No links here at all.").isEmpty)
        XCTAssertTrue(MeetingText.links(in: nil).isEmpty)
    }

    // MARK: the description

    /// The common case, and the one that must not be damaged.
    func testPlainTextPassesThroughUntouched() {
        let plain = "Bring the numbers.\n\nRoom 2."
        XCTAssertEqual(MeetingText.readable(plain), plain)
    }

    func testGooglesHtmlBecomesSomethingReadable() {
        let raw = "<div>Agenda:<br><ul><li>Numbers</li><li>Plan &amp; risks</li></ul></div>"
        let out = MeetingText.readable(raw)
        XCTAssertTrue(out.contains("Agenda:"), out)
        XCTAssertTrue(out.contains("\u{2022} Numbers"), out)
        XCTAssertTrue(out.contains("Plan & risks"), "the entity was not unwrapped: \(out)")
        XCTAssertFalse(out.contains("<"), "a tag survived: \(out)")
    }

    /// The run of blank lines every invitation puts before its legal boilerplate.
    ///
    /// Only in text that was HTML. Plain text is returned as it was written — the early return is
    /// deliberate and matches Android: whoever typed three blank lines meant them, and a tidy-up
    /// that rewrites what a person wrote is worse than the gap it closes.
    func testTheBoilerplateSeparatorIsCollapsedInHtml() {
        XCTAssertEqual(MeetingText.readable("One<br><br><br><br><br>Two"), "One\n\nTwo")
        let typed = "One\n\n\n\n\nTwo"
        XCTAssertEqual(MeetingText.readable(typed), typed, "plain text was rewritten")
    }

    func testNothingIsAnEmptyString() {
        XCTAssertEqual(MeetingText.readable(nil), "")
        XCTAssertEqual(MeetingText.readable("   "), "")
    }

    func testAShortenedLinkStillSaysWhereItGoes() {
        XCTAssertEqual(MeetingText.shortURL("https://www.example.com/agenda"), "example.com/agenda")
        XCTAssertTrue(MeetingText.shortURL("https://example.com/" + String(repeating: "x", count: 80)).hasSuffix("\u{2026}"))
    }
}

/// The two ways in, and what each one may do.
///
/// Both are the device flow; the difference is the registration. These pin the facts the screens
/// and the token handling depend on — a method whose `makesRepos` or `needsInstall` drifted would
/// show a button that cannot work, or hide the install step that makes a sign-in useful at all.
final class SignInMethodTests: XCTestCase {

    func testTheTwoRegistrationsAreDistinctAndBothOffered() {
        XCTAssertEqual(GitHubAuth.offered().count, 2, "a build with both ids offers both")
        XCTAssertNotEqual(GitHubAuth.Method.full.clientId, GitHubAuth.Method.restricted.clientId,
                          "these are two separate registrations on GitHub")
        XCTAssertTrue(GitHubAuth.Method.allCases.allSatisfy(\.configured))
    }

    /// Only `repo` can `POST /user/repos`; a GitHub App's permissions are fixed at registration, so
    /// it has to send the person to GitHub's own form.
    func testOnlyTheOAuthAppCanMakeRepositories() {
        XCTAssertTrue(GitHubAuth.Method.full.makesRepos)
        XCTAssertFalse(GitHubAuth.Method.restricted.makesRepos)
        XCTAssertEqual(GitHubAuth.Method.full.scope, "repo", "the scope is what the limit counts, and what creates")
        XCTAssertEqual(GitHubAuth.Method.restricted.scope, "", "an App asks for nothing here")
    }

    /// A GitHub App reaches nothing until it is installed somewhere — authenticating and seeing
    /// nothing is the most confusing state to leave somebody in, so the screen says it.
    func testOnlyTheAppNeedsInstalling() {
        XCTAssertTrue(GitHubAuth.Method.restricted.needsInstall)
        XCTAssertFalse(GitHubAuth.Method.full.needsInstall)
    }

    /// Each registration is revoked on its own application page.
    func testRevokingPointsAtTheRegistrationThatIssuedTheToken() {
        XCTAssertTrue(GitHubAuth.revokeURL(.full).absoluteString.hasSuffix(GitHubAuth.Method.full.clientId))
        XCTAssertTrue(GitHubAuth.revokeURL(.restricted).absoluteString.hasSuffix(GitHubAuth.Method.restricted.clientId))
    }
}

/// The unit ink is stored in, which has to mean the same thing on both platforms.
final class DocumentUnitsTests: XCTestCase {

    /// The numbers Android fixes. A page that is not 1000 du wide, or not A4-proportioned, is a page
    /// whose coordinates mean something different on the other app.
    func testThePageIsAThousandUnitsAcrossAndA4Shaped() {
        XCTAssertEqual(DocumentUnits.pageWidth, 1000)
        XCTAssertEqual(DocumentUnits.pageRatio, 2.0.squareRoot(), accuracy: 0.000_001)
        XCTAssertEqual(DocumentUnits.pageHeight, 1414.2135, accuracy: 0.001)
    }

    /// The same stroke, drawn on a phone and on a tablet, is the same position on the page — which
    /// is the entire reason for the unit.
    func testTheSamePlaceOnTwoSizesOfGlassIsTheSameNumber() {
        let onAPhone = DocumentUnits.toUnits(195, canvasWidth: 390)   // halfway across
        let onATablet = DocumentUnits.toUnits(512, canvasWidth: 1024) // halfway across
        XCTAssertEqual(onAPhone, 500, accuracy: 0.001)
        XCTAssertEqual(onATablet, 500, accuracy: 0.001)
    }

    func testPointsAndUnitsAreEachOthersInverse() {
        for width in [390.0, 744.0, 1024.0] {
            let there = DocumentUnits.toUnits(123.4, canvasWidth: width)
            XCTAssertEqual(DocumentUnits.toPoints(there, canvasWidth: width), 123.4, accuracy: 0.000_1)
        }
    }

    /// A canvas with no width yet must not divide by zero and must not silently scale to nothing.
    func testACanvasWithNoWidthLeavesTheNumbersAlone() {
        XCTAssertEqual(DocumentUnits.toUnits(42, canvasWidth: 0), 42)
    }
}
