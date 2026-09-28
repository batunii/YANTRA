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

    /// `PageCodec.parseLocal`: with a `T` it is `LocalDateTime.parse` (`2026-09-11T14:00`,
    /// `…T14:00:30`, `…T14:00:30.5`); without one it is `LocalDate.parse` at midnight. Nothing else
    /// parses — the same strict ISO grammar `java.time` applies, ASCII digits only.
    public init?(_ s: String) {
        let v = Array(s.unicodeScalars)
        guard let t = v.firstIndex(of: "T") else {
            guard let d = LocalDate(s) else { return nil }
            self.init(date: d); return
        }
        guard let d = LocalDate(String(String.UnicodeScalarView(v[..<t]))),
              let (h, m, sec, nanos, end) = JavaTime.parseTime(v, from: t + 1), end == v.count else { return nil }
        self.init(date: d, hour: h, minute: m, second: sec, nano: nanos)
    }

    /// How `java.time.LocalDateTime.toString()` prints: the seconds field appears only when it has
    /// something to say, and the fraction is written in groups of three digits.
    public var description: String {
        date.description + "T" + JavaTime.timeText(hour: hour, minute: minute, second: second, nano: nano)
    }

    public static func < (l: LocalDateTime, r: LocalDateTime) -> Bool {
        (l.date, l.hour, l.minute, l.second, l.nano) < (r.date, r.hour, r.minute, r.second, r.nano)
    }

    /// Seconds since this day's midnight — what a timeline lays out against.
    public var secondOfDay: Int { hour * 3600 + minute * 60 + second }

    public func adding(days: Int) -> LocalDateTime {
        LocalDateTime(date: date.adding(days: days), hour: hour, minute: minute, second: second, nano: nano)
    }

    public func adding(seconds: Int) -> LocalDateTime { adding(ISODuration(seconds: seconds)) }

    /// `plus(Duration)`, nanoseconds and all — a when-slot of `PT1.5S` is one and a half seconds.
    public func adding(_ d: ISODuration) -> LocalDateTime {
        var nanoTotal = nano + d.nanos
        var carry = 0
        if nanoTotal >= 1_000_000_000 { nanoTotal -= 1_000_000_000; carry = 1 }
        var total = secondOfDay + d.seconds + carry
        var dayShift = total / 86_400
        total %= 86_400
        if total < 0 { total += 86_400; dayShift -= 1 }
        return LocalDateTime(date: date.adding(days: dayShift), hour: total / 3600, minute: (total / 60) % 60,
                             second: total % 60, nano: nanoTotal)
    }

    /// Whole seconds from `self` to `other`, which is all any event length needs.
    public func seconds(until other: LocalDateTime) -> Int {
        date.days(until: other.date) * 86_400 + (other.secondOfDay - secondOfDay)
    }

    /// `Duration.between(self, other)`, exactly.
    public func duration(until other: LocalDateTime) -> ISODuration {
        ISODuration(seconds: seconds(until: other), nanos: other.nano - nano)
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

/// An ISO-8601 duration: `java.time.Duration`, seconds plus a nanosecond part, read and written by
/// Java's own rules.
///
/// Stored the way Java stores one — whole seconds and a non-negative nanosecond adjustment — so that
/// the text a duration renders to is a function of the value with no float to round differently on
/// two platforms and produce a diff out of nothing. Almost every duration in a real file is whole
/// minutes; the nanoseconds exist so that the rare `PT1.5S` somebody typed is written back as what
/// it says rather than as `PT1S`.
public struct ISODuration: Hashable, Sendable, CustomStringConvertible, Comparable {
    public private(set) var seconds: Int
    /// 0 ..< 1_000_000_000, as in `java.time.Duration`: `-0.5s` is `-1s + 500_000_000ns`.
    public private(set) var nanos: Int
    public init(seconds: Int, nanos: Int = 0) {
        var s = seconds, n = nanos
        s += n / 1_000_000_000; n %= 1_000_000_000
        if n < 0 { n += 1_000_000_000; s -= 1 }
        self.seconds = s; self.nanos = n
    }
    public static func minutes(_ m: Int) -> ISODuration { ISODuration(seconds: m * 60) }
    public static func hours(_ h: Int) -> ISODuration { ISODuration(seconds: h * 3600) }
    public var minutes: Int { seconds / 60 }
    public var isNegative: Bool { seconds < 0 }
    public static func < (l: ISODuration, r: ISODuration) -> Bool { (l.seconds, l.nanos) < (r.seconds, r.nanos) }

    /// `Duration.parse`: `[-+]?P(nD)?(T(nH)?(nM)?(n([.,]f{0,9})?S)?)?`, case-insensitive, every
    /// number optionally signed, ASCII digits only, at least one part, no `T` without a part after
    /// it, and anything that overflows refused. No weeks, months or years — Java refuses those too.
    public init?(_ s: String) {
        let v = Array(s.unicodeScalars)
        var i = 0
        func upper(_ u: Unicode.Scalar) -> Unicode.Scalar { (u.value >= 0x61 && u.value <= 0x7A) ? Unicode.Scalar(u.value - 32)! : u }
        var negate = false
        if i < v.count, v[i] == "-" || v[i] == "+" { negate = v[i] == "-"; i += 1 }
        guard i < v.count, upper(v[i]) == "P" else { return nil }
        i += 1
        // A signed run of ASCII digits, or nil. `i` moves only on success.
        func number() -> (Int64, String)? {
            var j = i, sign = ""
            if j < v.count, v[j] == "-" || v[j] == "+" { sign = v[j] == "-" ? "-" : ""; j += 1 }
            let start = j
            while j < v.count, KText.isAsciiDigit(v[j]) { j += 1 }
            guard j > start else { return nil }
            let digits = String(String.UnicodeScalarView(v[start..<j]))
            guard let n = Int64(sign + digits) else { return nil }
            i = j
            return (n, sign)
        }
        func peekUnit(_ unit: Unicode.Scalar) -> Bool {
            let save = i
            guard number() != nil, i < v.count, upper(v[i]) == unit else { i = save; return false }
            i = save; return true
        }
        var days: Int64? = nil, hours: Int64? = nil, mins: Int64? = nil, secs: Int64? = nil, frac: Int64 = 0
        var fracNegative = false
        if peekUnit("D") { days = number()!.0; i += 1 }
        var sawT = false
        if i < v.count, upper(v[i]) == "T" {
            sawT = true; i += 1
            if peekUnit("H") { hours = number()!.0; i += 1 }
            if peekUnit("M") { mins = number()!.0; i += 1 }
            // Seconds, with an optional fraction.
            let save = i
            if let (n, sign) = number() {
                var fracDigits = ""
                if i < v.count, v[i] == "." || v[i] == "," {
                    i += 1
                    let fs = i
                    while i < v.count, KText.isAsciiDigit(v[i]), i - fs < 9 { i += 1 }
                    fracDigits = String(String.UnicodeScalarView(v[fs..<i]))
                }
                if i < v.count, upper(v[i]) == "S" {
                    i += 1
                    secs = n
                    frac = fracDigits.isEmpty ? 0 : Int64(fracDigits.padding(toLength: 9, withPad: "0", startingAt: 0))!
                    fracNegative = sign == "-"
                } else { i = save }
            }
            if hours == nil && mins == nil && secs == nil { return nil }   // "T" with nothing after it
        }
        guard i == v.count, days != nil || sawT else { return nil }
        // Java: daysAsSecs + hoursAsSecs + minsAsSecs + seconds, each multiplyExact/addExact.
        func mul(_ a: Int64, _ b: Int64) -> Int64? { let (r, o) = a.multipliedReportingOverflow(by: b); return o ? nil : r }
        func add(_ a: Int64, _ b: Int64) -> Int64? { let (r, o) = a.addingReportingOverflow(b); return o ? nil : r }
        guard let d = mul(days ?? 0, 86_400), let h = mul(hours ?? 0, 3600), let m = mul(mins ?? 0, 60),
              let t1 = add(d, h), let t2 = add(t1, m), let total = add(t2, secs ?? 0) else { return nil }
        var nanosPart = fracNegative ? -frac : frac
        var totalSecs = total
        if negate {
            guard let neg = mul(totalSecs, -1) else { return nil }
            totalSecs = neg; nanosPart = -nanosPart
        }
        guard totalSecs >= Int64(Int.min / 2), totalSecs <= Int64(Int.max / 2) else { return nil }
        self.init(seconds: Int(totalSecs), nanos: Int(nanosPart))
    }

    /// What `java.time.Duration.toString()` emits: hours are total (a day is `PT24H`), zero parts
    /// are dropped, zero itself is `PT0S`, and a fraction is written without trailing zeros.
    public var description: String {
        if seconds == 0 && nanos == 0 { return "PT0S" }
        var effective = seconds
        if effective < 0 && nanos > 0 { effective += 1 }
        let h = effective / 3600, m = (effective % 3600) / 60, s = effective % 60
        var out = "PT"
        if h != 0 { out += "\(h)H" }
        if m != 0 { out += "\(m)M" }
        if s == 0 && nanos == 0 && out.count > 2 { return out }
        if seconds < 0 && nanos > 0 && s == 0 { out += "-0" } else { out += "\(s)" }
        if nanos > 0 {
            let n = seconds < 0 ? 2 * 1_000_000_000 - nanos : nanos + 1_000_000_000
            var digits = String(n)
            while digits.hasSuffix("0") { digits.removeLast() }
            // `n` is 1xxxxxxxxx or 1xxxxxxxxx; its leading 1 is where Java writes the point.
            out += "." + digits.dropFirst()
        }
        return out + "S"
    }
}

/// `java.time.Instant`: seconds from the epoch and a nanosecond part, with Java's text.
///
/// For `modified_at`, which is the last-writer-wins clock and is written back on every re-encode of
/// an unedited page. A `Date` is a `Double` and cannot hold what `Instant.parse` read — a file that
/// says `.207123Z` must be written back as `.207123Z`, not `.207Z` — so the page keeps this and
/// hands out a `Date` for arithmetic.
public struct JavaInstant: Hashable, Comparable, Sendable, CustomStringConvertible {
    public var epochSecond: Int64
    public var nano: Int
    public init(epochSecond: Int64, nano: Int = 0) { self.epochSecond = epochSecond; self.nano = nano }

    public static let epoch = JavaInstant(epochSecond: 0)

    /// The millisecond a `Date` names — the precision every Android stamp has
    /// (`Instant.ofEpochMilli(System.currentTimeMillis())`).
    public init(_ date: Date) {
        let ms = Int64((date.timeIntervalSince1970 * 1000).rounded())
        var s = ms / 1000, r = ms % 1000
        if r < 0 { r += 1000; s -= 1 }
        self.init(epochSecond: s, nano: Int(r) * 1_000_000)
    }

    public var date: Date { Date(timeIntervalSince1970: TimeInterval(epochSecond) + TimeInterval(nano) / 1e9) }

    public static func < (l: JavaInstant, r: JavaInstant) -> Bool { (l.epochSecond, l.nano) < (r.epochSecond, r.nano) }

    /// `Instant.parse` (ISO_INSTANT, as Android 14's libcore has it): a local date, `T`,
    /// `HH:mm:ss` (seconds required), an optional fraction of 0–9 digits, then `Z` or an offset of
    /// `±HH:MM[:SS]`. Case-insensitive. `24:00:00` is the next midnight and `23:59:60` is read as
    /// `23:59:59`, both as Java does.
    public static func parse(_ s: String) -> JavaInstant? {
        let v = Array(s.unicodeScalars)
        guard let t = v.firstIndex(where: { $0 == "T" || $0 == "t" }),
              let d = LocalDate(String(String.UnicodeScalarView(v[..<t]))) else { return nil }
        var i = t + 1
        func two() -> Int? {
            guard i + 2 <= v.count, KText.isAsciiDigit(v[i]), KText.isAsciiDigit(v[i + 1]) else { return nil }
            defer { i += 2 }
            return Int(v[i].value - 48) * 10 + Int(v[i + 1].value - 48)
        }
        func lit(_ c: Unicode.Scalar) -> Bool { guard i < v.count, v[i] == c else { return false }; i += 1; return true }
        guard var h = two(), lit(":"), let m = two(), lit(":"), var sec = two() else { return nil }
        var nanos = 0
        if lit(".") {
            let fs = i
            while i < v.count, KText.isAsciiDigit(v[i]), i - fs < 9 { i += 1 }
            let digits = String(String.UnicodeScalarView(v[fs..<i]))
            if !digits.isEmpty { nanos = Int(digits.padding(toLength: 9, withPad: "0", startingAt: 0))! }
        }
        // The offset: Z, or +HH:MM, or +HH:MM:SS.
        guard i < v.count else { return nil }
        var offset = 0
        if v[i] == "Z" || v[i] == "z" { i += 1 }
        else if v[i] == "+" || v[i] == "-" {
            let sign = v[i] == "-" ? -1 : 1; i += 1
            guard let oh = two(), lit(":"), let om = two(), oh <= 18, om <= 59 else { return nil }
            var os = 0
            if lit(":") { guard let x = two(), x <= 59 else { return nil }; os = x }
            offset = sign * (oh * 3600 + om * 60 + os)
            guard abs(offset) <= 18 * 3600 else { return nil }
        } else { return nil }
        guard i == v.count else { return nil }
        var dayShift = 0
        if h == 24 && m == 0 && sec == 0 && nanos == 0 { h = 0; dayShift = 1 }
        else if h == 23 && m == 59 && sec == 60 { sec = 59 }
        guard h <= 23, m <= 59, sec <= 59 else { return nil }
        let days = Int64(d.epochDay + dayShift)
        let secs = days * 86_400 + Int64(h * 3600 + m * 60 + sec) - Int64(offset)
        return JavaInstant(epochSecond: secs, nano: nanos)
    }

    /// `Instant.toString()`: always seconds, a fraction only when it is not zero, in groups of 3.
    public var description: String {
        let day = Int(floorDiv(epochSecond, 86_400))
        let secOfDay = Int(epochSecond - Int64(day) * 86_400)
        let date = LocalDate(epochDay: day)
        var s = date.description + "T" + String(format: "%02d:%02d:%02d", secOfDay / 3600, (secOfDay / 60) % 60, secOfDay % 60)
        s += JavaTime.fraction(nano)
        return s + "Z"
    }

    private func floorDiv(_ a: Int64, _ b: Int64) -> Int64 { let q = a / b; return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q }
}

/// `ZoneId.of(id).toString()` — whether a zone written in a when-slot is one `java.time` accepts,
/// and the id it would write back.
///
/// Region ids (`Europe/Dublin`) are kept exactly as written; Java is case-sensitive about them and
/// so is this. Offsets are normalised the way Java normalises them: `+1` and `+0100` are `+01:00`,
/// a zero offset is `Z`, and `GMT+1` is `GMT+01:00` — so an edited line carries the same bracket
/// on both apps even when the hand-written one did not.
public enum JavaZone {
    public static func normalize(_ id: String) -> String? {
        let v = Array(id.unicodeScalars)
        if v.count <= 1 || v.first == "+" || v.first == "-" { return offset(id) }
        for prefix in ["UTC", "GMT", "UT"] where KText.starts(id, prefix) {
            let rest = KText.drop(id, prefix.unicodeScalars.count)
            if rest.isEmpty { return prefix }
            guard let first = rest.unicodeScalars.first, first == "+" || first == "-" else { return region(id) }
            guard let o = offset(rest) else { return nil }
            return o == "Z" ? prefix : prefix + o
        }
        return region(id)
    }

    /// `ZoneOffset.of`: `Z`, `±h`, `±hh`, `±hh:mm`, `±hhmm`, `±hh:mm:ss`, `±hhmmss`, within ±18:00.
    static func offset(_ s: String) -> String? {
        if s == "Z" { return "Z" }
        let v = Array(s.unicodeScalars)
        guard let sign = v.first, sign == "+" || sign == "-" else { return nil }
        let body = Array(v.dropFirst())
        guard body.allSatisfy({ KText.isAsciiDigit($0) || $0 == ":" }) else { return nil }
        func n(_ a: Int, _ b: Int) -> Int? {
            guard b <= body.count, body[a..<b].allSatisfy(KText.isAsciiDigit) else { return nil }
            return Int(String(String.UnicodeScalarView(body[a..<b])))
        }
        var h = 0, m = 0, sec = 0
        switch body.count {
        case 1, 2: guard let x = n(0, body.count) else { return nil }; h = x
        case 4: guard let x = n(0, 2), let y = n(2, 4) else { return nil }; h = x; m = y
        case 5: guard body[2] == ":", let x = n(0, 2), let y = n(3, 5) else { return nil }; h = x; m = y
        case 6: guard let x = n(0, 2), let y = n(2, 4), let z = n(4, 6) else { return nil }; h = x; m = y; sec = z
        case 8: guard body[2] == ":", body[5] == ":", let x = n(0, 2), let y = n(3, 5), let z = n(6, 8) else { return nil }; h = x; m = y; sec = z
        default: return nil
        }
        guard h <= 18, m <= 59, sec <= 59 else { return nil }
        let total = h * 3600 + m * 60 + sec
        guard total <= 18 * 3600 else { return nil }
        if total == 0 { return "Z" }
        var out = (sign == "-" ? "-" : "+") + String(format: "%02d:%02d", h, m)
        if sec != 0 { out += String(format: ":%02d", sec) }
        return out
    }

    /// `ZoneRegion.ofId(id, true)`: the shape `[A-Za-z][A-Za-z0-9~/._+-]+`, and a zone the
    /// database knows by exactly that name. A bare abbreviation (`EST`, `PST`) is not a region to
    /// `java.time`, though Foundation will happily resolve one.
    static func region(_ id: String) -> String? {
        let v = Array(id.unicodeScalars)
        func letter(_ u: Unicode.Scalar) -> Bool { (u.value >= 0x41 && u.value <= 0x5A) || (u.value >= 0x61 && u.value <= 0x7A) }
        guard v.count >= 2, letter(v[0]),
              v.dropFirst().allSatisfy({ letter($0) || KText.isAsciiDigit($0) || "~/._+-".unicodeScalars.contains($0) }) else { return nil }
        guard let tz = TimeZone(identifier: id), tz.identifier == id else { return nil }
        let regionsThatAreAlsoAbbreviations: Set<String> = ["CET", "EET", "MET", "WET", "GMT", "UTC"]
        if TimeZone.abbreviationDictionary[id] != nil, !regionsThatAreAlsoAbbreviations.contains(id) { return nil }
        return id
    }
}

enum JavaTime {
    /// `HH:mm[:ss[.fffffffff]]` from index `i` of `v`, as ISO_LOCAL_TIME reads it (STRICT: hour
    /// 0–23, a point with no digits after it allowed). Returns the fields and where it stopped.
    static func parseTime(_ v: [Unicode.Scalar], from start: Int) -> (Int, Int, Int, Int, Int)? {
        var i = start
        func two() -> Int? {
            guard i + 2 <= v.count, KText.isAsciiDigit(v[i]), KText.isAsciiDigit(v[i + 1]) else { return nil }
            defer { i += 2 }
            return Int(v[i].value - 48) * 10 + Int(v[i + 1].value - 48)
        }
        guard let h = two(), h <= 23, i < v.count, v[i] == ":" else { return nil }
        i += 1
        guard let m = two(), m <= 59 else { return nil }
        var sec = 0, nanos = 0
        if i < v.count, v[i] == ":" {
            i += 1
            guard let s = two(), s <= 59 else { return nil }
            sec = s
            if i < v.count, v[i] == "." {
                i += 1
                let fs = i
                while i < v.count, KText.isAsciiDigit(v[i]), i - fs < 9 { i += 1 }
                let digits = String(String.UnicodeScalarView(v[fs..<i]))
                if !digits.isEmpty { nanos = Int(digits.padding(toLength: 9, withPad: "0", startingAt: 0))! }
            }
        }
        return (h, m, sec, nanos, i)
    }

    /// `LocalTime.toString()`.
    static func timeText(hour: Int, minute: Int, second: Int, nano: Int) -> String {
        var s = String(format: "%02d:%02d", hour, minute)
        if second != 0 || nano != 0 {
            s += String(format: ":%02d", second)
            s += fraction(nano)
        }
        return s
    }

    /// `.fff`, `.ffffff` or `.fffffffff` — the shortest group of three that holds the value.
    static func fraction(_ nano: Int) -> String {
        if nano == 0 { return "" }
        if nano % 1_000_000 == 0 { return String(format: ".%03d", nano / 1_000_000) }
        if nano % 1_000 == 0 { return String(format: ".%06d", nano / 1_000) }
        return String(format: ".%09d", nano)
    }
}
