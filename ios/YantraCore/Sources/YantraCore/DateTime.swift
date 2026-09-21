import Foundation

/// A wall-clock date and time, `java.time.LocalDateTime`'s shape and its printed form.
///
/// Deliberately not a `Date`. An instant is a point on the timeline, which is right for "remind me
/// at this moment" and wrong for anything that repeats: a standup at 09:00 every weekday is 09:00
/// *local*, and expanding a rule from instants moves it by an hour at every DST boundary while the
/// wall clock stays put. See CALENDAR_PLAN.md §2.1 and `EventTime`.
public struct LocalDateTime: Hashable, Comparable, Sendable, CustomStringConvertible {
    public var date: LocalDate
    public var hour: Int, minute: Int, second: Int, nano: Int

    public init(date: LocalDate, hour: Int = 0, minute: Int = 0, second: Int = 0, nano: Int = 0) {
        self.date = date; self.hour = hour; self.minute = minute; self.second = second; self.nano = nano
    }

    public var year: Int { date.year }
    public var month: Int { date.month }
    public var day: Int { date.day }

    /// `2026-09-11T14:00`, `2026-09-11T14:00:30`, `2026-09-11` (midnight). Nothing else parses.
    public init?(_ s: String) {
        guard let t = s.firstIndex(of: "T") else {
            guard let d = LocalDate(s) else { return nil }
            self.init(date: d); return
        }
        guard let d = LocalDate(String(s[s.startIndex..<t])) else { return nil }
        let time = s[s.index(after: t)...]
        let parts = time.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2 || parts.count == 3,
              parts[0].count == 2, parts[1].count == 2,
              let h = Int(parts[0]), let m = Int(parts[1]), (0...23).contains(h), (0...59).contains(m) else { return nil }
        var sec = 0, nanos = 0
        if parts.count == 3 {
            let sp = parts[2].split(separator: ".", omittingEmptySubsequences: false)
            guard sp.count == 1 || sp.count == 2, sp[0].count == 2, let ss = Int(sp[0]), (0...59).contains(ss) else { return nil }
            sec = ss
            if sp.count == 2 {
                // Java accepts 1–9 fraction digits and scales them to nanoseconds.
                let digits = sp[1]
                guard (1...9).contains(digits.count), digits.allSatisfy(\.isNumber), let raw = Int(digits) else { return nil }
                nanos = raw * Int(pow(10.0, Double(9 - digits.count)))
            }
        }
        self.init(date: d, hour: h, minute: m, second: sec, nano: nanos)
    }

    /// How `java.time.LocalDateTime.toString()` prints: the seconds field appears only when it has
    /// something to say, and the fraction is written in groups of three digits.
    public var description: String {
        var s = String(format: "%@T%02d:%02d", date.description, hour, minute)
        if second != 0 || nano != 0 {
            s += String(format: ":%02d", second)
            if nano != 0 {
                if nano % 1_000_000 == 0 { s += String(format: ".%03d", nano / 1_000_000) }
                else if nano % 1_000 == 0 { s += String(format: ".%06d", nano / 1_000) }
                else { s += String(format: ".%09d", nano) }
            }
        }
        return s
    }

    public static func < (l: LocalDateTime, r: LocalDateTime) -> Bool {
        (l.date, l.hour, l.minute, l.second, l.nano) < (r.date, r.hour, r.minute, r.second, r.nano)
    }

    /// Seconds since this day's midnight — what a timeline lays out against.
    public var secondOfDay: Int { hour * 3600 + minute * 60 + second }

    public func adding(days: Int) -> LocalDateTime {
        LocalDateTime(date: date.adding(days: days), hour: hour, minute: minute, second: second, nano: nano)
    }

    public func adding(seconds: Int) -> LocalDateTime {
        var total = secondOfDay + seconds
        var dayShift = total / 86_400
        total %= 86_400
        if total < 0 { total += 86_400; dayShift -= 1 }
        return LocalDateTime(date: date.adding(days: dayShift), hour: total / 3600, minute: (total / 60) % 60,
                             second: total % 60, nano: nano)
    }

    /// Whole seconds from `self` to `other`, which is all any event length needs.
    public func seconds(until other: LocalDateTime) -> Int {
        date.days(until: other.date) * 86_400 + (other.secondOfDay - secondOfDay)
    }

    public var isMidnight: Bool { hour == 0 && minute == 0 && second == 0 && nano == 0 }

    /// The instant this wall clock names in a given zone.
    public func instant(in zone: TimeZone = .current) -> Date {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = zone
        var c = DateComponents()
        c.year = date.year; c.month = date.month; c.day = date.day
        c.hour = hour; c.minute = minute; c.second = second
        return cal.date(from: c) ?? date.startOfDay()
    }

    /// The wall clock an instant shows on in a given zone.
    public static func of(_ instant: Date, in zone: TimeZone = .current) -> LocalDateTime {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = zone
        let c = cal.dateComponents([.year, .month, .day, .hour, .minute, .second], from: instant)
        return LocalDateTime(date: LocalDate(year: c.year!, month: c.month!, day: c.day!),
                             hour: c.hour!, minute: c.minute!, second: c.second!)
    }
}

/// An ISO-8601 duration, in the subset `java.time.Duration` reads and writes.
///
/// Whole seconds only. The format carries no length finer than that — a meeting measured in
/// milliseconds is not a thing anybody writes down — and keeping it integral means the text a
/// duration renders to is a function of the number, with no float to round differently on two
/// platforms and produce a diff out of nothing.
public struct ISODuration: Hashable, Sendable, CustomStringConvertible, Comparable {
    public var seconds: Int
    public init(seconds: Int) { self.seconds = seconds }
    public static func minutes(_ m: Int) -> ISODuration { ISODuration(seconds: m * 60) }
    public static func hours(_ h: Int) -> ISODuration { ISODuration(seconds: h * 3600) }
    public var minutes: Int { seconds / 60 }
    public static func < (l: ISODuration, r: ISODuration) -> Bool { l.seconds < r.seconds }

    /// `PnDTnHnMnS`, sign optional. Anything with a fractional or missing part is refused rather
    /// than rounded: a length the two apps would read differently is worse than no length at all.
    public init?(_ s: String) {
        var text = Substring(s)
        var sign = 1
        if text.hasPrefix("-") { sign = -1; text = text.dropFirst() }
        else if text.hasPrefix("+") { text = text.dropFirst() }
        guard text.hasPrefix("P") else { return nil }
        text = text.dropFirst()
        var total = 0, sawAny = false, inTime = false
        var number = ""
        for ch in text {
            if ch == "T" { guard !inTime else { return nil }; inTime = true; number = ""; continue }
            if ch.isNumber { number.append(ch); continue }
            guard let n = Int(number), !number.isEmpty else { return nil }
            switch (ch, inTime) {
            case ("D", false): total += n * 86_400
            case ("H", true): total += n * 3600
            case ("M", true): total += n * 60
            case ("S", true): total += n
            case ("W", false): total += n * 604_800
            default: return nil
            }
            sawAny = true
            number = ""
        }
        guard number.isEmpty, sawAny else { return nil }
        self.init(seconds: sign * total)
    }

    /// What `java.time.Duration.toString()` emits: hours are total (a day is `PT24H`), zero parts
    /// are dropped, and zero itself is `PT0S`.
    public var description: String {
        if seconds == 0 { return "PT0S" }
        let neg = seconds < 0
        let abs = Swift.abs(seconds)
        let h = abs / 3600, m = (abs % 3600) / 60, s = abs % 60
        var out = "PT"
        if h != 0 { out += "\(neg ? -h : h)H" }
        if m != 0 { out += "\(neg ? -m : m)M" }
        if s != 0 { out += "\(neg ? -s : s)S" }
        return out
    }
}
