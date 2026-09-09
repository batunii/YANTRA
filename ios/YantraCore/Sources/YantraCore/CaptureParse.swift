import Foundation

/// What the quick-capture grammar understood — `Captured` on Android. Spans are in the original
/// string's UTF-16 coordinates so a field can tint tokens in place without moving the caret.
public struct Captured: Equatable {
    public enum Kind: String { case date, time, label, priority, list, assignee, link }
    public struct Span: Equatable { public let range: NSRange; public let kind: Kind }
    public struct Time: Equatable { public let hour: Int, minute: Int }

    public var title: String
    public var date: LocalDate? = nil
    public var time: Time? = nil
    public var labels: [String] = []
    public var priority: String? = nil
    public var assignee: String? = nil
    public var list: String? = nil
    public var listIsNew: Bool = false
    public var spans: [Span] = []

    public var hasAnything: Bool {
        date != nil || time != nil || !labels.isEmpty || priority != nil || list != nil || assignee != nil || spans.contains { $0.kind == .link }
    }

    /// The due instant: all-day when no time was written, else the exact local time.
    public func due() -> DueSpec? {
        guard let d = date else { return nil }
        if let t = time {
            var c = DateComponents(); c.year = d.year; c.month = d.month; c.day = d.day; c.hour = t.hour; c.minute = t.minute
            if let at = Calendar.current.date(from: c) { return DueSpec(.at(at)) }
        }
        return DueSpec(.allDay(d))
    }
}

/// The quick-capture grammar — a port of `CaptureParse.kt`, regex for regex. Deterministic, no model.
/// Two invariants: nothing is consumed silently, and if stripping everything would leave a blank
/// title, nothing is stripped.
public enum CaptureParse {
    static let priorities = ["High", "Medium", "Low"]
    public static let listMark: Character = "~"

    static func re(_ p: String, _ opts: NSRegularExpression.Options = []) -> NSRegularExpression { try! NSRegularExpression(pattern: p, options: opts) }
    static let label = re(#"(?<=^|\s)#([\p{L}\p{N}_-]{1,40})"#)
    static let priorityRe = re(#"(?<=^|\s)!([\p{L}]{1,10})"#, .caseInsensitive)
    static let timeRe = re(#"(?<=^|\s)(\d{1,2})(?::(\d{2}))?\s?(am|pm)(?=$|\s)|(?<=^|\s)(\d{1,2}):(\d{2})(?=$|\s)"#, .caseInsensitive)
    static let isoDate = re(#"(?<=^|\s)(\d{4})-(\d{2})-(\d{2})(?=$|\s)"#)
    static let numericDate = re(#"(?<=^|\s)(\d{1,2})[/.](\d{1,2})(?:[/.](\d{2,4}))?(?=$|\s)"#)
    static let dayMonth = re(#"(?<=^|\s)(\d{1,2})(?:st|nd|rd|th)?\s+([a-z]{3,9})(?=$|\s)"#, .caseInsensitive)
    static let monthDay = re(#"(?<=^|\s)([a-z]{3,9})\s+(\d{1,2})(?:st|nd|rd|th)?(?=$|\s)"#, .caseInsensitive)
    static let nextWeekday = re(#"(?<=^|\s)next\s+([a-z]{3,9})(?=$|\s)"#, .caseInsensitive)
    static let weekday = re(#"(?<=^|\s)([a-z]{3,9})(?=$|\s)"#, .caseInsensitive)
    static let assigneeRe = re(#"(?<=^|\s)@([A-Za-z0-9][A-Za-z0-9-]{0,38})(?=$|\s)"#)
    static let linkDraft = re(#"\[\[([^\[\]|\n]+)]]"#)
    static let months = ["january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"]
    static let weekdays = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]

    public static func linkNames(_ input: String) -> [String] {
        let ns = input as NSString
        var out: [String] = []
        for m in linkDraft.matches(in: input, range: NSRange(location: 0, length: ns.length)) {
            let n = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces)
            if !n.isEmpty, !out.contains(n) { out.append(n) }
        }
        return out
    }

    public static func parse(_ input: String, today: LocalDate = .today(), now: Captured.Time? = nil, lists: [String] = [], people: [String] = [],
                             links: [String: String] = [:]) -> Captured {
        let ns = input as NSString
        let all = NSRange(location: 0, length: ns.length)
        var spans: [Captured.Span] = []
        var rewrites: [Int: String] = [:]   // by range.location
        var date: LocalDate? = nil, time: Captured.Time? = nil, priority: String? = nil, assignee: String? = nil
        var labels: [String] = []

        for m in linkDraft.matches(in: input, range: all) {
            let name = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces)
            guard let id = links[name.lowercased()] else { continue }
            spans.append(.init(range: m.range, kind: .link))
            rewrites[m.range.location] = Links.encode(label: name, targetId: id)
        }
        for m in assigneeRe.matches(in: input, range: all) where assignee == nil {
            let typed = ns.substring(with: m.range(at: 1))
            guard let login = people.first(where: { $0.caseInsensitiveCompare(typed) == .orderedSame }) else { continue }
            assignee = login
            spans.append(.init(range: m.range, kind: .assignee))
        }
        var list: String? = nil, listIsNew = false
        if let (name, at) = matchKnownList(input, lists) { list = name; spans.append(.init(range: at, kind: .list)) }
        for m in label.matches(in: input, range: all) { labels.append(ns.substring(with: m.range(at: 1))); spans.append(.init(range: m.range, kind: .label)) }
        for m in priorityRe.matches(in: input, range: all) {
            let w = ns.substring(with: m.range(at: 1))
            if let p = priorities.first(where: { $0.caseInsensitiveCompare(w) == .orderedSame }) ?? shorthandPriority(w) {
                priority = p; spans.append(.init(range: m.range, kind: .priority)); break
            }
        }
        for m in timeRe.matches(in: input, range: all) {
            if let t = parseTime(m, ns) { time = t; spans.append(.init(range: m.range, kind: .time)); break }
        }
        if let (d, r) = findDate(input, today: today, taken: spans.map(\.range)) { date = d; spans.append(.init(range: r, kind: .date)) }
        if date == nil, let t = time {
            let cur = now ?? { let c = Calendar.current.dateComponents([.hour, .minute], from: Date()); return Captured.Time(hour: c.hour!, minute: c.minute!) }()
            date = (t.hour, t.minute) > (cur.hour, cur.minute) ? today : today.adding(days: 1)
        }
        if list == nil, let (name, at) = newListAt(input, claimed: spans.map(\.range)) { list = name; listIsNew = true; spans.append(.init(range: at, kind: .list)) }

        let title = strip(input, spans, rewrites)
        if title.trimmingCharacters(in: .whitespaces).isEmpty { return Captured(title: input.trimmingCharacters(in: .whitespaces)) }
        return Captured(title: title, date: date, time: time, labels: labels, priority: priority, assignee: assignee, list: list, listIsNew: listIsNew,
                        spans: spans.sorted { $0.range.location < $1.range.location })
    }

    // MARK: lists

    static func normalise(_ s: String) -> String { String(s.lowercased().filter { !$0.isWhitespace }) }

    static func matchKnownList(_ input: String, _ lists: [String]) -> (String, NSRange)? {
        let known = lists.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.map { ($0, normalise($0)) }.sorted { $0.1.count > $1.1.count }
        if known.isEmpty { return nil }
        let chars = Array(input.utf16)
        var result: (String, NSRange)? = nil
        eachMark(chars) { mark, from in
            for (name, norm) in known {
                if let end = matchFrom(chars, from, Array(norm.utf16)) {
                    let closed = end < chars.count && chars[end] == listMark.utf16.first!
                    result = (name, NSRange(location: mark, length: (closed ? end : end - 1) - mark + 1)); return true
                }
            }
            return false
        }
        return result
    }

    static func isWS(_ u: UInt16) -> Bool { u == 0x20 || u == 0x09 || u == 0x0A || u == 0x0D }

    static func matchFrom(_ input: [UInt16], _ from: Int, _ norm: [UInt16]) -> Int? {
        var p = from, k = 0
        while p < input.count, k < norm.count {
            let ch = input[p]
            if isWS(ch) { p += 1; continue }
            let lower = String(utf16CodeUnits: [ch], count: 1).lowercased().utf16.first ?? ch
            if lower != norm[k] { return nil }
            p += 1; k += 1
        }
        if k != norm.count { return nil }
        return p == input.count || isWS(input[p]) || input[p] == listMark.utf16.first! ? p : nil
    }

    static func newListAt(_ input: String, claimed: [NSRange]) -> (String, NSRange)? {
        let chars = Array(input.utf16)
        var result: (String, NSRange)? = nil
        eachMark(chars) { mark, from in
            var end = chars.count
            for r in claimed where r.location >= from && r.location < end { end = r.location }
            var close: Int? = nil
            if let c = chars[from..<end].firstIndex(of: listMark.utf16.first!) { close = c }
            let stop = close ?? end
            var nameUnits = Array(chars[from..<stop])
            while let last = nameUnits.last, isWS(last) { nameUnits.removeLast() }
            let name = String(utf16CodeUnits: nameUnits, count: nameUnits.count)
            guard let first = name.first, first.isLetter else { return false }
            let last = close ?? (from + nameUnits.count - 1)
            result = (name, NSRange(location: mark, length: last - mark + 1)); return true
        }
        return result
    }

    /// Calls `body(mark, from)` for each `~` at line start or after whitespace; stops when body returns true.
    static func eachMark(_ chars: [UInt16], _ body: (Int, Int) -> Bool) {
        var at = 0
        let tilde = listMark.utf16.first!
        while at < chars.count {
            guard let mark = chars[at...].firstIndex(of: tilde) else { return }
            at = mark + 1
            if mark > 0, !isWS(chars[mark - 1]) { continue }
            var from = mark + 1
            while from < chars.count, isWS(chars[from]) { from += 1 }
            if from < chars.count, body(mark, from) { return }
        }
    }

    public static func listDraft(_ input: String) -> (NSRange, String)? {
        let chars = Array(input.utf16); let tilde = listMark.utf16.first!
        var found: (NSRange, String)? = nil
        var at = 0
        while at < chars.count {
            guard let mark = chars[at...].firstIndex(of: tilde) else { return found }
            at = mark + 1
            if mark > 0, !isWS(chars[mark - 1]) { continue }
            let rest = Array(chars[(mark + 1)...])
            if rest.contains(tilde) { found = nil }
            else {
                var trimmed = rest; while let f = trimmed.first, isWS(f) { trimmed.removeFirst() }
                found = (NSRange(location: mark, length: chars.count - mark), String(utf16CodeUnits: trimmed, count: trimmed.count))
            }
        }
        return found
    }

    public static func listSuggestions(_ draft: String, _ lists: [String]) -> [String] {
        let d = normalise(draft)
        if d.isEmpty { return lists }
        let starts = lists.filter { normalise($0).hasPrefix(d) }
        return starts + lists.filter { normalise($0).contains(d) && !starts.contains($0) }
    }

    // MARK: tokens

    static func shorthandPriority(_ w: String) -> String? {
        switch w.lowercased() { case "h", "hi", "urgent": return "High"; case "m", "med": return "Medium"; case "l", "lo": return "Low"; default: return nil }
    }

    static func group(_ m: NSTextCheckingResult, _ i: Int, _ ns: NSString) -> String {
        let r = m.range(at: i); return r.location == NSNotFound ? "" : ns.substring(with: r)
    }

    static func parseTime(_ m: NSTextCheckingResult, _ ns: NSString) -> Captured.Time? {
        let ampm = group(m, 3, ns)
        if !ampm.isEmpty {
            guard let raw = Int(group(m, 1, ns)), (1...12).contains(raw) else { return nil }
            let minute = Int(group(m, 2, ns)) ?? 0
            guard minute < 60 else { return nil }
            let hour = ampm.lowercased() == "am" ? (raw == 12 ? 0 : raw) : (raw == 12 ? 12 : raw + 12)
            return Captured.Time(hour: hour, minute: minute)
        }
        guard let h = Int(group(m, 4, ns)), let mi = Int(group(m, 5, ns)), h < 24, mi < 60 else { return nil }
        return Captured.Time(hour: h, minute: mi)
    }

    static func findDate(_ input: String, today: LocalDate, taken: [NSRange]) -> (LocalDate, NSRange)? {
        let ns = input as NSString
        let all = NSRange(location: 0, length: ns.length)
        func free(_ r: NSRange) -> Bool { !taken.contains { NSIntersectionRange($0, r).length > 0 || ($0.length == 0 && false) } }
        func first(_ re: NSRegularExpression, _ read: (NSTextCheckingResult) -> LocalDate?) -> (LocalDate, NSRange)? {
            for m in re.matches(in: input, range: all) where free(m.range) { if let d = read(m) { return (d, m.range) } }
            return nil
        }
        func make(_ y: Int, _ mo: Int, _ d: Int) -> LocalDate? { LocalDate(String(format: "%04d-%02d-%02d", y, mo, d)) }
        if let r = first(isoDate, { m in make(Int(group(m, 1, ns))!, Int(group(m, 2, ns))!, Int(group(m, 3, ns))!) }) { return r }
        if let r = first(numericDate, { m in
            let yTxt = group(m, 3, ns); let year = Int(yTxt).map { $0 < 100 ? 2000 + $0 : $0 }
            guard let d = make(year ?? today.year, Int(group(m, 2, ns))!, Int(group(m, 1, ns))!) else { return nil }
            return year == nil && d < today ? make(d.year + 1, d.month, d.day) ?? d : d
        }) { return r }
        if let r = first(dayMonth, { m in
            guard let mo = monthOf(group(m, 2, ns)), let d = make(today.year, mo, Int(group(m, 1, ns))!) else { return nil }
            return d < today ? make(d.year + 1, d.month, d.day) ?? d : d
        }) { return r }
        if let r = first(monthDay, { m in
            guard let mo = monthOf(group(m, 1, ns)), let d = make(today.year, mo, Int(group(m, 2, ns))!) else { return nil }
            return d < today ? make(d.year + 1, d.month, d.day) ?? d : d
        }) { return r }
        if let r = first(nextWeekday, { m in weekdayOf(group(m, 1, ns)).map { nextOccurrence(today, $0).adding(days: 7) } }) { return r }
        if let r = first(weekday, { m in
            switch group(m, 1, ns).lowercased() {
            case "today", "tonight": return today
            case "tomorrow", "tmrw": return today.adding(days: 1)
            case let w: return weekdayOf(w).map { nextOccurrence(today, $0) }
            }
        }) { return r }
        return nil
    }

    static func monthOf(_ word: String) -> Int? {
        let w = word.lowercased()
        return months.firstIndex { $0 == w || (w.count >= 3 && $0.hasPrefix(w)) }.map { $0 + 1 }
    }
    /// 1 = Monday … 7 = Sunday, as java.time numbers them.
    static func weekdayOf(_ word: String) -> Int? {
        let w = word.lowercased()
        return weekdays.firstIndex { $0 == w || (w.count >= 3 && $0.hasPrefix(w)) }.map { $0 + 1 }
    }
    static func nextOccurrence(_ today: LocalDate, _ day: Int) -> LocalDate {
        var d = today.adding(days: 1)
        while isoWeekday(d) != day { d = d.adding(days: 1) }
        return d
    }
    static func isoWeekday(_ d: LocalDate) -> Int {
        let wd = Calendar(identifier: .gregorian).component(.weekday, from: d.startOfDay())   // 1 = Sunday
        return wd == 1 ? 7 : wd - 1
    }

    static func strip(_ input: String, _ spans: [Captured.Span], _ rewrites: [Int: String]) -> String {
        if spans.isEmpty { return input.trimmingCharacters(in: .whitespaces) }
        let ns = NSMutableString(string: input)
        for s in spans.sorted { $0.range.location > $1.range.location } { ns.replaceCharacters(in: s.range, with: rewrites[s.range.location] ?? "") }
        return (ns as String).replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
    }
}
