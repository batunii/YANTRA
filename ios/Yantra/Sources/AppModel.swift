import SwiftUI
import Combine
import WidgetKit
import YantraCore

/// The app's one container — `AppContainer` on Android. Owns the local workspace, its writer, the
/// in-memory index, and the focus timer. Every screen reads the index and writes through the writer;
/// the index is rebuilt from files after every write, so nothing can live only in memory.
@MainActor
final class AppModel: ObservableObject {
    let store: WorkspaceStore
    let writer: WorkspaceWriter
    @Published private(set) var index = WorkspaceIndex()
    @Published private(set) var sessions: [FocusSession] = []
    let timer: FocusTimer

    init() {
        (store, writer) = AppGroup.openWorkspace()
        timer = FocusTimer()
        writer.onChange = { [weak self] _ in Task { @MainActor in self?.reindex() } }
        Notifications.shared.install()
        Notifications.shared.onMarkDone = { [weak self] id in self?.write { try self?.writer.setDone(id, true) } }
        reindex()
        timer.wake()
        sweepArchive()
    }

    func reindex() {
        index = WorkspaceIndex.read(store)
        sessions = FocusLedger.read(store)
        Notifications.shared.syncReminders(index)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Called when the app comes to the foreground: another process (a widget, the island, the
    /// share sheet) may have written files or moved the session.
    func wake() { reindex(); timer.wake() }

    /// Sync observes; it never participates in a write. Runs on open, on leaving, and on request.
    @Published private(set) var syncing = false
    func syncInBackground(_ reason: String) {
        guard SyncSettings.repo != nil, !syncing else { return }
        syncing = true
        Task {
            let r = await SyncSettings.syncNow(store: store, message: reason)
            await MainActor.run { syncing = false; if r.pulled { reindex() } }
        }
    }

    // MARK: writes, each surfacing a refusal rather than crashing

    @Published var refusal: String?

    func write(_ op: () throws -> Void) {
        do { try op() } catch let e as WorkspaceWriter.ReadOnly {
            refusal = "Read-only: this workspace needs a newer Yantra (format \(e.formatVersion))"
        } catch { refusal = "\(error)" }
    }

    func toggleDone(_ n: Node) { write { try writer.setDone(n.id, !n.done) } }
    func toggleInProgress(_ n: Node) { write { try writer.setInProgress(n.id, !n.inProgress) } }

    /// Quick capture: the grammar reads dates, times, #labels, !priority and ~list off the line; a smart
    /// list's apply-on-create values fill in what its rule needs. Capture is never blocked.
    func capture(_ raw: String, into target: Node?) {
        let listNames = index.children(of: nil).filter { $0.type == NodeType.list }
        let parsed = CaptureParse.parse(raw, lists: listNames.map { inlinePlain($0.title ?? "") })
        let t = parsed.title.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        write {
            var home: String?
            var due = parsed.due(), priority = parsed.priority
            if let name = parsed.list {
                if let l = listNames.first(where: { inlinePlain($0.title ?? "").caseInsensitiveCompare(name) == .orderedSame }) { home = l.id }
                else if parsed.listIsNew { home = try writer.createTopLevel(type: NodeType.list, title: name) }
            }
            if home == nil, let target, target.type == NodeType.smartList, let def = index.smartLists[target.id] {
                home = def.homeParentId ?? def.scopeRootId ?? index.node(systemKey: SystemKey.inbox)?.id
                for a in def.applyOnCreate {
                    if a.defId == BuiltIns.due, a.dateRel == .todayStart, due == nil { due = DueSpec(.allDay(.today())) }
                    if a.defId == BuiltIns.priority, let p = a.text, priority == nil { priority = p }
                }
            }
            if home == nil { home = target?.id ?? index.node(systemKey: SystemKey.inbox)?.id }
            guard let home else { return }
            _ = try writer.addBlock(to: home, type: NodeType.task, text: t, due: due, priority: priority, labels: parsed.labels)
        }
    }

    /// The colour a list wears, as a palette name — its own if somebody chose one, else the one its
    /// name seeds to. Seeding rather than leaving it blank is what makes a colour correctable: there
    /// is always one to change.
    func listColor(_ pageId: String) -> String? {
        guard let page = store.readPage(pageId) else { return nil }
        return page.color ?? page.title.map { LabelPalette.defaultNameFor($0) }
    }

    func smartListRows(_ n: Node) -> [Node] {
        guard let def = index.smartLists[n.id] else { return [] }
        return SmartListQuery.run(def, in: index)
    }

    func completedRows(_ n: Node) -> [Node] {
        guard let def = index.smartLists[n.id], let f = def.filter, let flipped = completedVariant(f) else { return [] }
        let v = SmartListDef(nodeId: def.nodeId, scopeRootId: def.scopeRootId, filterJson: FilterJSON.encode(flipped), sortJson: def.sortJson)
        return SmartListQuery.run(v, in: index)
    }

    // MARK: archive — the threshold is the workspace's, in its manifest; the sweep runs on launch too

    func setArchiveAfterDays(_ days: Int) {
        guard var m = store.readManifest() else { return }
        m.archiveAfterDays = days; store.writeManifest(m); objectWillChange.send()
    }

    @discardableResult
    func sweepArchive() -> Int {
        let days = store.readManifest()?.archiveAfterDays ?? 0
        guard days > 0 else { return 0 }
        var moved = 0
        write { moved = try writer.archiveFinished(before: LocalDate.today().adding(days: -days)) { [index] id in index.hasOpenChildren(id) } }
        return moved
    }

    // MARK: focus

    func sessions(for nodeId: String) -> [FocusSession] { sessions.filter { $0.nodeId == nodeId && $0.actualSecs != nil } }
}

/// The live session — `FocusTimer.kt`, over the shared `LiveSession` record so the Live Activity and
/// widget intents, which run in another process, see the same clock. The ledger row was written at
/// start; pause lives only in the shared scratch state, not in the repository.
@MainActor
final class FocusTimer: ObservableObject {
    struct State: Equatable {
        var sessionId: String, nodeId: String, nodeTitle: String
        var plannedSecs: Int, remainingSecs: Int, elapsedSecs: Int
        var isRunning: Bool, isFinished: Bool = false
        var isOpen: Bool { plannedSecs <= 0 }
        var isSpent: Bool { !isOpen && remainingSecs <= 0 }
        var progress: Double { isOpen ? min(Double(elapsedSecs) / 3600, 1) : plannedSecs == 0 ? 0 : Double(plannedSecs - remainingSecs) / Double(plannedSecs) }
    }
    @Published private(set) var state: State?
    private var ticker: Timer?
    /// A finished session shown until dismissed, as Android keeps `isFinished` on screen.
    private var finished: State?

    init() { tick(); wake() }

    func start(nodeId: String, title: String, plannedSecs: Int) {
        finished = nil
        SessionCommands.start(nodeId: nodeId, title: title, plannedSecs: plannedSecs)
        afterChange()
    }
    func pause() { SessionCommands.pause(); afterChange() }
    func resume() { SessionCommands.resume(); afterChange() }
    func finish() { finished = nil; SessionCommands.stop(); afterChange() }
    func abandon() { finished = nil; SessionCommands.stop(); afterChange() }
    func dismissFinished() { finished = nil; refresh() }

    private func afterChange() {
        FocusActivity.sync()
        Notifications.shared.scheduleBell(for: LiveSession.load())
        WidgetCenter.shared.reloadAllTimelines()
        refresh()
    }

    /// Re-reads the shared record; closes a session that ran out while nobody was running.
    func wake() {
        if let s = LiveSession.load(), s.isSpent {
            finished = State(sessionId: s.sessionId, nodeId: s.nodeId, nodeTitle: s.title, plannedSecs: s.plannedSecs, remainingSecs: 0, elapsedSecs: s.plannedSecs, isRunning: false, isFinished: true)
            _ = SessionCommands.settleIfSpent()
            FocusActivity.sync()
        }
        refresh()
    }

    private func refresh() {
        if let s = LiveSession.load() {
            if s.isSpent { wake(); return }
            state = State(sessionId: s.sessionId, nodeId: s.nodeId, nodeTitle: s.title, plannedSecs: s.plannedSecs,
                          remainingSecs: s.remaining(), elapsedSecs: s.elapsed(), isRunning: !s.isPaused)
        } else {
            state = finished
        }
    }

    private func tick() {
        ticker?.invalidate()
        ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in Task { @MainActor in self?.refresh() } }
    }
}

// MARK: - date labels (Common.kt)

func dateLabel(_ d: LocalDate) -> String {
    let today = LocalDate.today()
    if d == today { return "Today" }
    if d == today.adding(days: 1) { return "Tomorrow" }
    if d == today.adding(days: -1) { return "Yesterday" }
    let f = DateFormatter(); f.dateFormat = d.year == today.year ? "MMM d" : "MMM d, yyyy"
    return f.string(from: d.startOfDay())
}

func dueLabel(_ due: DueSpec) -> String {
    switch due.value {
    case let .allDay(d): return dateLabel(d)
    case let .at(t):
        let f = DateFormatter(); f.timeStyle = .short; f.dateStyle = .none
        return dateLabel(.of(t)) + " " + f.string(from: t)
    }
}

func deadlineLabel(_ d: LocalDate) -> String {
    let days = LocalDate.today().days(until: d)
    if days > 0 { return "\(days)d left" }
    if days == 0 { return "Due today" }
    return "\(-days)d over"
}

func isOverdue(_ n: Node) -> Bool {
    if let d = n.dueDate, d < (n.dueHasTime ? Date() : LocalDate.today().startOfDay()) { return true }
    if let dl = n.deadline, dl < .today() { return true }
    return false
}

func isDueToday(_ n: Node) -> Bool {
    if let d = n.dueDate { return LocalDate.of(d) == .today() }
    return false
}
