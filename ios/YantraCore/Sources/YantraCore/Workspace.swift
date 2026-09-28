import Foundation

// MARK: - meta files

/// `.yantra/manifest.json` — `data class Manifest` in `WorkspaceStore.kt`.
///
/// **Every field at its default is left out**, `formatVersion` included, because that is what
/// `encodeDefaults = false` does: a current manifest is `{"name":…,"createdAt":…}` and nothing
/// else, and a missing `formatVersion` reads as 2. That makes the version gate unable to fire for
/// version 2 — an older Android build, whose default was 1, reads the same missing key as 1 — and
/// matching it is still right: a manifest that says `"formatVersion":2` is a byte Android would
/// rewrite. Only a version above 2 is ever written out, and that is the one the gate is for.
public struct Manifest: Codable, Equatable, Sendable {
    public var formatVersion: Int = WorkspaceStore.formatVersion
    public var name: String
    public var createdAt: Int64
    /// Bumped when history is rewritten, so other devices know to reclone rather than merge.
    public var epoch: Int = 1
    /// Days a finished task stays before it is archived. Zero is never.
    public var archiveAfterDays: Int = 0
    enum CodingKeys: String, CodingKey { case formatVersion, name, createdAt, epoch, archiveAfterDays = "archive_after_days" }
    public init(formatVersion: Int = WorkspaceStore.formatVersion, name: String, createdAt: Int64, epoch: Int = 1, archiveAfterDays: Int = 0) {
        self.formatVersion = formatVersion; self.name = name; self.createdAt = createdAt; self.epoch = epoch; self.archiveAfterDays = archiveAfterDays
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        formatVersion = try c.decodeIfPresent(Int.self, forKey: .formatVersion) ?? WorkspaceStore.formatVersion
        name = try c.decode(String.self, forKey: .name)
        createdAt = try c.decode(Int64.self, forKey: .createdAt)
        epoch = try c.decodeIfPresent(Int.self, forKey: .epoch) ?? 1
        archiveAfterDays = try c.decodeIfPresent(Int.self, forKey: .archiveAfterDays) ?? 0
    }
    public func encode(to e: Encoder) throws {
        var c = e.container(keyedBy: CodingKeys.self)
        if formatVersion != WorkspaceStore.formatVersion { try c.encode(formatVersion, forKey: .formatVersion) }
        try c.encode(name, forKey: .name)
        try c.encode(createdAt, forKey: .createdAt)
        if epoch != 1 { try c.encode(epoch, forKey: .epoch) }
        if archiveAfterDays != 0 { try c.encode(archiveAfterDays, forKey: .archiveAfterDays) }
    }
    /// The bytes Kotlin writes: this key order, defaults omitted, no trailing newline.
    public func compact() -> String { KotlinJSON.encode(self) }
    /// False when the repository was written by a newer app than this one.
    public var readable: Bool { formatVersion <= WorkspaceStore.formatVersion }
}

public struct PropertyDef: Codable, Equatable, Sendable {
    public var id: String, name: String, kind: String, config: String?
    public init(id: String, name: String, kind: String, config: String? = nil) { self.id = id; self.name = name; self.kind = kind; self.config = config }
}

/// One choice a select property offers.
public struct SelectOption: Codable, Equatable, Sendable {
    public var name: String
    /// ARGB, as both apps store it. Optional: an option is allowed to be just a word.
    public var color: Int64?
    public init(name: String, color: Int64? = nil) { self.name = name; self.color = color }
}

/// The options a select property holds, as stored in `PropertyDef.config`.
public struct SelectConfig: Codable, Equatable, Sendable {
    public var options: [SelectOption]
    public init(options: [SelectOption] = []) { self.options = options }
    enum CodingKeys: String, CodingKey { case options }
    public init(from d: Decoder) throws {
        options = try d.container(keyedBy: CodingKeys.self).decodeIfPresent([SelectOption].self, forKey: .options) ?? []
    }
    /// `options = emptyList()` is the default, so an empty one is not written — `{}`.
    public func encode(to e: Encoder) throws {
        var c = e.container(keyedBy: CodingKeys.self)
        if !options.isEmpty { try c.encode(options, forKey: .options) }
    }
}

public extension PropertyDef {
    /// The select options this def declares — empty for any other kind, and empty rather than a
    /// throw for config that will not decode. A malformed registry entry should cost you a picker
    /// with nothing in it, not the screen that draws it.
    var selectConfig: SelectConfig {
        config.flatMap { FilterJSON.decode(SelectConfig.self, $0) } ?? SelectConfig()
    }

    func optionColor(_ name: String) -> Int64? {
        selectConfig.options.first { $0.name == name }?.color
    }
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

    // MARK: lifecycle

    /// What a workspace's `.gitignore` must say — `WorkspaceStore.GITIGNORE`.
    ///
    /// One entry, the suffix every atomic write uses on its way to the real name, and a comment
    /// because the file is in the repository and somebody reading it on GitHub should not have to
    /// guess what `*.tmp` is doing there. A walk of the directory in the half-second a page spends
    /// as `<name>.tmp` is how Android's sync once died with ENOENT on a file that was never an edit.
    public static let gitignore = ["# Half-written files, mid-rename. Never a real edit.", "*.tmp"]

    /// Lays out an empty workspace: the directories, `.gitignore`, the manifest, the built-in
    /// properties and an empty label registry. Seeds **no** content — that is gated on this having
    /// just happened, never on an index being empty, or every clone would scaffold a second Inbox.
    public func scaffold(name: String, now: Int64) {
        try? fm.createDirectory(at: pagesDir, withIntermediateDirectories: true)
        try? fm.createDirectory(at: smartDir, withIntermediateDirectories: true)
        ensureGitignore()
        writeManifest(Manifest(name: name, createdAt: now))
        writeProperties(Self.builtInProperties())
        writeLabels([])
    }

    /// `Workspaces.open`: scaffold a directory that is new, and bring one that is not up to date
    /// with what this build writes — the built-in fields added since it was made, the manifest's
    /// format version, the `.gitignore`. Returns true if it scaffolded.
    @discardableResult
    public func open(name: String, now: Int64) -> Bool {
        if !exists { scaffold(name: name, now: now); return true }
        upkeep()
        return false
    }

    /// The three catch-ups Android runs every time it opens an existing workspace. Each writes only
    /// when something is missing, so an up-to-date workspace is not touched.
    public func upkeep() {
        ensureBuiltInProperties()
        upgradeFormat()
        ensureGitignore()
    }

    /// Appends whichever `gitignore` lines are missing (compared trimmed), and says whether it wrote.
    ///
    /// Appends rather than replaces: the file is in the repository, so it is the user's to add to.
    /// Rewritten as `lines.joinToString("\n", postfix = "\n")`, which also normalises the trailing
    /// newline exactly as Android does — and, like Android's, this write is not refused on a
    /// workspace from a newer build.
    @discardableResult
    public func ensureGitignore() -> Bool {
        let f = root.appendingPathComponent(".gitignore")
        let lines = (try? Data(contentsOf: f)).map { KText.readLines(Self.decodeText($0)) } ?? []
        let missing = Self.gitignore.filter { want in !lines.contains { KText.same(KText.trim($0), want) } }
        if missing.isEmpty { return false }
        try? fm.createDirectory(at: root, withIntermediateDirectories: true)
        try? Data(((lines + missing).joined(separator: "\n") + "\n").utf8).write(to: f)
        return true
    }

    /// The fixed fields every workspace has, and the only defs the app writes — `builtInProperties`.
    ///
    /// Priority carries its option colours in `config`, encoded the way Kotlin encodes a
    /// `SelectConfig`: High `0xFFFF4A1F`, Medium `0xFFFFB020`, Low `0xFF4A90D9`. (This used to be a
    /// hand-typed string whose High was `4294986271` — `0x1_0000_4A1F`, a number with a bit above the
    /// alpha byte and no red in it, written into every workspace iOS scaffolded.)
    public static func builtInProperties() -> [PropertyDef] {
        let cfg = FilterJSON.encode(SelectConfig(options: [
            SelectOption(name: "High", color: 0xFFFF_4A1F),
            SelectOption(name: "Medium", color: 0xFFFF_B020),
            SelectOption(name: "Low", color: 0xFF4A_90D9),
        ]))
        return [
            PropertyDef(id: BuiltIns.priority, name: "Priority", kind: "select", config: cfg),
            PropertyDef(id: BuiltIns.due, name: "Due", kind: "date"),
            PropertyDef(id: BuiltIns.deadline, name: "Deadline", kind: "date"),
            PropertyDef(id: BuiltIns.assignee, name: "Assignee", kind: "text"),
        ]
    }

    /// Adds any built-in this workspace's `properties.json` is missing, at the end. True if it wrote.
    ///
    /// Existing entries are left exactly as they are, including any a newer build wrote and this
    /// one does not recognise. Nothing here overwrites; it only fills gaps.
    @discardableResult
    public func ensureBuiltInProperties() -> Bool {
        let have = readProperties()
        let known = Set(have.map(\.id))
        let missing = Self.builtInProperties().filter { !known.contains($0.id) }
        if missing.isEmpty { return false }
        writeProperties(have + missing)
        return true
    }

    /// Stamps an older workspace up to the current format. True if it wrote. Because the current
    /// version is the default, the rewritten manifest carries no `formatVersion` at all.
    @discardableResult
    public func upgradeFormat() -> Bool {
        guard var m = readManifest(), m.formatVersion < Self.formatVersion else { return false }
        m.formatVersion = Self.formatVersion
        writeManifest(m)
        return true
    }

    // manifest
    public func readManifest() -> Manifest? {
        guard let d = try? Data(contentsOf: manifestFile) else { return nil }
        return try? JSONDecoder().decode(Manifest.self, from: d)
    }
    public func writeManifest(_ m: Manifest) { write(manifestFile, Data(m.compact().utf8)) }
    /// True when the files were written by a newer build than this one — `isAhead`. An unreadable or
    /// absent manifest is the current version, not version zero: a workspace we cannot identify is
    /// not a workspace from the future.
    public var isReadOnly: Bool { readManifest().map { !$0.readable } ?? false }

    // pages
    public func pageFile(_ id: String) -> URL { pagesDir.appendingPathComponent("\(id).md") }
    public func readPage(_ id: String) -> PageDoc? {
        guard let d = try? Data(contentsOf: pageFile(id)) else { return nil }
        return PageCodec.decode(Self.decodeText(d))
    }

    /// `File.readText()`: UTF-8, a malformed sequence becoming U+FFFD rather than the whole file
    /// becoming nothing, and a BOM kept as the character it is (so, as on Android, a file that
    /// starts with one has no frontmatter). `String(contentsOf:encoding:)` returned nil for a single
    /// bad byte, which made the page vanish from the index while Android went on showing it.
    public static func decodeText(_ d: Data) -> String { String(decoding: d, as: UTF8.self) }

    /// Every page directly inside `pages/`, sorted by file name so two devices index in one order.
    public func readPages() -> [PageDoc] {
        let names = ((try? fm.contentsOfDirectory(atPath: pagesDir.path)) ?? []).filter { name in
            var dir: ObjCBool = false
            return name.hasSuffix(".md") && fm.fileExists(atPath: pagesDir.appendingPathComponent(name).path, isDirectory: &dir) && !dir.boolValue
        }.sorted { Array($0.utf16).lexicographicallyPrecedes(Array($1.utf16)) }
        return names.compactMap { readPage(String($0.dropLast(3))) }
    }
    public func writePage(_ page: PageDoc) { write(pageFile(page.id), Data(PageCodec.encode(page).utf8)) }

    /// Removes a page, the sidecars its blocks own, every task page beneath it, and — if it was a
    /// smart list — its definition.
    ///
    /// Sidecars are named after the *ink block*, not after the page, so deleting only `<page>.ink`
    /// would leave every stroke on disk with nothing referring to it. Child **task** pages go too
    /// (event pages do not, as on Android). `seen` is the cycle guard: a malformed workspace can
    /// name a page as its own descendant, and following it would recurse until the stack ran out.
    public func deletePage(_ id: String) {
        var seen = Set<String>()
        deletePage(id, seen: &seen)
    }

    private func deletePage(_ id: String, seen: inout Set<String>) {
        if isReadOnly { return }
        guard seen.insert(id).inserted else { return }
        if let p = readPage(id) {
            for b in p.blocks {
                switch b {
                case let .ink(inkId, _, _): try? fm.removeItem(at: inkFile(inkId))
                case let .image(uri, _, _): try? fm.removeItem(at: imageFile(uri))
                case let .task(t): if fm.fileExists(atPath: pageFile(t.id).path) { deletePage(t.id, seen: &seen) }
                default: break
                }
            }
        }
        try? fm.removeItem(at: pageFile(id))
        try? fm.removeItem(at: inkFile(id))
        deleteSmartList(id)
    }

    // ink and images
    public func inkFile(_ id: String) -> URL { pagesDir.appendingPathComponent("\(id).ink") }
    public func readInk(_ id: String) -> [Data] {
        guard let d = try? Data(contentsOf: inkFile(id)) else { return [] }
        return (try? InkSidecar.decode(d)) ?? []
    }
    public func writeInk(_ id: String, _ strokes: [Data]) {
        if isReadOnly { return }
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
    public func smartListFile(_ nodeId: String) -> URL { smartDir.appendingPathComponent("\(nodeId).json") }
    public func writeSmartList(_ def: SmartListDef) { write(smartListFile(def.nodeId), Data(FilterJSON.encode(def).utf8)) }
    public func deleteSmartList(_ nodeId: String) {
        if isReadOnly { return }
        try? fm.removeItem(at: smartListFile(nodeId))
    }

    // archive: one archived task LINE per line in archive/<pageId>.md; the task's own page moves to archive/pages/
    var archiveDir: URL { root.appendingPathComponent("archive") }
    public func archiveFile(_ pageId: String) -> URL { archiveDir.appendingPathComponent("\(pageId).md") }
    public func archivedPageFile(_ pageId: String) -> URL { archiveDir.appendingPathComponent("pages/\(pageId).md") }
    public func archivedPageIds() -> [String] {
        ((try? fm.contentsOfDirectory(atPath: archiveDir.path)) ?? []).filter { $0.hasSuffix(".md") }.map { String($0.dropLast(3)) }.sorted()
    }
    /// `readLines().filter { it.isNotBlank() }`.
    public func readArchivedLines(_ pageId: String) -> [String] {
        guard let d = try? Data(contentsOf: archiveFile(pageId)) else { return [] }
        return KText.readLines(Self.decodeText(d)).filter { !KText.isBlank($0) }
    }
    public func writeArchivedLines(_ pageId: String, _ lines: [String]) {
        if isReadOnly { return }
        if lines.isEmpty { try? fm.removeItem(at: archiveFile(pageId)); return }
        write(archiveFile(pageId), Data((lines.joined(separator: "\n") + "\n").utf8))
    }
    public func moveToArchive(_ pageId: String) {
        if isReadOnly { return }
        let from = pageFile(pageId); guard fm.fileExists(atPath: from.path) else { return }
        try? fm.createDirectory(at: archiveDir.appendingPathComponent("pages"), withIntermediateDirectories: true)
        try? fm.removeItem(at: archivedPageFile(pageId)); try? fm.moveItem(at: from, to: archivedPageFile(pageId))
    }
    public func restoreFromArchive(_ pageId: String) {
        if isReadOnly { return }
        let from = archivedPageFile(pageId); guard fm.fileExists(atPath: from.path) else { return }
        try? fm.removeItem(at: pageFile(pageId)); try? fm.moveItem(at: from, to: pageFile(pageId))
    }

    // focus log

    /// One line, appended in place — the one write that is not temp-and-rename, because two
    /// devices appending offline produce two tails git takes both of. Refused on a workspace from a
    /// newer build, and it moves a legacy `pomodoro/` first, as Android's `appendFocus` does.
    public func appendFocus(_ line: String, month: String) {
        if isReadOnly { return }
        migrateLegacyFocusDir()
        let dir = root.appendingPathComponent(Self.focusDir)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let f = dir.appendingPathComponent("\(month).log")
        // `line.trimEnd('\n') + "\n"`: exactly one newline, whatever the caller handed in.
        var body = line
        while body.unicodeScalars.last == "\n" { body.unicodeScalars.removeLast() }
        append(Data((body + "\n").utf8), to: f)
    }

    /// Both directories, because a repository is shared and not every device renames at once — and
    /// read as **one list of files sorted by name**, as Android's `readFocus` reads them, so a month
    /// logged under both names comes out in one place rather than in two runs.
    public func readFocusLines() -> [String] {
        var files: [(String, URL)] = []
        for dir in [Self.focusDir, Self.legacyFocusDir] {   // this order breaks a tie on the name: focus/ first
            let d = root.appendingPathComponent(dir)
            for n in ((try? fm.contentsOfDirectory(atPath: d.path)) ?? []) where n.hasSuffix(".log") {
                files.append((n, d.appendingPathComponent(n)))
            }
        }
        // Stable, like Kotlin's sortedBy.
        let ordered = files.enumerated().sorted { a, b in
            let x = Array(a.element.0.utf16), y = Array(b.element.0.utf16)
            return x == y ? a.offset < b.offset : x.lexicographicallyPrecedes(y)
        }.map(\.element.1)
        return ordered.flatMap { f in
            (try? Data(contentsOf: f)).map { KText.readLines(Self.decodeText($0)).filter { !KText.isBlank($0) } } ?? []
        }
    }

    /// Moves `pomodoro/` to `focus/`: each log appended to the same-named file there if one exists,
    /// copied if not, then deleted; the directory goes once it is empty — and only then, so anything
    /// unexpected in it is left alone. Run on every reindex, like Android's.
    public func migrateLegacyFocusDir() {
        let legacy = root.appendingPathComponent(Self.legacyFocusDir)
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: legacy.path, isDirectory: &isDir), isDir.boolValue else { return }
        let focus = root.appendingPathComponent(Self.focusDir)
        for n in ((try? fm.contentsOfDirectory(atPath: legacy.path)) ?? []) where n.hasSuffix(".log") {
            let old = legacy.appendingPathComponent(n), dest = focus.appendingPathComponent(n)
            guard let bytes = try? Data(contentsOf: old) else { continue }
            try? fm.createDirectory(at: focus, withIntermediateDirectories: true)
            if fm.fileExists(atPath: dest.path) { append(bytes, to: dest) } else { try? bytes.write(to: dest) }
            try? fm.removeItem(at: old)
        }
        if ((try? fm.contentsOfDirectory(atPath: legacy.path)) ?? ["?"]).isEmpty { try? fm.removeItem(at: legacy) }
    }

    // io
    private func readList<T: Decodable>(_ f: URL) -> [T] {
        guard let d = try? Data(contentsOf: f) else { return [] }
        return (try? JSONDecoder().decode([T].self, from: d)) ?? []
    }

    private func append(_ data: Data, to f: URL) {
        if let h = try? FileHandle(forWritingTo: f) {
            defer { try? h.close() }
            _ = try? h.seekToEnd()
            try? h.write(contentsOf: data)
        } else {
            try? data.write(to: f)
        }
    }

    /// Write to `<name>.tmp` beside the target and rename it over the target, so a reader sees the
    /// old file or the new one and never half of one. `rename(2)` replaces atomically; if it cannot
    /// (it can fail across volumes, which a sibling never is), the fallback copies and then removes
    /// the temporary file rather than leaving it behind — the old fallback wrote the target in place
    /// and left the `.tmp` for the next sync to find.
    ///
    /// Refused on a workspace from a newer build, as `writeBytesAtomically` is: writing the previous
    /// meaning of the format into files this build does not fully understand is the one thing worse
    /// than declining.
    private func write(_ f: URL, _ data: Data) {
        if isReadOnly { return }
        try? fm.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
        let tmp = f.deletingLastPathComponent().appendingPathComponent(f.lastPathComponent + ".tmp")
        do {
            try data.write(to: tmp)
            if Foundation.rename(tmp.path, f.path) != 0 {
                try? fm.removeItem(at: f)
                try fm.copyItem(at: tmp, to: f)
                try? fm.removeItem(at: tmp)
            }
        } catch {
            try? fm.removeItem(at: tmp)
        }
    }
}

// MARK: - seeding

public enum WorkspaceSeeder {
    /// Today: open tasks due or with a deadline by the end of today, soonest first, and a task made
    /// in it due today — exactly as `WorkspaceSeeder.kt` builds it, down to the bytes of each JSON.
    public static func todayDef(nodeId: String, inbox: String) -> SmartListDef {
        let todayFilter: Filter = .all([.type(NodeType.task), .done(false), .anyOf([
            .prop(defId: BuiltIns.due, op: .lte, dateRel: .todayEnd),
            .prop(defId: BuiltIns.deadline, op: .lte, dateRel: .todayEnd),
        ])])
        let apply = deriveApplyOnCreate(todayFilter)
        return SmartListDef(nodeId: nodeId, filterJson: FilterJSON.encode(todayFilter),
                            sortJson: FilterJSON.encode([SortSpec(by: .propDate, defId: BuiltIns.due)]), homeParentId: inbox,
                            // `takeIf { it.isNotEmpty() }` — an empty list is no key at all.
                            applyOnCreateJson: apply.isEmpty ? nil : FilterJSON.encode(apply))
    }

    /// High Priority: open tasks with priority exactly `High`, newest first.
    public static func highPriorityDef(nodeId: String, inbox: String) -> SmartListDef {
        SmartListDef(nodeId: nodeId,
                     filterJson: FilterJSON.encode(Filter.all([.type(NodeType.task), .done(false), .prop(defId: BuiltIns.priority, op: .eq, text: "High")])),
                     sortJson: FilterJSON.encode([SortSpec(by: .created, desc: true)]), homeParentId: inbox,
                     applyOnCreateJson: FilterJSON.encode([ApplyOnCreate(defId: BuiltIns.priority, text: "High")]))
    }

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

        let today = id()
        store.writePage(PageDoc(id: today, type: NodeType.smartList, parent: nil, title: "Today", systemKey: SystemKey.today, modifiedAt: now, device: nil, blocks: []))
        store.writeSmartList(todayDef(nodeId: today, inbox: inbox))
        let high = id()
        store.writePage(PageDoc(id: high, type: NodeType.smartList, parent: nil, title: "High Priority", modifiedAt: now, device: nil, blocks: []))
        store.writeSmartList(highPriorityDef(nodeId: high, inbox: inbox))
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
