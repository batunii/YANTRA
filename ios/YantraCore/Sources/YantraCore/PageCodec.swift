import Foundation

// MARK: - dates

/// A calendar day, `yyyy-MM-dd`, the way `java.time.LocalDate` prints. Files carry these for
/// all-day due dates, deadlines and completion days.
public struct LocalDate: Hashable, Comparable, Sendable, CustomStringConvertible {
    public var year: Int, month: Int, day: Int
    public init(year: Int, month: Int, day: Int) { self.year = year; self.month = month; self.day = day }

    /// `LocalDate.parse` — ISO_LOCAL_DATE, STRICT, exactly as `java.time` reads it: a year of
    /// exactly four ASCII digits, or a signed one (`+` only when it needs more than four, `-` from
    /// four up), then `-MM-dd`, and a day that exists. `2026-02-29` is not a date; neither is
    /// `+2026-01-01`, `2026-1-01` or a year written in full-width digits.
    public init?(_ s: String) {
        let v = Array(s.unicodeScalars)
        var i = 0, sign = 1, signed = false
        if i < v.count, v[i] == "+" || v[i] == "-" { signed = true; sign = v[i] == "-" ? -1 : 1; i += 1 }
        let ys = i
        while i < v.count, KText.isAsciiDigit(v[i]) { i += 1 }
        let yearDigits = i - ys
        if signed {
            if sign > 0 { guard yearDigits > 4, yearDigits <= 10 else { return nil } }
            else { guard yearDigits >= 4, yearDigits <= 10 else { return nil } }
        } else { guard yearDigits == 4 else { return nil } }
        guard let yAbs = Int(String(String.UnicodeScalarView(v[ys..<i]))) else { return nil }
        let y = sign * yAbs
        guard abs(y) <= 999_999_999 else { return nil }
        func two() -> Int? {
            guard i + 2 <= v.count, KText.isAsciiDigit(v[i]), KText.isAsciiDigit(v[i + 1]) else { return nil }
            defer { i += 2 }
            return Int(v[i].value - 48) * 10 + Int(v[i + 1].value - 48)
        }
        guard i < v.count, v[i] == "-" else { return nil }
        i += 1
        guard let m = two(), i < v.count, v[i] == "-" else { return nil }
        i += 1
        guard let d = two(), i == v.count, (1...12).contains(m), d >= 1, LocalDate.daysIn(month: m, year: y) >= d else { return nil }
        self.init(year: y, month: m, day: d)
    }

    /// Days since 1970-01-01, proleptic Gregorian — `toEpochDay()`.
    public var epochDay: Int {
        // Howard Hinnant's days_from_civil.
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = (month + 9) % 12
        let doy = (153 * mp + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /// `LocalDate.ofEpochDay`.
    public init(epochDay: Int) {
        let z = epochDay + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        self.init(year: yoe + era * 400 + (m <= 2 ? 1 : 0), month: m, day: d)
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

    /// `LocalDate.toString()`: four digits, a sign below year 0 and above 9999.
    public var description: String {
        let y: String
        if abs(year) < 1000 {
            y = (year < 0 ? "-" : "") + String(format: "%04d", abs(year))
        } else {
            y = (year > 9999 ? "+" : "") + String(year)
        }
        return y + String(format: "-%02d-%02d", month, day)
    }

    public static func < (l: LocalDate, r: LocalDate) -> Bool {
        (l.year, l.month, l.day) < (r.year, r.month, r.day)
    }

    public static func daysIn(month: Int, year: Int) -> Int {
        switch month {
        case 2: return (year & 3 == 0 && year % 100 != 0) || year % 400 == 0 ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }
}

/// `java.time.Instant` text for a `Date`: `2026-08-25T14:22:31.402Z`, seconds always, the
/// fraction only when it is not zero. A `Date` is taken to the millisecond, which is the precision
/// every stamp either app makes; text that carries more is kept exactly by `JavaInstant`.
public enum InstantText {
    public static func format(_ date: Date) -> String { JavaInstant(date).description }

    /// `Instant.parse`, Java's grammar — seconds required, `Z` or an offset. See `JavaInstant.parse`.
    public static func parse(_ s: String) -> Date? { JavaInstant.parse(s)?.date }
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
    public var duration: ISODuration { start.duration(until: end) }
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
    /// Whose calendar this meeting came off — `by:<account>`, beside `ext:`.
    ///
    /// Android stamps an event made by tapping somebody's meeting with the calendar account it came
    /// from, and indexes only the ones a workspace can claim. iOS carries it and gives it no meaning
    /// yet; what matters here is that it is a token, so the scan does not stop at it and swallow the
    /// `^id` and `ext:` written before it into the title.
    ///
    /// **Encoded on write and not decoded on read**, because that is what `PageCodec.kt` does: the
    /// value is `encodeValue`d when rendered and taken verbatim when parsed. An author with a space
    /// or a `%` in it therefore grows a layer of `%25` every time the line is rebuilt — an Android
    /// bug, reproduced rather than fixed so the two apps write the same bytes (appendix 01 §4.2).
    /// The accounts seen in practice are logins and email addresses, where it never triggers.
    public var author: String?
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
                external: ExternalRef? = nil, author: String? = nil, color: String? = nil, series: SeriesRef? = nil, cancelled: Bool = false,
                location: String? = nil, reminderMin: Int? = nil, labels: [String] = [], attendees: [String] = [],
                priority: String? = nil, indent: Int = 0, raw: String? = nil) {
        self.id = id; self.title = title; self.time = time; self.rrule = rrule; self.forTaskId = forTaskId
        self.external = external; self.author = author; self.color = color; self.series = series; self.cancelled = cancelled
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
    /// Tokens a newer Android build writes that this one gives no meaning — `by:<login>` (who added
    /// the task), `on:<login>` (who is on it), and any other trailing `key:value` word — verbatim,
    /// in the order they were written. PROMPT §1.2.
    ///
    /// Held as tokens rather than left in the title, because a reader that stops at the first word
    /// it does not know reads everything to the left of it as title: an older Android build met
    /// `on:batunii` at the end of a line, stopped, and took the task's `^id`, due date and assignee
    /// for words. Saved, that task would have lost its id for everybody. iOS must not be that reader.
    ///
    /// Nothing is built on them: no ownership, no "added by". An unedited line is written back
    /// byte-for-byte by raw-line preservation, so they stay exactly where they were; a rebuilt line
    /// puts them straight after the title, which is where the current Android build writes them.
    public var extras: [String] = []
    public var raw: String? = nil
    public init(id: String, title: String, status: TaskStatus = .open, indent: Int = 0, due: DueSpec? = nil, deadline: LocalDate? = nil,
                priority: String? = nil, labels: [String] = [], assignee: String? = nil, doneAt: LocalDate? = nil,
                external: ExternalRef? = nil, extras: [String] = [], raw: String? = nil) {
        self.id = id; self.title = title; self.status = status; self.indent = indent; self.due = due; self.deadline = deadline
        self.priority = priority; self.labels = labels; self.assignee = assignee; self.doneAt = doneAt
        self.external = external; self.extras = extras; self.raw = raw
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
    /// The same block at a different visual depth. `raw` goes with it: the line it was parsed from
    /// no longer describes it, and keeping it would write the old indent straight back.
    public func withIndent(_ indent: Int) -> Block {
        switch self {
        case let .prose(t, _, _): return .prose(t, indent: indent)
        case let .heading(t, _, _): return .heading(t, indent: indent)
        case let .bullet(t, _, _): return .bullet(t, indent: indent)
        case let .numbered(t, _, _): return .numbered(t, indent: indent)
        case let .ink(id, _, _): return .ink(id: id, indent: indent)
        case let .image(u, _, _): return .image(uri: u, indent: indent)
        case var .task(t): t.indent = indent; t.raw = nil; return .task(t)
        case var .event(e): e.indent = indent; e.raw = nil; return .event(e)
        }
    }

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
    /// `modified_at` exactly as the file said it, to the nanosecond — see `JavaInstant`. An unedited
    /// page is written back with these digits, not with a `Date`'s rounding of them.
    public var modifiedInstant: JavaInstant
    /// The same clock as a `Date`, for arithmetic. Setting it stamps to the millisecond, which is
    /// what Android's `Instant.ofEpochMilli(System.currentTimeMillis())` stamps.
    public var modifiedAt: Date {
        get { modifiedInstant.date }
        set { modifiedInstant = JavaInstant(newValue) }
    }
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
    /// Whether this list sits at the top of Home, or nil when the file has never said.
    ///
    /// Nullable rather than defaulting to false, because "never said" and "said no" have to be told
    /// apart: every list written before pinning existed says nothing, and the section used to be
    /// "every ungrouped smart list". Reading absence as *false* would empty Home's pinned section
    /// for everybody. Absence means the old rule (`effectivePinned`); a value means somebody chose.
    public var pinned: Bool?

    public init(id: String, type: String, parent: String?, title: String?, systemKey: String? = nil, modifiedAt: Date,
                device: String?, blocks: [Block], unknownKeys: [(String, String)] = [],
                icon: String? = nil, color: String? = nil, pinned: Bool? = nil) {
        self.init(id: id, type: type, parent: parent, title: title, systemKey: systemKey, modifiedInstant: JavaInstant(modifiedAt),
                  device: device, blocks: blocks, unknownKeys: unknownKeys, icon: icon, color: color, pinned: pinned)
    }

    public init(id: String, type: String, parent: String?, title: String?, systemKey: String? = nil, modifiedInstant: JavaInstant,
                device: String?, blocks: [Block], unknownKeys: [(String, String)] = [],
                icon: String? = nil, color: String? = nil, pinned: Bool? = nil) {
        self.id = id; self.type = type; self.parent = parent; self.title = title; self.systemKey = systemKey
        self.modifiedInstant = modifiedInstant; self.device = device; self.blocks = blocks; self.unknownKeys = unknownKeys
        self.icon = icon; self.color = color; self.pinned = pinned
    }

    /// `pinned`, or the rule the section was before anybody could choose: an ungrouped smart list.
    public var effectivePinned: Bool { pinned ?? (type == NodeType.smartList && parent == nil) }

    public static func == (l: PageDoc, r: PageDoc) -> Bool {
        l.id == r.id && l.type == r.type && l.parent == r.parent && l.title == r.title && l.systemKey == r.systemKey
            && l.modifiedInstant == r.modifiedInstant && l.device == r.device && l.blocks == r.blocks && l.icon == r.icon
            && l.color == r.color && l.pinned == r.pinned
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
    static let known: Set<String> = ["id", "type", "parent", "title", "system_key", "icon", "color", "pinned", "modified_at", "device"]

    public static func decode(_ text: String) -> PageDoc {
        let lines = KText.split(text.replacingOccurrences(of: "\r\n", with: "\n", options: .literal), "\n")
        var i = 0
        var front: [(String, String)] = []
        if let first = lines.first, KText.same(KText.trim(first), fence) {
            i = 1
            while i < lines.count, !KText.same(KText.trim(lines[i]), fence) {
                let line = lines[i]
                // Split at the first colon; a colon at the very start, or none, drops the line.
                let scalars = Array(line.unicodeScalars)
                if let colon = scalars.firstIndex(of: ":"), colon > 0 {
                    let k = KText.trim(String(String.UnicodeScalarView(scalars[..<colon])))
                    let v = KText.trim(String(String.UnicodeScalarView(scalars[(colon + 1)...])))
                    // A LinkedHashMap put: the last value wins and the first position stays.
                    if let at = front.firstIndex(where: { KText.same($0.0, k) }) { front[at].1 = v } else { front.append((k, v)) }
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
        func f(_ k: String) -> String? { front.first { KText.same($0.0, k) }?.1 }
        func nonBlank(_ s: String?) -> String? { s.flatMap { KText.isBlank($0) ? nil : $0 } }
        // Only "true"/"false" count. Anything else is a file somebody edited by hand into a state
        // this build does not understand, and the old rule is a better answer than a guess.
        let pinned: Bool? = f("pinned").flatMap { v in
            switch v.lowercased() { case "true": return true; case "false": return false; default: return nil }
        }
        return PageDoc(
            id: f("id") ?? "", type: f("type") ?? "", parent: nonBlank(f("parent")), title: nonBlank(f("title")),
            systemKey: nonBlank(f("system_key")),
            modifiedInstant: f("modified_at").flatMap(JavaInstant.parse) ?? .epoch,
            device: nonBlank(f("device")), blocks: blocks,
            unknownKeys: front.filter { k, _ in !known.contains(where: { KText.same($0, k) }) },
            icon: nonBlank(f("icon")), color: nonBlank(f("color")), pinned: pinned)
    }

    /// Peels indent markers off the front, returning the depth and what is left.
    static func splitIndent(_ line: String) -> (Int, String) {
        var rest = line, depth = 0
        while KText.starts(rest, indentMarker) {
            depth += 1
            rest = KText.removePrefix(KText.drop(rest, 1), " ")
        }
        return (depth, rest)
    }

    /// An event line. See `EventRef` for why it is not `* `.
    public static let eventMarker = "@ "

    /// `^!\[\[<kind>:([^\]]+)]]$`, as `Regex.find` runs it: the id is everything up to the closing
    /// brackets, has no `]` in it and is not empty, and `$` may sit before one final line terminator.
    static func sidecarRef(_ rest: String, _ kind: String) -> String? {
        let open = "![[" + kind + ":"
        guard KText.starts(rest, open) else { return nil }
        var body = Array(KText.drop(rest, open.unicodeScalars.count).unicodeScalars)
        // `$` matches before a final line terminator; `\r\n` counts as one.
        if body.count >= 2, body[body.count - 2] == "\r", body[body.count - 1] == "\n" { body.removeLast(2) }
        else if let last = body.last, KText.isLineTerminator(last) { body.removeLast() }
        guard body.count >= 3, body[body.count - 1] == "]", body[body.count - 2] == "]" else { return nil }
        let id = body[..<(body.count - 2)]
        guard !id.isEmpty, !id.contains("]") else { return nil }
        return String(String.UnicodeScalarView(id))
    }

    /// `^\d+\.\s+(.*)$` under `matches`, with Java's ASCII `\d`/`\s` and a `.` that stops at line
    /// terminators. Returns the text after the whitespace run.
    static func numberedText(_ rest: String) -> String? {
        let v = Array(rest.unicodeScalars)
        var i = 0
        while i < v.count, KText.isAsciiDigit(v[i]) { i += 1 }
        guard i > 0, i < v.count, v[i] == "." else { return nil }
        i += 1
        let ws = i
        while i < v.count, KText.isRegexSpace(v[i]) { i += 1 }
        guard i > ws else { return nil }
        let text = v[i...]
        // `.*` cannot cross a line terminator, so a whole-string match needs none after the run.
        // (The run itself may have swallowed `\n` and `\r`, which `\s` includes.)
        guard !text.contains(where: KText.isLineTerminator) else { return nil }
        return String(String.UnicodeScalarView(text))
    }

    public static func parseBlock(_ raw: String) -> Block {
        let (indent, rest) = splitIndent(raw)
        // Deliberately not trimmed: an empty heading is written "# " and an empty bullet "- ", and
        // the marker's trailing space is part of the marker.
        if let id = sidecarRef(rest, "ink") { return .ink(id: id, indent: indent, raw: raw) }
        if let uri = sidecarRef(rest, "image") { return .image(uri: uri, indent: indent, raw: raw) }
        // `@ ` cannot collide with the checkbox or with a bullet, so the order here is only for
        // reading. A `@ ` line whose time makes no sense is not an event and must not be silently
        // dropped: it falls through to prose holding exactly what was written.
        if KText.starts(rest, eventMarker), let e = parseEvent(KText.drop(rest, 2), indent: indent, raw: raw) {
            return .event(e)
        }
        // `^- \[([ xX~])] ?` — the box, with or without anything after it.
        let v = Array(rest.unicodeScalars)
        if v.count >= 5, v[0] == "-", v[1] == " ", v[2] == "[", v[4] == "]", " xX~".unicodeScalars.contains(v[3]) {
            let status: TaskStatus
            switch v[3] { case "x", "X": status = .done; case "~": status = .inProgress; default: status = .open }
            let markerLength = v.count > 5 && v[5] == " " ? 6 : 5
            return .task(parseTask(KText.drop(rest, markerLength), status: status, indent: indent, raw: raw))
        }
        if KText.starts(rest, "# ") { return .heading(KText.drop(rest, 2), indent: indent, raw: raw) }
        if KText.starts(rest, "- ") { return .bullet(KText.drop(rest, 2), indent: indent, raw: raw) }
        if let t = numberedText(rest) { return .numbered(t, indent: indent, raw: raw) }
        if KText.isBlank(rest) { return .prose("", indent: indent, raw: raw) }
        return .prose(rest, indent: indent, raw: raw)
    }

    /// The trailing tokens a newer Android build writes and this one keeps without meaning —
    /// `by:<login>` and `on:<login>`. See `TaskRef.extras`.
    static let ownershipKeys = ["by:", "on:"]

    /// A word shaped like a token nobody here knows: `key:value`, the key lowercase ASCII letters,
    /// digits, `_` or `-` starting with a letter, the value non-empty and not a path (so `https://…`
    /// is never one). The keys this build does know are not unknown, and a known key with a value
    /// that will not parse stops the scan exactly as it does on Android — `due:not-a-date` is title.
    static func isUnknownToken(_ w: String) -> Bool {
        let v = Array(w.unicodeScalars)
        guard let colon = v.firstIndex(of: ":"), colon > 0, colon + 1 < v.count, v[colon + 1] != "/" else { return false }
        guard v[0].value >= 0x61 && v[0].value <= 0x7A else { return false }
        for u in v[..<colon] {
            let ok = (u.value >= 0x61 && u.value <= 0x7A) || KText.isAsciiDigit(u) || u == "_" || u == "-"
            if !ok { return false }
        }
        let key = String(String.UnicodeScalarView(v[...colon]))
        return !["due:", "deadline:", "done:", "ext:"].contains(key)
    }

    /// Splits a task line into title and trailing tokens, scanning **right to left** and stopping
    /// at the first word that is not a token.
    ///
    /// That direction is the whole trick. "Buy milk #groceries" tags the task; "Buy #2 pencils" does
    /// not, because the scan hits `pencils` and stops, leaving `#2` where it belongs — in the title.
    ///
    /// Two additions over `PageCodec.kt` at 17d0beb, both about text a newer Android build writes
    /// (PROMPT §1.2), and both inert on every line that does not carry such text:
    ///
    /// - `by:` and `on:` are tokens wherever they sit, kept verbatim in `extras`.
    /// - Any other unknown `key:value` word is held back while the scan goes on, and becomes a token
    ///   only if a known token turns up to its left — that is, only when it was standing between the
    ///   title and tokens the scan would otherwise have lost. `Fix login re:bug` keeps its title
    ///   whole, exactly as 17d0beb reads it; `X ^t1 foo:bar` keeps its id.
    static func parseTask(_ body: String, status: TaskStatus, indent: Int, raw: String) -> TaskRef {
        var words = KText.split(KText.trim(body), " ")
        var id = "", due: DueSpec? = nil, deadline: LocalDate? = nil, doneAt: LocalDate? = nil
        var priority: String? = nil, assignee: String? = nil, labels: [String] = [], external: ExternalRef? = nil
        var extras: [String] = [], pending: [String] = [], author = false
        while let w = words.last {
            var consumed = false, known = true
            if KText.contains(w, Links.close) { consumed = false }
            else if KText.starts(w, "^"), id.isEmpty { id = KText.drop(w, 1); consumed = true }
            else if KText.starts(w, "due:") { if let d = parseDue(KText.drop(w, 4)) { due = d; consumed = true } }
            else if KText.starts(w, "deadline:") { if let d = LocalDate(KText.drop(w, 9)) { deadline = d; consumed = true } }
            else if KText.starts(w, "done:") { if let d = LocalDate(KText.drop(w, 5)) { doneAt = d; consumed = true } }
            else if KText.starts(w, "ext:") { if let x = parseExternal(KText.drop(w, 4)) { external = x; consumed = true } }
            else if KText.starts(w, "!"), KText.length(w) > 1 { priority = KText.drop(w, 1); consumed = true }
            else if KText.starts(w, "@"), KText.length(w) > 1 { assignee = KText.drop(w, 1); consumed = true }
            else if KText.starts(w, "#"), KText.length(w) > 1 { labels.append(KText.drop(w, 1)); consumed = true }
            // Newer Android: `by:` once (the leftmost is not a second author), `on:` any number.
            else if KText.starts(w, "by:") { if KText.length(w) > 3, !author { author = true; extras.append(w); consumed = true } }
            else if KText.starts(w, "on:"), KText.length(w) > 3 { extras.append(w); consumed = true }
            else if isUnknownToken(w) { pending.append(w); known = false; consumed = true }
            if !consumed { break }
            if known { extras += pending; pending.removeAll() }
            words.removeLast()
        }
        // Held-back words that never found a known token to their left were title all along.
        words += pending.reversed()
        return TaskRef(id: id, title: words.joined(separator: " "), status: status, indent: indent, due: due, deadline: deadline,
                       priority: priority, labels: labels.reversed(), assignee: assignee, doneAt: doneAt,
                       external: external, extras: extras.reversed(), raw: raw)
    }

    /// Where a task line's title ends and its trailing tokens begin — `PageCodec.tokenStart`.
    ///
    /// Answered by running `parseTask` and measuring the title it kept, rather than by a second
    /// scanner that agrees with it today. Returns the body's length (in UTF-16 units, the way a text
    /// field counts) when nothing trails, so the caller's range is simply empty.
    public static func tokenStart(_ body: String) -> Int {
        let lead = KText.length(body) - KText.length(KText.trimStart(body))
        let title = parseTask(body, status: .open, indent: 0, raw: body).title
        let u = Array(body.utf16)
        var at = min(lead + KText.length(title), u.count)
        while at < u.count, u[at] == 0x20 { at += 1 }
        return at
    }

    /// `2026-08-26`, `2026-08-26T09:00:00Z`, either optionally carrying a length and a reminder:
    /// `due:2026-08-26T09:00:00Z/PT1H+r15`.
    ///
    /// The `/PT1H` tail is the same ISO interval the event when-slot uses, deliberately — a task
    /// blocked out from nine to ten and a meeting from nine to ten are the same shape on a timeline.
    /// A length on an all-day task is refused rather than kept: "all of Tuesday, for one hour" does
    /// not mean anything, and storing it would leave the timeline to decide what it meant.
    static func parseDue(_ token: String) -> DueSpec? {
        let v = Array(token.unicodeScalars)
        var head = token, reminders: [Int] = []
        if let at = (0..<max(0, v.count - 1)).first(where: { v[$0] == "+" && v[$0 + 1] == "r" }) {
            head = String(String.UnicodeScalarView(v[..<at]))
            // `+r30` and `+r30,15` are the same syntax with one and two reminders in it. The older
            // spelling is the new one with a single element, so every file written before this
            // parses unchanged and nothing needs converting.
            //
            // All or nothing: a tail that is partly unreadable — `+r30,x` — is refused rather than
            // quietly kept as `+r30`, because a task that silently loses one of its two reminders
            // is the failure this whole feature exists to avoid.
            let parts = KText.split(String(String.UnicodeScalarView(v[(at + 2)...])), ",").map { KText.toInt(KText.trim($0)) }
            if parts.isEmpty || parts.contains(where: { $0 == nil }) { return nil }
            reminders = DueSpec.reminders(parts.compactMap { $0 })
        }
        var body = head, duration: ISODuration? = nil
        let hv = Array(head.unicodeScalars)
        if let slash = hv.firstIndex(of: "/") {
            body = String(String.UnicodeScalarView(hv[..<slash]))
            guard let d = ISODuration(String(String.UnicodeScalarView(hv[(slash + 1)...]))) else { return nil }
            duration = d
        }
        if body.unicodeScalars.contains("T") {
            guard let d = JavaInstant.parse(body) else { return nil }
            return DueSpec(.at(d.date), reminders: reminders, duration: duration)
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
        let trimmed = KText.trim(body)
        if trimmed.isEmpty { return nil }
        let tv = Array(trimmed.unicodeScalars)
        let whenEnd = tv.firstIndex(of: " ") ?? tv.count
        let whenWord = String(String.UnicodeScalarView(tv[..<whenEnd]))
        guard let time = parseWhen(whenWord) else { return nil }

        // Unlike a task, empty words are dropped: event titles normalise runs of spaces.
        var words = KText.split(KText.trim(String(String.UnicodeScalarView(tv[whenEnd...]))), " ").filter { !$0.isEmpty }
        var id = "", rrule: String? = nil, forTask: String? = nil, series: SeriesRef? = nil
        var cancelled = false, location: String? = nil, color: String? = nil, author: String? = nil
        var external: ExternalRef? = nil, reminder: Int? = nil, priority: String? = nil
        var labels: [String] = [], attendees: [String] = []

        func value(_ w: String, _ n: Int) -> String? { let v = KText.drop(w, n); return v.isEmpty ? nil : v }
        while let w = words.last {
            var consumed = false
            if KText.contains(w, Links.close) { consumed = false }
            else if KText.starts(w, "^"), id.isEmpty { id = KText.drop(w, 1); consumed = true }
            else if KText.starts(w, "rrule:") { rrule = value(w, 6); consumed = rrule != nil }
            else if KText.starts(w, "for:") { forTask = value(w, 4); consumed = forTask != nil }
            else if KText.starts(w, "series:") { if let x = parseSeries(KText.drop(w, 7)) { series = x; consumed = true } }
            else if KText.same(w, "cancelled") { cancelled = true; consumed = true }
            else if KText.starts(w, "loc:") { location = value(w, 4).map(decodeValue); consumed = location != nil }
            else if KText.starts(w, "col:") { color = value(w, 4); consumed = color != nil }
            else if KText.starts(w, "ext:") { if let x = parseExternal(KText.drop(w, 4)) { external = x; consumed = true } }
            // Not decoded — see `EventRef.author`.
            else if KText.starts(w, "by:") { author = value(w, 3); consumed = author != nil }
            else if KText.starts(w, "remind:") { reminder = KText.toInt(KText.drop(w, 7)); consumed = reminder != nil }
            else if KText.starts(w, "!"), KText.length(w) > 1 { priority = KText.drop(w, 1); consumed = true }
            else if KText.starts(w, "@"), KText.length(w) > 1 { attendees.append(KText.drop(w, 1)); consumed = true }
            else if KText.starts(w, "#"), KText.length(w) > 1 { labels.append(KText.drop(w, 1)); consumed = true }
            if !consumed { break }
            words.removeLast()
        }

        return EventRef(id: id, title: words.joined(separator: " "), time: time, rrule: rrule, forTaskId: forTask,
                        external: external, author: author, color: color, series: series, cancelled: cancelled, location: location,
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
        guard raw.unicodeScalars.contains("%") else { return raw }
        return raw.replacingOccurrences(of: "%20", with: " ", options: .literal).replacingOccurrences(of: "%25", with: "%", options: .literal)
    }

    /// The inverse. Percent first, or encoding a space would then be re-encoded.
    static func encodeValue(_ raw: String) -> String {
        guard raw.unicodeScalars.contains(" ") || raw.unicodeScalars.contains("%") else { return raw }
        return raw.replacingOccurrences(of: "%", with: "%25", options: .literal).replacingOccurrences(of: " ", with: "%20", options: .literal)
    }

    /// `ext:<uid>` or `ext:<uid>@<occurrence>`.
    ///
    /// The split is on the **last** `@`, because an iCalendar UID very often contains one —
    /// `abc123@google.com` is the ordinary form — and splitting on the first would take the domain
    /// for an occurrence start and lose the identity of every Google event there is.
    static func parseExternal(_ token: String) -> ExternalRef? {
        if token.isEmpty { return nil }
        let v = Array(token.unicodeScalars)
        guard let at = v.lastIndex(of: "@") else { return ExternalRef(decodeValue(token)) }
        let tail = String(String.UnicodeScalarView(v[(at + 1)...]))
        guard let occurrence = LocalDateTime(tail) else { return ExternalRef(decodeValue(token)) }  // an @ in the uid
        let uid = String(String.UnicodeScalarView(v[..<at]))
        return uid.isEmpty ? nil : ExternalRef(decodeValue(uid), occurrence: occurrence)
    }

    /// `s1` or `s1@2026-10-28T09:00`. The bare form means "the occurrence at this line's own start".
    static func parseSeries(_ token: String) -> SeriesRef? {
        if token.isEmpty { return nil }
        let v = Array(token.unicodeScalars)
        guard let at = v.firstIndex(of: "@") else { return SeriesRef(token) }
        let id = String(String.UnicodeScalarView(v[..<at]))
        if id.isEmpty { return nil }
        guard let original = LocalDateTime(String(String.UnicodeScalarView(v[(at + 1)...]))) else { return nil }
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
        let hv = Array(head.unicodeScalars)
        let slash = hv.firstIndex(of: "/")
        let startText = slash.map { String(String.UnicodeScalarView(hv[..<$0])) } ?? head
        let tailText = slash.map { String(String.UnicodeScalarView(hv[($0 + 1)...])) }
        let allDay = !startText.unicodeScalars.contains("T")

        guard let start = LocalDateTime(startText) else { return nil }
        let end: LocalDateTime
        if let tail = tailText {
            if KText.starts(tail, "P") {
                guard let d = ISODuration(tail) else { return nil }
                end = start.adding(d)
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
        let v = Array(token.unicodeScalars)
        guard let open = v.firstIndex(of: "[") else { return (token, nil) }
        guard let close = v[open...].firstIndex(of: "]") else { return nil }
        // `ZoneId.of`, and the id it would print — which is what a rebuilt line writes.
        guard let zone = JavaZone.normalize(String(String.UnicodeScalarView(v[(open + 1)..<close]))) else { return nil }
        return (String(String.UnicodeScalarView(v[..<open])) + String(String.UnicodeScalarView(v[(close + 1)...])), zone)
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
        if let p = page.pinned { s += "pinned: \(p)\n" }
        s += "modified_at: \(page.modifiedInstant)\n"
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

    /// Whether a block's original line still says exactly what the block now says.
    ///
    /// Re-parsing the source and comparing is what makes preservation safe. Having callers clear
    /// `raw` whenever they change something works right up until somebody forgets, and then the
    /// stale line is written back and the edit vanishes with no error anywhere.
    ///
    /// Numbered items need the extra check: their ordinal is positional and therefore not part of
    /// the model, so a moved item compares equal to its own stale text. The leading number is read
    /// as `^(\d+)\.` and `toIntOrNull` read it — ASCII digits, and a number too big for an `Int`
    /// never matches.
    static func rawStillDescribes(_ block: Block, ordinal: Int) -> Bool {
        guard let raw = block.raw else { return false }
        if parseBlock(raw).strippingRaw != block.strippingRaw { return false }
        guard case .numbered = block else { return true }
        let rest = Array(splitIndent(raw).1.unicodeScalars)
        var i = 0
        while i < rest.count, KText.isAsciiDigit(rest[i]) { i += 1 }
        guard i > 0, i < rest.count, rest[i] == "." else { return false }
        return KText.toInt(String(String.UnicodeScalarView(rest[..<i]))) == ordinal
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
        // Tokens a newer build wrote, straight after the title as that build writes them. Only a
        // rebuilt line comes through here; an unedited one keeps its own bytes and positions.
        for x in t.extras { s += " " + x }
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
        // Whose calendar this came off, beside `ext:`. Encoded here and not decoded on the way in —
        // Android's asymmetry, kept; see `EventRef.author`.
        if let a = e.author { s += " by:" + encodeValue(a) }
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
    /// `\[\[([^\[\]|\n]*)\|\^([^\[\]|\s]+)]]` with Java's ASCII `\s` spelled out: ICU's `\s` would
    /// also refuse a no-break space in an id that Android accepts.
    static let link = try! NSRegularExpression(pattern: #"\[\[([^\[\]|\n]*)\|\^([^\[\]| \t\n\x{0B}\f\r]+)]]"#)

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

    /// Every task a piece of text points at.
    public static func targets(_ text: String) -> [String] {
        var seen = Set<String>()
        return links(text).map(\.targetId).filter { seen.insert($0).inserted }
    }

    /// A `[[` being typed, and what has been typed since — `Links.draft` on Android.
    ///
    /// Returns the range of the draft (the `[[` included) and the text after it, or nil when the
    /// caret is not inside one. This is what turns typing into a picker: the caller looks the typed
    /// text up and replaces the range with a real link.
    public static func draft(_ text: String, caret: Int) -> (NSRange, String)? {
        let ns = text as NSString
        let at = max(0, min(caret, ns.length))
        guard at > 0 else { return nil }
        let before = ns.substring(to: at)
        guard let openRange = before.range(of: open, options: .backwards) else { return nil }
        let openAt = before.distance(from: before.startIndex, to: openRange.lowerBound)
        guard openAt + open.count <= at else { return nil }
        let typed = ns.substring(with: NSRange(location: openAt + open.count, length: at - openAt - open.count))
        // Anything that ends the token ends the draft. A newline or a bracket means the `[[` was
        // never the start of a link; a `]]` means it was one and is finished.
        if typed.contains(close) || typed.contains(where: { $0 == "\n" || $0 == "[" || $0 == "]" }) { return nil }
        return (NSRange(location: openAt, length: at - openAt), typed)
    }

    public static func encode(label: String, targetId: String) -> String {
        // Per UTF-16 char as Kotlin maps it, then Java's ASCII `\s+` to one space, then `trim()`.
        var out = String.UnicodeScalarView(), inSpace = false
        for u in label.unicodeScalars {
            let c: Unicode.Scalar = "[]|\n".unicodeScalars.contains(u) ? " " : u
            if KText.isRegexSpace(c) { if !inSpace { out.append(" ") }; inSpace = true }
            else { out.append(c); inSpace = false }
        }
        let safe = KText.trim(String(out))
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
