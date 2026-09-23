import Foundation

/// What a row is allowed to say about a task, given where the task is being looked at —
/// `data/filter/Salience.kt`, and `ROW_SALIENCE.md` for the reasoning.
///
/// A field earns its place on a row only when the view has not already said it. "Due today" on a
/// row in Today is a sentence that ends where it began; the same words on a list page are the one
/// thing the row is scanned for. So a field's importance is not a property of the field — it is a
/// property of the *view*, and the view is a `Filter`. This is the same walk the smart list does,
/// asked a different question: for each thing a row could carry, how much of it does the rule
/// already fix?
public enum Field: Hashable, Sendable {
    /// A typed property: due, deadline, assignee, or a user def.
    case prop(String)
    /// One label. Pinning `#work` drops `#work` from the row and leaves every other tag alone.
    case label(String)
    /// The list the task lives in.
    case originList
    /// The repository the task came from.
    case workspace
}

/// How much the view has already said about a field, least informative first.
///
/// - `pinned`: every task here has the same value (an `eq`, an `isSet`, a label, a workspace, or
///   the page the list *is*). Saying it on the row is saying it twice.
/// - `bounded`: every task is inside a band (`lte today`) but the value still varies inside it —
///   "5 Aug" and "today" are both due-today-or-earlier and are not the same news. Shown, demoted.
/// - `branched`: the field is one arm of an `anyOf`. A task is here *because* of one branch, and
///   which one is exactly what the reader wants to know. Promoted above everything.
/// - `free`: the rule never mentions it. Ordinary metadata, in the default order.
public enum Weight: Sendable { case pinned, bounded, branched, free }

/// Where a row is being read.
///
/// `filter` is nil for a plain list page, which has no rule — its rule is "children of this page",
/// and that pins `originList` by construction. `singleWorkspace` says whether this device has only
/// one repository open; when it does the workspace distinguishes nothing and is pinned whatever the
/// rule says, which is the rule the spine already follows.
public struct ViewContext: Sendable {
    public var filter: Filter?
    public var singleWorkspace: Bool
    public init(filter: Filter?, singleWorkspace: Bool) {
        self.filter = filter; self.singleWorkspace = singleWorkspace
    }
}

/// The row's grammar for one view: what rides the title slot, what the sub-line says and in what
/// order, and what the spine means.
///
/// `subLine` is ordered most-to-least important and the row ellipsises from the tail, so the order
/// is the budget. `pinned` is what the engine chose *not* to say, kept so an override can put it
/// back. `branched` is what the rule branched on whether or not it reached the sub-line: labels
/// never do — they are many, and the row already holds the whole set — so this is how a row learns
/// which tag is the *reason* it is on this screen. An ordering fact, not an ink one.
public struct RowGrammar: Sendable {
    public var titleSlot: Field?
    public var subLine: [Field]
    public var spine: Field?
    public var pinned: Set<Field>
    public var branched: Set<Field>
}

public enum Salience {

    /// The default rank for a field the rule leaves alone. Due first because it is what a row is
    /// scanned for; the list and the repository last because they are where a task *is*, not what
    /// it *needs*.
    static let defaultOrder: [Field] = [
        .prop(BuiltIns.due), .prop(BuiltIns.deadline), .prop(BuiltIns.assignee), .originList, .workspace,
    ]

    /// The weight of every field the rule mentions; anything absent is `free`.
    public static func weigh(_ context: ViewContext) -> [Field: Weight] {
        var out: [Field: Weight] = [:]
        if let filter = context.filter { walk(filter, inAny: false, negated: false, &out) }
        // A list page pins its own list; a rule does not name the list it reads from.
        if context.filter == nil { out[.originList] = .pinned }
        // A device fact outranks what the rule hoped for: with one repository open, `ws A OR ws B`
        // can only ever match one of them, so the workspace distinguishes nothing and the spine is
        // absent. This overwrites a branched weight deliberately.
        if context.singleWorkspace { out[.workspace] = .pinned }
        return out
    }

    /// Turns weights into a row. Labels are not in `defaultOrder` — they are many and the rule may
    /// pin some of them — so the row appends the unpinned ones itself; this decides only what the
    /// rule has an opinion on.
    public static func grammar(_ context: ViewContext) -> RowGrammar {
        let weights = weigh(context)
        func w(_ f: Field) -> Weight { weights[f] ?? .free }

        let pinned = Set(weights.filter { $0.value == .pinned }.keys)
        var candidates = defaultOrder
        for f in weights.keys where !candidates.contains(f) {
            if case .prop = f { candidates.append(f) }
        }
        candidates = candidates.filter { w($0) != .pinned }
        // Branched leads, then free in default order, then bounded — a bounded value is still news
        // but the least of it, because the reader already knows the band it sits in.
        let ranked = candidates.enumerated().sorted { a, b in
            let ra = rank(w(a.element)), rb = rank(w(b.element))
            if ra != rb { return ra < rb }
            let ia = defaultOrder.firstIndex(of: a.element) ?? Int.max
            let ib = defaultOrder.firstIndex(of: b.element) ?? Int.max
            if ia != ib { return ia < ib }
            return a.offset < b.offset
        }.map(\.element)

        // The title slot takes the first date worth saying; a slot carrying an assignee would read
        // as a title. The spine is the workspace whenever it varies.
        let titleSlot = ranked.first(where: isDate)
        let spine: Field? = w(.workspace) != .pinned ? .workspace : nil
        let subLine = ranked.filter { $0 != titleSlot && $0 != spine }
        return RowGrammar(titleSlot: titleSlot, subLine: subLine, spine: spine, pinned: pinned,
                          branched: Set(weights.filter { $0.value == .branched }.keys))
    }

    static func rank(_ w: Weight) -> Int {
        switch w { case .branched: return 0; case .free: return 1; case .bounded: return 2; case .pinned: return 3 }
    }

    static func isDate(_ f: Field) -> Bool { f == .prop(BuiltIns.due) || f == .prop(BuiltIns.deadline) }

    static func walk(_ f: Filter, inAny: Bool, negated: Bool, _ out: inout [Field: Weight]) {
        switch f {
        // De Morgan, pushed down as we walk: under a negation an `all` is an `anyOf` and an `anyOf`
        // is an `all`. Without it `not(anyOf(due ≤ today, deadline ≤ today))` promotes both dates to
        // the front of the row as the reason it is there, when they are the reason it is not.
        //
        // A one-armed `anyOf` is an `all` wearing a costume, either way round.
        case let .all(fs):
            for sub in fs { walk(sub, inAny: inAny || (negated && fs.count > 1), negated: negated, &out) }
        case let .anyOf(fs):
            for sub in fs { walk(sub, inAny: inAny || (!negated && fs.count > 1), negated: negated, &out) }
        case let .not(sub):
            walk(sub, inAny: inAny, negated: !negated, &out)
        // Priority joins done, in-progress and type: it is drawn as the enclosure around the task
        // glyph, never as a word on the meta line, so the meta line has no opinion to form about it.
        // Without this the row would be told to stay quiet about a priority the checkbox is
        // shouting — the duplication this engine exists to delete.
        case let .prop(defId, op, _, _, _, _, _):
            if defId != BuiltIns.priority {
                put(&out, .prop(defId), weightOf(op, inAny: inAny, negated: negated))
            }
        case let .hasLabel(id):
            put(&out, .label(id), inAny && negated ? .bounded     // a tag no matching row need carry
                                : inAny ? .branched
                                : .pinned)                        // on every row, or on none
        case let .inWorkspace(id):
            _ = id
            put(&out, .workspace, inAny && negated ? .bounded
                                : inAny ? .branched
                                // Excluding one repository leaves the others, so the workspace
                                // still varies and still earns its spine.
                                : negated ? .bounded
                                : .pinned)
        // Done, in-progress and type are the glyph's business, not the meta line's.
        case .done, .inProgress, .type:
            break
        }
    }

    static func weightOf(_ op: Op, inAny: Bool, negated: Bool) -> Weight {
        // A negated clause under a branch fixes nothing at all: the row may be here for another arm,
        // and this arm only says what the value is *not*. It never pins and never leads.
        if inAny && negated { return .bounded }
        if inAny { return .branched }
        // `not(isSet)` pins absence: nothing to draw. `not(eq x)` only excludes one value, so the
        // rest still vary — bounded. Negated ranges likewise.
        if negated { return op == .isSet ? .pinned : .bounded }
        if op == .eq || op == .isSet || op == .notSet { return .pinned }
        return .bounded
    }

    /// A field mentioned twice keeps its *more* informative weight: one OR arm outranks a pin.
    static func put(_ out: inout [Field: Weight], _ f: Field, _ w: Weight) {
        if let prev = out[f], rank(w) >= rank(prev) { return }
        out[f] = w
    }
}
