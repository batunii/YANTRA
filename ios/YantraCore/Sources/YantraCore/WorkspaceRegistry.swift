import Foundation

/// One workspace the app knows about — `WorkspaceEntry` on Android.
///
/// `id` is generated rather than derived from the repository, because a repository can be renamed
/// or moved to another owner while the id is written into every node this workspace holds. A slug
/// as the primary key would mean a rename silently orphaning every task in it. `slug` is kept
/// beside it so a screen can say where a workspace points, and is allowed to go stale.
public struct WorkspaceEntry: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var slug: String?
    /// The colour this workspace wears, as a palette name. Stored and editable, exactly as a
    /// label's is, rather than recomputed from a hash of the name that nobody chose.
    public var color: String?
    /// When this workspace was linked, in epoch milliseconds.
    ///
    /// Kept so the newest can be shown first: the one you just added is the one you are looking
    /// for, and a list in the order things happened to be appended puts it at the bottom. Optional
    /// because entries written before this existed have no answer — they sort as oldest, which is
    /// what they are.
    public var addedAt: Int64?
    public init(id: String, name: String, slug: String? = nil, color: String? = nil, addedAt: Int64? = nil) {
        self.id = id; self.name = name; self.slug = slug; self.color = color; self.addedAt = addedAt
    }
}

/// The list of workspaces, on disk — `WorkspaceRegistry.kt`.
///
/// Everything else in this app rebuilds from files, so discovering workspaces by scanning for
/// directories that look like one would be the consistent thing to do. It fails in the direction
/// that matters: a link that got half way — directory made, repository refused — would come back on
/// every launch as a workspace that cannot sync, with nowhere to record that it is not real. The
/// list is written *after* the link succeeds, so a failed attempt leaves nothing to resurrect.
///
/// The local workspace is not in here. It has the empty id, it is always present, and it is the one
/// thing that must exist before any of this does.
public struct WorkspaceRegistry: Sendable {
    public let root: URL
    public init(root: URL) { self.root = root }

    private var file: URL { root.appendingPathComponent("registry.json") }

    public func entries() -> [WorkspaceEntry] {
        guard let data = try? Data(contentsOf: file) else { return [] }
        return (try? JSONDecoder().decode([WorkspaceEntry].self, from: data)) ?? []
    }

    /// Adds, or replaces an entry with the same id.
    ///
    /// A replacement keeps the moment the workspace was first linked — renaming one or giving it a
    /// colour is not adding it again, and should not jump it to the top of the list.
    public func add(_ adding: WorkspaceEntry) {
        var row = adding
        if row.addedAt == nil {
            row.addedAt = entry(adding.id)?.addedAt ?? Int64(Date().timeIntervalSince1970 * 1000)
        }
        write(entries().filter { $0.id != row.id } + [row])
    }

    /// The linked workspaces, newest first — the order they are shown in.
    public func newestFirst() -> [WorkspaceEntry] {
        entries().sorted { ($0.addedAt ?? 0, $0.name) > ($1.addedAt ?? 0, $1.name) }
    }

    public func remove(_ id: String) {
        write(entries().filter { $0.id != id })
    }

    public func entry(_ id: String) -> WorkspaceEntry? { entries().first { $0.id == id } }

    /// The colour a workspace wears, as a palette name, seeded from its name until somebody picks one.
    ///
    /// Stored and editable, exactly as a label's is. Android learned this the hard way: recomputing
    /// it from a hash of the name gave every workspace a hue nobody chose, which collided with a
    /// colour somebody *had* chosen about one time in five, and which a reader who cannot tell Blue
    /// from Violet had no way to correct.
    ///
    /// The local workspace is answered from a file of its own rather than from the list, because the
    /// list holds *linked repositories* and the local one deliberately is not in it — giving it an
    /// entry to hold one colour would put a workspace with no repository into every list of
    /// repositories in the app.
    public func color(for id: String) -> String? {
        if id.isEmpty {
            return (try? String(contentsOf: localColor, encoding: .utf8))?
                .trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        }
        return entry(id)?.color
    }

    public func setColor(_ color: String?, for id: String) {
        guard !id.isEmpty else {
            try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            if let color { try? color.write(to: localColor, atomically: true, encoding: .utf8) }
            else { try? FileManager.default.removeItem(at: localColor) }
            return
        }
        guard var found = entry(id) else { return }
        found.color = color
        add(found)
    }

    private var localColor: URL { root.appendingPathComponent("local-colour") }

    /// Where a workspace's files live. `local` is spelled out rather than being the empty id's
    /// empty string, which would resolve to the parent directory and put one workspace's pages
    /// beside every other workspace's directory.
    public func dir(for id: String) -> URL {
        root.appendingPathComponent(id.isEmpty ? "local" : id, isDirectory: true)
    }

    private func write(_ list: [WorkspaceEntry]) {
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(list) else { return }
        // The same write-and-rename the workspace files use. A half-written registry is the one
        // file that could lose track of a repository somebody has already put work into.
        let tmp = root.appendingPathComponent("registry.json.tmp")
        try? data.write(to: tmp, options: .atomic)
        _ = try? FileManager.default.replaceItemAt(file, withItemAt: tmp)
        try? FileManager.default.removeItem(at: tmp)
    }
}

public extension WorkspaceIndex {
    /// The index of several workspaces at once, read as one.
    ///
    /// Home shows every list you have, wherever it lives, and Today spans them — so the screens
    /// want one index, not a dictionary of them. Nothing collides: ids are UUIDs and every node
    /// carries the `workspaceId` that says which store a write has to go back to.
    ///
    /// Smart lists come from the local workspace alone. A smart list is a view *over* repositories
    /// rather than a thing inside one — it can name any of them — so no repository it names can own
    /// it without becoming unreadable when cloned on its own.
    static func read(_ stores: [WorkspaceStore]) -> WorkspaceIndex {
        var out = WorkspaceIndex()
        for store in stores {
            let ix = read(store)
            out.nodes.merge(ix.nodes) { a, _ in a }
            out.labels += ix.labels
            out.properties += ix.properties
            out.problems += ix.problems
            if store.id.isEmpty { out.smartLists = ix.smartLists }
        }
        // A label registered in two workspaces is one label with two colours, and the palette reads
        // the first it finds. Local wins, because it is the one somebody edits in Settings.
        var seen = Set<String>()
        out.labels = out.labels.filter { seen.insert($0.name).inserted }
        var seenProps = Set<String>()
        out.properties = out.properties.filter { seenProps.insert($0.id).inserted }
        return out
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
