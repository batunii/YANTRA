import Foundation

/// What to do when two devices changed the same file — `ConflictResolver.kt` at 17d0beb, rule for
/// rule and in the same order.
///
/// - A delete never beats an edit.
/// - An append-only focus log takes both sides, the last line per session id winning.
/// - A page merges block by block around the disagreement when a base exists.
/// - Anything else that is not a page — the manifest included — keeps the local side, consistently,
///   so nothing ping-pongs.
/// - A page with no base is last-writer-wins on `modified_at`, tie broken by device name.
///
/// **The output has to be the bytes Android would have written.** Two devices resolving the same
/// conflict must land on the same file, or they have not merged anything: they have made a new
/// conflict, and they will make it again on every pull. That is why the block merge below follows
/// the Kotlin line for line — including the parts that look arbitrary.
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

        if path.hasSuffix(".md"), let merged = mergePage(base: base, local: local, remote: remote) {
            return Resolution(path: path, bytes: merged, reason: "merged the page around the disagreement")
        }

        // Sidecars and registries have no readable clock, and a stroke set has no line structure to
        // merge. Keeping the local side is arbitrary but it is *consistently* arbitrary, which is what
        // stops two devices ping-ponging a file between them. The manifest is one of these: Android
        // keeps its local side of it, so this does too — a field-by-field merge that only one of the
        // two apps runs answers differently depending on which device resolved the conflict.
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

    /// Epoch milliseconds; 0 when there is no readable stamp.
    static func modifiedAt(_ bytes: Data) -> Double {
        (decode(bytes).modifiedAt.timeIntervalSince1970 * 1000).rounded()
    }

    /// `ByteArray.decodeToString()`: malformed UTF-8 becomes U+FFFD rather than failing, so a page
    /// Kotlin would merge is a page this merges too.
    static func text(_ bytes: Data) -> String { String(decoding: bytes, as: UTF8.self) }
    static func decode(_ bytes: Data) -> PageDoc { PageCodec.decode(text(bytes)) }

    /// Kotlin's `CharSequence.lines()`: `\r\n`, `\n` and a lone `\r` each end a line.
    ///
    /// Not `split(separator: "\n")`. Swift treats `\r\n` as **one** Character, so splitting on `"\n"`
    /// leaves a CRLF file as a single line — every session in such a log would have collapsed into
    /// one key, and one surviving line.
    static func lines(_ s: String) -> [String] {
        s.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            .split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
    }

    /// Both sides' lines, with the last line for any repeated id winning.
    ///
    /// A session is appended once when it starts and again when it ends, so "last wins" is what turns
    /// two partial views into one complete one: the device that finished the session has the closing
    /// line, the other only has the opening one, and taking both in order lands on the closing one
    /// whichever way round they arrive. A key keeps the position it was first seen at, as a
    /// `LinkedHashMap` does when a value is overwritten.
    static func mergeLog(_ local: Data, _ remote: Data) -> Data {
        var seen: [String: String] = [:]
        var order: [String] = []
        for line in lines(text(remote)) + lines(text(local)) {
            if line.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || line.hasPrefix("<<<") || line.hasPrefix("===") || line.hasPrefix(">>>") { continue }
            let key = line.firstIndex(of: "\t").map { String(line[..<$0]) } ?? line
            if seen[key] == nil { order.append(key) }
            seen[key] = line
        }
        return Data((order.compactMap { seen[$0] }.joined(separator: "\n") + "\n").utf8)
    }

    /// Merges two versions of a page **block by block**, not line by line — `mergePage` in
    /// `ConflictResolver.kt`, ported line for line.
    ///
    /// Tasks and ink carry ids; everything else is keyed by kind and by its position among its own
    /// kind. Blocks are compared **whole** — `raw` included, and every field the model carries, however
    /// many it grows — exactly as Kotlin's data-class equality compares them.
    ///
    /// Only a missing base declines (the caller then falls back to last-writer-wins). A page with no
    /// id — an `archive/<page>.md`, which has no frontmatter at all — is merged like any other, as
    /// Kotlin merges it. Declining there sent archived lines through whole-file LWW, which kept one
    /// side's archive and dropped the other's.
    static func mergePage(base: Data?, local: Data, remote: Data) -> Data? {
        guard let base else { return nil }
        let b = decode(base), l = decode(local), r = decode(remote)

        // **Which side comes first, decided from the pages and never from which one is "local".**
        //
        // `local` and `remote` are opposite labels on the two machines — my local is your remote — so
        // a rule phrased in those terms answers differently on each. `modified_at` decides it, and a
        // tie falls to the device name, which is a fact about the pages rather than about who is
        // asking. A `>=` on the timestamps alone silently picks your own side on a tie, which is
        // exactly what two offline saves in the same millisecond produce.
        let localFirst: Bool
        if l.modifiedAt != r.modifiedAt { localFirst = l.modifiedAt > r.modifiedAt }
        else { localFirst = (l.device ?? "") >= (r.device ?? "") }

        let bk = keyed(b.blocks), lk = keyed(l.blocks), rk = keyed(r.blocks)

        // The order is decided by the same value, for the same reason: walking my page first puts my
        // new subtask above yours on my phone and yours above mine on yours — same blocks, different
        // bytes, and a conflict again on the next pull.
        let (lead, follow) = localFirst ? (lk.order, rk.order) : (rk.order, lk.order)
        var order = lead
        let leadSet = Set(lead)
        for key in follow where !leadSet.contains(key) { order.append(key) }

        var merged: [Block] = []
        for key in order {
            let bb = bk.map[key], ll = lk.map[key], rr = rk.map[key]
            switch (ll, rr) {
            case (nil, nil): break
            // A side dropping a block only wins if the other side left it alone. An edit always
            // outlives a delete.
            case let (nil, r?): if r != bb { merged.append(r) }
            case let (l?, nil): if l != bb { merged.append(l) }
            case let (l?, r?):
                if l == r { merged.append(l) }
                else if bb == l { merged.append(r) }          // only the remote touched it
                else if bb == r { merged.append(l) }          // only the local touched it
                // **Both added something here. That is two additions, not a disagreement.** A block
                // with no id of its own is keyed by kind and position, so a paragraph you wrote and a
                // paragraph somebody else wrote land on the same key without being the same block.
                // The base has neither, which is what tells this apart from an edit. Both are kept,
                // the leading side first.
                else if bb == nil { merged += localFirst ? [l, r] : [r, l] }
                // Both edited the *same* block; someone still has to lose.
                else { merged.append(localFirst ? l : r) }
            }
        }

        // Raw source belongs to the file it came from; a merged block has to be re-rendered.
        var header = localFirst ? l : r
        header.blocks = merged.map(\.strippingRaw)
        return Data(PageCodec.encode(header).utf8)
    }

    struct Keyed { var order: [String] = []; var map: [String: Block] = [:] }

    /// A stable name for a block: `task:<id>` and `ink:<id>`, otherwise `<Kind>:<n>` for the nth of
    /// that kind. A repeated key overwrites in place, as `LinkedHashMap` does, so it keeps its first
    /// position and the last block with it wins.
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
        // Positional, exactly as Kotlin keys it: an event carries an id, but the Kotlin side does
        // not key on it, and a key the two apps computed differently would merge the wrong lines.
        case .event: return "EventRef"
        }
    }
}
