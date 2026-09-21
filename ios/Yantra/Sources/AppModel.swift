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
        startMinuteTick()
        sweepArchive()
    }

    func reindex() {
        index = WorkspaceIndex.read(store)
        sessions = FocusLedger.read(store)
        Notifications.shared.syncReminders(index)
        rebuildRunning()
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Called when the app comes to the foreground: another process (a widget, the island, the
    /// share sheet) may have written files or moved the session.
    func wake() { reindex(); timer.wake(); startMinuteTick() }

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

    // MARK: what is on the go

    /// The player's stack — see `RunningStack`.
    ///
    /// Recomputed on reindex and on the timer's tick, because the front card's clock is the one
    /// thing here that changes without a file changing.
    @Published private(set) var running: [RunningStack.Now] = []

    /// Ticks on the minute so a sitting that has arrived is noticed without anybody opening a
    /// screen. On the minute rather than every sixty seconds from whenever this started, so the bar
    /// changes as the clock does rather than up to a minute after it.
    ///
    /// Only the *set* is rebuilt here. The front card's clock is read live from the timer where it
    /// is drawn, so a running second does not put every task row in the app on a one-second loop to
    /// re-render "still not me".
    private var minuteTick: Timer?

    private func startMinuteTick() {
        minuteTick?.invalidate()
        let now = Date().timeIntervalSince1970
        let toNextMinute = 60 - now.truncatingRemainder(dividingBy: 60)
        minuteTick = Timer.scheduledTimer(withTimeInterval: toNextMinute, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.rebuildRunning(); self?.startMinuteTick() }
        }
    }

    func rebuildRunning() {
        let live = timer.state.flatMap { $0.isFinished ? nil : $0 }
        let started = index.nodes.values
            .filter { $0.type == NodeType.task && $0.inProgress && !$0.done }
            // Newest first, which is the order the bar's third rank keeps.
            .sorted { ($0.createdAt, $0.id) > ($1.createdAt, $1.id) }

        var lists: [String: (String?, String?)] = [:]
        for n in started {
            guard let page = n.homePageId, let doc = store.readPage(page) else { continue }
            lists[n.id] = (doc.title.map { inlinePlain($0) }, listColor(page))
        }

        running = RunningStack.stack(
            started: started.map { ($0.id, inlinePlain($0.title ?? "")) },
            timing: live.map { ($0.nodeId, $0.elapsedSecs) },
            sittings: sittingsNow(),
            at: Int64(Date().timeIntervalSince1970 * 1000),
            lists: lists)
    }

    /// Which task holds the live clock. Null when things are started but nothing is being timed.
    var timingId: String? { timer.state.flatMap { $0.isFinished ? nil : $0.nodeId } }

    /// Picks a task up. Nothing else is put down — several things can be on the go.
    func startRunning(_ nodeId: String) { write { try writer.setInProgress(nodeId, true) } }

    /// Presses play: an **open** stopwatch, not a committed length.
    ///
    /// The player's button is a control on a bar you were passing anyway — it means "start
    /// counting", which promises nothing about how long. Committing to a length is a decision with
    /// its own screen, and tapping the body of the player is how you get there.
    func startTiming(_ nodeId: String, title: String) -> RunningStack.Play {
        if let busy = timingId, busy != nodeId {
            return .occupied(byId: busy, byTitle: inlinePlain(index.nodes[busy]?.title ?? ""))
        }
        // Starting a session marks the task: you cannot be focusing on something you have not
        // started. The reverse does not hold, which is why nothing here puts anything down.
        if index.nodes[nodeId]?.inProgress == false { startRunning(nodeId) }
        timer.start(nodeId: nodeId, title: inlinePlain(title), plannedSecs: 0)
        rebuildRunning()
        return .started
    }

    /// Takes the clock. The previous session closes as interrupted; its time still counts.
    func switchTimingTo(_ nodeId: String, title: String) {
        if index.nodes[nodeId]?.inProgress == false { startRunning(nodeId) }
        timer.start(nodeId: nodeId, title: inlinePlain(title), plannedSecs: 0)
        rebuildRunning()
    }

    /// Ends the session but leaves the task started.
    ///
    /// Finishing a focus is not the same as putting the task down — you stopped timing, and you are
    /// usually still on the thing. Clearing the mark here would make the card vanish the moment a
    /// pomodoro ran out, which is the opposite of what just happened.
    func stopTiming() {
        if timingId != nil { timer.finish() }
        rebuildRunning()
    }

    /// The sittings covering this moment, read through the same bucketer the calendar uses so a
    /// block that repeats is expanded once, in one place, by one set of rules.
    private func sittingsNow() -> [SittingSpan] {
        let today = LocalDate.today()
        // Yesterday too: a sitting that began before midnight is still happening now.
        let days = CalendarBucketer.bucket(nodes: Array(index.nodes.values),
                                           from: today.adding(days: -1), toExclusive: today.adding(days: 1))
        var out: [SittingSpan] = []
        for (_, items) in days {
            for case let .event(e) in items {
                guard let taskId = e.forTaskId, !e.allDay else { continue }
                out.append(SittingSpan(taskId: taskId,
                                       title: index.nodes[taskId]?.title ?? e.title,
                                       startUtc: Int64(e.start.instant().timeIntervalSince1970 * 1000),
                                       endUtc: Int64(e.end.instant().timeIntervalSince1970 * 1000)))
            }
        }
        return out
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
