import Foundation

// MARK: - dates

/// A calendar day, `yyyy-MM-dd`, the way `java.time.LocalDate` prints. Files carry these for
/// all-day due dates, deadlines and completion days.
public struct LocalDate: Hashable, Comparable, Sendable, CustomStringConvertible {
    public var year: Int, month: Int, day: Int
    public init(year: Int, month: Int, day: Int) { self.year = year; self.month = month; self.day = day }

    public init?(_ s: String) {
        let p = s.split(separator: "-", omittingEmptySubsequences: false)
        guard p.count == 3, p[0].count == 4, p[1].count == 2, p[2].count == 2,
              let y = Int(p[0]), let m = Int(p[1]), let d = Int(p[2]), (1...12).contains(m), (1...31).contains(d) else { return nil }
        var c = DateComponents(); c.year = y; c.month = m; c.day = d
        guard Calendar(identifier: .gregorian).date(from: c) != nil, LocalDate.daysIn(month: m, year: y) >= d else { return nil }
        self.init(year: y, month: m, day: d)
    }

    public static func today(_ cal: Calendar = .current) -> LocalDate {
        let c = cal.dateComponents([.year, .month, .day], from: Date())
        return LocalDate(year: c.year!, month: c.month!, day: c.day!)
    }

    public static func of(_ date: Date, _ cal: Calendar = .current) -> LocalDate {
        let c = cal.dateComponents([.year, .month, .day], from: date)
        return LocalDate(year: c.year!, month: c.month!, day: c.day!)
    }

    /// Local midnight of this day.
    public func startOfDay(_ cal: Calendar = .current) -> Date {
        var c = DateComponents(); c.year = year; c.month = month; c.day = day
        return cal.date(from: c)!
    }

    public func adding(days: Int, _ cal: Calendar = .current) -> LocalDate {
        LocalDate.of(cal.date(byAdding: .day, value: days, to: startOfDay(cal))!, cal)
    }

    public func days(until other: LocalDate, _ cal: Calendar = .current) -> Int {
        cal.dateComponents([.day], from: startOfDay(cal), to: other.startOfDay(cal)).day ?? 0
    }

    public var description: String { String(format: "%04d-%02d-%02d", year, month, day) }

    public static func < (l: LocalDate, r: LocalDate) -> Bool {
        (l.year, l.month, l.day) < (r.year, r.month, r.day)
    }

    public static func daysIn(month: Int, year: Int) -> Int {
        switch month {
        case 2: return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }
}

/// `java.time.Instant` text: `2026-08-25T14:22:31.402Z`, with the fraction omitted when it is
/// zero and otherwise printed to milliseconds (which is all the app ever stores).
public enum InstantText {
    public static func format(_ date: Date) -> String {
        let ms = Int64((date.timeIntervalSince1970 * 1000).rounded())
        let whole = ms / 1000, frac = ms % 1000
        var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "UTC")!
        let d = c.dateComponents([.year, .month, .day, .hour, .minute, .second], from: Date(timeIntervalSince1970: TimeInterval(whole)))
        var s = String(format: "%04d-%02d-%02dT%02d:%02d:%02d", d.year!, d.month!, d.day!, d.hour!, d.minute!, d.second!)
        if frac != 0 { s += String(format: ".%03d", frac) }
        return s + "Z"
    }

    public static func parse(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }
}

// MARK: - model

public enum NodeType {
    public static let list = "list", task = "task", paragraph = "paragraph", heading = "heading"
    public static let bullet = "bullet", numbered = "numbered", ink = "ink", image = "image"
    public static let smartList = "smart_list", group = "group"
    /// Something that happens at a time. Deliberately **not** in `textual`: those convert freely
    /// between each other because a line of text is all any of them holds, and there is nothing
    /// honest to invent when a paragraph is asked to become a span.
    public static let event = "event"
    public static let textual: Set<String> = [task, paragraph, heading, bullet, numbered]
}

public enum SystemKey { public static let today = "today", inbox = "inbox" }

public enum TaskStatus: Sendable { case open, inProgress, done }

public enum DueValue: Equatable, Sendable {
    case allDay(LocalDate)
    case at(Date)
}

public struct DueSpec: Equatable, Sendable {
    public var value: DueValue
    /// Minutes *before* the due moment, one per reminder. Negative means after; empty means none.
    ///
    /// A list rather than a single offset, because one warning is not always the right number of
    /// warnings: half an hour before is useful for getting to a thing, and a day before is what
    /// stops you from having nothing ready when you get there. They answer different questions and
    /// neither replaces the other.
    ///
    /// **Kept sorted, largest first, and distinct.** The order is the order they fire in, so it is
    /// the order a person reads them in — and two devices holding the same task have to produce the
    /// same bytes or every sync is a diff about nothing. `DueSpec.reminders(_:)` is the only way one
    /// should be built.
    public var reminders: [Int]
    /// How long it is expected to take — what makes a task drawable on a timeline beside an event.
    ///
    /// Null for an all-day task and for one that is merely *at* a time, because a moment and a span
    /// are different claims and only one of them can be drawn to scale.
    public var duration: ISODuration?
    public init(_ value: DueValue, reminders: [Int] = [], duration: ISODuration? = nil) {
        self.value = value; self.reminders = DueSpec.reminders(reminders); self.duration = duration
    }

    /// The first reminder that will fire, for the places that only need to know there is one.
    public var firstReminder: Int? { reminders.first }

    /// Canonical order and no repeats — the two things that make the bytes stable.
    public static func reminders<S: Sequence>(_ offsets: S) -> [Int] where S.Element == Int {
        Array(Set(offsets)).sorted(by: >)
    }
}

/// The event **in somebody else's calendar** that a line is a note about — CALENDAR_PLAN.md §19.
///
/// `uid` is the identity the sync source gave the event, never the provider's local row id: that is
/// a number this device made up, different on your other phone and gone after a reinstall, so a
/// file carrying one would claim a relationship it cannot honour anywhere else.
public struct ExternalRef: Equatable, Sendable {
    public var uid: String
    public var occurrence: LocalDateTime?
    public init(_ uid: String, occurrence: LocalDateTime? = nil) { self.uid = uid; self.occurrence = occurrence }
}

/// The occurrence of a repeating event that this line replaces. `originalStart` is the start the
/// rule would have produced, not where the override moved it to; nil means "the occurrence starting
/// at this line's own start", which keeps a cancellation short.
public struct SeriesRef: Equatable, Sendable {
    public var id: String
    public var originalStart: LocalDateTime?
    public init(_ id: String, originalStart: LocalDateTime? = nil) { self.id = id; self.originalStart = originalStart }
}

/// When an event happens — a wall clock and a zone, deliberately not an instant. See `LocalDateTime`.
///
/// `zone` nil means **floating**: "09:00 wherever you are". A birthday is floating; a meeting with
/// someone in another country is not.
///
/// `end` is **exclusive**, so a length is `end - start` with no off-by-one. All-day events are
/// written in the file with an *inclusive* last date, because that is what somebody reading the line
/// means by "the 11th to the 13th"; `PageCodec` is where that seam lives.
public struct EventTime: Equatable, Sendable {
    public var start: LocalDateTime
    public var end: LocalDateTime
    public var zone: String?
    public var allDay: Bool
    public init(start: LocalDateTime, end: LocalDateTime, zone: String? = nil, allDay: Bool = false) {
        self.start = start; self.end = end; self.zone = zone; self.allDay = allDay
    }
    public var duration: ISODuration { ISODuration(seconds: start.seconds(until: end)) }
    /// True for a moment rather than a span — a reminder-shaped event.
    public var isInstantaneous: Bool { start == end }
}

/// Something that happens, as opposed to something to be done.
///
/// An event has a span and no done state — it is not finished, it simply passes. Written
/// `@ <when> <title> ^<id> <tokens…>`; the marker is `@ ` rather than `* ` because a leading
/// asterisk is a bullet in every markdown editor there is, and a bullet somebody types by hand must
/// not become a meeting.
public struct EventRef: Equatable, Sendable {
    public var id: String
    public var title: String
    public var time: EventTime
    /// RFC 5545 subset, stored verbatim — including rules this build cannot expand.
    public var rrule: String?
    /// The task this block is time set aside for — a **sitting**. It carries no title of its own;
    /// it draws with the task's, because storing the name twice gives two places to rename from.
    public var forTaskId: String?
    public var external: ExternalRef?
    /// What colour it wears, by **name** — `col:teal`. A name, not a value, so the same word can be
    /// a slightly different ink on paper and at night.
    public var color: String?
    public var series: SeriesRef?
    /// Only meaningful alongside `series`: how one occurrence of a repeat is removed without
    /// rewriting the series line.
    public var cancelled: Bool
    public var location: String?
    /// Minutes *before* the start; negative means after.
    public var reminderMin: Int?
    public var labels: [String]
    /// Who is involved, as `@name`. Nothing is sent to anybody; this is a note about who.
    public var attendees: [String]
    public var priority: String?
    public var indent: Int
    public var raw: String?

    public init(id: String, title: String, time: EventTime, rrule: String? = nil, forTaskId: String? = nil,
                external: ExternalRef? = nil, color: String? = nil, series: SeriesRef? = nil, cancelled: Bool = false,
                location: String? = nil, reminderMin: Int? = nil, labels: [String] = [], attendees: [String] = [],
                priority: String? = nil, indent: Int = 0, raw: String? = nil) {
        self.id = id; self.title = title; self.time = time; self.rrule = rrule; self.forTaskId = forTaskId
        self.external = external; self.color = color; self.series = series; self.cancelled = cancelled
        self.location = location; self.reminderMin = reminderMin; self.labels = labels; self.attendees = attendees
        self.priority = priority; self.indent = indent; self.raw = raw
    }
}

public struct TaskRef: Equatable, Sendable {
    public var id: String
    public var title: String
    public var status: TaskStatus = .open
    public var indent: Int = 0
    public var due: DueSpec? = nil
    public var deadline: LocalDate? = nil
    public var priority: String? = nil
    public var labels: [String] = []
    public var assignee: String? = nil
    public var doneAt: LocalDate? = nil
    /// The meeting **in somebody else's calendar** this task is about. A task that carries one is a
    /// task *about* a meeting, not a copy of it: the day draws one block at the meeting's hours and
    /// the meeting's own details are read live rather than written here.
    public var external: ExternalRef? = nil
    public var raw: String? = nil
    public init(id: String, title: String, status: TaskStatus = .open, indent: Int = 0, due: DueSpec? = nil, deadline: LocalDate? = nil,
                priority: String? = nil, labels: [String] = [], assignee: String? = nil, doneAt: LocalDate? = nil,
                external: ExternalRef? = nil, raw: String? = nil) {
        self.id = id; self.title = title; self.status = status; self.indent = indent; self.due = due; self.deadline = deadline
        self.priority = priority; self.labels = labels; self.assignee = assignee; self.doneAt = doneAt
        self.external = external; self.raw = raw
    }
}

/// One line of a page. `raw` is the source line; the emitter prefers it after checking that it
/// still parses to this exact block.
public enum Block: Equatable, Sendable {
    case prose(String, indent: Int = 0, raw: String? = nil)
    case heading(String, indent: Int = 0, raw: String? = nil)
    case bullet(String, indent: Int = 0, raw: String? = nil)
    case numbered(String, indent: Int = 0, raw: String? = nil)
    case task(TaskRef)
    case ink(id: String, indent: Int = 0, raw: String? = nil)
    case image(uri: String, indent: Int = 0, raw: String? = nil)
    case event(EventRef)

    public var indent: Int {
        switch self {
        case let .prose(_, i, _), let .heading(_, i, _), let .bullet(_, i, _), let .numbered(_, i, _), let .ink(_, i, _), let .image(_, i, _): return i
        case let .task(t): return t.indent
        case let .event(e): return e.indent
        }
    }
    public var raw: String? {
        switch self {
        case let .prose(_, _, r), let .heading(_, _, r), let .bullet(_, _, r), let .numbered(_, _, r), let .ink(_, _, r), let .image(_, _, r): return r
        case let .task(t): return t.raw
        case let .event(e): return e.raw
        }
    }
    public var strippingRaw: Block {
        switch self {
        case let .prose(t, i, _): return .prose(t, indent: i)
        case let .heading(t, i, _): return .heading(t, indent: i)
        case let .bullet(t, i, _): return .bullet(t, indent: i)
        case let .numbered(t, i, _): return .numbered(t, indent: i)
        case let .ink(id, i, _): return .ink(id: id, indent: i)
        case let .image(u, i, _): return .image(uri: u, indent: i)
        case var .task(t): t.raw = nil; return .task(t)
        case var .event(e): e.raw = nil; return .event(e)
        }
    }
    /// Prose and headings breathe; consecutive list items do not, and an event line is one.
    public var isListish: Bool {
        switch self { case .task, .bullet, .numbered, .event: return true; default: return false }
    }
    /// The text a textual block carries; nil for ink and image.
    public var text: String? {
        switch self {
        case let .prose(t, _, _), let .heading(t, _, _), let .bullet(t, _, _), let .numbered(t, _, _): return t
        case let .task(t): return t.title
        case let .event(e): return e.title
        default: return nil
        }
    }
    public var nodeType: String {
        switch self {
        case .prose: return NodeType.paragraph
        case .heading: return NodeType.heading
        case .bullet: return NodeType.bullet
        case .numbered: return NodeType.numbered
        case .task: return NodeType.task
        case .ink: return NodeType.ink
        case .image: return NodeType.image
        case .event: return NodeType.event
        }
    }
}

public struct PageDoc: Equatable, Sendable {
    public var id: String
    public var type: String
    public var parent: String?
    /// Authoritative only when `parent` is nil.
    public var title: String?
    public var systemKey: String?
    public var modifiedAt: Date
    public var device: String?
    public var blocks: [Block]
    /// Frontmatter keys this version does not understand, in file order.
    public var unknownKeys: [(String, String)]
    /// The emoji this list wears instead of its drawn mark, or nil to keep the mark.
    ///
    /// In the file for the same reason as `color`: it is a choice somebody made, and choices live
    /// where the tasks do. Kept apart from `color` rather than folded into one "appearance" field,
    /// because they are genuinely independent — an emoji carries its own colours, so a list can
    /// have a mark and a colour, an emoji and a colour, or neither.
    public var icon: String?
    /// The colour this list wears, as a palette **name** — in the file, because it is a choice
    /// somebody made and the file is where choices live.
    public var color: String?

    public init(id: String, type: String, parent: String?, title: String?, systemKey: String? = nil, modifiedAt: Date,
                device: String?, blocks: [Block], unknownKeys: [(String, String)] = [],
                icon: String? = nil, color: String? = nil) {
        self.id = id; self.type = type; self.parent = parent; self.title = title; self.systemKey = systemKey
        self.modifiedAt = modifiedAt; self.device = device; self.blocks = blocks; self.unknownKeys = unknownKeys
        self.icon = icon; self.color = color
    }

    public static func == (l: PageDoc, r: PageDoc) -> Bool {
        l.id == r.id && l.type == r.type && l.parent == r.parent && l.title == r.title && l.systemKey == r.systemKey
            && l.modifiedAt == r.modifiedAt && l.device == r.device && l.blocks == r.blocks && l.icon == r.icon && l.color == r.color
            && l.unknownKeys.count == r.unknownKeys.count && zip(l.unknownKeys, r.unknownKeys).allSatisfy { $0 == $1 }
    }
}

// MARK: - codec

/// Reads and writes a page file. A port of `data/format/PageCodec.kt`; the comments there explain
/// every rule, and `conformance/pages` pins the bytes.
public enum PageCodec {
    /// One level of visual indent — a guillemet, which means nothing to markdown.
    public static let indentMarker = "\u{00BB}"
    static let fence = "---"
    static let known: Set<String> = ["id", "type", "parent", "title", "system_key", "icon", "color", "modified_at", "device"]

    public static func decode(_ text: String) -> PageDoc {
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n").split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        var i = 0
        var front: [(String, String)] = []
        if lines.first?.trimmingCharacters(in: .whitespaces) == fence {
            i = 1
            while i < lines.count, lines[i].trimmingCharacters(in: .whitespaces) != fence {
                let line = lines[i]
                if let colon = line.firstIndex(of: ":"), colon > line.startIndex {
                    let k = String(line[..<colon]).trimmingCharacters(in: .whitespaces)
                    let v = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
                    if let at = front.firstIndex(where: { $0.0 == k }) { front[at].1 = v } else { front.append((k, v)) }
                }
                i += 1
            }
            i += 1
        }
        var blocks: [Block] = []
        while i < lines.count {
            // Empty, not blank: an empty line separates blocks; a whitespace-only line is an empty block.
            if !lines[i].isEmpty { blocks.append(parseBlock(lines[i])) }
            i += 1
        }
        func f(_ k: String) -> String? { front.first { $0.0 == k }?.1 }
        func nonBlank(_ s: String?) -> String? { (s?.trimmingCharacters(in: .whitespaces).isEmpty ?? true) ? nil : s }
        return PageDoc(
            id: f("id") ?? "", type: f("type") ?? "", parent: nonBlank(f("parent")), title: nonBlank(f("title")),
            systemKey: nonBlank(f("system_key")),
            modifiedAt: f("modified_at").flatMap(InstantText.parse) ?? Date(timeIntervalSince1970: 0),
            device: nonBlank(f("device")), blocks: blocks,
            unknownKeys: front.filter { !known.contains($0.0) },
            icon: nonBlank(f("icon")), color: nonBlank(f("color")))
    }

    static func splitIndent(_ line: String) -> (Int, String) {
        var rest = Substring(line), depth = 0
        while rest.hasPrefix(indentMarker) {
            depth += 1
            rest = rest.dropFirst(indentMarker.count)
            if rest.hasPrefix(" ") { rest = rest.dropFirst() }
        }
        return (depth, String(rest))
    }

    /// An event line. See `EventRef` for why it is not `* `.
    public static let eventMarker = "@ "
    static let taskMarker = try! NSRegularExpression(pattern: #"^- \[([ xX~])] ?"#)
    static let numbered = try! NSRegularExpression(pattern: #"^(\d+)\.\s+(.*)$"#, options: [.dotMatchesLineSeparators])
    static let inkRef = try! NSRegularExpression(pattern: #"^!\[\[ink:([^\]]+)]]$"#)
    static let imageRef = try! NSRegularExpression(pattern: #"^!\[\[image:([^\]]+)]]$"#)

    public static func parseBlock(_ raw: String) -> Block {
        let (indent, rest) = splitIndent(raw)
        let ns = rest as NSString
        let all = NSRange(location: 0, length: ns.length)
        if let m = inkRef.firstMatch(in: rest, range: all) { return .ink(id: ns.substring(with: m.range(at: 1)), indent: indent, raw: raw) }
        if let m = imageRef.firstMatch(in: rest, range: all) { return .image(uri: ns.substring(with: m.range(at: 1)), indent: indent, raw: raw) }
        // `@ ` cannot collide with the checkbox or with a bullet, so the order here is only for
        // reading. A `@ ` line whose time makes no sense is not an event and must not be silently
        // dropped: it falls through to prose holding exactly what was written.
        if rest.hasPrefix(eventMarker), let e = parseEvent(String(rest.dropFirst(eventMarker.count)), indent: indent, raw: raw) {
            return .event(e)
        }
        if let m = taskMarker.firstMatch(in: rest, range: all) {
            let status: TaskStatus
            switch ns.substring(with: m.range(at: 1)) { case "x", "X": status = .done; case "~": status = .inProgress; default: status = .open }
            return .task(parseTask(ns.substring(from: m.range.length), status: status, indent: indent, raw: raw))
        }
        if rest.hasPrefix("# ") { return .heading(String(rest.dropFirst(2)), indent: indent, raw: raw) }
        if rest.hasPrefix("- ") { return .bullet(String(rest.dropFirst(2)), indent: indent, raw: raw) }
        if let m = numbered.firstMatch(in: rest, range: all) { return .numbered(ns.substring(with: m.range(at: 2)), indent: indent, raw: raw) }
        if rest.allSatisfy(\.isWhitespace) { return .prose("", indent: indent, raw: raw) }
        return .prose(rest, indent: indent, raw: raw)
    }

    /// Splits a task line into title and trailing tokens, scanning right to left and stopping at
    /// the first word that is not a token.
    static func parseTask(_ body: String, status: TaskStatus, indent: Int, raw: String) -> TaskRef {
        var words = body.trimmingCharacters(in: .whitespaces).components(separatedBy: " ")
        var id = "", due: DueSpec? = nil, deadline: LocalDate? = nil, doneAt: LocalDate? = nil
        var priority: String? = nil, assignee: String? = nil, labels: [String] = [], external: ExternalRef? = nil
        while let w = words.last {
            var consumed = false
            if w.contains(Links.close) { consumed = false }
            else if w.hasPrefix("^"), id.isEmpty { id = String(w.dropFirst()); consumed = true }
            else if w.hasPrefix("due:") { if let d = parseDue(String(w.dropFirst(4))) { due = d; consumed = true } }
            else if w.hasPrefix("deadline:") { if let d = LocalDate(String(w.dropFirst(9))) { deadline = d; consumed = true } }
            else if w.hasPrefix("done:") { if let d = LocalDate(String(w.dropFirst(5))) { doneAt = d; consumed = true } }
            else if w.hasPrefix("ext:") { if let x = parseExternal(String(w.dropFirst(4))) { external = x; consumed = true } }
            else if w.hasPrefix("!"), w.count > 1 { priority = String(w.dropFirst()); consumed = true }
            else if w.hasPrefix("@"), w.count > 1 { assignee = String(w.dropFirst()); consumed = true }
            else if w.hasPrefix("#"), w.count > 1 { labels.append(String(w.dropFirst())); consumed = true }
            if !consumed { break }
            words.removeLast()
        }
        return TaskRef(id: id, title: words.joined(separator: " "), status: status, indent: indent, due: due, deadline: deadline,
                       priority: priority, labels: labels.reversed(), assignee: assignee, doneAt: doneAt,
                       external: external, raw: raw)
    }

    /// `2026-08-26`, `2026-08-26T09:00:00Z`, either optionally carrying a length and a reminder:
    /// `due:2026-08-26T09:00:00Z/PT1H+r15`.
    ///
    /// The `/PT1H` tail is the same ISO interval the event when-slot uses, deliberately — a task
    /// blocked out from nine to ten and a meeting from nine to ten are the same shape on a timeline.
    /// A length on an all-day task is refused rather than kept: "all of Tuesday, for one hour" does
    /// not mean anything, and storing it would leave the timeline to decide what it meant.
    static func parseDue(_ token: String) -> DueSpec? {
        var head = token, reminders: [Int] = []
        if let at = token.range(of: "+r") {
            head = String(token[..<at.lowerBound])
            // `+r30` and `+r30,15` are the same syntax with one and two reminders in it. The older
            // spelling is the new one with a single element, so every file written before this
            // parses unchanged and nothing needs converting.
            //
            // All or nothing: a tail that is partly unreadable — `+r30,x` — is refused rather than
            // quietly kept as `+r30`, because a task that silently loses one of its two reminders
            // is the failure this whole feature exists to avoid.
            let parts = token[at.upperBound...].split(separator: ",", omittingEmptySubsequences: false)
                .map { Int($0.trimmingCharacters(in: .whitespaces)) }
            if parts.isEmpty || parts.contains(where: { $0 == nil }) { return nil }
            reminders = DueSpec.reminders(parts.compactMap { $0 })
        }
        var body = head, duration: ISODuration? = nil
        if let slash = head.firstIndex(of: "/") {
            body = String(head[..<slash])
            guard let d = ISODuration(String(head[head.index(after: slash)...])) else { return nil }
            duration = d
        }
        if body.contains("T") {
            guard let d = InstantText.parse(body) else { return nil }
            return DueSpec(.at(d), reminders: reminders, duration: duration)
        }
        guard let d = LocalDate(body) else { return nil }
        if duration != nil { return nil }
        return DueSpec(.allDay(d), reminders: reminders)
    }

    // MARK: the event line

    /// `@ <when> <title> <tokens…>`.
    ///
    /// The when comes **first**, unlike a task's `due:`: a line that opens with its time reads like
    /// a calendar, and a file of events sorts and greps by time without a parser. It is always
    /// exactly one whitespace-free word, so the split is unambiguous. Everything after it is scanned
    /// right to left for tokens, the same way and for the same reason as `parseTask`.
    ///
    /// Returns nil for a when-slot that will not parse, so the caller can fall back to prose.
    static func parseEvent(_ body: String, indent: Int, raw: String) -> EventRef? {
        let trimmed = body.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return nil }
        let whenWord = String(trimmed.prefix(while: { $0 != " " }))
        guard let time = parseWhen(whenWord) else { return nil }

        var words = trimmed.dropFirst(whenWord.count).trimmingCharacters(in: .whitespaces)
            .components(separatedBy: " ").filter { !$0.isEmpty }
        var id = "", rrule: String? = nil, forTask: String? = nil, series: SeriesRef? = nil
        var cancelled = false, location: String? = nil, color: String? = nil
        var external: ExternalRef? = nil, reminder: Int? = nil, priority: String? = nil
        var labels: [String] = [], attendees: [String] = []

        while let w = words.last {
            var consumed = false
            if w.contains(Links.close) { consumed = false }
            else if w.hasPrefix("^"), id.isEmpty { id = String(w.dropFirst()); consumed = true }
            else if w.hasPrefix("rrule:") { let v = String(w.dropFirst(6)); if !v.isEmpty { rrule = v; consumed = true } }
            else if w.hasPrefix("for:") { let v = String(w.dropFirst(4)); if !v.isEmpty { forTask = v; consumed = true } }
            else if w.hasPrefix("series:") { if let x = parseSeries(String(w.dropFirst(7))) { series = x; consumed = true } }
            else if w == "cancelled" { cancelled = true; consumed = true }
            else if w.hasPrefix("loc:") { let v = String(w.dropFirst(4)); if !v.isEmpty { location = decodeValue(v); consumed = true } }
            else if w.hasPrefix("col:") { let v = String(w.dropFirst(4)); if !v.isEmpty { color = v; consumed = true } }
            else if w.hasPrefix("ext:") { if let x = parseExternal(String(w.dropFirst(4))) { external = x; consumed = true } }
            else if w.hasPrefix("remind:") { if let r = Int(w.dropFirst(7)) { reminder = r; consumed = true } }
            else if w.hasPrefix("!"), w.count > 1 { priority = String(w.dropFirst()); consumed = true }
            else if w.hasPrefix("@"), w.count > 1 { attendees.append(String(w.dropFirst())); consumed = true }
            else if w.hasPrefix("#"), w.count > 1 { labels.append(String(w.dropFirst())); consumed = true }
            if !consumed { break }
            words.removeLast()
        }

        return EventRef(id: id, title: words.joined(separator: " "), time: time, rrule: rrule, forTaskId: forTask,
                        external: external, color: color, series: series, cancelled: cancelled, location: location,
                        reminderMin: reminder, labels: labels.reversed(), attendees: attendees.reversed(),
                        priority: priority, indent: indent, raw: raw)
    }

    /// A token's value, with the spaces put back — CALENDAR_PLAN.md §27.
    ///
    /// **A token is one word.** The whole line grammar rests on it: tokens are scanned right to left
    /// and the first word that is not one ends the scan. So a value with a space in it does not
    /// merely look untidy, it *ends the scan early* and swallows every token written before it into
    /// the title — including `^id`.
    static func decodeValue(_ raw: String) -> String {
        raw.contains("%") ? raw.replacingOccurrences(of: "%20", with: " ").replacingOccurrences(of: "%25", with: "%") : raw
    }

    /// The inverse. Percent first, or encoding a space would then be re-encoded.
    static func encodeValue(_ raw: String) -> String {
        guard raw.contains(" ") || raw.contains("%") else { return raw }
        return raw.replacingOccurrences(of: "%", with: "%25").replacingOccurrences(of: " ", with: "%20")
    }

    /// `ext:<uid>` or `ext:<uid>@<occurrence>`.
    ///
    /// The split is on the **last** `@`, because an iCalendar UID very often contains one —
    /// `abc123@google.com` is the ordinary form — and splitting on the first would take the domain
    /// for an occurrence start and lose the identity of every Google event there is.
    static func parseExternal(_ token: String) -> ExternalRef? {
        if token.isEmpty { return nil }
        guard let at = token.lastIndex(of: "@") else { return ExternalRef(decodeValue(token)) }
        let tail = String(token[token.index(after: at)...])
        guard let occurrence = LocalDateTime(tail) else { return ExternalRef(decodeValue(token)) }  // an @ in the uid
        let uid = String(token[..<at])
        return uid.isEmpty ? nil : ExternalRef(decodeValue(uid), occurrence: occurrence)
    }

    /// `s1` or `s1@2026-10-28T09:00`. The bare form means "the occurrence at this line's own start".
    static func parseSeries(_ token: String) -> SeriesRef? {
        if token.isEmpty { return nil }
        guard let at = token.firstIndex(of: "@") else { return SeriesRef(token) }
        let id = String(token[..<at])
        if id.isEmpty { return nil }
        guard let original = LocalDateTime(String(token[token.index(after: at)...])) else { return nil }
        return SeriesRef(id, originalStart: original)
    }

    /// The when-slot: an ISO-8601 instant-or-interval, in local time.
    ///
    /// | Written | Means |
    /// |---|---|
    /// | `2026-09-11` | all day, that day |
    /// | `2026-09-11/2026-09-13` | all day, the 11th to the 13th **inclusive** |
    /// | `2026-09-11T14:00` | a moment |
    /// | `2026-09-11T14:00/PT1H` | an hour from then |
    /// | `2026-09-11T14:00/2026-09-11T15:30` | until then |
    /// | `2026-09-11T14:00[Europe/Dublin]/PT1H` | the same, pinned to a zone |
    ///
    /// All-day spans are inclusive in the text and exclusive in `EventTime`, because the inclusive
    /// reading is what a person writing "the 11th to the 13th" means and the exclusive one is what
    /// arithmetic wants. This function is the seam.
    ///
    /// The split on `/` has to happen *outside* the zone brackets: `Europe/Dublin` contains one.
    static func parseWhen(_ token: String) -> EventTime? {
        guard let (head, zone) = splitZone(token) else { return nil }
        let slash = head.firstIndex(of: "/")
        let startText = slash.map { String(head[..<$0]) } ?? head
        let tailText = slash.map { String(head[head.index(after: $0)...]) }
        let allDay = !startText.contains("T")

        guard let start = LocalDateTime(startText) else { return nil }
        let end: LocalDateTime
        if let tail = tailText {
            if tail.hasPrefix("P") {
                guard let d = ISODuration(tail) else { return nil }
                end = start.adding(seconds: d.seconds)
            } else {
                guard let parsed = LocalDateTime(tail) else { return nil }
                // Inclusive last day in the text, exclusive end in the model.
                end = allDay ? parsed.adding(days: 1) : parsed
            }
        } else {
            end = allDay ? start.adding(days: 1) : start        // a day, or a moment
        }
        if end < start { return nil }
        return EventTime(start: start, end: end, zone: zone, allDay: allDay)
    }

    /// Peels a trailing `[Zone/Id]`, returning the rest and the zone. Nil zone when absent.
    static func splitZone(_ token: String) -> (String, String?)? {
        guard let open = token.firstIndex(of: "[") else { return (token, nil) }
        guard let close = token[open...].firstIndex(of: "]") else { return nil }
        let name = String(token[token.index(after: open)..<close])
        guard TimeZone(identifier: name) != nil else { return nil }
        return (String(token[..<open]) + String(token[token.index(after: close)...]), name)
    }

    // MARK: encode

    public static func encodeBlock(_ block: Block) -> String {
        let s = rawStillDescribes(block, ordinal: 0) ? block.raw! : render(block, ordinal: 0)
        return s.isEmpty ? " " : s
    }

    public static func decodeBlock(_ line: String) -> Block { parseBlock(line) }

    public static func encode(_ page: PageDoc) -> String {
        var s = fence + "\n"
        s += "id: \(page.id)\n"
        s += "type: \(page.type)\n"
        if let p = page.parent { s += "parent: \(p)\n" }
        if let t = page.title { s += "title: \(t)\n" }
        if let k = page.systemKey { s += "system_key: \(k)\n" }
        if let i = page.icon { s += "icon: \(i)\n" }
        if let c = page.color { s += "color: \(c)\n" }
        s += "modified_at: \(InstantText.format(page.modifiedAt))\n"
        if let d = page.device { s += "device: \(d)\n" }
        for (k, v) in page.unknownKeys { s += "\(k): \(v)\n" }
        s += fence + "\n"
        for (i, block) in page.blocks.enumerated() {
            if i > 0, !(page.blocks[i - 1].isListish && block.isListish) { s += "\n" }
            let ordinal = ordinalOf(page.blocks, i)
            let text = rawStillDescribes(block, ordinal: ordinal) ? block.raw! : render(block, ordinal: ordinal)
            s += text.isEmpty ? " " : text
            s += "\n"
        }
        return s
    }

    static let numberedPrefix = try! NSRegularExpression(pattern: #"^(\d+)\."#)

    static func rawStillDescribes(_ block: Block, ordinal: Int) -> Bool {
        guard let raw = block.raw else { return false }
        if parseBlock(raw).strippingRaw != block.strippingRaw { return false }
        guard case .numbered = block else { return true }
        let rest = splitIndent(raw).1
        guard let m = numberedPrefix.firstMatch(in: rest, range: NSRange(location: 0, length: (rest as NSString).length)) else { return false }
        return Int((rest as NSString).substring(with: m.range(at: 1))) == ordinal
    }

    static func ordinalOf(_ blocks: [Block], _ index: Int) -> Int {
        guard case .numbered = blocks[index] else { return 0 }
        let indent = blocks[index].indent
        var n = 1, i = index - 1
        while i >= 0, case .numbered = blocks[i], blocks[i].indent == indent { n += 1; i -= 1 }
        return n
    }

    static func render(_ block: Block, ordinal: Int) -> String {
        let pad = block.indent == 0 ? "" : Array(repeating: indentMarker, count: block.indent).joined(separator: " ") + " "
        switch block {
        case let .heading(t, _, _): return pad + "# " + t
        case let .bullet(t, _, _): return pad + "- " + t
        case let .numbered(t, _, _): return pad + "\(ordinal). " + t
        case let .prose(t, _, _): return pad + t
        case let .ink(id, _, _): return pad + "![[ink:\(id)]]"
        case let .image(u, _, _): return pad + "![[image:\(u)]]"
        case let .task(t): return pad + renderTask(t)
        case let .event(e): return pad + renderEvent(e)
        }
    }

    static func renderTask(_ t: TaskRef) -> String {
        var s: String
        switch t.status { case .open: s = "- [ ]"; case .done: s = "- [x]"; case .inProgress: s = "- [~]" }
        if !t.title.isEmpty { s += " " + t.title }
        if !t.id.isEmpty { s += " ^" + t.id }
        if let d = t.due { s += " due:" + renderDue(d) }
        if let d = t.deadline { s += " deadline:" + d.description }
        if t.status == .done, let d = t.doneAt { s += " done:" + d.description }
        if let x = t.external {
            s += " ext:" + encodeValue(x.uid)
            if let o = x.occurrence { s += "@" + renderLocal(o) }
        }
        if let p = t.priority { s += " !" + p }
        for l in t.labels { s += " #" + l }
        if let a = t.assignee { s += " @" + a }
        return s
    }

    static func renderEvent(_ e: EventRef) -> String {
        var s = String(eventMarker.dropLast())
        s += " " + renderWhen(e.time)
        if !e.title.isEmpty { s += " " + e.title }
        // Fixed order, for the reason renderTask gives: two devices holding the same event must
        // produce the same bytes, or every sync looks like a change.
        if !e.id.isEmpty { s += " ^" + e.id }
        if let r = e.rrule { s += " rrule:" + r }
        if let f = e.forTaskId { s += " for:" + f }
        if let x = e.series {
            s += " series:" + x.id
            // The bare form means "the occurrence at this line's own start", so an override that
            // moved somewhere else has to say which occurrence it replaces, and a cancellation
            // sitting on its original start does not.
            if let o = x.originalStart, o != e.time.start { s += "@" + renderLocal(o) }
        }
        if e.cancelled { s += " cancelled" }
        if let l = e.location { s += " loc:" + encodeValue(l) }
        if let c = e.color { s += " col:" + c }
        if let x = e.external {
            s += " ext:" + encodeValue(x.uid)
            // The occurrence only when there is one to name. A one-off meeting has a single
            // instance, and writing its start twice would be two places to disagree.
            if let o = x.occurrence { s += "@" + renderLocal(o) }
        }
        if let r = e.reminderMin { s += " remind:\(r)" }
        if let p = e.priority { s += " !" + p }
        for l in e.labels { s += " #" + l }
        for a in e.attendees { s += " @" + a }
        return s
    }

    /// The inverse of `parseWhen`, preferring a duration over an explicit end.
    ///
    /// A meeting that moves keeps its length, and a diff shows one changed field instead of two.
    /// Somebody who wrote an explicit end by hand keeps it regardless: `rawStillDescribes` re-parses
    /// their line, gets the same event back, and writes their bytes rather than these.
    static func renderWhen(_ t: EventTime) -> String {
        let zoneSuffix = t.zone.map { "[\($0)]" } ?? ""
        if t.allDay {
            let lastDay = t.end.date.adding(days: -1)   // exclusive in the model, inclusive in the text
            let head = t.start.date.description
            return lastDay <= t.start.date ? head + zoneSuffix : "\(head)\(zoneSuffix)/\(lastDay)"
        }
        let head = renderLocal(t.start) + zoneSuffix
        if t.isInstantaneous { return head }
        return head + "/" + t.duration.description
    }

    /// `2026-09-11T14:00`, dropping seconds when they are zero — the common case and less to read.
    static func renderLocal(_ d: LocalDateTime) -> String { d.description }

    static func renderDue(_ d: DueSpec) -> String {
        let body: String
        switch d.value { case let .allDay(day): body = day.description; case let .at(date): body = InstantText.format(date) }
        // Length before reminder, always: the reminder's `+r` is a suffix on the whole thing, and
        // two devices holding the same task must produce the same bytes or every sync is a diff.
        let withLength = d.duration.map { "\(body)/\($0)" } ?? body
        // One reminder writes exactly what it always wrote, so adding the feature did not rewrite
        // every task file on the first sync after updating.
        if d.reminders.isEmpty { return withLength }
        return withLength + "+r" + d.reminders.map(String.init).joined(separator: ",")
    }
}

// MARK: - links and inline markdown

/// `[[label|^id]]` — the one construct a task title may carry that needs a plain-text reading.
public enum Links {
    public static let open = "[[", close = "]]"
    static let link = try! NSRegularExpression(pattern: #"\[\[([^\[\]|\n]*)\|\^([^\[\]|\s]+)]]"#)

    public struct Link { public let label: String, targetId: String, range: NSRange }

    public static func links(_ text: String) -> [Link] {
        let ns = text as NSString
        return link.matches(in: text, range: NSRange(location: 0, length: ns.length)).map {
            Link(label: ns.substring(with: $0.range(at: 1)), targetId: ns.substring(with: $0.range(at: 2)), range: $0.range)
        }
    }

    public static func hasLink(_ text: String) -> Bool { !links(text).isEmpty }

    /// Every link reduced to what it says; `title` answers "what is this id called now".
    public static func plain(_ text: String, title: (String) -> String? = { _ in nil }) -> String {
        let found = links(text)
        if found.isEmpty { return text }
        let ns = NSMutableString(string: text)
        for l in found.reversed() { ns.replaceCharacters(in: l.range, with: title(l.targetId) ?? l.label) }
        return ns as String
    }

    public static func encode(label: String, targetId: String) -> String {
        var safe = String(label.map { "[]|\n".contains($0) ? " " : $0 })
        safe = safe.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        return open + (safe.isEmpty ? "Untitled" : safe) + "|^" + targetId + close
    }
}

/// Emphasis runs in prose (`***`, `**`, `*`, backticks) and the plain reading without markers.
public enum Markdown {
    public enum Kind { case code, bold, italic, boldItalic }
    public struct Run: Equatable { public let kind: Kind; public let outer: NSRange; public let inner: NSRange }

    static let boldItalic = try! NSRegularExpression(pattern: #"\*\*\*(?=\S)(.+?)(?<=\S)\*\*\*"#)
    static let bold = try! NSRegularExpression(pattern: #"\*\*(?=\S)(.+?)(?<=\S)\*\*"#)
    static let italic = try! NSRegularExpression(pattern: #"(?<!\*)\*(?=\S)([^*]+?)(?<=\S)\*(?!\*)"#)
    static let code = try! NSRegularExpression(pattern: #"`(?=\S)([^`]+?)(?<=\S)`"#)

    public static func runs(_ text: String) -> [Run] {
        runsIn(text as NSString, from: 0, to: (text as NSString).length).sorted { $0.outer.location < $1.outer.location }
    }

    public static func plain(_ text: String) -> String {
        let rs = runs(text)
        if rs.isEmpty { return text }
        let ns = text as NSString
        var out = "", at = 0, lastEnd = -1
        for r in rs {
            if r.outer.location < lastEnd { continue }
            out += ns.substring(with: NSRange(location: at, length: r.outer.location - at))
            out += ns.substring(with: r.inner)
            at = r.outer.location + r.outer.length
            lastEnd = at
        }
        out += ns.substring(from: at)
        return out
    }

    static func runsIn(_ text: NSString, from: Int, to: Int) -> [Run] {
        if to - from < 3 { return [] }
        let slice = text.substring(with: NSRange(location: from, length: to - from))
        let sliceNS = slice as NSString
        var found: [Run] = []
        var claimed = [Bool](repeating: false, count: sliceNS.length)
        func scan(_ re: NSRegularExpression, _ marker: Int, _ kind: Kind) {
            for m in re.matches(in: slice, range: NSRange(location: 0, length: sliceNS.length)) {
                let outer = m.range
                if (outer.location..<(outer.location + outer.length)).contains(where: { claimed[$0] }) { continue }
                let innerStart = outer.location + marker, innerEnd = outer.location + outer.length - marker
                if innerEnd <= innerStart { continue }
                for k in outer.location..<(outer.location + outer.length) { claimed[k] = true }
                found.append(Run(kind: kind, outer: NSRange(location: from + outer.location, length: outer.length),
                                 inner: NSRange(location: from + innerStart, length: innerEnd - innerStart)))
                if kind != .code { found += runsIn(text, from: from + innerStart, to: from + innerEnd) }
            }
        }
        scan(code, 1, .code); scan(boldItalic, 3, .boldItalic); scan(bold, 2, .bold); scan(italic, 1, .italic)
        return found
    }
}

/// The plain reading every surface that cannot style uses: links collapsed, emphasis stripped.
public func inlinePlain(_ text: String, title: (String) -> String? = { _ in nil }) -> String {
    Markdown.plain(Links.plain(text, title: title))
}
