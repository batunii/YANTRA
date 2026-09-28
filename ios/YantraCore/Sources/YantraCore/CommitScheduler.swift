import Foundation

/// When a batch of changes has waited long enough — `CommitPolicy.kt`, number for number.
///
/// Pure on purpose. The cadence is the part with judgement in it and the part most likely to be
/// argued about later, so it is a function of numbers rather than something tangled up in tasks and
/// wall clocks. `CommitScheduler` owns the timers; this owns the opinion.
///
/// The shape matters more than the numbers, and the shape is: **what someone else might be waiting
/// for goes now, and everything else waits until you have stopped.** Completing a task is how a
/// collaborator learns not to start it. A title being typed is not — and a commit per keystroke would
/// bury the history under a thousand one-character diffs.
public enum CommitPolicy {
    /// Long enough that typing a sentence is one commit, short enough to feel prompt.
    public static let editQuietMs: Int64 = 20_000
    /// Nothing waits longer than this, however continuously you type.
    public static let editMaxAgeMs: Int64 = 15 * 60_000
    /// Strokes come in bursts; this is roughly "you have stopped drawing".
    public static let inkQuietMs: Int64 = 8_000
    /// A long drawing session still lands in the repo before it is forgotten.
    public static let inkMaxAgeMs: Int64 = 20 * 60_000

    public struct Pending: Equatable, Sendable {
        public var edits = 0, ink = 0
        public var oldestAt: Int64 = 0, lastAt: Int64 = 0
        public init() {}
        public var isEmpty: Bool { edits == 0 && ink == 0 }

        /// A structural change counts nothing but still moves the clocks, exactly as in Kotlin.
        public func plus(_ change: WorkspaceWriter.Change, at: Int64) -> Pending {
            var p = self
            p.oldestAt = isEmpty ? at : oldestAt
            p.lastAt = at
            switch change {
            case .edit: p.edits += 1
            case .ink: p.ink += 1
            case .structural: break
            }
            return p
        }
    }

    /// Why this batch should be committed now, or nil to keep waiting. The reason ends up in the
    /// commit message, so the history says why each commit exists.
    public static func reasonToFlush(_ p: Pending, now: Int64, leavingApp: Bool = false) -> String? {
        if p.isEmpty { return nil }
        if leavingApp { return "app went to the background" }
        let quiet = now - p.lastAt, age = now - p.oldestAt
        if p.ink > 0 {
            if quiet >= inkQuietMs { return "finished drawing (\(p.ink) strokes)" }
            if age >= inkMaxAgeMs { return "a long drawing session (\(p.ink) strokes)" }
        }
        if p.edits > 0 {
            if quiet >= editQuietMs { return "quiet after \(p.edits) edits" }
            if age >= editMaxAgeMs { return "\(p.edits) edits, oldest is overdue" }
        }
        return nil
    }

    /// How long to wait before asking again; nil when nothing is pending.
    public static func nextCheckDelay(_ p: Pending, now: Int64) -> Int64? {
        if p.isEmpty { return nil }
        var candidates: [Int64] = []
        if p.ink > 0 { candidates += [p.lastAt + inkQuietMs - now, p.oldestAt + inkMaxAgeMs - now] }
        if p.edits > 0 { candidates += [p.lastAt + editQuietMs - now, p.oldestAt + editMaxAgeMs - now] }
        return candidates.filter { $0 > 0 }.min() ?? 0
    }

    /// `12 edits · quiet after 12 edits` — a commit message that says what it is.
    public static func commitMessage(_ reason: String, _ p: Pending) -> String {
        var parts: [String] = []
        if p.edits > 0 { parts.append("\(p.edits) edit\(p.edits == 1 ? "" : "s")") }
        if p.ink > 0 { parts.append("\(p.ink) stroke\(p.ink == 1 ? "" : "s")") }
        return parts.isEmpty ? reason : parts.joined(separator: " · ") + " · " + reason
    }
}

/// Turns a stream of edits into a sensible number of commits — `CommitScheduler.kt`.
///
/// Every write already reaches the file immediately; none of this changes that. What this decides is
/// when those files become a *commit*, which is a different question with a different answer: the
/// file has to be right the instant you stop typing, but the history does not have to record every
/// keystroke that got it there. A structural change goes straight out because someone else may be
/// waiting to see it; everything else waits for you to stop.
public final class CommitScheduler: @unchecked Sendable {
    public typealias Flush = @Sendable (_ message: String) async -> SyncResult

    private let flushBody: Flush
    private let now: @Sendable () -> Int64
    private let sleep: @Sendable (Int64) async -> Void
    private let lock = NSLock()
    private var pending = CommitPolicy.Pending()
    private var timer: Task<Void, Never>?
    private var generation = 0

    public init(flush: @escaping Flush,
                now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
                sleep: @escaping @Sendable (Int64) async -> Void = { ms in try? await Task.sleep(nanoseconds: UInt64(max(ms, 0)) * 1_000_000) }) {
        self.flushBody = flush; self.now = now; self.sleep = sleep
    }

    /// Records a change and decides what to do about it. Cheap to call from inside a write: it never
    /// syncs on the caller's thread, so a keystroke is not waiting on the network.
    public func record(_ change: WorkspaceWriter.Change) {
        add(change)
        if change == .structural {
            Task { _ = await flush("a task was created, finished or removed") }
        } else {
            arm()
        }
    }

    /// What is waiting, for a caller that wants to know.
    public var waiting: CommitPolicy.Pending { lock.lock(); defer { lock.unlock() }; return pending }

    /// Commits and syncs whatever is pending, now, with the batch's counts in the message.
    @discardableResult
    public func flush(_ reason: String) async -> SyncResult {
        let batch = take()
        return await flushBody(CommitPolicy.commitMessage(reason, batch))
    }

    private func add(_ change: WorkspaceWriter.Change) {
        lock.lock(); defer { lock.unlock() }
        pending = pending.plus(change, at: now())
    }

    private func take() -> CommitPolicy.Pending {
        lock.lock(); defer { lock.unlock() }
        let p = pending
        pending = CommitPolicy.Pending()
        timer?.cancel(); timer = nil
        generation += 1
        return p
    }

    /// Re-armed on every edit rather than left running, so a burst of typing keeps pushing the
    /// deadline out instead of committing halfway through a sentence.
    private func arm() {
        lock.lock()
        timer?.cancel()
        generation += 1
        let mine = generation
        timer = Task { [weak self] in
            while let self, !Task.isCancelled {
                guard let answer = self.check(mine) else { return }
                if let reason = answer.1 {
                    // In a task of its own. `flush` cancels the timer, and this *is* the timer: a pass
                    // run from here would be cancelled by its own start, and every request in it
                    // would fail as though the network had gone.
                    Task { _ = await self.flush(reason) }
                    return
                }
                guard let wait = answer.0 else { return }
                await self.sleep(max(wait, 50))
            }
        }
        lock.unlock()
    }

    /// The policy's answer, or nil when this timer has been replaced.
    private func check(_ gen: Int) -> (Int64?, String?)? {
        lock.lock(); defer { lock.unlock() }
        guard gen == generation else { return nil }
        let t = now()
        return (CommitPolicy.nextCheckDelay(pending, now: t), CommitPolicy.reasonToFlush(pending, now: t))
    }
}
