import Foundation

/// The smart-list filter tree — `data/filter/Filter.kt`. JSON uses `kind` as the discriminator and
/// omits defaults, exactly as kotlinx.serialization writes it, so a rule made on either phone is
/// the other's to read.
public indirect enum Filter: Equatable, Sendable {
    case all([Filter])
    case anyOf([Filter])
    case not(Filter)
    case done(Bool)
    case inProgress(Bool)
    case type(String)
    case prop(defId: String, op: Op, text: String? = nil, number: Double? = nil, date: Int64? = nil, bool: Bool? = nil, dateRel: DateRel? = nil)
    case hasLabel(String)
    case inWorkspace(String)

    public var workspacesNamed: Set<String> {
        switch self {
        case let .inWorkspace(w): return [w]
        case let .all(fs), let .anyOf(fs): return fs.reduce(into: []) { $0.formUnion($1.workspacesNamed) }
        case let .not(f): return f.workspacesNamed
        default: return []
        }
    }
}

public enum Op: String, Codable, Sendable { case eq, neq, lt, lte, gt, gte, isSet = "is_set", notSet = "not_set" }
public enum DateRel: String, Codable, Sendable { case todayStart = "today_start", todayEnd = "today_end" }
public enum SortBy: String, Codable, Sendable { case propDate = "prop_date", propNumber = "prop_number", propText = "prop_text", title, created }

public struct SortSpec: Codable, Equatable, Sendable {
    public var by: SortBy
    public var defId: String?
    public var desc: Bool
    public var nullsLast: Bool
    public init(by: SortBy, defId: String? = nil, desc: Bool = false, nullsLast: Bool = true) {
        self.by = by; self.defId = defId; self.desc = desc; self.nullsLast = nullsLast
    }
    enum CodingKeys: String, CodingKey { case by, defId, desc, nullsLast }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        by = try c.decode(SortBy.self, forKey: .by)
        defId = try c.decodeIfPresent(String.self, forKey: .defId)
        desc = try c.decodeIfPresent(Bool.self, forKey: .desc) ?? false
        nullsLast = try c.decodeIfPresent(Bool.self, forKey: .nullsLast) ?? true
    }
    public func encode(to e: Encoder) throws {
        var c = e.container(keyedBy: CodingKeys.self)
        try c.encode(by, forKey: .by)
        try c.encodeIfPresent(defId, forKey: .defId)
        if desc { try c.encode(desc, forKey: .desc) }
        if !nullsLast { try c.encode(nullsLast, forKey: .nullsLast) }
    }
}

public struct ApplyOnCreate: Codable, Equatable, Sendable {
    public var defId: String
    public var text: String?
    public var number: Double?
    public var date: Int64?
    public var bool: Bool?
    public var dateRel: DateRel?
    public init(defId: String, text: String? = nil, number: Double? = nil, date: Int64? = nil, bool: Bool? = nil, dateRel: DateRel? = nil) {
        self.defId = defId; self.text = text; self.number = number; self.date = date; self.bool = bool; self.dateRel = dateRel
    }
}

public enum BuiltIns {
    public static let priority = "builtin-priority", due = "builtin-due", deadline = "builtin-deadline", assignee = "builtin-assignee"
}

// MARK: Codable with the `kind` discriminator

extension Filter: Codable {
    enum K: String, CodingKey { case kind, filters, filter, value, defId, op, text, number, date, bool, dateRel, labelId, workspaceId }

    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        switch try c.decode(String.self, forKey: .kind) {
        case "all": self = .all(try c.decode([Filter].self, forKey: .filters))
        case "any": self = .anyOf(try c.decode([Filter].self, forKey: .filters))
        case "not": self = .not(try c.decode(Filter.self, forKey: .filter))
        case "done": self = .done(try c.decode(Bool.self, forKey: .value))
        case "in_progress": self = .inProgress(try c.decode(Bool.self, forKey: .value))
        case "type": self = .type(try c.decode(String.self, forKey: .value))
        case "prop": self = .prop(defId: try c.decode(String.self, forKey: .defId), op: try c.decode(Op.self, forKey: .op),
                                 text: try c.decodeIfPresent(String.self, forKey: .text), number: try c.decodeIfPresent(Double.self, forKey: .number),
                                 date: try c.decodeIfPresent(Int64.self, forKey: .date), bool: try c.decodeIfPresent(Bool.self, forKey: .bool),
                                 dateRel: try c.decodeIfPresent(DateRel.self, forKey: .dateRel))
        case "has_label": self = .hasLabel(try c.decode(String.self, forKey: .labelId))
        case "in_workspace": self = .inWorkspace(try c.decode(String.self, forKey: .workspaceId))
        case let k: throw DecodingError.dataCorruptedError(forKey: .kind, in: c, debugDescription: "unknown filter kind \(k)")
        }
    }

    public func encode(to e: Encoder) throws {
        var c = e.container(keyedBy: K.self)
        switch self {
        case let .all(fs): try c.encode("all", forKey: .kind); try c.encode(fs, forKey: .filters)
        case let .anyOf(fs): try c.encode("any", forKey: .kind); try c.encode(fs, forKey: .filters)
        case let .not(f): try c.encode("not", forKey: .kind); try c.encode(f, forKey: .filter)
        case let .done(v): try c.encode("done", forKey: .kind); try c.encode(v, forKey: .value)
        case let .inProgress(v): try c.encode("in_progress", forKey: .kind); try c.encode(v, forKey: .value)
        case let .type(v): try c.encode("type", forKey: .kind); try c.encode(v, forKey: .value)
        case let .prop(defId, op, text, number, date, bool, dateRel):
            try c.encode("prop", forKey: .kind); try c.encode(defId, forKey: .defId); try c.encode(op, forKey: .op)
            try c.encodeIfPresent(text, forKey: .text); try c.encodeIfPresent(number, forKey: .number); try c.encodeIfPresent(date, forKey: .date)
            try c.encodeIfPresent(bool, forKey: .bool); try c.encodeIfPresent(dateRel, forKey: .dateRel)
        case let .hasLabel(id): try c.encode("has_label", forKey: .kind); try c.encode(id, forKey: .labelId)
        case let .inWorkspace(w): try c.encode("in_workspace", forKey: .kind); try c.encode(w, forKey: .workspaceId)
        }
    }
}

/// kotlinx-compatible JSON: compact, keys in declaration order (JSONEncoder does not promise order,
/// so smart-list definitions written by iOS are re-read by Android fine but may not be byte-equal;
/// the plan is to route them through `JSONValue` before the first shared write).
public enum FilterJSON {
    public static func encode<T: Encodable>(_ v: T) -> String {
        let e = JSONEncoder(); e.outputFormatting = [.withoutEscapingSlashes]
        return String(decoding: try! e.encode(v), as: UTF8.self)
    }
    public static func decode<T: Decodable>(_ t: T.Type, _ s: String) -> T? {
        try? JSONDecoder().decode(t, from: Data(s.utf8))
    }
}

// MARK: derive apply-on-create (write side)

/// Equality clauses are writable and self-satisfying — `FilterCompiler.deriveApplyOnCreate`.
public func deriveApplyOnCreate(_ f: Filter) -> [ApplyOnCreate] {
    switch f {
    case let .all(fs): return fs.flatMap(deriveApplyOnCreate)
    case let .anyOf(fs): return fs.lazy.map(deriveApplyOnCreate).first { !$0.isEmpty } ?? []
    case let .prop(defId, op, text, number, date, bool, dateRel):
        if op == .eq, dateRel == nil { return [ApplyOnCreate(defId: defId, text: text, number: number, date: date, bool: bool)] }
        if (op == .lte && dateRel == .todayEnd) || (op == .gte && dateRel == .todayStart) {
            return [ApplyOnCreate(defId: defId, bool: false, dateRel: .todayStart)]
        }
        return []
    default: return []
    }
}

/// The flipped rule for the "done" half of a view, or nil when the rule has no `done(false)` or asks
/// for in-progress tasks (finishing clears that flag, so the half cannot exist).
public func completedVariant(_ f: Filter) -> Filter? {
    func has(_ f: Filter, _ p: (Filter) -> Bool) -> Bool {
        if p(f) { return true }
        switch f {
        case let .all(fs), let .anyOf(fs): return fs.contains { has($0, p) }
        case let .not(x): return has(x, p)
        default: return false
        }
    }
    guard has(f, { if case .done(false) = $0 { return true }; return false }),
          !has(f, { if case .inProgress(true) = $0 { return true }; return false }) else { return nil }
    func flip(_ f: Filter) -> Filter {
        switch f {
        case let .done(v): return .done(!v)
        case let .all(fs): return .all(fs.map(flip))
        case let .anyOf(fs): return .anyOf(fs.map(flip))
        case let .not(x): return .not(flip(x))
        default: return f
        }
    }
    return flip(f)
}
