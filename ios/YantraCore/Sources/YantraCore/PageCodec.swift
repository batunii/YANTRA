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

    static func daysIn(month: Int, year: Int) -> Int {
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
    /// Minutes *before* the due moment; negative means after. nil is no reminder.
    public var reminderMin: Int?
    public init(_ value: DueValue, reminderMin: Int? = nil) { self.value = value; self.reminderMin = reminderMin }
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
    public var raw: String? = nil
    public init(id: String, title: String, status: TaskStatus = .open, indent: Int = 0, due: DueSpec? = nil, deadline: LocalDate? = nil,
                priority: String? = nil, labels: [String] = [], assignee: String? = nil, doneAt: LocalDate? = nil, raw: String? = nil) {
        self.id = id; self.title = title; self.status = status; self.indent = indent; self.due = due; self.deadline = deadline
        self.priority = priority; self.labels = labels; self.assignee = assignee; self.doneAt = doneAt; self.raw = raw
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

    public var indent: Int {
        switch self {
        case let .prose(_, i, _), let .heading(_, i, _), let .bullet(_, i, _), let .numbered(_, i, _), let .ink(_, i, _), let .image(_, i, _): return i
        case let .task(t): return t.indent
        }
    }
    public var raw: String? {
        switch self {
        case let .prose(_, _, r), let .heading(_, _, r), let .bullet(_, _, r), let .numbered(_, _, r), let .ink(_, _, r), let .image(_, _, r): return r
        case let .task(t): return t.raw
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
        }
    }
    public var isListish: Bool {
        switch self { case .task, .bullet, .numbered: return true; default: return false }
    }
    /// The text a textual block carries; nil for ink and image.
    public var text: String? {
        switch self {
        case let .prose(t, _, _), let .heading(t, _, _), let .bullet(t, _, _), let .numbered(t, _, _): return t
        case let .task(t): return t.title
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

    public init(id: String, type: String, parent: String?, title: String?, systemKey: String? = nil, modifiedAt: Date,
                device: String?, blocks: [Block], unknownKeys: [(String, String)] = []) {
        self.id = id; self.type = type; self.parent = parent; self.title = title; self.systemKey = systemKey
        self.modifiedAt = modifiedAt; self.device = device; self.blocks = blocks; self.unknownKeys = unknownKeys
    }

    public static func == (l: PageDoc, r: PageDoc) -> Bool {
        l.id == r.id && l.type == r.type && l.parent == r.parent && l.title == r.title && l.systemKey == r.systemKey
            && l.modifiedAt == r.modifiedAt && l.device == r.device && l.blocks == r.blocks
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
    static let known: Set<String> = ["id", "type", "parent", "title", "system_key", "modified_at", "device"]

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
            unknownKeys: front.filter { !known.contains($0.0) })
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
        var priority: String? = nil, assignee: String? = nil, labels: [String] = []
        while let w = words.last {
            var consumed = false
            if w.contains(Links.close) { consumed = false }
            else if w.hasPrefix("^"), id.isEmpty { id = String(w.dropFirst()); consumed = true }
            else if w.hasPrefix("due:") { if let d = parseDue(String(w.dropFirst(4))) { due = d; consumed = true } }
            else if w.hasPrefix("deadline:") { if let d = LocalDate(String(w.dropFirst(9))) { deadline = d; consumed = true } }
            else if w.hasPrefix("done:") { if let d = LocalDate(String(w.dropFirst(5))) { doneAt = d; consumed = true } }
            else if w.hasPrefix("!"), w.count > 1 { priority = String(w.dropFirst()); consumed = true }
            else if w.hasPrefix("@"), w.count > 1 { assignee = String(w.dropFirst()); consumed = true }
            else if w.hasPrefix("#"), w.count > 1 { labels.append(String(w.dropFirst())); consumed = true }
            if !consumed { break }
            words.removeLast()
        }
        return TaskRef(id: id, title: words.joined(separator: " "), status: status, indent: indent, due: due, deadline: deadline,
                       priority: priority, labels: labels.reversed(), assignee: assignee, doneAt: doneAt, raw: raw)
    }

    /// `2026-08-26`, `2026-08-26T09:00:00Z`, either optionally suffixed `+r<minutes>`.
    static func parseDue(_ token: String) -> DueSpec? {
        var body = token, reminder: Int? = nil
        if let at = token.range(of: "+r") {
            body = String(token[..<at.lowerBound])
            guard let r = Int(token[at.upperBound...]) else { return nil }
            reminder = r
        }
        if body.contains("T") {
            guard let d = InstantText.parse(body) else { return nil }
            return DueSpec(.at(d), reminderMin: reminder)
        }
        guard let d = LocalDate(body) else { return nil }
        return DueSpec(.allDay(d), reminderMin: reminder)
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
        if let p = t.priority { s += " !" + p }
        for l in t.labels { s += " #" + l }
        if let a = t.assignee { s += " @" + a }
        return s
    }

    static func renderDue(_ d: DueSpec) -> String {
        let body: String
        switch d.value { case let .allDay(day): body = day.description; case let .at(date): body = InstantText.format(date) }
        return d.reminderMin.map { "\(body)+r\($0)" } ?? body
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
