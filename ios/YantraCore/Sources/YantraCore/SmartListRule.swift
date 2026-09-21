import Foundation

/// What a smart list shows, as the builder's one top-level choice.
public enum ShowMode: String, CaseIterable, Sendable {
    case open = "Open"
    /// The tasks you have said you are in the middle of.
    case started = "Started"
    case all = "All"
    case done = "Completed"

    public var label: String { rawValue }
}

/// Whether a label condition needs any of its labels or all of them.
public enum LabelMatchMode: Sendable { case any, all }

/// One condition: either a property condition (`defId` set) or a label condition (`labelIds` set,
/// possibly still empty right after being added) — never both.
///
/// Multiple label conditions can coexist and are ANDed together, while each one's own mode decides
/// whether it needs any or all of its own labels. Composing the two gives AND-of-ORs without a
/// boolean-tree editor anybody would have to learn.
public struct Cond: Equatable, Identifiable, Sendable {
    public var id = UUID()
    public var defId: String?
    public var labelIds: [String] = []
    public var labelMatch: LabelMatchMode = .any
    public var op: Op = .isSet
    public var text: String?
    public var number: Double?
    public var dateRel: DateRel?
    public var bool: Bool?

    public var isLabelCond: Bool { defId == nil }

    public init(defId: String? = nil, labelIds: [String] = [], labelMatch: LabelMatchMode = .any,
                op: Op = .isSet, text: String? = nil, number: Double? = nil,
                dateRel: DateRel? = nil, bool: Bool? = nil) {
        self.defId = defId; self.labelIds = labelIds; self.labelMatch = labelMatch
        self.op = op; self.text = text; self.number = number; self.dateRel = dateRel; self.bool = bool
    }

    public static func == (l: Cond, r: Cond) -> Bool {
        l.defId == r.defId && l.labelIds == r.labelIds && l.labelMatch == r.labelMatch
            && l.op == r.op && l.text == r.text && l.number == r.number
            && l.dateRel == r.dateRel && l.bool == r.bool
    }
}

/// One choice of operator, as the builder offers it.
public struct OpOption: Equatable, Sendable {
    public let label: String, op: Op, dateRel: DateRel?, bool: Bool?
    public init(_ label: String, _ op: Op, dateRel: DateRel? = nil, bool: Bool? = nil) {
        self.label = label; self.op = op; self.dateRel = dateRel; self.bool = bool
    }
}

public enum PropertyKind {
    public static let select = "select", date = "date", datetime = "datetime"
    public static let number = "number", checkbox = "checkbox", text = "text"
}

/// What this editor could recover from a stored rule.
///
/// `extras` holds branches the builder has no control for — Today ships as "due OR deadline", a
/// nested any-of of two property conditions, and there is no UI for an OR of properties. They are
/// carried through untouched and re-emitted, so opening the sheet on a rule it cannot fully express
/// edits what it can and leaves the rest exactly as it was. The alternative — dropping what it does
/// not understand — would quietly rewrite a working smart list the first time anyone looked at it.
public struct DecodedRule: Sendable {
    public var show: ShowMode
    public var conds: [Cond]
    public var extras: [Filter]
    /// Workspaces the rule names. Empty is "everywhere", not "nowhere".
    public var workspaces: [String]
}

/// The smart list rule, decoded for editing and encoded back — `ui/smart/SmartListBuilder.kt`.
public enum SmartListRule {

    public static func ops(for kind: String) -> [OpOption] {
        switch kind {
        case PropertyKind.select:
            return [OpOption("is", .eq), OpOption("is not", .neq),
                    OpOption("is set", .isSet), OpOption("is empty", .notSet)]
        case PropertyKind.date, PropertyKind.datetime:
            return [OpOption("today or earlier", .lte, dateRel: .todayEnd),
                    OpOption("today or later", .gte, dateRel: .todayStart),
                    OpOption("has a date", .isSet), OpOption("no date", .notSet)]
        case PropertyKind.number:
            return [OpOption("equals", .eq), OpOption("less than", .lt), OpOption("greater than", .gt),
                    OpOption("is set", .isSet), OpOption("empty", .notSet)]
        case PropertyKind.checkbox:
            return [OpOption("is checked", .eq, bool: true), OpOption("is unchecked", .eq, bool: false)]
        default:
            return [OpOption("is", .eq), OpOption("is set", .isSet), OpOption("is empty", .notSet)]
        }
    }

    /// The condition a property starts as when it is first added: its first operator, and for a
    /// select, its first option — so a freshly added row already says something true rather than
    /// sitting incomplete until every dropdown has been visited.
    public static func defaultCond(_ def: PropertyDef) -> Cond {
        let op = ops(for: def.kind).first ?? OpOption("is set", .isSet)
        let firstOption = def.kind == PropertyKind.select ? def.selectConfig.options.first?.name : nil
        return Cond(defId: def.id, op: op.op, text: firstOption, dateRel: op.dateRel, bool: op.bool)
    }

    /// Whether this operator needs a value alongside it. "is set" and "is empty" ask about presence
    /// and would be contradicted by a value sitting next to them.
    public static func showsValue(_ op: Op) -> Bool {
        op == .eq || op == .neq || op == .lt || op == .gt
    }

    public static func decode(_ filter: Filter) -> DecodedRule {
        let parts: [Filter]
        if case let .all(fs) = filter { parts = fs } else { parts = [filter] }

        var show = ShowMode.all
        var started = false
        var conds: [Cond] = [], extras: [Filter] = [], workspaces: [String] = []

        for f in parts {
            switch f {
            // Every smart list is tasks-only; the builder never offers to change that.
            case .type:
                continue
            case let .inWorkspace(w):
                workspaces.append(w)
            case let .inProgress(v) where v:
                started = true
            case let .done(v):
                show = v ? .done : .open
            case let .prop(defId, op, text, number, _, bool, dateRel):
                conds.append(Cond(defId: defId, op: op, text: text, number: number,
                                  dateRel: dateRel, bool: bool))
            case let .anyOf(fs) where !fs.isEmpty && fs.allSatisfy(isWorkspace):
                // Several workspaces are an OR, which is what `encode` writes for more than one.
                workspaces += fs.compactMap(workspaceId)
            case let .anyOf(fs) where !fs.isEmpty && fs.allSatisfy(isLabel):
                conds.append(Cond(labelIds: fs.compactMap(labelId), labelMatch: .any))
            case let .all(fs) where !fs.isEmpty && fs.allSatisfy(isLabel):
                conds.append(Cond(labelIds: fs.compactMap(labelId), labelMatch: .all))
            default:
                extras.append(f)
            }
        }
        // Started is the narrower claim, so it wins over an accompanying "open".
        return DecodedRule(show: started ? .started : show, conds: conds, extras: extras,
                           workspaces: workspaces)
    }

    public static func encode(show: ShowMode, conds: [Cond], extras: [Filter] = [],
                              workspaces: Set<String> = []) -> Filter {
        var base: [Filter] = [.type(NodeType.task)]
        switch show {
        case .open: base.append(.done(false))
        case .done: base.append(.done(true))
        // No done(false) alongside it: finishing a task clears in-progress in the same write, so
        // "started" already means "not finished" and the extra clause would be noise in the rule.
        case .started: base.append(.inProgress(true))
        case .all: break
        }
        for c in conds {
            if c.isLabelCond {
                if !c.labelIds.isEmpty {
                    let labels = c.labelIds.map { Filter.hasLabel($0) }
                    base.append(c.labelMatch == .all ? .all(labels) : .anyOf(labels))
                }
            } else if let defId = c.defId {
                base.append(.prop(defId: defId, op: c.op, text: c.text, number: c.number,
                                  date: nil, bool: c.bool, dateRel: c.dateRel))
            }
        }
        // No clause at all for "everywhere": the absence is what makes the rule portable, and one
        // clause per repository would silently stop covering a workspace added afterwards.
        switch workspaces.count {
        case 0: break
        case 1: base.append(.inWorkspace(workspaces.first!))
        default: base.append(.anyOf(workspaces.sorted().map { Filter.inWorkspace($0) }))
        }
        base += extras
        return .all(base)
    }

    /// Sorted by the date the rule asks about, when it asks about one — otherwise newest first.
    public static func sort(_ conds: [Cond]) -> [SortSpec] {
        if let dated = conds.first(where: { $0.dateRel != nil && $0.defId != nil }), let defId = dated.defId {
            return [SortSpec(by: .propDate, defId: defId)]
        }
        return [SortSpec(by: .created, desc: true)]
    }

    // MARK: the small predicates the decoder leans on

    static func isLabel(_ f: Filter) -> Bool { if case .hasLabel = f { return true }; return false }
    static func labelId(_ f: Filter) -> String? { if case let .hasLabel(id) = f { return id }; return nil }
    static func isWorkspace(_ f: Filter) -> Bool { if case .inWorkspace = f { return true }; return false }
    static func workspaceId(_ f: Filter) -> String? { if case let .inWorkspace(w) = f { return w }; return nil }
}

/// Quick-fill starting points shown at the top of the builder.
///
/// They pre-populate the controls in place rather than gating a separate screen before you reach
/// the real ones.
public enum SmartTemplate: String, CaseIterable, Sendable {
    case dueToday = "Due today"
    case highPriority = "High priority"
    case allOpen = "All open tasks"

    public var label: String { rawValue }

    public func preset(_ defs: [PropertyDef]) -> (ShowMode, [Cond]) {
        let due = defs.first { $0.name.caseInsensitiveCompare("Due") == .orderedSame }
        let priority = defs.first { $0.name.caseInsensitiveCompare("Priority") == .orderedSame }
        switch self {
        case .dueToday:
            return (.open, due.map { [Cond(defId: $0.id, op: .lte, dateRel: .todayEnd)] } ?? [])
        case .highPriority:
            return (.open, priority.map { [Cond(defId: $0.id, op: .eq, text: "High")] } ?? [])
        case .allOpen:
            return (.open, [])
        }
    }
}
