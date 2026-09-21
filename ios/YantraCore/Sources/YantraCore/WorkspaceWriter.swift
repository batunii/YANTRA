import Foundation

/// Every write the app makes, expressed as a change to a file — the port of `WorkspaceWriter.kt`.
/// The file changes first; the index only ever learns what the files already say.
public final class WorkspaceWriter {
    public enum Change { case structural, edit, ink }
    public struct ReadOnly: Error { public let formatVersion: Int }

    public let store: WorkspaceStore
    public let device: String?
    public var onChange: (Change) -> Void = { _ in }
    private let lock = NSLock()

    public init(store: WorkspaceStore, device: String?) { self.store = store; self.device = device }

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

    /// A task's page exists only once it holds something.
    private func ensurePage(_ id: String, parent: String) {
        if store.readPage(id) == nil {
            store.writePage(PageDoc(id: id, type: NodeType.task, parent: parent, title: nil, modifiedAt: Date(), device: device, blocks: []))
        }
    }

    /// Adds a block to a page, after `afterIndex` or at the end. Mints an id for tasks and ink.
    public func addBlock(to pageId: String, type: String, text: String, afterIndex: Int? = nil, indent: Int = 0,
                         due: DueSpec? = nil, priority: String? = nil, labels: [String] = []) throws -> String {
        try guardWritable()
        var id = ""
        let block: Block
        switch type {
        case NodeType.task: id = newId(); block = .task(TaskRef(id: id, title: text, indent: indent, due: due, priority: priority, labels: labels))
        case NodeType.ink: id = newId(); block = .ink(id: id, indent: indent)
        case NodeType.heading: block = .heading(text, indent: indent)
        case NodeType.bullet: block = .bullet(text, indent: indent)
        case NodeType.numbered: block = .numbered(text, indent: indent)
        default: block = .prose(text, indent: indent)
        }
        // A task's page is created lazily when it first holds a block. If the target is a task with
        // no page yet, make one under its home page.
        if store.readPage(pageId) == nil {
            guard let (home, _) = locate(taskId: pageId) else { throw ReadOnly(formatVersion: -2) }
            ensurePage(pageId, parent: home)
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
            guard let (home, _) = locate(taskId: pageId) else { throw ReadOnly(formatVersion: -2) }
            ensurePage(pageId, parent: home)
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
        guard let (home, _) = locate(eventId: eventId) else { return }
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
        guard let (home, i) = locate(eventId: eventId) else { return }
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

    /// Which page holds a task's line, and the line's index.
    public func locate(taskId: String) -> (String, Int)? {
        for p in store.readPages() {
            if let i = p.blocks.firstIndex(where: { if case let .task(t) = $0 { return t.id == taskId }; return false }) { return (p.id, i) }
        }
        return nil
    }

    public func editTask(_ taskId: String, _ transform: (TaskRef) -> TaskRef) throws {
        guard let (home, _) = locate(taskId: taskId) else { return }
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
        if let t = removedTask, !t.isEmpty { store.deletePage(t) }
    }

    public func deletePage(_ id: String) throws {
        try guardWritable()
        store.deletePage(id)
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
    public func moveTask(_ taskId: String, toList listId: String) throws {
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

    /// Adds a name to the label registry if it is new; colour stays nil until someone picks one.
    public func upsertLabel(_ name: String, color: Int64? = nil) throws {
        try guardWritable()
        var labels = store.readLabels()
        if let i = labels.firstIndex(where: { $0.name.lowercased() == name.lowercased() }) {
            if let color, labels[i].color != color { labels[i].color = color } else { return }
        } else {
            labels.append(LabelDef(id: "\(store.id):label:\(name.lowercased())", name: name, color: color))
        }
        store.writeLabels(labels); onChange(.edit)
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
