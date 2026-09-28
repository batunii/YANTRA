import XCTest
@testable import YantraCore

/// Commit timing — `CommitPolicy.kt` and `CommitScheduler.kt` (appendix 03 §3.1).
final class CommitSchedulerTests: XCTestCase {

    typealias P = CommitPolicy

    func pending(_ changes: [(WorkspaceWriter.Change, Int64)]) -> P.Pending {
        changes.reduce(P.Pending()) { $0.plus($1.0, at: $1.1) }
    }

    // MARK: the policy

    func testNothingPendingNeverFlushes() {
        XCTAssertNil(P.reasonToFlush(P.Pending(), now: 1_000_000, leavingApp: true))
        XCTAssertNil(P.nextCheckDelay(P.Pending(), now: 0))
    }

    func testTypingWaitsForTwentyQuietSeconds() {
        let p = pending([(.edit, 0), (.edit, 5_000)])
        XCTAssertNil(P.reasonToFlush(p, now: 24_999))
        XCTAssertEqual(P.reasonToFlush(p, now: 25_000), "quiet after 2 edits")
        XCTAssertEqual(P.nextCheckDelay(p, now: 10_000), 15_000)
    }

    func testContinuousTypingStillGoesAfterFifteenMinutes() {
        var p = P.Pending()
        var t: Int64 = 0
        while t < 15 * 60_000 { p = p.plus(.edit, at: t); t += 10_000 }
        XCTAssertEqual(P.reasonToFlush(p, now: 15 * 60_000), "\(p.edits) edits, oldest is overdue")
    }

    func testDrawingWaitsForEightSeconds() {
        let p = pending([(.ink, 0), (.ink, 1_000), (.edit, 1_000)])
        XCTAssertNil(P.reasonToFlush(p, now: 8_999))
        XCTAssertEqual(P.reasonToFlush(p, now: 9_000), "finished drawing (2 strokes)")
    }

    func testALongDrawingSessionGoesAfterTwentyMinutes() {
        var p = P.Pending()
        var t: Int64 = 0
        while t <= 20 * 60_000 { p = p.plus(.ink, at: t); t += 5_000 }
        XCTAssertEqual(P.reasonToFlush(p, now: 20 * 60_000), "a long drawing session (\(p.ink) strokes)")
    }

    func testLeavingTheAppFlushesAnythingPending() {
        XCTAssertEqual(P.reasonToFlush(pending([(.edit, 0)]), now: 1, leavingApp: true), "app went to the background")
    }

    /// A structural change counts nothing but starts the clocks, as in Kotlin.
    func testAStructuralChangeCountsNothing() {
        let p = pending([(.structural, 7)])
        XCTAssertEqual(p.edits + p.ink, 0)
        XCTAssertTrue(p.isEmpty)
        XCTAssertEqual(p.oldestAt, 7)
    }

    func testCommitMessagesSayWhatTheyAre() {
        XCTAssertEqual(P.commitMessage("quiet after 12 edits", pending(Array(repeating: (.edit, 0), count: 12))), "12 edits · quiet after 12 edits")
        XCTAssertEqual(P.commitMessage("x", pending([(.edit, 0), (.ink, 0)])), "1 edit · 1 stroke · x")
        XCTAssertEqual(P.commitMessage("asked to sync", P.Pending()), "asked to sync")
    }

    // MARK: the scheduler

    final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var t: Int64 = 0
        var now: Int64 { lock.lock(); defer { lock.unlock() }; return t }
        func advance(_ ms: Int64) { lock.lock(); t += ms; lock.unlock() }
    }

    final class Flushes: @unchecked Sendable {
        private let lock = NSLock()
        private var messages: [String] = []
        let arrived: XCTestExpectation?
        init(_ e: XCTestExpectation? = nil) { arrived = e }
        func add(_ m: String) { lock.lock(); messages.append(m); lock.unlock(); arrived?.fulfill() }
        var all: [String] { lock.lock(); defer { lock.unlock() }; return messages }
    }

    func testAFinishedTaskGoesStraightAway() async {
        let e = expectation(description: "flushed")
        let flushes = Flushes(e), clock = Clock()
        let s = CommitScheduler(flush: { flushes.add($0); return SyncResult() }, now: { clock.now }, sleep: { _ in })
        s.record(.edit)
        s.record(.structural)
        await fulfillment(of: [e], timeout: 2)
        XCTAssertEqual(flushes.all, ["1 edit · a task was created, finished or removed"])
        XCTAssertTrue(s.waiting.isEmpty)
    }

    /// Each edit pushes the deadline out; the batch goes once, 20 s after the last one, with its count.
    func testABurstOfTypingIsOneCommit() async {
        let e = expectation(description: "flushed")
        let flushes = Flushes(e), clock = Clock()
        let s = CommitScheduler(flush: { flushes.add($0); return SyncResult() }, now: { clock.now },
                                sleep: { ms in clock.advance(ms); await Task.yield() })
        s.record(.edit); s.record(.edit); s.record(.edit)
        await fulfillment(of: [e], timeout: 2)
        XCTAssertEqual(flushes.all, ["3 edits · quiet after 3 edits"])
        XCTAssertGreaterThanOrEqual(clock.now, CommitPolicy.editQuietMs)
    }

    /// An explicit flush — Sync now, leaving the app, pull-to-sync — carries what was waiting.
    func testAnAskedForSyncCarriesThePendingEdits() async {
        let flushes = Flushes(), clock = Clock()
        let s = CommitScheduler(flush: { flushes.add($0); return SyncResult() }, now: { clock.now },
                                sleep: { _ in try? await Task.sleep(nanoseconds: 10_000_000_000) })
        s.record(.ink); s.record(.edit)
        await s.flush("asked to sync")
        XCTAssertEqual(flushes.all, ["1 edit · 1 stroke · asked to sync"])
        XCTAssertTrue(s.waiting.isEmpty)
    }
}
