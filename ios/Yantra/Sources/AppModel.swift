import SwiftUI
import Combine
import WidgetKit
import YantraCore

/// The app's one container — `AppContainer` on Android. Owns the local workspace, its writer, the
/// in-memory index, and the focus timer. Every screen reads the index and writes through the writer;
/// the index is rebuilt from files after every write, so nothing can live only in memory.
@MainActor
final class AppModel: ObservableObject {
    /// Every workspace this device has open, by id, and the writer for each.
    ///
    /// The repositories are global but a write is not: it belongs to whichever workspace the node
    /// came from, so mutations resolve their writer through `writerFor`. See Android's `Workspaces`.
    @Published private(set) var stores: [String: WorkspaceStore] = [:]
    private var writers: [String: WorkspaceWriter] = [:]
    let registry: WorkspaceRegistry

    /// The local workspace — the empty id, always present, the one smart lists live in. `personal()`
    /// on Android. Force-unwrapped because a device without it has no app to run: `openWorkspace`
    /// scaffolds it before anything else reads a file.
    var store: WorkspaceStore { stores[""]! }
    var writer: WorkspaceWriter { writers[""]! }

    @Published private(set) var index = WorkspaceIndex()
    @Published private(set) var sessions: [FocusSession] = []
    let timer: FocusTimer
    private var bag = Set<AnyCancellable>()

    init() {
        registry = AppGroup.registry
        timer = FocusTimer()
        openAll()
        // The clock ticks on `timer`, and every screen observes `model`. A nested ObservableObject
        // publishes on *itself*, so the second hand moved and nothing redrew — the focus screen and
        // the player bar sat on the number they were built with and only caught up when some other
        // write happened to refresh the model. Forwarding its changes is what makes a running clock
        // look like one.
        timer.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &bag)
        Notifications.shared.install()
        Notifications.shared.onMarkDone = { [weak self] id in self?.write { try self?.writerFor(id).setDone(id, true) } }
        reindex()
        timer.wake()
        startMinuteTick()
        sweepArchive()
    }

    /// Opens the local workspace and every linked one, and points each writer back here.
    ///
    /// Called again after a workspace is added or forgotten, so neither needs a restart — the thing
    /// that made adding one feel broken even when the link itself had worked.
    private func openAll() {
        var stores: [String: WorkspaceStore] = [:], writers: [String: WorkspaceWriter] = [:]
        for (store, writer) in AppGroup.openWorkspaces() {
            writer.onChange = { [weak self] _ in Task { @MainActor in self?.reindex() } }
            stores[store.id] = store
            writers[store.id] = writer
        }
        self.stores = stores
        self.writers = writers
    }

    /// The writer that owns a node, found through the index.
    ///
    /// Using the index to route a write is not a contradiction of files-being-the-truth: the index
    /// is a map of what the files say, and "which workspace is this node in" is exactly what a map
    /// is for. Falls back to the local workspace for a node that does not exist yet.
    func writerFor(_ nodeId: String?) -> WorkspaceWriter {
        nodeId.flatMap { index.nodes[$0]?.workspaceId }.flatMap { writers[$0] } ?? writer
    }

    /// The writer for a workspace named outright — creating something *in* a workspace, rather
    /// than editing something that is already in one. Falls back to local for an id that has gone.
    func writerIn(_ workspaceId: String) -> WorkspaceWriter { writers[workspaceId] ?? writer }

    /// The palette name a workspace wears — the one somebody chose, else the one its name seeds to.
    ///
    /// Seeded rather than left blank so there is always a colour to correct: a workspace with no
    /// colour at all is a row you cannot tell from the next one, and nothing to change.
    func workspaceColorName(_ workspaceId: String) -> String {
        registry.color(for: workspaceId) ?? LabelPalette.defaultNameFor(workspaceName(workspaceId))
    }

    func setWorkspaceColor(_ name: String?, for workspaceId: String) {
        registry.setColor(name, for: workspaceId)
        objectWillChange.send()
    }

    /// What a workspace is called: the name it was linked under, else its manifest's, else a word.
    func workspaceName(_ workspaceId: String) -> String {
        if workspaceId.isEmpty { return store.readManifest()?.name ?? "Personal" }
        return registry.entry(workspaceId)?.name ?? stores[workspaceId]?.readManifest()?.name ?? "Workspace"
    }

    /// The store a node's files are in. Same rule as `writerFor`.
    func storeFor(_ nodeId: String?) -> WorkspaceStore {
        nodeId.flatMap { index.nodes[$0]?.workspaceId }.flatMap { stores[$0] } ?? store
    }

    /// Local first, then the linked ones **newest first**.
    ///
    /// The one you just added is the one you are looking for, so it goes above the ones you added
    /// last month. Personal keeps the top because it is the workspace that is always there and the
    /// one quick capture falls back to — demoting it would move a fixed landmark every time a
    /// repository is joined.
    var allStores: [WorkspaceStore] {
        [store] + registry.newestFirst().compactMap { $0.id.isEmpty ? nil : stores[$0.id] }
    }

    func reindex() {
        // What the screen needs, straight away.
        index = WorkspaceIndex.read(allStores)
        sessions = allStores.flatMap { FocusLedger.read($0) }.sorted { $0.startedAt < $1.startedAt }
        rebuildRunning()
        // What nothing on screen is waiting for, once the writing stops.
        scheduleFollowUp()
    }

    /// Notifications and the widget timelines, coalesced.
    ///
    /// Both were run on **every** write. Rescheduling every reminder and reloading every widget is
    /// cross-process work measured in tens of milliseconds, and it sat between the tap and the
    /// redraw — so ticking a checkbox paid for a notification sweep and a trip to the widget host
    /// before the tick appeared. Neither is urgent: nobody is looking at a widget while typing into
    /// the app, and a reminder that settles a moment after the last edit is indistinguishable from
    /// one that settles during it.
    private var followUp: Timer?
    private func scheduleFollowUp() {
        followUp?.invalidate()
        followUp = Timer.scheduledTimer(withTimeInterval: 0.6, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                Notifications.shared.syncReminders(self.index)
                WidgetCenter.shared.reloadAllTimelines()
            }
        }
    }

    /// Runs the follow-up now rather than on the timer — for leaving the app, where "in a moment"
    /// may never arrive.
    func flushFollowUp() {
        followUp?.invalidate()
        followUp = nil
        Notifications.shared.syncReminders(index)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Called when the app comes to the foreground: another process (a widget, the island, the
    /// share sheet) may have written files or moved the session.
    func wake() { reindex(); timer.wake(); startMinuteTick() }

    /// Sync observes; it never participates in a write. Runs on open, on leaving, and on request.
    @Published private(set) var syncing = false
    func syncInBackground(_ reason: String) {
        let connected = allStores.filter { SyncSettings.repo(for: $0.id) != nil }
        guard !connected.isEmpty, !syncing else { return }
        syncing = true
        Task {
            // One at a time. They are independent repositories, but they share one credential and
            // one token renewal, and three passes starting together is three refreshes racing to
            // rotate the same refresh token — which GitHub answers by invalidating it.
            var pulled = false
            // Said out loud while it happens. This was the most silent thing in the app and the one
            // most likely to leave somebody wondering whether anything was working — see
            // `NetworkActivity`.
            await NetworkActivity.shared.during(NetworkActivity.Words.syncing) {
                for store in connected {
                    let r = await SyncSettings.syncNow(store: store, message: reason)
                    pulled = pulled || r.pulled
                }
            }
            await MainActor.run { syncing = false; if pulled { reindex() } }
        }
    }

    /// A sync the person asked for by pulling the list down, and waited through.
    ///
    /// Distinct from `syncInBackground` in the one way that matters here: it does not return until
    /// the pass is done, because `.refreshable` keeps its spinner up for exactly as long as this
    /// takes. Returning early would spin for a frame and tell them nothing.
    ///
    /// What it says afterwards is the pass's own words — "Synced", "Nothing to sync", or the reason
    /// it did not. A pull that reports nothing is indistinguishable from a pull that did nothing.
    func syncNowAndWait() async {
        let connected = allStores.filter { SyncSettings.repo(for: $0.id) != nil }
        guard !connected.isEmpty else {
            refusal = SyncSettings.login == nil ? "Sign in to GitHub to sync" : "No repository connected yet"
            return
        }
        syncing = true
        // **In a task of its own, awaited.** `.refreshable` runs its body in a task that SwiftUI
        // cancels when the view it is attached to rebuilds — and this very method rebuilds it, by
        // publishing `syncing`. The request was being cancelled mid-flight, and a cancelled
        // URLSession call arrives as an `NSURLError`, which the engine reports as "Cannot reach
        // GitHub": a network error for a network that was perfectly fine. The log made it obvious —
        // every failed pass took **zero seconds** while the ones that worked took three to nine.
        //
        // An unstructured `Task` does not inherit cancellation, so the pass finishes and the
        // spinner still waits for it.
        let work = Task { () -> (Bool, String?) in
            var pulled = false, failure: String?
            await NetworkActivity.shared.during(NetworkActivity.Words.syncing) {
                for store in connected {
                    let r = await SyncSettings.syncNow(store: store, message: "pulled to sync")
                    pulled = pulled || r.pulled
                    if failure == nil, let e = r.error { failure = e }
                }
            }
            return (pulled, failure)
        }
        let (pulled, failure) = await work.value
        syncing = false
        if pulled { reindex() }
        refusal = failure.map { "Not synced: \($0)" } ?? SyncSettings.lastStatus
    }

    // MARK: writes, each surfacing a refusal rather than crashing

    @Published var refusal: String?

    func write(_ op: () throws -> Void) {
        // Successful writes are counted, not just the failed ones. A week of "nothing happened" is
        // otherwise indistinguishable from a week of "nothing was attempted", and the difference is
        // the whole question: an app that refused every save and an app nobody opened leave the
        // same silence. The count only, never what was written.
        do { try op(); Diagnostics.tick("write.ok") } catch let e as WorkspaceWriter.ReadOnly {
            refusal = "Read-only: this workspace needs a newer Yantra (format \(e.formatVersion))"
            Diagnostics.log("write.readOnly", ["format": e.formatVersion])
        } catch {
            refusal = "\(error)"
            // Every write that did not happen, with the reason. A task that "did not save" is the
            // report this exists to answer, and it leaves no other trace.
            Diagnostics.log("write.failed", ["error": "\(error)"])
        }
    }

    func toggleDone(_ n: Node) { write { try writerFor(n.id).setDone(n.id, !n.done) } }
    func toggleInProgress(_ n: Node) { write { try writerFor(n.id).setInProgress(n.id, !n.inProgress) } }

    /// Quick capture: the grammar reads dates, times, #labels, !priority and ~list off the line; a smart
    /// list's apply-on-create values fill in what its rule needs. Capture is never blocked.
    func capture(_ raw: String, into target: Node?) {
        let listNames = index.children(of: nil).filter { $0.type == NodeType.list }
        // `people:` is what makes `@name` an assignment rather than a word. A login outside the set
        // is left in the title, which is what stops "email me @ 5" and "meet @ the office" being
        // read as handing work to somebody called 5.
        // Every roster this device holds, not just the target's.
        //
        // A name offered by the picker has to be read back by the parser or the pick does nothing —
        // and which workspace a capture lands in is decided *below*, after the parse, by the list
        // the line names. Asking one workspace's roster before knowing which workspace it is made
        // `@somebody` from a shared project fall back into the title.
        let known = People.shared.everyLogin(index: index)
        let parsed = CaptureParse.parse(raw, lists: listNames.map { inlinePlain($0.title ?? "") },
                                        people: known)
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
            // **The same writer for both.** `writerFor(id)` routes through the index, and a task
            // created a line ago is not in it yet — the rebuild happens after the write — so it fell
            // back to the local workspace and wrote the assignee into a store that had never heard
            // of the task. On a list belonging to another repository that meant the name simply
            // vanished. The writer that made the block is the one that owns it.
            let writer = writerFor(home)
            let id = try writer.addBlock(to: home, type: NodeType.task, text: t, due: due, priority: priority, labels: parsed.labels)
            if let who = parsed.assignee { try writer.setAssignee(id, who) }
        }
    }

    /// The colour a list wears, as a palette name — its own if somebody chose one, else the one its
    /// name seeds to. Seeding rather than leaving it blank is what makes a colour correctable: there
    /// is always one to change.
    func listColor(_ pageId: String?) -> String? {
        // From the index, not the disk. This is called once per task while the calendar rebuilds,
        // and a `readPage` here meant one file read per task on a screen that redraws whenever the
        // month, the selection or any node changes — which is what made every tap feel slow.
        guard let pageId, let page = index.nodes[pageId] else { return nil }
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
            guard let page = n.homePageId, let doc = storeFor(n.id).readPage(page) else { continue }
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
    func startRunning(_ nodeId: String) { write { try writerFor(nodeId).setInProgress(nodeId, true) } }

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

    /// Sets time aside for a task — a **sitting**.
    ///
    /// The block carries no title of its own: its words *are* the task's, which is why there is only
    /// ever one place to rename from. It lives on the page the task's own line lives on, so the
    /// claim and the thing claimed stay together in one file.
    func schedule(taskId: String, from: LocalDateTime, to: LocalDateTime) {
        // A task whose line has no page is not something to fail quietly about: the block the person
        // just dropped would simply not appear, with nothing anywhere to say why.
        guard let page = index.nodes[taskId]?.homePageId else {
            Diagnostics.log("calendar.scheduleRefused", [
                "task": taskId,
                "known": index.nodes[taskId] != nil,
                "why": index.nodes[taskId] == nil ? "no such node" : "the node has no home page",
            ])
            refusal = "That task has nowhere to keep the time"
            return
        }
        write {
            _ = try writerFor(taskId).addEvent(to: page, title: "", time: EventTime(start: from, end: to),
                                               forTaskId: taskId)
        }
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

    /// Sweeps every workspace, each against **its own** threshold.
    ///
    /// The setting lives in a manifest, and a manifest belongs to one repository — a shared project
    /// that keeps finished work for a year must not have it swept away because this phone's own
    /// list is set to thirty days.
    @discardableResult
    func sweepArchive() -> Int {
        var moved = 0
        for store in allStores {
            let days = store.readManifest()?.archiveAfterDays ?? 0
            guard days > 0, let writer = writers[store.id] else { continue }
            write { moved += try writer.archiveFinished(before: LocalDate.today().adding(days: -days)) { [index] id in index.hasOpenChildren(id) } }
        }
        return moved
    }

    /// Moves a node, and everything under it, onto a list — in this workspace or another one.
    ///
    /// Everything goes through here rather than through a writer, because only this knows which
    /// workspace each end belongs to. Within one workspace it is the ordinary re-homing; across two
    /// it is a copy, then the line, then the removal — see `WorkspaceMove`.
    func move(nodeId: String, toList listId: String) {
        let from = writerFor(nodeId), to = writerFor(listId)
        write { try WorkspaceMove.move(nodeId: nodeId, toList: listId, from: from, to: to) }
        reindex()
    }

    /// Every list a node could be moved to, with the workspace each one is in.
    ///
    /// The node's own list is left out, and so is anything filed under the node itself: a task
    /// cannot be moved into its own subtask, and offering it is how somebody detaches a branch of
    /// their own work from the tree.
    func moveTargets(for nodeId: String) -> [(list: Node, workspace: String)] {
        let under = Set(descendants(of: nodeId) + [nodeId])
        let home = index.nodes[nodeId]?.parentId
        return index.nodes.values
            .filter { $0.type == NodeType.list && !under.contains($0.id) && $0.id != home }
            .sorted { (workspaceName($0.workspaceId), inlinePlain($0.title ?? "")) < (workspaceName($1.workspaceId), inlinePlain($1.title ?? "")) }
            .map { ($0, $0.workspaceId) }
    }

    private func descendants(of nodeId: String) -> [String] {
        let kids = index.children(of: nodeId).map(\.id)
        return kids + kids.flatMap { descendants(of: $0) }
    }

    /// Opens somebody else's meeting as **a node of yours** — `CalendarViewModel.openLocally`.
    ///
    /// Made on the first tap and found on every one after, so there is one page for one meeting,
    /// reached the one way: by tapping the block. The node is an *event* line carrying `ext:` — the
    /// meeting's own identity — which is what makes the timeline draw one block rather than two, and
    /// what lets the page survive the meeting being moved. It is never a copy: the meeting belongs
    /// to a calendar this app cannot write, and a second, diverging copy in the repository is the
    /// one thing that must not happen.
    ///
    /// Returns the node to open, or nil when there is nowhere to put it.
    @discardableResult
    func openMeetingLocally(_ item: DayItem.DeviceItem) -> String? {
        if let existing = item.noteId { return existing }
        guard let page = index.node(systemKey: SystemKey.inbox)?.id else { return nil }
        // A provider that gives no identity still has to open. `uid` is null on some calendars, and
        // a tap that silently did nothing is the worst available answer — it reads as the app being
        // broken half the time, because it is broken for half the calendars. The fallback is this
        // device's own row id, marked as such: it cannot mean anything on another phone, and saying
        // so in the file beats pretending otherwise.
        let uid = item.uid ?? "local:\(item.eventId)"
        var made: String?
        write {
            made = try writerFor(page).addEvent(
                to: page, title: item.title,
                time: EventTime(start: item.start, end: item.end, allDay: item.allDay),
                location: item.location,
                // Named only when the meeting repeats, so a page about this Monday does not become
                // the page for every Monday.
                external: ExternalRef(uid, occurrence: item.repeating ? item.start : nil))
        }
        return made
    }

    /// Makes a task **about** somebody else's meeting, and links the two.
    ///
    /// Never a copy of the meeting. The meeting belongs to a calendar this app cannot write, and
    /// duplicating it into the repository would put a second, diverging copy on every device that
    /// syncs. What is written is a task of yours carrying `ext:` — the meeting's own identity — which
    /// is what makes the timeline draw one block rather than two, and what lets the task survive the
    /// meeting being moved.
    ///
    /// Returns the id of the task, existing or new, so the caller can go straight to it.
    @discardableResult
    func makeTaskForMeeting(_ item: DayItem.DeviceItem) -> String? {
        if let existing = item.taskId { return existing }
        guard let inbox = index.node(systemKey: SystemKey.inbox)?.id else { return nil }
        var made: String?
        write {
            let writer = writerFor(inbox)
            let id = try writer.addBlock(to: inbox, type: NodeType.task, text: item.title,
                                         due: DueSpec(.at(item.beginUtc)))
            // The occurrence, not just the series: a weekly standup is one rule and fifty-two
            // mornings, and a task about *this* Monday must not attach itself to all of them.
            // Same writer, for the same reason as in `capture`: the index has not seen this task.
            try writer.setExternal(id, ExternalRef(item.uid ?? item.eventId, occurrence: item.start))
            made = id
        }
        return made
    }

    // MARK: adding and forgetting workspaces

    /// Re-opens everything after the set of workspaces has changed, and rebuilds the index.
    func workspacesChanged() { openAll(); reindex() }

    /// Forgets a workspace: its files, its registration and which repository it pointed at.
    ///
    /// **Nothing on GitHub is touched.** The repository, and every task in it, stays exactly where
    /// it is — this removes a copy from this device, and another device or a fresh join brings it
    /// all back. Deleting a repository is done on GitHub, by somebody who means it.
    func forgetWorkspace(_ id: String) {
        guard !id.isEmpty else { return }
        Diagnostics.log("workspace.forgotten", ["workspace": id])     // the local workspace is not optional
        SyncSettings.setRepo(nil, for: id)
        SyncSettings.setStatus(nil, for: id)
        registry.remove(id)
        try? FileManager.default.removeItem(at: registry.dir(for: id))
        try? FileManager.default.removeItem(at: AppGroup.container.appendingPathComponent("sync/\(id)", isDirectory: true))
        workspacesChanged()
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

    // Every session, and how it ended. What a week of focus actually looked like — how many were
    // started, how many were paused and never resumed, how many ran to the bell — is the question
    // the stats screen answers from the ledger, and the log is how to tell whether the stats screen
    // is telling the truth. How long was planned, never what the task was called.
    func start(nodeId: String, title: String, plannedSecs: Int) {
        finished = nil
        SessionCommands.start(nodeId: nodeId, title: title, plannedSecs: plannedSecs)
        Diagnostics.log("focus.start", ["planned": plannedSecs, "open": plannedSecs == 0])
        afterChange()
    }
    func pause() { SessionCommands.pause(); Diagnostics.log("focus.pause"); afterChange() }
    func resume() { SessionCommands.resume(); Diagnostics.log("focus.resume"); afterChange() }
    func finish() {
        finished = nil
        Diagnostics.log("focus.finish", ["ran": LiveSession.load().map { Int(Date().timeIntervalSince($0.startedAt)) } ?? -1])
        SessionCommands.stop()
        afterChange()
    }
    func abandon() {
        finished = nil
        Diagnostics.log("focus.abandon", ["ran": LiveSession.load().map { Int(Date().timeIntervalSince($0.startedAt)) } ?? -1])
        SessionCommands.stop()
        afterChange()
    }
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
