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

    public func renamePage(_ id: String, _ title: String) throws {
        try editPage(id) { var p = $0; p.title = title; return p }
    }
}
