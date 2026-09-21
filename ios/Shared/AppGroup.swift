import Foundation
import YantraCore

/// Where the workspace lives so the app, the widgets, the Live Activity intents and the share sheet
/// all read and write the same files — the iOS counterpart of one process owning one directory.
public enum AppGroup {
    public static let id = "group.ie.shoonya.yantra"

    public static var container: URL {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id)
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    public static var workspaceRoot: URL { container.appendingPathComponent("workspaces/local", isDirectory: true) }

    public static var defaults: UserDefaults { UserDefaults(suiteName: id) ?? .standard }

    /// The device string on commits and `device:` lines — the GitHub login once signed in, else the name.
    public static var device: String {
        defaults.string(forKey: "github_login") ?? "iphone"
    }

    /// A store + writer over the shared workspace, scaffolded and seeded on first use.
    public static func openWorkspace() -> (WorkspaceStore, WorkspaceWriter) {
        resetIfAsked()
        connectIfAsked()
        let store = WorkspaceStore(root: workspaceRoot, id: "")
        if !store.exists {
            store.scaffold(name: "Personal", now: Int64(Date().timeIntervalSince1970 * 1000))
            if CommandLine.arguments.contains("-uitest") { UITestFixture.seed(store) }
            else { WorkspaceSeeder.seed(store) }
        }
        return (store, WorkspaceWriter(store: store, device: device))
    }

    /// `-uitest-connect <owner/name> <token>` signs a simulator in without the device flow.
    ///
    /// **Debug builds only**, and deliberately so: this installs a live credential from a launch
    /// argument, which is a thing a release binary must not be able to do at all, whatever it is
    /// guarded on. `#if DEBUG` is the only guard strong enough — the code is not in the shipped
    /// binary to be reached.
    ///
    /// It exists because sync between two devices cannot otherwise be tested end to end: the device
    /// flow needs a human at github.com, and "two devices agreeing" is exactly the property that
    /// cannot be checked with one.
    private static func connectIfAsked() {
        #if DEBUG
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "-uitest-connect"), i + 2 < args.count,
              let ref = RepoRef.parse(args[i + 1]) else { return }
        SyncSettings.repo = ref
        Keychain.accountToken = GitHubAuth.Token(accessToken: args[i + 2])
        // The name on this device's commits and `device:` lines, and what makes the screen show as
        // signed in. Two devices sharing one must not happen here of all places — telling them
        // apart is the whole point of the exercise.
        let named = i + 3 < args.count && !args[i + 3].hasPrefix("-") ? args[i + 3] : "tester"
        SyncSettings.login = named
        #endif
    }

    /// `-uitest-reset` empties the workspace and the preferences before anything reads them.
    ///
    /// UI tests need a known starting point, and the app group survives a reinstall of the app, so
    /// "delete the app between runs" does not give them one. Guarded on the argument so it can only
    /// happen when a test harness asks: nothing a person can do reaches this.
    private static var didReset = false
    private static func resetIfAsked() {
        guard !didReset, CommandLine.arguments.contains("-uitest-reset") else { return }
        didReset = true
        try? FileManager.default.removeItem(at: container.appendingPathComponent("workspaces"))
        for (k, _) in defaults.dictionaryRepresentation() { defaults.removeObject(forKey: k) }
    }
}

/// The running session as both processes see it. Persisted in the App Group defaults — not in the
/// repository, like Android's in-memory pause — so a Live Activity button in the extension and the
/// ticker in the app agree. The ledger row (start line, end line) is what survives; this is the
/// clock's scratch state.
public struct LiveSession: Codable, Equatable {
    public var sessionId: String
    public var nodeId: String
    public var title: String
    public var startedAt: Date
    /// 0 = open stopwatch.
    public var plannedSecs: Int
    public var pausedAt: Date?
    /// Seconds spent paused before `pausedAt`.
    public var pausedTotal: TimeInterval

    public init(sessionId: String, nodeId: String, title: String, startedAt: Date, plannedSecs: Int, pausedAt: Date? = nil, pausedTotal: TimeInterval = 0) {
        self.sessionId = sessionId; self.nodeId = nodeId; self.title = title; self.startedAt = startedAt; self.plannedSecs = plannedSecs
        self.pausedAt = pausedAt; self.pausedTotal = pausedTotal
    }

    public var isOpen: Bool { plannedSecs <= 0 }
    public var isPaused: Bool { pausedAt != nil }
    public func elapsed(at now: Date = Date()) -> Int {
        let end = pausedAt ?? now
        return max(0, Int(end.timeIntervalSince(startedAt) - pausedTotal))
    }
    public func remaining(at now: Date = Date()) -> Int { max(0, plannedSecs - elapsed(at: now)) }
    public var isSpent: Bool { !isOpen && remaining() <= 0 }
    /// The instant the countdown reaches zero if it runs from now uninterrupted.
    public func endDate(at now: Date = Date()) -> Date? { isOpen ? nil : now.addingTimeInterval(TimeInterval(remaining(at: now))) }
    /// The instant a running clock started from, adjusted for pauses — what a timer text counts from.
    public func effectiveStart(at now: Date = Date()) -> Date { now.addingTimeInterval(-TimeInterval(elapsed(at: now))) }

    static let key = "live_session"
    public static func load() -> LiveSession? {
        guard let d = AppGroup.defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(LiveSession.self, from: d)
    }
    public func save() { AppGroup.defaults.set(try? JSONEncoder().encode(self), forKey: LiveSession.key) }
    public static func clear() { AppGroup.defaults.removeObject(forKey: key) }
}

/// The three ways a session ends, and the one way it starts — shared so a widget button and the
/// focus screen do exactly the same thing to the ledger.
public enum SessionCommands {
    public static func start(nodeId: String, title: String, plannedSecs: Int) {
        let (_, writer) = AppGroup.openWorkspace()
        if let live = LiveSession.load() { end(live, outcome: FocusOutcome.interrupted, writer: writer) }
        let now = Date()
        let row = FocusSession(id: UUID().uuidString.lowercased(), nodeId: nodeId, startedAt: Int64(now.timeIntervalSince1970 * 1000), plannedSecs: plannedSecs)
        try? writer.appendFocus(row.line, month: row.monthKey)
        LiveSession(sessionId: row.id, nodeId: nodeId, title: inlinePlain(title), startedAt: now, plannedSecs: plannedSecs).save()
        AppGroup.defaults.set(nodeId, forKey: "last_focus_node")
        AppGroup.defaults.set(inlinePlain(title), forKey: "last_focus_title")
    }

    public static func pause() { guard var s = LiveSession.load(), s.pausedAt == nil else { return }; s.pausedAt = Date(); s.save() }
    public static func resume() {
        guard var s = LiveSession.load(), let p = s.pausedAt else { return }
        s.pausedTotal += Date().timeIntervalSince(p); s.pausedAt = nil; s.save()
    }

    /// Stop: the session ends `stopped` (or `ran_out` if its promise was already met); the task stays as it was.
    public static func stop() {
        guard let s = LiveSession.load() else { return }
        let (_, writer) = AppGroup.openWorkspace()
        end(s, outcome: s.isSpent ? FocusOutcome.ranOut : FocusOutcome.stopped, writer: writer)
    }

    /// Done: stop the clock and finish the task.
    public static func done() {
        let node = LiveSession.load()?.nodeId
        stop()
        if let node { let (_, writer) = AppGroup.openWorkspace(); try? writer.setDone(node, true) }
    }

    /// Closes a session that reached its end while nobody was running — the lazy `ran_out`.
    public static func settleIfSpent() -> Bool {
        guard let s = LiveSession.load(), s.isSpent else { return false }
        let (_, writer) = AppGroup.openWorkspace()
        end(s, outcome: FocusOutcome.ranOut, writer: writer)
        return true
    }

    static func end(_ s: LiveSession, outcome: String, writer: WorkspaceWriter) {
        let actual = outcome == FocusOutcome.ranOut ? s.plannedSecs : s.elapsed()
        let row = FocusSession(id: s.sessionId, nodeId: s.nodeId, startedAt: Int64(s.startedAt.timeIntervalSince1970 * 1000), plannedSecs: s.plannedSecs)
        let settled = FocusLedger.settle(row, actualSecs: actual, outcome: outcome, now: Int64(Date().timeIntervalSince1970 * 1000))
        try? writer.appendFocus(settled.line, month: settled.monthKey)
        LiveSession.clear()
        // Tell the app, if it is alive, that the ledger moved.
        AppGroup.defaults.set(Date().timeIntervalSince1970, forKey: "ledger_changed_at")
    }
}
