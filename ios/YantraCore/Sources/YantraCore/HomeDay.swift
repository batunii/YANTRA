import Foundation

/// What Home says about today — `HomeViewModel.bookedMinutes` and `nextToday` at 17d0beb.
///
/// Both read today's events as the calendar buckets them, so a recurring meeting and a sitting count
/// the same way here as on the day view.
public enum HomeDay {

    /// How much of today is already spoken for, in minutes.
    ///
    /// **All-day events are excluded.** A passport renewal marked all day is not twenty-four hours
    /// of capacity, and counting it as such would make the one number Home reports useless on
    /// exactly the days it matters. Clipped to today at both ends, so a meeting running past
    /// midnight contributes the part of it that is actually today.
    public static func bookedMinutes(_ events: [DayItem.EventItem], today: LocalDate,
                                     zone: TimeZone = .current) -> Int {
        let from = LocalDateTime(date: today).instant(in: zone)
        let to = LocalDateTime(date: today.adding(days: 1)).instant(in: zone)
        return events.filter { !$0.allDay }.reduce(0) { sum, e in
            let start = max(e.start.instant(in: zone), from)
            let end = min(e.end.instant(in: zone), to)
            return sum + max(0, Int(end.timeIntervalSince(start)) / 60)
        }
    }

    /// The next event today that has not yet ended.
    ///
    /// Not *started*: one already running is still the thing you are next expected at, and dropping
    /// it the moment it begins would take the row away at the point it is most use. Nil when there
    /// is nothing left today, so evenings are quiet rather than showing tomorrow morning.
    public static func next(_ events: [DayItem.EventItem], now: Date, zone: TimeZone = .current) -> DayItem.EventItem? {
        events.filter { !$0.allDay && $0.end.instant(in: zone) > now }
            .min { $0.start.instant(in: zone) < $1.start.instant(in: zone) }
    }

    /// "2h 20m", "45m", "3h" — how much of the day is booked, in Home's words.
    public static func bookedWords(_ minutes: Int) -> String {
        if minutes < 60 { return "\(minutes)m" }
        let words = "\(minutes / 60)h \(minutes % 60)m"
        return words.hasSuffix(" 0m") ? String(words.dropLast(3)) : words
    }

    /// The window inside which "in 12m" is worth saying — the one that promotes a Join control.
    public static let minutesBefore = 30

    /// `HH:mm–HH:mm`, then `  ·  in Nm` inside the window or `  ·  now` once it has begun.
    public static func nextCaption(_ e: DayItem.EventItem, now: Date, zone: TimeZone = .current) -> String {
        func clock(_ t: LocalDateTime) -> String { String(format: "%02d:%02d", t.hour, t.minute) }
        var s = "\(clock(e.start))–\(clock(e.end))"
        // `Duration.toMinutes()` truncates toward zero.
        let away = Int(e.start.instant(in: zone).timeIntervalSince(now) / 60)
        if (1...minutesBefore).contains(away) { s += "  ·  in \(away)m" } else if away <= 0 { s += "  ·  now" }
        return s
    }
}
