import XCTest
@testable import YantraCore

/// One pass per workspace, and one token renewal however many ask.
final class SyncGateTests: XCTestCase {

    final class Box: @unchecked Sendable {
        private let lock = NSLock()
        private var _running = 0, _most = 0, _order: [String] = []
        func enter(_ tag: String) { lock.lock(); _running += 1; _most = max(_most, _running); _order.append(tag); lock.unlock() }
        func leave() { lock.lock(); _running -= 1; lock.unlock() }
        var most: Int { lock.lock(); defer { lock.unlock() }; return _most }
        var order: [String] { lock.lock(); defer { lock.unlock() }; return _order }
    }

    func testPassesOnOneWorkspaceNeverOverlap() async {
        let gate = SyncGate(), box = Box()
        await withTaskGroup(of: Void.self) { group in
            for i in 0..<8 {
                group.addTask {
                    await gate.run("ws") {
                        box.enter("\(i)")
                        try? await Task.sleep(nanoseconds: 2_000_000)
                        box.leave()
                    }
                }
            }
        }
        XCTAssertEqual(box.most, 1, "two passes held one workspace at once")
        XCTAssertEqual(box.order.count, 8, "a pass was dropped rather than queued")
        XCTAssertFalse(gate.isBusy("ws"))
    }

    func testDifferentWorkspacesDoNotWaitForEachOther() async {
        let gate = SyncGate(), box = Box()
        await withTaskGroup(of: Void.self) { group in
            for key in ["a", "b", "c"] {
                group.addTask {
                    await gate.run(key) {
                        box.enter(key)
                        try? await Task.sleep(nanoseconds: 30_000_000)
                        box.leave()
                    }
                }
            }
        }
        XCTAssertGreaterThan(box.most, 1)
    }

    /// B2. A flush asked for while a pass runs happens after it, in order — not dropped.
    func testAWaiterRunsAfterTheHolderInTheOrderItAsked() async {
        let gate = SyncGate(), box = Box()
        let holding = expectation(description: "holding")
        let first = Task {
            await gate.run("ws") {
                box.enter("running"); holding.fulfill()
                try? await Task.sleep(nanoseconds: 50_000_000)
                box.leave()
            }
        }
        await fulfillment(of: [holding], timeout: 2)
        let second = Task { await gate.run("ws") { box.enter("leaving app"); box.leave() } }
        try? await Task.sleep(nanoseconds: 5_000_000)
        let third = Task { await gate.run("ws") { box.enter("asked to sync"); box.leave() } }
        _ = await (first.value, second.value, third.value)
        XCTAssertEqual(box.order, ["running", "leaving app", "asked to sync"])
    }

    func testAThrowingPassStillLetsGo() async {
        struct Boom: Error {}
        let gate = SyncGate()
        do { try await gate.run("ws") { throw Boom() } } catch {}
        XCTAssertFalse(gate.isBusy("ws"))
        let ran = await gate.run("ws") { true }
        XCTAssertTrue(ran)
    }

    // MARK: renewal

    final class Vault: @unchecked Sendable {
        private let lock = NSLock()
        private var _token: GitHubAuth.Token?
        private var _refreshes = 0
        init(_ t: GitHubAuth.Token?) { _token = t }
        var token: GitHubAuth.Token? {
            get { lock.lock(); defer { lock.unlock() }; return _token }
            set { lock.lock(); _token = newValue; lock.unlock() }
        }
        func counted() -> Int { lock.lock(); _refreshes += 1; defer { lock.unlock() }; return _refreshes }
        var refreshes: Int { lock.lock(); defer { lock.unlock() }; return _refreshes }
    }

    func lapsing(_ access: String, _ refresh: String) -> GitHubAuth.Token {
        GitHubAuth.Token(accessToken: access, refreshToken: refresh, expiresAt: Date().timeIntervalSince1970 - 1)
    }
    func fresh(_ access: String, _ refresh: String) -> GitHubAuth.Token {
        GitHubAuth.Token(accessToken: access, refreshToken: refresh, expiresAt: Date().timeIntervalSince1970 + 8 * 3600)
    }

    /// A3. GitHub rotates the refresh token on each use. Five callers renewing at once spent it five
    /// times; the four that lost were refused, and a refusal clears the credential — signed out a
    /// moment after the first renewal had fixed everything.
    func testConcurrentCallersShareOneRenewal() async {
        let vault = Vault(lapsing("gho_old", "ghr_1"))
        let keeper = TokenKeeper(load: { vault.token }, save: { vault.token = $0 }, refresh: { [self] t in
            let n = vault.counted()
            try? await Task.sleep(nanoseconds: 20_000_000)
            // GitHub's behaviour: a refresh token works once.
            return n == 1 && t.refreshToken == "ghr_1" ? .renewed(fresh("gho_new", "ghr_2")) : .needsSignIn("The refresh token passed is incorrect or expired.")
        })
        let answers = await withTaskGroup(of: TokenKeeper.Outcome.self) { group in
            for _ in 0..<5 { group.addTask { await keeper.credential() } }
            var out: [TokenKeeper.Outcome] = []
            for await a in group { out.append(a) }
            return out
        }
        XCTAssertEqual(vault.refreshes, 1)
        XCTAssertEqual(answers, Array(repeating: .ok("gho_new"), count: 5))
        XCTAssertEqual(vault.token?.refreshToken, "ghr_2")
    }

    /// A refusal is about the token that was sent. If a sign-in replaced it meanwhile, the new one stays.
    func testARefusalDoesNotClearATokenThatReplacedTheRefusedOne() async {
        let vault = Vault(lapsing("gho_old", "ghr_1"))
        let keeper = TokenKeeper(load: { vault.token }, save: { vault.token = $0 }, refresh: { [self] _ in
            vault.token = fresh("gho_signed_in_again", "ghr_9")      // the person signed in meanwhile
            return .needsSignIn("The refresh token passed is incorrect or expired.")
        })
        let answer = await keeper.credential()
        XCTAssertEqual(answer, .ok("gho_signed_in_again"))
        XCTAssertEqual(vault.token?.accessToken, "gho_signed_in_again")
    }

    func testARefusalOfTheStoredTokenSignsOut() async {
        let vault = Vault(lapsing("gho_old", "ghr_1"))
        let keeper = TokenKeeper(load: { vault.token }, save: { vault.token = $0 }, refresh: { _ in .needsSignIn("bad_refresh_token") })
        let answer = await keeper.credential()
        XCTAssertEqual(answer, .needsSignIn("bad_refresh_token"))
        XCTAssertNil(vault.token)
    }

    /// A tunnel must not sign anybody out.
    func testCouldNotAskChangesNothing() async {
        let old = lapsing("gho_old", "ghr_1")
        let vault = Vault(old)
        let keeper = TokenKeeper(load: { vault.token }, save: { vault.token = $0 }, refresh: { _ in .couldNotAsk })
        let answer = await keeper.credential()
        XCTAssertEqual(answer, .ok("gho_old"))
        XCTAssertEqual(vault.token, old)
    }

    func testATokenWithTimeLeftCostsNoRequest() async {
        let vault = Vault(fresh("gho_live", "ghr_1"))
        let keeper = TokenKeeper(load: { vault.token }, save: { vault.token = $0 }, refresh: { _ in _ = vault.counted(); return .couldNotAsk })
        let answer = await keeper.credential()
        XCTAssertEqual(answer, .ok("gho_live"))
        XCTAssertEqual(vault.refreshes, 0)
    }

    /// A caller that is cancelled does not cancel the renewal it is waiting on: the refresh token may
    /// already have been spent at GitHub, and dropping the answer loses the one that replaced it.
    func testACancelledCallerDoesNotAbandonTheRenewal() async {
        let vault = Vault(lapsing("gho_old", "ghr_1"))
        let keeper = TokenKeeper(load: { vault.token }, save: { vault.token = $0 }, refresh: { [self] _ in
            try? await Task.sleep(nanoseconds: 30_000_000)
            return .renewed(fresh("gho_new", "ghr_2"))
        })
        let caller = Task { await keeper.credential() }
        try? await Task.sleep(nanoseconds: 5_000_000)
        caller.cancel()
        _ = await caller.value
        XCTAssertEqual(vault.token?.refreshToken, "ghr_2")
    }
}

/// The lock the writer and sync share. flock excludes between open file descriptions, so two
/// `WorkspaceLock`s — as two processes would hold — exclude each other, and it lives beside the
/// workspace rather than in it.
final class WorkspaceLockTests: XCTestCase {
    func testTwoLocksOnOneWorkspaceExcludeEachOther() {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("lock-\(UUID())/ws")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let a = WorkspaceLock(root: root), b = WorkspaceLock(root: root)
        XCTAssertEqual(a.file.deletingLastPathComponent().standardizedFileURL, root.deletingLastPathComponent().standardizedFileURL,
                       "the lock file must sit beside the workspace, never inside it where it would be pushed")
        a.lock()
        let got = expectation(description: "b acquired")
        let released = NSLock(); var aReleased = false
        DispatchQueue.global().async {
            b.lock()
            released.lock(); XCTAssertTrue(aReleased, "b got the lock while a held it"); released.unlock()
            b.unlock()
            got.fulfill()
        }
        Thread.sleep(forTimeInterval: 0.05)
        released.lock(); aReleased = true; released.unlock()
        a.unlock()
        wait(for: [got], timeout: 2)
    }
}
