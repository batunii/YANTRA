import XCTest
@testable import YantraCore

/// Keeping a sign-in usable without asking again.
///
/// A GitHub App issues user tokens that lapse after a few hours unless expiry is switched off.
/// Android already shipped the version that assumed otherwise: sync worked for an afternoon, then
/// failed with "not authorized", and only signing in again fixed it — with nothing saying why.
final class TokenRenewalTests: XCTestCase {

    func token(refresh: String? = "ghr_x", expiresIn: Double?) -> GitHubAuth.Token {
        GitHubAuth.Token(accessToken: "gho_live", refreshToken: refresh,
                         expiresAt: expiresIn.map { Date().timeIntervalSince1970 + $0 })
    }

    // MARK: when to bother

    func testATokenWithNoRefreshTokenNeverNeedsRenewing() {
        // A pasted personal token and a non-expiring App token both look like this, and neither
        // should cost a network request before every sync.
        XCTAssertFalse(token(refresh: nil, expiresIn: -10).needsRenewal)
        XCTAssertFalse(token(refresh: nil, expiresIn: nil).needsRenewal)
    }

    func testATokenWithHoursLeftIsLeftAlone() {
        XCTAssertFalse(token(expiresIn: 8 * 3600).needsRenewal)
    }

    func testATokenIsRenewedShortlyBeforeItLapsesRatherThanAfter() {
        // The phone's clock and GitHub's need not agree to the second, and a token that expires
        // mid-rebase fails far more confusingly than one renewed a few minutes early.
        XCTAssertTrue(token(expiresIn: 60).needsRenewal, "a token about to lapse was not renewed")
        XCTAssertTrue(token(expiresIn: -1).needsRenewal, "an expired token was not renewed")
        XCTAssertFalse(token(expiresIn: 600).needsRenewal, "renewed far too eagerly")
    }

    func testARefreshTokenWithNoRecordedExpiryIsRenewed() {
        // We do not know how long the access token has left. Renewing is the safe reading: the cost
        // is one request, and the cost of guessing the other way is a failed sync.
        XCTAssertTrue(token(expiresIn: nil).needsRenewal)
    }

    // MARK: what GitHub actually says

    /// These are the bodies github.com returns — captured from the live endpoint, not invented.
    func testTheRealRefusalsAreReadAsSentencesNotSlugs() {
        func described(_ json: String) -> String? {
            let obj = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
            return GitHubAuth.describe(obj)
        }
        XCTAssertEqual(described(#"{"error":"incorrect_client_credentials"}"#),
                       "This build's GitHub app is misconfigured")
        XCTAssertEqual(described(#"{"error":"device_flow_disabled"}"#),
                       "This build's GitHub app does not have device flow enabled")
        // Anything else: GitHub's own sentence, which is better than its slug.
        XCTAssertEqual(described(#"{"error":"incorrect_device_code","error_description":"The device_code provided is not valid."}"#),
                       "The device_code provided is not valid.")
        XCTAssertNil(described(#"{"access_token":"gho_x"}"#), "a success was read as a failure")
    }

    /// A rotated refresh token has to replace the one that was sent: keeping the old one makes the
    /// *next* refresh fail in exactly the way renewal exists to prevent.
    func testARenewedTokenCarriesTheNewRefreshTokenAndExpiry() throws {
        let body = #"{"access_token":"gho_new","refresh_token":"ghr_new","expires_in":28800}"#
        let t = try JSONDecoder().decode(GitHubAuth.Token.self, from: Data(body.utf8))
        XCTAssertEqual(t.accessToken, "gho_new")
        XCTAssertEqual(t.refreshToken, "ghr_new", "the rotated refresh token was dropped")
        let left = try XCTUnwrap(t.expiresAt) - Date().timeIntervalSince1970
        XCTAssertEqual(left, 28800, accuracy: 5, "the expiry did not survive")
        XCTAssertFalse(t.needsRenewal, "a token issued seconds ago wants renewing already")
    }

    func testATokenSurvivesBeingStoredAndReadBack() throws {
        // It goes through the Keychain as JSON, so a field lost in encoding is a sign-in lost on the
        // next launch — and the expiry is the field with no second source.
        let before = token(expiresIn: 3600)
        let after = try JSONDecoder().decode(GitHubAuth.Token.self, from: JSONEncoder().encode(before))
        XCTAssertEqual(after, before)
    }

    func testAnErrorBodyIsNotMistakenForAToken() {
        // The whole renewal path turns on this: an error decoded as a token would store a
        // credential with no access token in it.
        let body = Data(#"{"error":"bad_refresh_token","error_description":"nope"}"#.utf8)
        XCTAssertNil(try? JSONDecoder().decode(GitHubAuth.Token.self, from: body))
    }
}
