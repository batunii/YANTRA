import Foundation

/// Moving a node, and everything under it, from one workspace to another —
/// `Workspaces.moveAcross` on Android.
///
/// **Why this cannot be `WorkspaceWriter.moveTask`.** A writer owns one store. `moveTask` looks the
/// destination page up in its own, so across workspaces it finds nothing: the older code appended
/// the line to a page it had just invented in the *source* repository and left the node in neither
/// place a person could reach. `moveTask` now refuses a destination it does not hold, and this is
/// what to call instead.
public enum WorkspaceMove {

    public struct Refused: Error, CustomStringConvertible {
        public let reason: String
        public var description: String { reason }
    }

    /// Copies a node's files into `destination`, re-homes its line, then removes the originals.
    ///
    /// **Ordered so that a failure in the middle leaves two copies rather than none.** The files are
    /// copied in first, then the line changes hands, and only then are the originals removed. A
    /// duplicate is something a person can delete; a page that stopped existing half way through a
    /// move is not.
    ///
    /// The ids do not change. They are UUIDs, they are what every link and every focus row refers
    /// to, and rewriting them would turn a move into a copy that quietly orphans its own history.
    public static func move(nodeId: String,
                            toList listId: String,
                            from source: WorkspaceWriter,
                            to destination: WorkspaceWriter) throws {
        guard source.store.id != destination.store.id else {
            try source.moveTask(nodeId, toList: listId)
            return
        }
        guard destination.store.readPage(listId) != nil else {
            throw Refused(reason: "that list is not in the workspace being moved to")
        }
        guard let (home, index) = source.writerLocate(nodeId) else {
            throw Refused(reason: "the task is not where the index says it is")
        }
        guard let line = source.store.readPage(home)?.blocks[safeIndex: index] else {
            throw Refused(reason: "the task's line has gone")
        }

        // 1. The files, first. A node's own page, its descendants' pages, and the sidecars they own.
        try copyTree(nodeId, from: source.store, to: destination.store)

        // 2. The line, into the destination list.
        try destination.editPage(listId, change: .structural) { page in
            var p = page; p.blocks.append(line.strippingRaw); return p
        }
        if destination.store.readPage(nodeId) != nil {
            try destination.editPage(nodeId) { var p = $0; p.parent = listId; return p }
        }

        // 3. The originals, last.
        try source.editPage(home, change: .structural) { page in
            var p = page
            guard index < p.blocks.count else { return p }
            p.blocks.remove(at: index)
            return p
        }
        source.store.deletePage(nodeId)
    }

    /// A page, everything filed under it, and the sidecars its blocks own.
    private static func copyTree(_ pageId: String, from source: WorkspaceStore, to destination: WorkspaceStore) throws {
        guard let page = source.readPage(pageId) else { return }     // a line with no page of its own
        destination.writePage(page)
        for block in page.blocks {
            switch block {
            case let .task(t) where !t.id.isEmpty:
                try copyTree(t.id, from: source, to: destination)
            case let .ink(inkId, _, _):
                let strokes = source.readInk(inkId)
                if !strokes.isEmpty { destination.writeInk(inkId, strokes) }
            case let .image(uri, _, _):
                let stem = uri.hasSuffix(".jpg") ? String(uri.dropLast(4)) : uri
                if let bytes = try? Data(contentsOf: source.imageFile(stem)) {
                    destination.writeImage(stem, bytes)
                }
            default:
                break
            }
        }
    }
}

extension Array {
    subscript(safeIndex i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}

public extension WorkspaceWriter {
    /// `locate(taskId:)` under a name that says it may be any node, not only a task — a list being
    /// moved is located the same way.
    func writerLocate(_ nodeId: String) -> (String, Int)? { locate(taskId: nodeId) }
}
