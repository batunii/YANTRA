import Foundation

/// Every write the app makes, expressed as a change to a file — the port of `WorkspaceWriter.kt`.
/// The file changes first; the index only ever learns what the files already say.
public final class WorkspaceWriter {
    public enum Change { case structural, edit, ink }
    public struct ReadOnly: Error { public let formatVersion: Int }

    public let store: WorkspaceStore
    public let device: String?
    public var onChange: (Change) -> Void = { _ in }
    /// Who a new task is by, in a shared workspace — `by:<login>`, as newer Android builds stamp it
    /// (`feature/in-progress-is-yours`). Written once, when the line is made, and kept through
    /// every edit after that by the codec's raw-line and `extras` handling. Nil for Personal, where
    /// it could only ever say you, and before sign-in, when there is nobody to name.
    public var author: () -> String? = { nil }
    /// Cross-process, and the one sync takes before swapping a file in: see `WorkspaceLock`.
    private let lock: WorkspaceLock

    public init(store: WorkspaceStore, device: String?) { self.store = store; self.device = device; lock = WorkspaceLock(root: store.root) }

    private func guardWritable() throws {
        if store.isReadOnly { throw ReadOnly(formatVersion: store.readManifest()?.formatVersion ?? -1) }
    }

    private func newId() -> String { UUID().uuidString.lowercased() }

    /// Rewrites a page through `transform`, stamping `modified_at` only when something changed.
    @discardableResult
    public func editPage(_ pageId: String, change: Change = .edit, _ transform: (PageDoc) -> PageDoc) throws -> Bool {
        try guardWritable()
        lock.lock(); defer { lock.unlock() }
        guard let page = store.readPage(pageId) else { return false }
        let next = transform(page)
        if next == page { return false }
        var stamped = next
        stamped.modifiedAt = Date()
        stamped.device = device
        store.writePage(stamped)
        onChange(change)
        return true
    }

    public func createTopLevel(type: String, title: String?, systemKey: String? = nil) throws -> String {
        try guardWritable()
        let id = newId()
        store.writePage(PageDoc(id: id, type: type, parent: nil, title: title, systemKey: systemKey, modifiedAt: Date(), device: device, blocks: []))
        onChange(.structural)
        return id
    }

    /// Makes sure a task — or an event — has a page of its own, because it is about to hold
    /// something. `WorkspaceWriter.ensurePage`.
    ///
    /// The page takes its type from the line that names it (`type: task` or `type: event`), its
    /// parent from the page that line is on, and no title: a parented page is named by its line.
    /// This used to write `type: task` whatever the line was, so the first note added under an event
    /// made a page that called the event a task.
    private func ensurePage(_ id: String, parent: String, type: String) {
        if store.readPage(id) == nil {
            store.writePage(PageDoc(id: id, type: type, parent: parent, title: nil, modifiedAt: Date(), device: device, blocks: []))
        }
    }

    /// Which page holds the task or event line with this id, and what kind of line it is.
    public func locateLine(_ id: String) -> (page: String, type: String)? {
        for p in store.readPages() {
            for b in p.blocks {
                if case let .task(t) = b, t.id == id { return (p.id, NodeType.task) }
                if case let .event(e) = b, e.id == id { return (p.id, NodeType.event) }
            }
        }
        return nil
    }

    /// Adds a block to a page, after `afterIndex` or at the end. Mints an id for tasks and ink.
    public func addBlock(to pageId: String, type: String, text: String, afterIndex: Int? = nil, indent: Int = 0,
                         due: DueSpec? = nil, priority: String? = nil, labels: [String] = []) throws -> String {
        try guardWritable()
        var id = ""
        let block: Block
        switch type {
        case NodeType.task:
            id = newId()
            let by = author().flatMap { $0.isEmpty ? nil : ["by:\($0)"] } ?? []
            block = .task(TaskRef(id: id, title: text, indent: indent, due: due, priority: priority, labels: labels, extras: by))
        case NodeType.ink: id = newId(); block = .ink(id: id, indent: indent)
        // The text carries the file name, which is what the line points at: the bytes are written
        // beside the page, and the block only names them.
        case NodeType.image: block = .image(uri: text, indent: indent)
        case NodeType.heading: block = .heading(text, indent: indent)
        case NodeType.bullet: block = .bullet(text, indent: indent)
        case NodeType.numbered: block = .numbered(text, indent: indent)
        default: block = .prose(text, indent: indent)
        }
        // A task's page is created lazily when it first holds a block. If the target is a task (or
        // an event) with no page yet, make one under its home page.
        if store.readPage(pageId) == nil {
            guard let (home, kind) = locateLine(pageId) else { throw ReadOnly(formatVersion: -2) }
            ensurePage(pageId, parent: home, type: kind)
        }
        var index = -1
        try editPage(pageId, change: .structural) { page in
            var p = page
            let at = afterIndex.map { min($0 + 1, p.blocks.count) } ?? p.blocks.count
            p.blocks.insert(block, at: at)
            index = at
            return p
        }
        if id.isEmpty { id = WorkspaceIndex.blockId(pageId: pageId, index: index) }
        return id
    }

    // MARK: events

    /// Adds an event line to a page. Mints an id, the way a task line gets one.
    @discardableResult
    public func addEvent(to pageId: String, title: String, time: EventTime, afterIndex: Int? = nil, indent: Int = 0,
                         forTaskId: String? = nil, location: String? = nil, color: String? = nil,
                         external: ExternalRef? = nil, reminderMin: Int? = nil, rrule: String? = nil,
                         labels: [String] = [], attendees: [String] = [], priority: String? = nil) throws -> String {
        try guardWritable()
        let id = newId()
        let block = Block.event(EventRef(id: id, title: title, time: time, rrule: rrule, forTaskId: forTaskId,
                                         external: external, color: color, location: location,
                                         reminderMin: reminderMin, labels: labels, attendees: attendees,
                                         priority: priority, indent: indent))
        if store.readPage(pageId) == nil {
            guard let (home, kind) = locateLine(pageId) else { throw ReadOnly(formatVersion: -2) }
            ensurePage(pageId, parent: home, type: kind)
        }
        try editPage(pageId, change: .structural) { page in
            var p = page
            p.blocks.insert(block, at: afterIndex.map { min($0 + 1, p.blocks.count) } ?? p.blocks.count)
            return p
        }
        return id
    }

    /// Which page holds an event's line, and the line's index.
    public func locate(eventId: String) -> (String, Int)? {
        for p in store.readPages() {
            if let i = p.blocks.firstIndex(where: { if case let .event(e) = $0 { return e.id == eventId }; return false }) { return (p.id, i) }
        }
        return nil
    }

    public func editEvent(_ eventId: String, _ transform: (EventRef) -> EventRef) throws {
        guard let (home, _) = locate(eventId: eventId) else { throw NotHere(what: "event", id: eventId) }
        try editPage(home) { page in
            var p = page
            p.blocks = p.blocks.map { b in
                if case let .event(e) = b, e.id == eventId { return .event(transform(e)) }
                return b
            }
            return p
        }
    }

    public func setEventTime(_ eventId: String, _ time: EventTime) throws {
        try editEvent(eventId) { var x = $0; x.time = time; return x }
    }

    public func setEventTitle(_ eventId: String, _ title: String) throws {
        try editEvent(eventId) { var x = $0; x.title = title; return x }
    }

    /// Removes an event line. A sitting is only ever time set aside, so deleting it leaves the task
    /// it was for exactly where it was — there is no page of its own to take with it.
    public func deleteEvent(_ eventId: String) throws {
        guard let (home, i) = locate(eventId: eventId) else { throw NotHere(what: "event", id: eventId) }
        try editPage(home, change: .structural) { page in
            var p = page
            guard i < p.blocks.count else { return p }
            p.blocks.remove(at: i)
            return p
        }
    }

    /// Cancels one occurrence of a repeat by writing the tombstone line the series reads, rather
    /// than rewriting the series line itself — which is what two devices cancelling two different
    /// days would otherwise collide on.
    @discardableResult
    public func cancelOccurrence(of seriesEventId: String, at start: LocalDateTime) throws -> String? {
        guard let (home, _) = locate(eventId: seriesEventId),
              let page = store.readPage(home),
              case let .event(series)? = page.blocks.first(where: { if case let .event(e) = $0 { return e.id == seriesEventId }; return false })
        else { return nil }
        let span = ISODuration(seconds: series.time.start.seconds(until: series.time.end))
        let time = EventTime(start: start, end: start.adding(seconds: span.seconds), zone: series.time.zone, allDay: series.time.allDay)
        try guardWritable()
        let id = newId()
        try editPage(home, change: .structural) { p in
            var page = p
            page.blocks.append(.event(EventRef(id: id, title: series.title, time: time,
                                               series: SeriesRef(series.id, originalStart: start), cancelled: true)))
            return page
        }
        return id
    }

    // MARK: other line fields

    public func setDeadline(_ taskId: String, _ deadline: LocalDate?) throws {
        try editTask(taskId) { var x = $0; x.deadline = deadline; return x }
    }

    public func setAssignee(_ taskId: String, _ assignee: String?) throws {
        try editTask(taskId) { var x = $0; x.assignee = assignee; return x }
    }

    public func setLabels(_ taskId: String, _ labels: [String]) throws {
        try editTask(taskId) { var x = $0; x.labels = labels; return x }
    }

    /// Links a task to a meeting in somebody else's calendar, or unlinks it.
    public func setExternal(_ taskId: String, _ external: ExternalRef?) throws {
        try editTask(taskId) { var x = $0; x.external = external; return x }
    }

    /// The colour a list wears, by palette name. On the page, because it is a choice somebody made.
    public func setPageColor(_ pageId: String, _ color: String?) throws {
        try editPage(pageId) { var p = $0; p.color = color; return p }
    }

    /// Pins a list to the top of Home, or takes it off — `NodeRepository.setPinned`. Always written
    /// as a value once somebody has chosen, so "said no" is never mistaken for "never said".
    public func setPinned(_ pageId: String, _ pinned: Bool) throws {
        try editPage(pageId) { var p = $0; p.pinned = pinned; return p }
    }

    /// The emoji a list wears instead of its mark. Cleaned to one user-perceived character on the
    /// way in, so what reaches the file is what the slot can actually hold.
    public func setPageIcon(_ pageId: String, _ icon: String?) throws {
        let cleaned = ListIcon.clean(icon)
        try editPage(pageId) { var p = $0; p.icon = cleaned; return p }
    }

    /// Which page holds a task's line, and the line's index.
    public func locate(taskId: String) -> (String, Int)? {
        for p in store.readPages() {
            if let i = p.blocks.firstIndex(where: { if case let .task(t) = $0 { return t.id == taskId }; return false }) { return (p.id, i) }
        }
        return nil
    }

    public func editTask(_ taskId: String, _ transform: (TaskRef) -> TaskRef) throws {
        guard let (home, _) = locate(taskId: taskId) else { throw NotHere(what: "task", id: taskId) }
        try editPage(home) { page in
            var p = page
            p.blocks = p.blocks.map { b in
                if case let .task(t) = b, t.id == taskId { return .task(transform(t)) }
                return b
            }
            return p
        }
    }

    public func setDone(_ taskId: String, _ done: Bool) throws {
        try editTask(taskId) { t in
            var x = t
            x.status = done ? .done : .open
            x.doneAt = done ? .today() : nil
            return x
        }
        onChange(.structural)
    }

    public func setInProgress(_ taskId: String, _ on: Bool) throws {
        try editTask(taskId) { t in var x = t; if x.status != .done { x.status = on ? .inProgress : .open }; return x }
    }

    public func setTitle(_ taskId: String, _ title: String) throws {
        try editTask(taskId) { t in var x = t; x.title = title; return x }
    }

    public func setDue(_ taskId: String, _ due: DueSpec?) throws {
        try editTask(taskId) { t in var x = t; x.due = due; return x }
    }

    public func setPriority(_ taskId: String, _ priority: String?) throws {
        try editTask(taskId) { t in var x = t; x.priority = priority; return x }
    }

    /// Edits a non-task block's text by its line on a page.
    public func editBlockText(pageId: String, index: Int, text: String) throws {
        try editPage(pageId) { page in
            var p = page
            guard index < p.blocks.count else { return p }
            switch p.blocks[index] {
            case let .prose(_, i, _): p.blocks[index] = .prose(text, indent: i)
            case let .heading(_, i, _): p.blocks[index] = .heading(text, indent: i)
            case let .bullet(_, i, _): p.blocks[index] = .bullet(text, indent: i)
            case let .numbered(_, i, _): p.blocks[index] = .numbered(text, indent: i)
            case .task(var t): t.title = text; p.blocks[index] = .task(t)
            case .event(var e): e.title = text; p.blocks[index] = .event(e)
            default: break
            }
            return p
        }
    }

    /// Nudges a block's visual indent, clamped so it cannot go negative or run away.
    ///
    /// Indent is *visual*, not nesting — a guillemet on the front of the line, which markdown reads
    /// as nothing. Four levels is as deep as a phone can show a line and still leave room for it.
    public func indentBlock(pageId: String, index: Int, by delta: Int) throws {
        try editPage(pageId) { page in
            var p = page
            guard index < p.blocks.count else { return p }
            let next = min(max(p.blocks[index].indent + delta, 0), 4)
            p.blocks[index] = p.blocks[index].withIndent(next)
            return p
        }
    }

    public func removeBlock(pageId: String, index: Int) throws {
        var removedTask: String?
        try editPage(pageId, change: .structural) { page in
            var p = page
            guard index < p.blocks.count else { return p }
            if case let .task(t) = p.blocks[index] { removedTask = t.id }
            if case let .ink(id, _, _) = p.blocks[index] { store.writeInk(id, []) }
            p.blocks.remove(at: index)
            return p
        }
        if let t = removedTask, !t.isEmpty {
            store.deletePage(t)
            try dropSittings(for: t)
        }
    }

    /// Removes the time set aside for a task that no longer exists.
    ///
    /// A **sitting** is an event line whose whole meaning is `for:<task>` — it carries no title of
    /// its own and draws with the task's words, because storing the name twice would give two places
    /// to rename from. So when the task goes, the sitting is not merely stale: it cannot even say
    /// what it is. It stayed on the calendar as a block with no words in it, which is exactly what
    /// "I deleted the task and it is still on my calendar" looks like.
    ///
    /// Every page, not just the task's own: a sitting lives beside the task's line, and a task that
    /// has been moved leaves its sittings on the page it came from.
    func dropSittings(for taskId: String) throws {
        for page in store.readPages() {
            guard page.blocks.contains(where: { if case let .event(e) = $0 { return e.forTaskId == taskId }; return false })
            else { continue }
            try editPage(page.id, change: .structural) { doc in
                var p = doc
                p.blocks.removeAll { if case let .event(e) = $0 { return e.forTaskId == taskId }; return false }
                return p
            }
        }
    }

    public func deletePage(_ id: String) throws {
        try guardWritable()
        store.deletePage(id)
        // The other half of the same rule as `removeBlock`: whatever time was set aside for this
        // goes with it, or the calendar keeps drawing a block for something that is not there.
        try dropSittings(for: id)
        onChange(.structural)
    }

    public func moveBlock(pageId: String, from: Int, to: Int) throws {
        try editPage(pageId, change: .structural) { page in
            var p = page
            guard from < p.blocks.count, to < p.blocks.count, from != to else { return p }
            let b = p.blocks.remove(at: from)
            p.blocks.insert(b, at: to)
            return p
        }
    }

    public func writeInk(_ nodeId: String, _ strokes: [Data]) throws {
        try guardWritable()
        store.writeInk(nodeId, strokes)
        onChange(.ink)
    }

    public func appendFocus(_ line: String, month: String) throws {
        try guardWritable()
        store.appendFocus(line, month: month)
        onChange(.edit)
    }

    /// Re-homes a task line onto another list's page (its own page, if any, moves parent too).
    ///
    /// **One workspace.** A writer owns one store, so a destination that lives in another
    /// repository is refused rather than attempted: `editPage` on a page this store does not have
    /// would invent one here and leave the task in neither place a person can reach. Moving between
    /// workspaces is a copy-then-delete across two stores — Android's `Workspaces.moveAcross` — and
    /// nothing offers it here yet.
    /// A write aimed at something this workspace does not hold.
    ///
    /// It used to be a silent `return`, and that is how an edit disappears: the picker offered a
    /// name, the line carried it, the write ran against the wrong store, found nothing, and said
    /// nothing — leaving a task with an empty assignee and no error anywhere to explain it. A write
    /// that cannot find its target is a bug in the caller, and the only way that becomes visible is
    /// if it is loud.
    public struct NotHere: Error, CustomStringConvertible {
        public let what: String, id: String
        public var description: String { "no \(what) with id \(id) in this workspace" }
    }

    /// A move whose destination is not in this writer's workspace.
    public struct MoveRefused: Error, CustomStringConvertible {
        public let reason: String
        public var description: String { reason }
    }

    public func moveTask(_ taskId: String, toList listId: String) throws {
        guard store.readPage(listId) != nil else {
            throw MoveRefused(reason: "that list is in another workspace")
        }
        guard let (home, i) = locate(taskId: taskId), home != listId else { return }
        var line: Block?
        try editPage(home, change: .structural) { page in
            var p = page; guard i < p.blocks.count else { return p }
            line = p.blocks[i]; p.blocks.remove(at: i); return p
        }
        guard let moved = line else { return }
        try editPage(listId, change: .structural) { var p = $0; p.blocks.append(moved.strippingRaw); return p }
        if store.readPage(taskId) != nil { try editPage(taskId) { var p = $0; p.parent = listId; return p } }
    }

    // MARK: smart lists and labels

    public func writeSmartList(_ def: SmartListDef) throws {
        try guardWritable(); store.writeSmartList(def); onChange(.structural)
    }

    /// `WorkspaceReconciler.idFor`: a label's id is its workspace and its lowercased name, minted
    /// here and nowhere else, and recomputed on read — the id in the file is a cache of this.
    public static func labelId(workspace: String, name: String) -> String { "\(workspace):label:\(name.lowercased())" }

    /// `LabelCanon.key`: one tag whatever its case or padding.
    static func labelKey(_ name: String) -> String { KText.trim(name).lowercased() }

    /// `WorkspaceWriter.upsertLabel`: whatever entry has this id **or this name** goes, and the new
    /// one is appended at the end. By name as well as id because one workspace cannot hold two
    /// definitions of one tag, and a registry that carries both cannot say which colour it means.
    /// Moving to the end on every write is Android's, and so are the bytes it produces.
    public func upsertLabel(_ label: LabelDef) throws {
        try guardWritable()
        let kept = store.readLabels().filter { $0.id != label.id && $0.name.lowercased() != label.name.lowercased() }
        store.writeLabels(kept + [label]); onChange(.edit)
    }

    /// Registers a name if the registry does not know it yet — `LabelRepository.getOrCreate`. A
    /// name it already knows (in any case, with any padding) is left exactly as it is: the
    /// registry's spelling and colour win, so this writes nothing.
    ///
    /// A new label is written **with** the colour its name seeds to, not with none. On Android a
    /// stored nil is a choice — the neutral chip somebody picked — so a label this device made
    /// without a colour came out grey on every other device while it was coloured here.
    public func upsertLabel(_ name: String, color: Int64? = nil) throws {
        let trimmed = KText.trim(name)
        if store.readLabels().contains(where: { Self.labelKey($0.name) == Self.labelKey(trimmed) }) { return }
        try upsertLabel(LabelDef(id: Self.labelId(workspace: store.id, name: trimmed), name: trimmed,
                                 color: color ?? LabelPalette.defaultFor(trimmed)))
    }

    /// Recolours a label — `LabelRepository.setColor`, which writes `upsertLabel` with the label's
    /// own id and the registry's spelling of its name. Nil is the neutral chip, never "pick one for
    /// me". A tag the registry has not heard of yet (it is only on lines) is added with the colour.
    public func setLabelColor(_ name: String, _ color: Int64?) throws {
        let existing = store.readLabels().first { Self.labelKey($0.name) == Self.labelKey(name) }
        let spelled = existing?.name ?? name
        try upsertLabel(LabelDef(id: Self.labelId(workspace: store.id, name: spelled), name: spelled, color: color))
    }

    /// Removes a label from the workspace **and** the tag from every task line carrying it —
    /// `WorkspaceWriter.deleteLabel`. Both halves, or it comes back: the reconciler derives a label
    /// from any `#tag` on a line, so forgetting the lines leaves a label that reappears on the next
    /// reindex. Case-insensitive, like Android's. Returns how many lines it changed.
    @discardableResult
    public func deleteLabel(_ name: String) throws -> Int {
        try guardWritable()
        func matches(_ s: String) -> Bool { s.lowercased() == name.lowercased() }
        store.writeLabels(store.readLabels().filter { !matches($0.name) })
        var stripped = 0
        for page in store.readPages() {
            var touched = false
            var p = page
            p.blocks = page.blocks.map { b in
                guard case var .task(t) = b, t.labels.contains(where: matches) else { return b }
                touched = true; stripped += 1
                // Written from the block, not from the bytes it was read as — those still hold the tag.
                t.labels.removeAll(where: matches); t.raw = nil
                return .task(t)
            }
            if touched { p.modifiedAt = Date(); p.device = device; store.writePage(p) }
        }
        onChange(.structural)
        return stripped
    }

    // MARK: archive — finished tasks leave on a threshold, and can come back

    /// A DONE task finished before `before` with no unfinished children leaves its page: the line is
    /// appended to archive/<pageId>.md and its own page moves to archive/pages/. Returns how many moved.
    public func archiveFinished(before: LocalDate, hasUnfinishedChildren: (String) -> Bool) throws -> Int {
        try guardWritable()
        var moved = 0
        for page in store.readPages() {
            let leaving = page.blocks.compactMap { b -> TaskRef? in
                guard case let .task(t) = b, t.status == .done, let d = t.doneAt, d < before, !hasUnfinishedChildren(t.id) else { return nil }
                return t
            }
            if leaving.isEmpty { continue }
            store.writeArchivedLines(page.id, store.readArchivedLines(page.id) + leaving.map { PageCodec.encodeBlock(.task($0)) })
            for t in leaving { store.moveToArchive(t.id) }
            let ids = Set(leaving.map(\.id))
            var p = page
            p.blocks = p.blocks.filter { if case let .task(t) = $0 { return !ids.contains(t.id) }; return true }
            p.modifiedAt = Date(); p.device = device
            store.writePage(p)
            moved += leaving.count
        }
        if moved > 0 { onChange(.structural) }
        return moved
    }

    public func restoreArchived(pageId: String, taskIds: Set<String>) throws -> Int {
        try guardWritable()
        let archived = store.readArchivedLines(pageId)
        if archived.isEmpty { return 0 }
        let decoded = archived.map { ($0, PageCodec.decodeBlock($0)) }
        let coming = decoded.filter { if case let .task(t) = $0.1 { return taskIds.contains(t.id) }; return false }
        if coming.isEmpty { return 0 }
        guard var page = store.readPage(pageId) else { return 0 }
        page.blocks += coming.map(\.1)
        page.modifiedAt = Date(); page.device = device
        store.writePage(page)
        for (_, b) in coming { if case let .task(t) = b { store.restoreFromArchive(t.id) } }
        store.writeArchivedLines(pageId, decoded.filter { d in !coming.contains { $0.0 == d.0 } }.map(\.0))
        onChange(.structural)
        return coming.count
    }

    public func archivedCount() -> Int { store.archivedPageIds().reduce(0) { $0 + store.readArchivedLines($1).count } }

    public func renamePage(_ id: String, _ title: String) throws {
        try editPage(id) { var p = $0; p.title = title; return p }
    }
}
