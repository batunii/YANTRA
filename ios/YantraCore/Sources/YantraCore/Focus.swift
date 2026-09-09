import Foundation

/// How a session ended. Strings, because the log is append-only and an older build must degrade
/// an unknown value to "some session happened".
public enum FocusOutcome {
    public static let ranOut = "ran_out", stopped = "stopped", interrupted = "interrupted", lost = "lost", discarded = "discarded"
    /// A session under this that did not reach a target is a mis-tap.
    public static let minKeptSecs = 60
    public static func countsAsTime(_ o: String) -> Bool { o != discarded }
    public static func wouldBeKept(elapsed: Int, planned: Int) -> Bool { elapsed >= minKeptSecs || (planned >= 1 && planned <= elapsed) }
    public static func keptItsPromise(_ outcome: String, planned: Int) -> Bool { planned > 0 && outcome == ranOut }
}

/// One row of the ledger — `focus_session`. Written once at start and again at end; the reader keeps
/// the last line per id.
public struct FocusSession: Identifiable, Equatable, Sendable {
    public var id: String
    public var nodeId: String
    public var startedAt: Int64
    public var endedAt: Int64?
    /// 0 means open (stopwatch).
    public var plannedSecs: Int
    public var actualSecs: Int?
    public var outcome: String

    public init(id: String, nodeId: String, startedAt: Int64, endedAt: Int64? = nil, plannedSecs: Int, actualSecs: Int? = nil, outcome: String = FocusOutcome.ranOut) {
        self.id = id; self.nodeId = nodeId; self.startedAt = startedAt; self.endedAt = endedAt; self.plannedSecs = plannedSecs; self.actualSecs = actualSecs; self.outcome = outcome
    }

    public var counts: Bool { actualSecs != nil && FocusOutcome.countsAsTime(outcome) }
    public var isOpen: Bool { plannedSecs <= 0 }

    /// The TSV line: id, node, started, ended|"", planned, actual|"", ran_out flag, outcome.
    public var line: String {
        [id, nodeId, String(startedAt), endedAt.map(String.init) ?? "", String(plannedSecs), actualSecs.map(String.init) ?? "",
         outcome == FocusOutcome.ranOut ? "1" : "0", outcome].joined(separator: "\t")
    }

    /// Reads a line; needs at least seven fields, the eighth is optional.
    public static func parse(_ line: String) -> FocusSession? {
        let f = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
        guard f.count >= 7, let started = Int64(f[2]), let planned = Int(f[4]) else { return nil }
        let outcome = f.count >= 8 && !f[7].isEmpty ? f[7] : (f[6] == "1" ? FocusOutcome.ranOut : FocusOutcome.stopped)
        return FocusSession(id: f[0], nodeId: f[1], startedAt: started, endedAt: Int64(f[3]), plannedSecs: planned, actualSecs: Int(f[5]), outcome: outcome)
    }

    /// `yyyy-MM` of the start, in the system zone — the log file the line goes to.
    public var monthKey: String {
        let c = Calendar.current.dateComponents([.year, .month], from: Date(timeIntervalSince1970: TimeInterval(startedAt) / 1000))
        return String(format: "%04d-%02d", c.year!, c.month!)
    }
}

/// The ledger over a store: last line per id wins.
public struct FocusLedger {
    public static func read(_ store: WorkspaceStore) -> [FocusSession] {
        var byId: [String: FocusSession] = [:]
        var order: [String] = []
        for line in store.readFocusLines() {
            guard let s = FocusSession.parse(line) else { continue }
            if byId[s.id] == nil { order.append(s.id) }
            byId[s.id] = s
        }
        return order.compactMap { byId[$0] }
    }

    /// Ends a session, downgrading a mis-tap to `discarded` so the row closes either way.
    public static func settle(_ s: FocusSession, actualSecs: Int, outcome: String, now: Int64) -> FocusSession {
        var x = s
        x.endedAt = now
        x.actualSecs = actualSecs
        x.outcome = (actualSecs < FocusOutcome.minKeptSecs && outcome != FocusOutcome.ranOut) ? FocusOutcome.discarded : outcome
        return x
    }
}

/// `"1h 5m"` / `"5m"` / `"30s"` — reported totals, never the live instrument.
public func durationLabel(_ totalSecs: Int) -> String {
    let h = totalSecs / 3600, m = (totalSecs % 3600) / 60
    if h > 0 { return "\(h)h \(m)m" }
    if m > 0 { return "\(m)m" }
    return "\(totalSecs)s"
}
