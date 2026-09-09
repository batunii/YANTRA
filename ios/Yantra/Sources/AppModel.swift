import SwiftUI
import Combine
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
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("workspaces/local", isDirectory: true)
        store = WorkspaceStore(root: base, id: "")
        writer = WorkspaceWriter(store: store, device: UIDevice.current.name.lowercased().replacingOccurrences(of: " ", with: "-"))
        if !store.exists {
            store.scaffold(name: "Personal", now: Int64(Date().timeIntervalSince1970 * 1000))
            WorkspaceSeeder.seed(store)
        }
        timer = FocusTimer()
        timer.ledger = self
        writer.onChange = { [weak self] _ in Task { @MainActor in self?.reindex() } }
        reindex()
        timer.restoreIfNeeded()
    }

    func reindex() {
        index = WorkspaceIndex.read(store)
        sessions = FocusLedger.read(store)
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

    /// Quick capture into a list, or into a smart list's home with its apply-on-create values.
    func capture(_ title: String, into target: Node?) {
        let t = title.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        write {
            if let target, target.type == NodeType.smartList, let def = index.smartLists[target.id] {
                let home = def.homeParentId ?? def.scopeRootId ?? index.node(systemKey: SystemKey.inbox)?.id
                guard let home else { return }
                var due: DueSpec? = nil, priority: String? = nil
                for a in def.applyOnCreate {
                    if a.defId == BuiltIns.due, a.dateRel == .todayStart { due = DueSpec(.allDay(.today())) }
                    if a.defId == BuiltIns.priority, let p = a.text { priority = p }
                }
                _ = try writer.addBlock(to: home, type: NodeType.task, text: t, due: due, priority: priority)
            } else {
                let home = target?.id ?? index.node(systemKey: SystemKey.inbox)?.id
                guard let home else { return }
                _ = try writer.addBlock(to: home, type: NodeType.task, text: t)
            }
        }
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

    // MARK: focus

    func sessions(for nodeId: String) -> [FocusSession] { sessions.filter { $0.nodeId == nodeId && $0.actualSecs != nil } }
}

/// The live session — `FocusTimer.kt`. Written to the ledger at start, so it survives the process;
/// pause is deliberately not persisted.
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
    weak var ledger: AppModel?
    private var ticker: Timer?

    func start(nodeId: String, title: String, plannedSecs: Int) {
        if let s = state, !s.isFinished { end(FocusOutcome.interrupted) }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let s = FocusSession(id: UUID().uuidString.lowercased(), nodeId: nodeId, startedAt: now, plannedSecs: plannedSecs)
        append(s)
        state = State(sessionId: s.id, nodeId: nodeId, nodeTitle: inlinePlain(title), plannedSecs: plannedSecs, remainingSecs: plannedSecs, elapsedSecs: 0, isRunning: true)
        tick()
    }

    func pause() { state?.isRunning = false }
    func resume() { if state?.isFinished == false { state?.isRunning = true } }
    func finish() { end(FocusOutcome.stopped) }
    func abandon() { end(FocusOutcome.interrupted) }
    func dismissFinished() { if state?.isFinished == true { state = nil; ticker?.invalidate() } }

    private func end(_ outcome: String) {
        guard let s = state else { return }
        ticker?.invalidate()
        if !s.isFinished { close(s, actual: s.elapsedSecs, outcome: outcome) }
        state = nil
    }

    private func close(_ s: State, actual: Int, outcome: String) {
        guard let model = ledger, let row = model.sessions.first(where: { $0.id == s.sessionId }) else { return }
        append(FocusLedger.settle(row, actualSecs: actual, outcome: outcome, now: Int64(Date().timeIntervalSince1970 * 1000)))
    }

    private func append(_ s: FocusSession) {
        ledger?.write { try ledger?.writer.appendFocus(s.line, month: s.monthKey) }
    }

    private func tick() {
        ticker?.invalidate()
        ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, var s = self.state, s.isRunning, !s.isFinished else { return }
                s.elapsedSecs += 1
                if !s.isOpen {
                    s.remainingSecs -= 1
                    if s.remainingSecs <= 0 {
                        s.remainingSecs = 0; s.isRunning = false; s.isFinished = true
                        self.state = s
                        self.close(s, actual: s.plannedSecs, outcome: FocusOutcome.ranOut)
                        self.ticker?.invalidate()
                        return
                    }
                }
                self.state = s
            }
        }
    }

    /// Rebuilds the live session from the ledger on a cold start — the mechanism that makes the
    /// session outlive the process on both platforms.
    func restoreIfNeeded() {
        guard state == nil, let model = ledger, let open = model.sessions.last(where: { $0.endedAt == nil }) else { return }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let elapsed = Int((nowMs - open.startedAt) / 1000)
        let title = model.index.nodes[open.nodeId]?.title ?? ""
        if open.plannedSecs > 0, nowMs >= open.startedAt + Int64(open.plannedSecs) * 1000 {
            append(FocusLedger.settle(open, actualSecs: open.plannedSecs, outcome: FocusOutcome.ranOut, now: nowMs))
            return
        }
        state = State(sessionId: open.id, nodeId: open.nodeId, nodeTitle: inlinePlain(title), plannedSecs: open.plannedSecs,
                      remainingSecs: max(open.plannedSecs - elapsed, 0), elapsedSecs: elapsed, isRunning: true)
        tick()
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
