import Foundation

/// What to do when two devices changed the same file — `ConflictResolver.kt`, rule for rule.
///
/// - A delete never beats an edit.
/// - An append-only focus log takes both sides, last line per session id winning.
/// - The manifest merges field by field.
/// - A page merges block by block around the disagreement when a base exists.
/// - Anything else that is not a page keeps the local side, consistently, so nothing ping-pongs.
/// - A page that cannot be merged is last-writer-wins on `modified_at`, tie broken by device name.
public enum ConflictResolver {
    public struct Resolution: Equatable {
        public let path: String
        /// nil means the file stays deleted.
        public let bytes: Data?
        public let reason: String
    }

    public static func resolve(path: String, local: Data?, remote: Data?, device: String = "", otherDevice: String = "", base: Data? = nil) -> Resolution {
        if local == nil, remote == nil { return Resolution(path: path, bytes: nil, reason: "deleted on both sides") }
        guard let local else { return Resolution(path: path, bytes: remote, reason: "kept the edit over a delete") }
        guard let remote else { return Resolution(path: path, bytes: local, reason: "kept the edit over a delete") }

        if isLog(path) { return Resolution(path: path, bytes: mergeLog(local, remote), reason: "merged an append-only log") }

        if path == WorkspaceStore.manifestPath, let merged = ManifestMerge.merge(
            base: base.flatMap { String(data: $0, encoding: .utf8) }, local: String(decoding: local, as: UTF8.self),
            remote: String(decoding: remote, as: UTF8.self), device: device, otherDevice: otherDevice) {
            return Resolution(path: path, bytes: Data(merged.utf8), reason: "merged the manifest field by field")
        }

        if path.hasSuffix(".md"), let merged = mergePage(base: base, local: local, remote: remote) {
            return Resolution(path: path, bytes: merged, reason: "merged the page around the disagreement")
        }

        if !path.hasSuffix(".md") { return Resolution(path: path, bytes: local, reason: "kept the local side of an unmergeable file") }

        let lt = modifiedAt(local), rt = modifiedAt(remote)
        if lt > rt { return Resolution(path: path, bytes: local, reason: "local page was newer") }
        if rt > lt { return Resolution(path: path, bytes: remote, reason: "remote page was newer") }
        return device > otherDevice ? Resolution(path: path, bytes: local, reason: "same timestamp, decided by device")
            : Resolution(path: path, bytes: remote, reason: "same timestamp, decided by device")
    }

    static func isLog(_ path: String) -> Bool {
        path.hasSuffix(".log") && (path.hasPrefix(WorkspaceStore.focusDir + "/") || path.hasPrefix(WorkspaceStore.legacyFocusDir + "/"))
    }

    static func modifiedAt(_ bytes: Data) -> Double {
        guard let s = String(data: bytes, encoding: .utf8) else { return 0 }
        return (PageCodec.decode(s).modifiedAt.timeIntervalSince1970 * 1000).rounded()
    }

    /// Both sides' lines, the last line for any repeated id winning; remote first so a closing line
    /// on either side lands last.
    static func mergeLog(_ local: Data, _ remote: Data) -> Data {
        var seen: [String: String] = [:]
        var order: [String] = []
        for line in (String(decoding: remote, as: UTF8.self).split(separator: "\n") + String(decoding: local, as: UTF8.self).split(separator: "\n")).map(String.init) {
            if line.trimmingCharacters(in: .whitespaces).isEmpty || line.hasPrefix("<<<") || line.hasPrefix("===") || line.hasPrefix(">>>") { continue }
            let key = String(line.split(separator: "\t", omittingEmptySubsequences: false).first ?? "")
            if seen[key] == nil { order.append(key) }
            seen[key] = line
        }
        return Data((order.compactMap { seen[$0] }.joined(separator: "\n") + "\n").utf8)
    }

    /// Block-level three-way merge. Tasks and ink carry ids; everything else is keyed by kind and
    /// position among its kind. A side dropping a block wins only if the other side left it alone.
    static func mergePage(base: Data?, local: Data, remote: Data) -> Data? {
        guard let base, let bs = String(data: base, encoding: .utf8), let ls = String(data: local, encoding: .utf8), let rs = String(data: remote, encoding: .utf8) else { return nil }
        let b = PageCodec.decode(bs), l = PageCodec.decode(ls), r = PageCodec.decode(rs)
        if b.id.isEmpty || l.id.isEmpty || r.id.isEmpty { return nil }
        let localNewer = l.modifiedAt >= r.modifiedAt
        let bk = keyed(b.blocks), lk = keyed(l.blocks), rk = keyed(r.blocks)
        var order = lk.order
        for k in rk.order where lk.map[k] == nil { order.append(k) }
        var merged: [Block] = []
        for key in order {
            let bb = bk.map[key], ll = lk.map[key], rr = rk.map[key]
            let pick: Block?
            switch (ll, rr) {
            case (nil, nil): pick = nil
            case (nil, let r?): pick = r.strippingRaw == bb?.strippingRaw ? nil : r
            case (let l?, nil): pick = l.strippingRaw == bb?.strippingRaw ? nil : l
            case (let l?, let r?):
                if l.strippingRaw == r.strippingRaw { pick = l }
                else if bb?.strippingRaw == l.strippingRaw { pick = r }
                else if bb?.strippingRaw == r.strippingRaw { pick = l }
                else { pick = localNewer ? l : r }
            }
            if let p = pick { merged.append(p.strippingRaw) }
        }
        var header = localNewer ? l : r
        header.blocks = merged
        return Data(PageCodec.encode(header).utf8)
    }

    struct Keyed { var order: [String] = []; var map: [String: Block] = [:] }

    static func keyed(_ blocks: [Block]) -> Keyed {
        var out = Keyed()
        var counts: [String: Int] = [:]
        for b in blocks {
            let key: String
            switch b {
            case let .task(t) where !t.id.isEmpty: key = "task:\(t.id)"
            case let .ink(id, _, _): key = "ink:\(id)"
            default:
                let kind = kindName(b)
                counts[kind, default: 0] += 1
                key = "\(kind):\(counts[kind]!)"
            }
            if out.map[key] == nil { out.order.append(key) }
            out.map[key] = b
        }
        return out
    }

    /// The Kotlin class names, so both sides key positional blocks identically.
    static func kindName(_ b: Block) -> String {
        switch b {
        case .prose: return "Prose"; case .heading: return "Heading"; case .bullet: return "Bullet"; case .numbered: return "Numbered"
        case .task: return "TaskRef"; case .ink: return "InkRef"; case .image: return "ImageRef"
        }
    }
}
