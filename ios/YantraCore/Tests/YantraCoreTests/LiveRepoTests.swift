import XCTest
@testable import YantraCore

/// The repository calls, against the real GitHub.
///
/// Listing and creating are new endpoints, and a fake cannot prove either: it answers whatever this
/// code asks it, in the shape this code expects, so a wrong path, a wrong header or a misread field
/// is invisible there and self-consistent. Everything here is read-only except
/// `testMakingOneThatAlreadyExistsIsSaidPlainly`, which asks for a name that is already taken and so
/// creates nothing.
///
///     YANTRA_LIVE_TOKEN=$(gh auth token) swift test --filter LiveRepoTests
final class LiveRepoTests: XCTestCase {

    func token() throws -> String {
        guard let t = ProcessInfo.processInfo.environment["YANTRA_LIVE_TOKEN"], !t.isEmpty else {
            throw XCTSkip("set YANTRA_LIVE_TOKEN to run the live repository tests")
        }
        return t
    }

    func testTheRepositoriesComeBackWithWhatThePickerNeeds() async throws {
        let repos = await GitHubTransport.repositories(token: try token())
        XCTAssertFalse(repos.isEmpty, "a signed-in account with no repositories at all is possible, but not this one")
        for r in repos.prefix(5) {
            XCTAssertTrue(r.slug.contains("/"), "a slug the parser can read is the whole point of listing them")
            XCTAssertNotNil(r.ref, "\(r.slug) did not parse back into a RepoRef")
        }
        // Newest first is what the picker relies on to put the likely one at the top.
        let stamps = repos.compactMap { $0.updatedAt.isEmpty ? nil : $0.updatedAt }
        XCTAssertEqual(stamps, stamps.sorted(by: >), "GitHub was asked for sort=updated")
    }

    /// The name of a repository that certainly exists is refused as `.exists` rather than as a
    /// failure — the difference between "add it instead" and "something went wrong".
    ///
    /// The name has to be one taken **on the token's own account**, because that is the only
    /// namespace `POST /user/repos` writes into. An earlier version of this test picked the first
    /// repository the token could push to, which can perfectly well be an organisation's — and a
    /// name that is taken over there is free here, so the call it expected to be refused succeeded
    /// and made a repository. Hence `viewer()` first, and the owner check, and the assertion below
    /// that fails loudly rather than quietly accepting a success.
    func testMakingOneThatAlreadyExistsIsSaidPlainly() async throws {
        let token = try token()
        guard let me = try await GitHubTransport(repo: RepoRef(owner: "x", name: "x"), token: token).viewer() else {
            throw XCTSkip("GitHub would not say who this token belongs to")
        }
        let repos = await GitHubTransport.repositories(token: token)
        guard let owned = repos.first(where: { $0.ref?.owner == me.login }) else {
            throw XCTSkip("no repository owned by \(me.login) to borrow a taken name from")
        }
        let name = owned.ref?.name ?? owned.slug
        switch await GitHubTransport.createRepo(name: name, token: token) {
        case .exists: break
        case .unauthorized: break     // a token that cannot create at all is also an answer
        case let .failed(why): XCTFail("expected a plain 'already exists', got: \(why)")
        case let .ok(ref): XCTFail("a repository was created at \(ref.slug), which this test must never do")
        }
    }
}

/// Who can push, read from the real GitHub.
///
/// Worth a live test more than most calls here: this endpoint has more ways to say no than any
/// other in the file, and the one that matters — a token that syncs perfectly well and is not
/// allowed to see the roster — answers **404**, which a fake would never think to return.
extension LiveRepoTests {

    func testTheCollaboratorListIsReadOrRefusedInTermsWeCanSay() async throws {
        let token = try token()
        let repos = await GitHubTransport.repositories(token: token)
        guard let owned = repos.first(where: { $0.canPush }), let ref = owned.ref else {
            throw XCTSkip("no repository this token can push to")
        }
        switch await GitHubTransport(repo: ref, token: token).collaborators() {
        case let .ok(logins):
            // Whoever else is on it, you are: you can push to it.
            XCTAssertFalse(logins.isEmpty, "\(ref.slug) came back with nobody able to push to it")
            print("YANTRA-PEOPLE: \(ref.slug) -> \(logins.joined(separator: ", "))")
        case let .notPermitted(code):
            // Not a failure of ours. Reading a repository's people needs a heavier permission than
            // syncing its files, and the app says so rather than looking broken.
            XCTAssertTrue(code == 403 || code == 404, "unexpected refusal code \(code)")
            print("YANTRA-PEOPLE: \(ref.slug) refused with \(code) — the app offers typing a login")
        case .unauthorized:
            XCTFail("the token was rejected outright")
        case let .failed(why):
            throw XCTSkip("could not reach GitHub: \(why)")
        }
    }
}

/// Adding somebody to a repository, against the real GitHub.
///
/// Nobody is actually invited. The call is aimed at the signed-in account itself, which GitHub
/// refuses with a 422 — and that refusal is the proof worth having: it means the request reached
/// the endpoint, with credentials it accepted, on a repository this token administers. A 403 or a
/// 404 would mean the opposite.
extension LiveRepoTests {

    func testTheInviteCallIsAcceptedEvenThoughNobodyIsInvited() async throws {
        let token = try token()
        guard let me = try await GitHubTransport(repo: RepoRef(owner: "x", name: "x"), token: token).viewer() else {
            throw XCTSkip("GitHub would not say who this token belongs to")
        }
        let mine = await GitHubTransport.repositories(token: token)
            .first { $0.ref?.owner == me.login && $0.canPush }
        guard let ref = mine?.ref else { throw XCTSkip("no repository owned by \(me.login)") }

        let transport = GitHubTransport(repo: ref, token: token)
        guard await transport.canAdminister() else {
            throw XCTSkip("this token does not administer \(ref.slug) — a GitHub App token would not")
        }

        switch await transport.invite(me.login) {
        case .notAllowed, .noSuchPerson:
            XCTFail("the invite call was refused outright on a repository this token administers")
        case let .failed(why):
            // The expected landing: GitHub will not let you invite yourself, and says so.
            print("YANTRA-INVITE: \(ref.slug) answered — \(why)")
        case let .invited(who), let .already(who):
            // Harmless — it is the account's own repository and it is already on it.
            print("YANTRA-INVITE: \(ref.slug) answered for \(who)")
        }
    }
}
