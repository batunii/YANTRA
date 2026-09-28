import Foundation

/// When an event is, in as few words as say it — `EventBlockRow.kt`'s `whenWords`.
///
/// On a page an event is a span, and the span is the part worth reading: its title is the least of
/// it, and a sitting has no title at all. So the row says the time, in the same words Android uses.
public enum EventWords {
    public static func when(start: LocalDateTime?, end: LocalDateTime?, allDay: Bool, sitting: Bool,
                            locale: Locale = .current) -> String {
        guard let start else { return "Sometime" }
        let prefix = sitting ? "for " : ""
        if allDay { return prefix + day(start, locale) + " · all day" }
        let head = "\(day(start, locale)) · \(clock(start))"
        // A moment has no tail, and an end on the same day needs only its clock.
        guard let end, end != start else { return prefix + head }
        if end.date == start.date { return prefix + "\(head)–\(clock(end))" }
        return prefix + "\(head) → \(day(end, locale)) \(clock(end))"
    }

    private static func day(_ t: LocalDateTime, _ locale: Locale) -> String {
        let f = DateFormatter()
        f.locale = locale
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "EEE d MMM"
        var c = DateComponents(); c.year = t.year; c.month = t.month; c.day = t.day; c.hour = 12
        var g = Calendar(identifier: .gregorian); g.timeZone = TimeZone(identifier: "UTC")!
        return f.string(from: g.date(from: c)!)
    }

    private static func clock(_ t: LocalDateTime) -> String { String(format: "%02d:%02d", t.hour, t.minute) }
}
