import XCTest
@testable import YantraCore

/// `GitHubTransport` against answers in the shapes GitHub documents and actually sends.
///
/// `FakeRemote` in `SyncEngineTests` proves the merge, but it answers in whatever shape the engine
/// expects, so a wrong assumption about GitHub stays self-consistent there (CLAUDE.md §4 has three
/// that did). These put the REST layer itself in front of GitHub's real bodies: a truncated recursive
/// tree, base64 wrapped at 60 columns, a 422 that is a race and a 422 or 403 that is not, and the
/// Contents API filling in whoever is signed in for a committer nobody named.
final class GitHubTransportTests: XCTestCase {

    /// One repository's worth of REST, answering by method and path.
    final class FakeGitHub: @unchecked Sendable {
        struct Call { let method: String; let path: String; let body: [String: Any]? }
        var calls: [Call] = []
        var route: (String, String, [String: Any]?) -> (Int, Any) = { _, _, _ in (404, ["message": "Not Found"]) }

        func transport() -> GitHubTransport {
            GitHubTransport(repo: RepoRef(owner: "o", name: "r"), token: "t") { [self] req in
                let path = String(req.url!.absoluteString.dropFirst("https://api.github.com".count))
                let body = req.httpBody.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
                calls.append(Call(method: req.httpMethod ?? "GET", path: path, body: body))
                let (code, json) = route(req.httpMethod ?? "GET", path, body)
                let data = try JSONSerialization.data(withJSONObject: json)
                return (data, HTTPURLResponse(url: req.url!, statusCode: code, httpVersion: nil, headerFields: nil)!)
            }
        }
    }

    let gh = FakeGitHub()
    let docs = "https://docs.github.com/rest/git/refs#update-a-reference"

    func entry(_ path: String, _ type: String, _ sha: String) -> [String: Any] {
        ["path": path, "mode": type == "tree" ? "040000" : "100644", "type": type, "sha": sha, "url": "https://api.github.com/x"]
    }

    /// The blob body GitHub sends: base64, wrapped with a newline every 60 characters.
    func blobBody(_ data: Data) -> [String: Any] {
        let b64 = data.base64EncodedString(options: [.lineLength64Characters, .endLineWithLineFeed])
        return ["sha": gitBlobSha(data), "size": data.count, "url": "https://api.github.com/x", "content": b64 + "\n", "encoding": "base64", "node_id": "B_x"]
    }

    // MARK: reading the tree

    /// A6. Past its limits GitHub cuts a recursive listing short and says so only in `truncated`.
    /// Read as the whole tree, every path it left out looked deleted on the remote.
    func testATruncatedTreeIsWalkedDirectoryByDirectory() async throws {
        gh.route = { [self] method, path, _ in
            switch path {
            case "/repos/o/r/git/ref/heads/yantra-tasks":
                return (200, ["ref": "refs/heads/yantra-tasks", "node_id": "R", "url": "u", "object": ["sha": "c1", "type": "commit", "url": "u"]])
            case "/repos/o/r/git/commits/c1":
                return (200, ["sha": "c1", "tree": ["sha": "root", "url": "u"], "parents": [], "message": "m"])
            case "/repos/o/r/git/trees/root?recursive=1":
                // Only part of it — and the flag.
                return (200, ["sha": "root", "url": "u", "truncated": true,
                              "tree": [entry(".gitignore", "blob", "g"), entry("pages", "tree", "tp")]])
            case "/repos/o/r/git/trees/root":
                return (200, ["sha": "root", "url": "u", "truncated": false,
                              "tree": [entry(".gitignore", "blob", "g"), entry(".yantra", "tree", "ty"), entry("pages", "tree", "tp")]])
            case "/repos/o/r/git/trees/ty":
                return (200, ["sha": "ty", "url": "u", "truncated": false, "tree": [entry("manifest.json", "blob", "m"), entry("meta", "tree", "tm")]])
            case "/repos/o/r/git/trees/tm":
                return (200, ["sha": "tm", "url": "u", "truncated": false, "tree": [entry("labels.json", "blob", "l")]])
            case "/repos/o/r/git/trees/tp":
                return (200, ["sha": "tp", "url": "u", "truncated": false, "tree": [entry("a.md", "blob", "pa"), entry("b.md", "blob", "pb")]])
            default:
                XCTFail("unexpected \(method) \(path)"); return (404, ["message": "Not Found"])
            }
        }
        let head = try await gh.transport().head(branch: "yantra-tasks")
        XCTAssertEqual(head?.tree, [".gitignore": "g", ".yantra/manifest.json": "m", ".yantra/meta/labels.json": "l",
                                    "pages/a.md": "pa", "pages/b.md": "pb"])
    }

    func testAnUntruncatedTreeIsReadInOneCall() async throws {
        gh.route = { [self] _, path, _ in
            switch path {
            case "/repos/o/r/git/ref/heads/yantra-tasks": return (200, ["object": ["sha": "c1", "type": "commit"]])
            case "/repos/o/r/git/commits/c1": return (200, ["sha": "c1", "tree": ["sha": "root"]])
            case "/repos/o/r/git/trees/root?recursive=1":
                return (200, ["sha": "root", "truncated": false, "tree": [entry("pages", "tree", "tp"), entry("pages/a.md", "blob", "pa")]])
            default: return (500, ["message": "no"])
            }
        }
        let head = try await gh.transport().head(branch: "yantra-tasks")
        XCTAssertEqual(head?.tree, ["pages/a.md": "pa"])
        XCTAssertEqual(gh.calls.count, 3)
    }

    // MARK: reading a blob

    func testABlobWrappedAtSixtyColumnsDecodes() async throws {
        let data = Data(String(repeating: "- [ ] A task with a long enough title ^t1\n", count: 20).utf8)
        let sha = gitBlobSha(data)
        gh.route = { [self] _, _, _ in (200, blobBody(data)) }
        let got = try await gh.transport().blob(sha: sha)
        XCTAssertEqual(got, data)
    }

    /// `?? Data()` wrote an empty file over the page and pushed it on the next pass.
    func testABlobThatWillNotDecodeThrowsRatherThanReadingAsEmpty() async throws {
        gh.route = { _, _, _ in (200, ["sha": "abc", "content": "!!!not base64!!!", "encoding": "base64"]) }
        do { _ = try await gh.transport().blob(sha: "abc"); XCTFail("read as something") }
        catch let e as SyncError { XCTAssertEqual(e, .corrupt("GitHub sent a file that did not match its id")) }
    }

    func testABlobThatIsNotTheOneAskedForThrows() async throws {
        let data = Data("real".utf8)
        gh.route = { [self] _, _, _ in (200, blobBody(data)) }
        do { _ = try await gh.transport().blob(sha: String(repeating: "0", count: 40)); XCTFail("accepted the wrong bytes") }
        catch let e as SyncError { XCTAssertEqual(e, .corrupt("GitHub sent a file that did not match its id")) }
    }

    // MARK: moving the branch

    /// Answers the commit-building calls, and the ref update with `ref`.
    func committing(ref: @escaping (Int) -> (Int, Any)) {
        var n = 0
        gh.route = { method, path, _ in
            switch (method, path) {
            case ("POST", "/repos/o/r/git/blobs"): return (201, ["sha": "b1", "url": "u"])
            case ("POST", "/repos/o/r/git/trees"): return (201, ["sha": "t2", "url": "u", "tree": [], "truncated": false])
            case ("POST", "/repos/o/r/git/commits"): return (201, ["sha": "c2", "tree": ["sha": "t2"], "parents": [["sha": "c1"]]])
            case ("PATCH", "/repos/o/r/git/refs/heads/yantra-tasks"): n += 1; return ref(n)
            default: return (500, ["message": "unexpected \(method) \(path)"])
            }
        }
    }

    func push() async throws -> String? {
        try await gh.transport().commit(branch: "yantra-tasks", parent: "c1", baseTree: "t1", files: ["pages/a.md": Data("x".utf8)], message: "m")
    }

    func testAMovedBranchIsARace() async throws {
        committing { _ in (422, ["message": "Update is not a fast forward", "documentation_url": self.docs, "status": "422"]) }
        let sha = try await push()
        XCTAssertNil(sha, "a non-fast-forward is the one refusal worth another round")
    }

    /// B3. Any other refusal used to read as "the branch moved" too: retried three times, then
    /// reported as a race nobody lost.
    func testAProtectedBranchIsARefusalNotARace() async throws {
        committing { _ in (422, ["message": "Protected branch update failed for refs/heads/yantra-tasks.", "documentation_url": self.docs, "status": "422"]) }
        do { _ = try await push(); XCTFail("a protected branch read as success or as a race") }
        catch let e as SyncError {
            XCTAssertEqual(e, .pushRefused("Protected branch update failed for refs/heads/yantra-tasks."))
            XCTAssertEqual(SyncEngine.readable(e), "the remote refused the push: Protected branch update failed for refs/heads/yantra-tasks.")
        }
    }

    func testATokenThatMayNotWriteIsARefusal() async throws {
        committing { _ in (403, ["message": "Resource not accessible by integration", "documentation_url": self.docs, "status": "403"]) }
        do { _ = try await push(); XCTFail("refusal went unnoticed") }
        catch let e as SyncError { XCTAssertEqual(e, .pushRefused("Resource not accessible by integration")) }
    }

    func testTheCommitIsSignedYantraAsAuthorAndCommitter() async throws {
        committing { _ in (200, ["ref": "refs/heads/yantra-tasks", "object": ["sha": "c2", "type": "commit"]]) }
        let sha = try await push()
        XCTAssertEqual(sha, "c2")
        let body = try XCTUnwrap(gh.calls.first { $0.path == "/repos/o/r/git/commits" }?.body)
        XCTAssertEqual(body["author"] as? [String: String], ["name": "Yantra", "email": "yantra@shoonya.ie"])
        XCTAssertEqual(body["committer"] as? [String: String], ["name": "Yantra", "email": "yantra@shoonya.ie"])
    }

    /// B9. The first commit into an empty repository goes through the Contents API, which records the
    /// signed-in user for any committer left out — the one commit in the history that carried a login.
    func testTheFirstCommitIntoAnEmptyRepositoryIsSignedYantra() async throws {
        var started = false
        gh.route = { method, path, _ in
            switch (method, path) {
            case ("GET", "/repos/o/r/git/ref/heads/yantra-tasks"):
                return started ? (200, ["object": ["sha": "c1", "type": "commit"]])
                    : (409, ["message": "Git Repository is empty.", "documentation_url": "https://docs.github.com/rest/git/refs#get-a-reference", "status": "409"])
            case ("PUT", "/repos/o/r/contents/.gitignore"):
                started = true
                return (201, ["content": ["path": ".gitignore", "sha": "g"], "commit": ["sha": "c1", "message": "scaffold"]])
            case ("GET", "/repos/o/r/git/commits/c1"): return (200, ["sha": "c1", "tree": ["sha": "t1"]])
            case ("GET", "/repos/o/r/git/trees/t1?recursive=1"): return (200, ["sha": "t1", "truncated": false, "tree": []])
            case ("POST", "/repos/o/r/git/blobs"): return (201, ["sha": "b1"])
            case ("POST", "/repos/o/r/git/trees"): return (201, ["sha": "t2"])
            case ("POST", "/repos/o/r/git/commits"): return (201, ["sha": "c2"])
            case ("PATCH", "/repos/o/r/git/refs/heads/yantra-tasks"): return (200, ["object": ["sha": "c2"]])
            default: return (500, ["message": "unexpected \(method) \(path)"])
            }
        }
        let sha = try await gh.transport().commit(branch: "yantra-tasks", parent: nil, baseTree: nil,
                                                   files: [".gitignore": Data("*.tmp\n".utf8), "pages/a.md": Data("a".utf8)], message: "scaffold")
        XCTAssertEqual(sha, "c2")
        let put = try XCTUnwrap(gh.calls.first { $0.method == "PUT" }?.body)
        XCTAssertEqual(put["committer"] as? [String: String], ["name": "Yantra", "email": "yantra@shoonya.ie"])
        XCTAssertEqual(put["author"] as? [String: String], ["name": "Yantra", "email": "yantra@shoonya.ie"])
    }

    func testABranchStartedByAnotherDeviceMeanwhileIsARace() async throws {
        gh.route = { method, path, _ in
            switch (method, path) {
            case ("GET", "/repos/o/r/git/ref/heads/yantra-tasks"): return (404, ["message": "Not Found"])
            case ("POST", "/repos/o/r/git/blobs"): return (201, ["sha": "b1"])
            case ("POST", "/repos/o/r/git/trees"): return (201, ["sha": "t2"])
            case ("POST", "/repos/o/r/git/commits"): return (201, ["sha": "c2"])
            case ("POST", "/repos/o/r/git/refs"): return (422, ["message": "Reference already exists", "status": "422"])
            default: return (500, ["message": "unexpected \(method) \(path)"])
            }
        }
        let sha = try await gh.transport().commit(branch: "yantra-tasks", parent: nil, baseTree: nil, files: ["a": Data("a".utf8)], message: "m")
        XCTAssertNil(sha)
    }
}

/// What a failed pass says — `SyncErrorWordsTest.kt`, for the errors iOS actually meets.
final class SyncErrorWordsTests: XCTestCase {
    func testOfflineIsNotSomethingTheReaderHasToAct() {
        XCTAssertEqual(SyncEngine.readable(URLError(.notConnectedToInternet)), SyncEngine.Words.unreachable)
        XCTAssertEqual(SyncEngine.readable(URLError(.cannotFindHost)), SyncEngine.Words.unreachable)
        XCTAssertEqual(SyncEngine.readable(URLError(.timedOut)), SyncEngine.Words.unreachable)
    }

    /// The reason is read through the wrapper: a URLSession error keeps the POSIX cause underneath.
    func testANetworkWithNoRouteSaysToReconnect() {
        let posix = NSError(domain: NSPOSIXErrorDomain, code: Int(EHOSTUNREACH))
        let wrapped = NSError(domain: NSURLErrorDomain, code: NSURLErrorCannotConnectToHost, userInfo: [NSUnderlyingErrorKey: posix])
        XCTAssertEqual(SyncEngine.readable(wrapped), SyncEngine.Words.noRoute)
    }

    func testARejectedSignInSaysToSignInAgain() {
        XCTAssertEqual(SyncEngine.readable(SyncError.http(401, "ref", message: "Bad credentials")), SyncEngine.Words.signIn)
    }

    func testAFullPhoneSaysSo() {
        XCTAssertEqual(SyncEngine.readable(NSError(domain: NSCocoaErrorDomain, code: NSFileWriteOutOfSpaceError)), SyncEngine.Words.noSpace)
    }

    func testARefusedPushGivesGitHubsReason() {
        XCTAssertEqual(SyncEngine.readable(SyncError.pushRefused("Resource not accessible by integration")),
                       "the remote refused the push: Resource not accessible by integration")
    }
}
