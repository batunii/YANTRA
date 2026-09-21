import Foundation

// MARK: - meta files

public struct Manifest: Codable, Equatable, Sendable {
    public var formatVersion: Int = 1
    public var name: String
    public var createdAt: Int64
    public var epoch: Int = 1
    public var archiveAfterDays: Int = 0
    enum CodingKeys: String, CodingKey { case formatVersion, name, createdAt, epoch, archiveAfterDays = "archive_after_days" }
    public init(formatVersion: Int = 1, name: String, createdAt: Int64, epoch: Int = 1, archiveAfterDays: Int = 0) {
        self.formatVersion = formatVersion; self.name = name; self.createdAt = createdAt; self.epoch = epoch; self.archiveAfterDays = archiveAfterDays
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        formatVersion = try c.decodeIfPresent(Int.self, forKey: .formatVersion) ?? 1
        name = try c.decode(String.self, forKey: .name)
        createdAt = try c.decode(Int64.self, forKey: .createdAt)
        epoch = try c.decodeIfPresent(Int.self, forKey: .epoch) ?? 1
        archiveAfterDays = try c.decodeIfPresent(Int.self, forKey: .archiveAfterDays) ?? 0
    }
    /// Written the way Kotlin writes it: formatVersion always, defaults omitted, this key order.
    public func compact() -> String {
        var s = "{\"formatVersion\":\(formatVersion),\"name\":\(JSONText.quote(name)),\"createdAt\":\(createdAt)"
        if epoch != 1 { s += ",\"epoch\":\(epoch)" }
        if archiveAfterDays != 0 { s += ",\"archive_after_days\":\(archiveAfterDays)" }
        return s + "}"
    }
    /// False when the repository was written by a newer app than this one.
    public var readable: Bool { formatVersion <= WorkspaceStore.formatVersion }
}

public struct PropertyDef: Codable, Equatable, Sendable {
    public var id: String, name: String, kind: String, config: String?
    public init(id: String, name: String, kind: String, config: String? = nil) { self.id = id; self.name = name; self.kind = kind; self.config = config }
}

public struct LabelDef: Codable, Equatable, Sendable {
    public var id: String, name: String, color: Int64?
    public init(id: String, name: String, color: Int64? = nil) { self.id = id; self.name = name; self.color = color }
}

public struct SmartListDef: Codable, Equatable, Sendable {
    public var nodeId: String
    public var scopeRootId: String?
    public var filterJson: String
    public var sortJson: String?
    public var homeParentId: String?
    public var applyOnCreateJson: String?
    public init(nodeId: String, scopeRootId: String? = nil, filterJson: String, sortJson: String? = nil, homeParentId: String? = nil, applyOnCreateJson: String? = nil) {
        self.nodeId = nodeId; self.scopeRootId = scopeRootId; self.filterJson = filterJson; self.sortJson = sortJson
        self.homeParentId = homeParentId; self.applyOnCreateJson = applyOnCreateJson
    }
    public var filter: Filter? { FilterJSON.decode(Filter.self, filterJson) }
    public var sort: [SortSpec] { sortJson.flatMap { FilterJSON.decode([SortSpec].self, $0) } ?? [] }
    public var applyOnCreate: [ApplyOnCreate] { applyOnCreateJson.flatMap { FilterJSON.decode([ApplyOnCreate].self, $0) } ?? [] }
}

// MARK: - the store

/// The on-disk shape of a workspace — `GIT_WORKSPACES_PLAN.md` §2. Plain files in a plain
/// directory; knows nothing about git or an index. Every write is temp-file plus rename.
public final class WorkspaceStore: @unchecked Sendable {
    /// The newest workspace format this build reads and writes.
    public static let formatVersion = 2
    public static let manifestPath = ".yantra/manifest.json"
    public static let focusDir = "focus", legacyFocusDir = "pomodoro"

    public let root: URL
    public let id: String
    private let fm = FileManager.default

    public init(root: URL, id: String = "") { self.root = root; self.id = id }

    var metaDir: URL { root.appendingPathComponent(".yantra") }
    var pagesDir: URL { root.appendingPathComponent("pages") }
    var manifestFile: URL { metaDir.appendingPathComponent("manifest.json") }
    var labelsFile: URL { metaDir.appendingPathComponent("meta/labels.json") }
    var propsFile: URL { metaDir.appendingPathComponent("meta/properties.json") }
    var smartDir: URL { metaDir.appendingPathComponent("meta/smartlists") }

    public var exists: Bool { fm.fileExists(atPath: manifestFile.path) }

    public func scaffold(name: String, now: Int64) {
        try? fm.createDirectory(at: pagesDir, withIntermediateDirectories: true)
        try? fm.createDirectory(at: smartDir, withIntermediateDirectories: true)
        writeManifest(Manifest(formatVersion: Self.formatVersion, name: name, createdAt: now))
        writeProperties(Self.builtInProperties())
        writeLabels([])
    }

    /// The fixed fields every workspace has. Ids are literal strings shared by both apps.
    public static func builtInProperties() -> [PropertyDef] {
        let cfg = #"{"options":[{"name":"High","color":4294986271},{"name":"Medium","color":4294946848},{"name":"Low","color":4283076825}]}"#
        return [
            PropertyDef(id: BuiltIns.priority, name: "Priority", kind: "select", config: cfg),
            PropertyDef(id: BuiltIns.due, name: "Due", kind: "date"),
            PropertyDef(id: BuiltIns.deadline, name: "Deadline", kind: "date"),
            PropertyDef(id: BuiltIns.assignee, name: "Assignee", kind: "text"),
        ]
    }

    // manifest
    public func readManifest() -> Manifest? {
        guard let d = try? Data(contentsOf: manifestFile) else { return nil }
        return try? JSONDecoder().decode(Manifest.self, from: d)
    }
    public func writeManifest(_ m: Manifest) { write(manifestFile, Data(m.compact().utf8)) }
    public var isReadOnly: Bool { readManifest().map { !$0.readable } ?? false }

    // pages
    public func pageFile(_ id: String) -> URL { pagesDir.appendingPathComponent("\(id).md") }
    public func readPage(_ id: String) -> PageDoc? {
        guard let s = try? String(contentsOf: pageFile(id), encoding: .utf8) else { return nil }
        return PageCodec.decode(s)
    }
    public func readPages() -> [PageDoc] {
        let names = ((try? fm.contentsOfDirectory(atPath: pagesDir.path)) ?? []).filter { $0.hasSuffix(".md") }.sorted()
        return names.compactMap { readPage(String($0.dropLast(3))) }
    }
    public func writePage(_ page: PageDoc) { write(pageFile(page.id), Data(PageCodec.encode(page).utf8)) }
    public func deletePage(_ id: String) {
        // The page, its descendants' pages, and the sidecars its blocks own.
        if let p = readPage(id) {
            for b in p.blocks {
                if case let .task(t) = b, !t.id.isEmpty { deletePage(t.id) }
                if case let .ink(inkId, _, _) = b { try? fm.removeItem(at: inkFile(inkId)) }
                if case let .image(uri, _, _) = b { try? fm.removeItem(at: imageFile(uri)) }
            }
        }
        try? fm.removeItem(at: pageFile(id))
        try? fm.removeItem(at: inkFile(id))
    }

    // ink and images
    public func inkFile(_ id: String) -> URL { pagesDir.appendingPathComponent("\(id).ink") }
    public func readInk(_ id: String) -> [Data] {
        guard let d = try? Data(contentsOf: inkFile(id)) else { return [] }
        return (try? InkSidecar.decode(d)) ?? []
    }
    public func writeInk(_ id: String, _ strokes: [Data]) {
        if strokes.isEmpty { try? fm.removeItem(at: inkFile(id)) } else { write(inkFile(id), InkSidecar.encode(strokes)) }
    }
    public func imageFile(_ id: String) -> URL { pagesDir.appendingPathComponent("\(id).jpg") }
    public func writeImage(_ id: String, _ bytes: Data) { write(imageFile(id), bytes) }

    // meta
    public func readLabels() -> [LabelDef] { readList(labelsFile) }
    public func writeLabels(_ l: [LabelDef]) { write(labelsFile, Data(FilterJSON.encode(l).utf8)) }
    public func readProperties() -> [PropertyDef] { readList(propsFile) }
    public func writeProperties(_ p: [PropertyDef]) { write(propsFile, Data(FilterJSON.encode(p).utf8)) }
    public func readSmartLists() -> [SmartListDef] {
        let names = ((try? fm.contentsOfDirectory(atPath: smartDir.path)) ?? []).filter { $0.hasSuffix(".json") }.sorted()
        return names.compactMap { n in (try? Data(contentsOf: smartDir.appendingPathComponent(n))).flatMap { try? JSONDecoder().decode(SmartListDef.self, from: $0) } }
    }
    public func writeSmartList(_ def: SmartListDef) { write(smartDir.appendingPathComponent("\(def.nodeId).json"), Data(FilterJSON.encode(def).utf8)) }
    public func deleteSmartList(_ nodeId: String) { try? fm.removeItem(at: smartDir.appendingPathComponent("\(nodeId).json")) }

    // archive: one archived task LINE per line in archive/<pageId>.md; the task's own page moves to archive/pages/
    var archiveDir: URL { root.appendingPathComponent("archive") }
    public func archiveFile(_ pageId: String) -> URL { archiveDir.appendingPathComponent("\(pageId).md") }
    public func archivedPageFile(_ pageId: String) -> URL { archiveDir.appendingPathComponent("pages/\(pageId).md") }
    public func archivedPageIds() -> [String] {
        ((try? fm.contentsOfDirectory(atPath: archiveDir.path)) ?? []).filter { $0.hasSuffix(".md") }.map { String($0.dropLast(3)) }.sorted()
    }
    public func readArchivedLines(_ pageId: String) -> [String] {
        guard let s = try? String(contentsOf: archiveFile(pageId), encoding: .utf8) else { return [] }
        return s.split(separator: "\n").map(String.init).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }
    public func writeArchivedLines(_ pageId: String, _ lines: [String]) {
        if lines.isEmpty { try? fm.removeItem(at: archiveFile(pageId)); return }
        write(archiveFile(pageId), Data((lines.joined(separator: "\n") + "\n").utf8))
    }
    public func moveToArchive(_ pageId: String) {
        let from = pageFile(pageId); guard fm.fileExists(atPath: from.path) else { return }
        try? fm.createDirectory(at: archiveDir.appendingPathComponent("pages"), withIntermediateDirectories: true)
        try? fm.removeItem(at: archivedPageFile(pageId)); try? fm.moveItem(at: from, to: archivedPageFile(pageId))
    }
    public func restoreFromArchive(_ pageId: String) {
        let from = archivedPageFile(pageId); guard fm.fileExists(atPath: from.path) else { return }
        try? fm.removeItem(at: pageFile(pageId)); try? fm.moveItem(at: from, to: pageFile(pageId))
    }

    // focus log
    public func appendFocus(_ line: String, month: String) {
        let dir = root.appendingPathComponent(Self.focusDir)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let f = dir.appendingPathComponent("\(month).log")
        if let h = try? FileHandle(forWritingTo: f) { h.seekToEndOfFile(); h.write(Data((line + "\n").utf8)); try? h.close() }
        else { try? Data((line + "\n").utf8).write(to: f) }
    }
    public func readFocusLines() -> [String] {
        var out: [String] = []
        for dir in [Self.legacyFocusDir, Self.focusDir] {
            let d = root.appendingPathComponent(dir)
            let names = ((try? fm.contentsOfDirectory(atPath: d.path)) ?? []).filter { $0.hasSuffix(".log") }.sorted()
            for n in names { if let s = try? String(contentsOf: d.appendingPathComponent(n), encoding: .utf8) { out += s.split(separator: "\n").map(String.init) } }
        }
        return out
    }

    // io
    private func readList<T: Decodable>(_ f: URL) -> [T] {
        guard let d = try? Data(contentsOf: f) else { return [] }
        return (try? JSONDecoder().decode([T].self, from: d)) ?? []
    }
    private func write(_ f: URL, _ data: Data) {
        try? fm.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
        let tmp = f.appendingPathExtension("tmp")
        do {
            try data.write(to: tmp)
            _ = try fm.replaceItemAt(f, withItemAt: tmp)
        } catch {
            try? data.write(to: f)
        }
    }
}

// MARK: - seeding

public enum WorkspaceSeeder {
    /// Fresh local workspace content. Gated on having just scaffolded the directory, never on an
    /// empty index — otherwise every clone would make a second Inbox and Today.
    public static func seed(_ store: WorkspaceStore, now: Date = Date()) {
        func id() -> String { UUID().uuidString.lowercased() }
        let inbox = id()
        store.writePage(PageDoc(id: inbox, type: NodeType.list, parent: nil, title: "Inbox", systemKey: SystemKey.inbox, modifiedAt: now, device: nil, blocks: []))

        let sample = id(), started = id(), gettingStarted = id()
        store.writePage(PageDoc(id: gettingStarted, type: NodeType.list, parent: nil, title: "Getting started", modifiedAt: now, device: nil, blocks: [
            .task(TaskRef(id: sample, title: "Try opening this task as a page", due: DueSpec(.allDay(.today())), priority: "High")),
            .task(TaskRef(id: started, title: "Start a focus on any task", priority: "Medium")),
            .task(TaskRef(id: id(), title: "Add an ink block and scribble on it ✏️")),
        ]))
        store.writePage(PageDoc(id: sample, type: NodeType.task, parent: gettingStarted, title: nil, modifiedAt: now, device: nil, blocks: [
            .heading("Welcome 👋"),
            .prose("A list holds tasks. A task's own page holds anything — notes, headings, lists, ink sketches, images — and other tasks."),
            .task(TaskRef(id: id(), title: "Tasks can nest — this is a subtask")),
        ]))

        let todayFilter: Filter = .all([.type(NodeType.task), .done(false), .anyOf([
            .prop(defId: BuiltIns.due, op: .lte, dateRel: .todayEnd),
            .prop(defId: BuiltIns.deadline, op: .lte, dateRel: .todayEnd),
        ])])
        let today = id()
        store.writePage(PageDoc(id: today, type: NodeType.smartList, parent: nil, title: "Today", systemKey: SystemKey.today, modifiedAt: now, device: nil, blocks: []))
        store.writeSmartList(SmartListDef(nodeId: today, filterJson: FilterJSON.encode(todayFilter),
                                          sortJson: FilterJSON.encode([SortSpec(by: .propDate, defId: BuiltIns.due)]), homeParentId: inbox,
                                          applyOnCreateJson: FilterJSON.encode(deriveApplyOnCreate(todayFilter))))
        let high = id()
        store.writePage(PageDoc(id: high, type: NodeType.smartList, parent: nil, title: "High Priority", modifiedAt: now, device: nil, blocks: []))
        store.writeSmartList(SmartListDef(nodeId: high,
                                          filterJson: FilterJSON.encode(Filter.all([.type(NodeType.task), .done(false), .prop(defId: BuiltIns.priority, op: .eq, text: "High")])),
                                          sortJson: FilterJSON.encode([SortSpec(by: .created, desc: true)]), homeParentId: inbox,
                                          applyOnCreateJson: FilterJSON.encode([ApplyOnCreate(defId: BuiltIns.priority, text: "High")])))
    }
}

// MARK: - the index (in memory for now; Android keeps it in Room)

/// What a page's lines become: one node per block, plus the page nodes themselves.
public struct Node: Identifiable, Equatable, Sendable {
    public var id: String
    public var workspaceId: String
    public var parentId: String?
    public var type: String
    public var title: String?
    public var rank: String
    public var done: Bool
    public var inProgress: Bool
    public var indent: Int
    public var systemKey: String?
    public var createdAt: Int64
    // The task-line values, denormalised (Android keeps them in property_value).
    public var due: DueSpec?
    public var deadline: LocalDate?
    public var priority: String?
    public var labels: [String]
    public var assignee: String?
    public var doneAt: LocalDate?
    /// Which page file holds this node's line (nil for top-level pages).
    public var homePageId: String?
    /// Index of the line on its home page.
    public var lineIndex: Int?
    /// The ink block id or image payload, for those block kinds.
    public var payload: String?
    /// The meeting in somebody else's calendar this line is about, when it is about one.
    public var external: ExternalRef?
    /// The emoji a list wears, and the palette name it wears — both only on a page node.
    public var icon: String?
    public var color: String?
    /// The whole event line, for an event node.
    ///
    /// Kept intact rather than spread across the fields above: an event's span, zone, rule and
    /// colour are not a due date with extras, and flattening them here would mean rebuilding an
    /// EventRef — badly — everywhere one is written back.
    public var event: EventRef?

    public init(id: String, workspaceId: String, parentId: String?, type: String, title: String?, rank: String,
                done: Bool, inProgress: Bool, indent: Int, systemKey: String?, createdAt: Int64,
                due: DueSpec? = nil, deadline: LocalDate? = nil, priority: String? = nil, labels: [String] = [],
                assignee: String? = nil, doneAt: LocalDate? = nil, homePageId: String? = nil, lineIndex: Int? = nil,
                payload: String? = nil, external: ExternalRef? = nil, event: EventRef? = nil,
                icon: String? = nil, color: String? = nil) {
        self.id = id; self.workspaceId = workspaceId; self.parentId = parentId; self.type = type; self.title = title
        self.rank = rank; self.done = done; self.inProgress = inProgress; self.indent = indent; self.systemKey = systemKey
        self.createdAt = createdAt; self.due = due; self.deadline = deadline; self.priority = priority; self.labels = labels
        self.assignee = assignee; self.doneAt = doneAt; self.homePageId = homePageId; self.lineIndex = lineIndex
        self.payload = payload; self.external = external; self.event = event
        self.icon = icon; self.color = color
    }

    /// Due as an instant for filtering and sorting: all-day is local midnight.
    public var dueDate: Date? {
        switch due?.value { case let .allDay(d)?: return d.startOfDay(); case let .at(t)?: return t; case nil: return nil }
    }
    public var dueHasTime: Bool { if case .at? = due?.value { return true }; return false }
}

/// The whole workspace, read from files. A pure function of the directory — `WorkspaceReconciler`.
public struct WorkspaceIndex: Sendable {
    public init() {}
    public var nodes: [String: Node] = [:]
    public var smartLists: [String: SmartListDef] = [:]
    public var labels: [LabelDef] = []
    public var properties: [PropertyDef] = []
    public var problems: [String] = []

    public static func blockId(pageId: String, index: Int) -> String { "\(pageId)~\(index)" }

    public static func read(_ store: WorkspaceStore) -> WorkspaceIndex {
        var ix = WorkspaceIndex()
        let ws = store.id
        let pages = store.readPages()
        let stamp = store.readManifest()?.createdAt ?? 0
        var lineNodes: [String: Node] = [:]     // nodes produced by lines, by id
        var pageDocs: [String: PageDoc] = [:]
        for p in pages { pageDocs[p.id] = p }

        for p in pages {
            var rank = Rank.after(nil)
            for (i, b) in p.blocks.enumerated() {
                if i > 0 { rank = Rank.after(rank) }
                var n = Node(id: "", workspaceId: ws, parentId: p.id, type: b.nodeType, title: b.text, rank: rank, done: false, inProgress: false,
                             indent: b.indent, systemKey: nil, createdAt: stamp, due: nil, deadline: nil, priority: nil, labels: [], assignee: nil,
                             doneAt: nil, homePageId: p.id, lineIndex: i, payload: nil)
                switch b {
                case let .task(t):
                    n.id = t.id.isEmpty ? blockId(pageId: p.id, index: i) : t.id
                    n.done = t.status == .done; n.inProgress = t.status == .inProgress
                    n.due = t.due; n.deadline = t.deadline; n.priority = t.priority; n.labels = t.labels; n.assignee = t.assignee; n.doneAt = t.doneAt
                    n.external = t.external
                case let .event(e):
                    // Positional only when the line carries no id, exactly as a task line is: an
                    // event written by hand has no `^id` until something edits it.
                    n.id = e.id.isEmpty ? blockId(pageId: p.id, index: i) : e.id
                    n.event = e
                    n.priority = e.priority
                    n.labels = e.labels
                case let .ink(id, _, _): n.id = id; n.payload = id
                case let .image(uri, _, _): n.id = blockId(pageId: p.id, index: i); n.payload = uri
                default: n.id = blockId(pageId: p.id, index: i)
                }
                lineNodes[n.id] = n
            }
        }
        for p in pages {
            if p.parent == nil {
                ix.nodes[p.id] = Node(id: p.id, workspaceId: ws, parentId: nil, type: p.type, title: p.title, rank: Rank.first, done: false, inProgress: false,
                                      indent: 0, systemKey: p.systemKey, createdAt: stamp, due: nil, deadline: nil, priority: nil, labels: [], assignee: nil,
                                      doneAt: nil, homePageId: nil, lineIndex: nil, payload: nil,
                                      icon: p.icon, color: p.color)
            } else if let line = lineNodes[p.id] {
                // The page file supplies identity and body; the parent's line supplies title, status, indent, rank.
                var n = line; n.systemKey = p.systemKey
                lineNodes[p.id] = n
            } else {
                ix.problems.append("page \(p.id) has a parent but no line names it")
                ix.nodes[p.id] = Node(id: p.id, workspaceId: ws, parentId: p.parent, type: p.type, title: p.title, rank: Rank.first, done: false, inProgress: false,
                                      indent: 0, systemKey: p.systemKey, createdAt: stamp, due: nil, deadline: nil, priority: nil, labels: [], assignee: nil,
                                      doneAt: nil, homePageId: nil, lineIndex: nil, payload: nil)
            }
        }
        for (id, n) in lineNodes { ix.nodes[id] = n }
        for def in store.readSmartLists() { ix.smartLists[def.nodeId] = def }
        ix.labels = store.readLabels()
        ix.properties = store.readProperties()
        return ix
    }

    public func children(of parentId: String?) -> [Node] {
        nodes.values.filter { $0.parentId == parentId }.sorted { $0.rank < $1.rank }
    }
    public func node(systemKey: String) -> Node? { nodes.values.first { $0.systemKey == systemKey } }
    public func title(of id: String) -> String? { nodes[id]?.title }
    /// Root to parent, like Android's `ancestors`.
    public func ancestors(of id: String) -> [Node] {
        var out: [Node] = []; var cur = nodes[id]?.parentId
        while let c = cur, let n = nodes[c] { out.insert(n, at: 0); cur = n.parentId }
        return out
    }
    public func hasOpenChildren(_ id: String) -> Bool { children(of: id).contains { $0.type == NodeType.task && !$0.done } }
    public func openChildCount(_ id: String) -> Int { children(of: id).filter { $0.type == NodeType.task && !$0.done }.count }
}

// MARK: - evaluating a smart list over the index

/// The read side of a smart list, over the in-memory index. Semantically what `FilterCompiler`'s
/// SQL asks; the SQL port comes with the SQLite index.
public enum SmartListQuery {
    public static func run(_ def: SmartListDef, in ix: WorkspaceIndex, now: Date = Date()) -> [Node] {
        guard let f = def.filter else { return [] }
        let todayStart = LocalDate.today().startOfDay()
        let todayEnd = LocalDate.today().adding(days: 1).startOfDay().addingTimeInterval(-0.001)
        var pool = Array(ix.nodes.values)
        if let scope = def.scopeRootId { pool = pool.filter { ix.ancestors(of: $0.id).contains { $0.id == scope } } }
        var out = pool.filter { matches(f, $0, ix, todayStart: todayStart, todayEnd: todayEnd) }
        let specs = def.sort
        out.sort { a, b in
            // Started tasks first, always.
            if a.inProgress != b.inProgress { return a.inProgress }
            for s in specs {
                switch s.by {
                case .propDate:
                    let da = s.defId == BuiltIns.deadline ? a.deadline?.startOfDay() : a.dueDate
                    let db = s.defId == BuiltIns.deadline ? b.deadline?.startOfDay() : b.dueDate
                    if da == db { continue }
                    guard let x = da else { return !s.nullsLast }
                    guard let y = db else { return s.nullsLast }
                    return s.desc ? x > y : x < y
                case .title:
                    let x = (a.title ?? "").lowercased(), y = (b.title ?? "").lowercased()
                    if x == y { continue }
                    return s.desc ? x > y : x < y
                case .created:
                    if a.createdAt == b.createdAt { continue }
                    return s.desc ? a.createdAt > b.createdAt : a.createdAt < b.createdAt
                case .propText:
                    let x = a.priority ?? "", y = b.priority ?? ""
                    if x == y { continue }
                    return s.desc ? x > y : x < y
                case .propNumber: continue
                }
            }
            if specs.isEmpty, a.createdAt != b.createdAt { return a.createdAt > b.createdAt }
            return a.rank < b.rank
        }
        return out
    }

    static func matches(_ f: Filter, _ n: Node, _ ix: WorkspaceIndex, todayStart: Date, todayEnd: Date) -> Bool {
        switch f {
        case let .all(fs): return fs.allSatisfy { matches($0, n, ix, todayStart: todayStart, todayEnd: todayEnd) }
        case let .anyOf(fs): return fs.isEmpty || fs.contains { matches($0, n, ix, todayStart: todayStart, todayEnd: todayEnd) }
        case let .not(x): return !matches(x, n, ix, todayStart: todayStart, todayEnd: todayEnd)
        case let .done(v): return n.done == v
        case let .inProgress(v): return n.inProgress == v
        case let .type(t): return n.type == t
        case let .hasLabel(id):
            let name = id.components(separatedBy: ":label:").last?.lowercased() ?? id.lowercased()
            return n.labels.contains { $0.lowercased() == name }
        case let .inWorkspace(w): return n.workspaceId == w
        case let .prop(defId, op, text, number, date, bool, dateRel):
            let value: Any?
            switch defId {
            case BuiltIns.due: value = n.dueDate
            case BuiltIns.deadline: value = n.deadline?.startOfDay()
            case BuiltIns.priority: value = n.priority
            case BuiltIns.assignee: value = n.assignee
            default: value = nil
            }
            switch op {
            case .isSet: return value != nil
            case .notSet: return value == nil
            default: break
            }
            if let rel = dateRel {
                guard let d = value as? Date else { return false }
                let bound = rel == .todayStart ? todayStart : todayEnd
                return compare(op, d.timeIntervalSince1970, bound.timeIntervalSince1970)
            }
            if let t = text { guard let v = value as? String else { return false }; return op == .neq ? v != t : v == t }
            if let ms = date { guard let d = value as? Date else { return false }; return compare(op, d.timeIntervalSince1970 * 1000, Double(ms)) }
            if let x = number { guard let v = value as? Double else { return false }; return compare(op, v, x) }
            if let b = bool { guard let v = value as? Bool else { return false }; return op == .neq ? v != b : v == b }
            return false
        }
    }

    static func compare(_ op: Op, _ a: Double, _ b: Double) -> Bool {
        switch op { case .eq: return a == b; case .neq: return a != b; case .lt: return a < b; case .lte: return a <= b; case .gt: return a > b; case .gte: return a >= b; default: return false }
    }
}
