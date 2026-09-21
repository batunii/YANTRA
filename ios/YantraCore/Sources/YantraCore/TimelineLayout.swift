import Foundation

/// One block on a timeline: what it is, and where it sits once overlaps are resolved.
///
/// `column` and `columns` are a fraction of the day's width — block *n* of *m* across. A thing alone
/// in its hour is `0 of 1` and spans the lot; two at once are `0 of 2` and `1 of 2`, side by side.
public struct TimedBlock: Identifiable, Equatable, Sendable {
    public var item: DayItem
    public var startMinute: Int, endMinute: Int
    public var column: Int, columns: Int
    public var id: String { "\(item.nodeId)@\(startMinute)" }
}

/// What a day looks like once split into the bar at the top and the timeline underneath.
public struct TimelineDay: Equatable, Sendable {
    /// All-day, cross-day, and anything with no time of its own. Drawn as chips above the ruler.
    public var allDay: [DayItem]
    public var blocks: [TimedBlock]
}

/// Turns a day's items into blocks that do not sit on top of each other.
///
/// **Overlaps share the width rather than hiding each other.** Nothing here reserves time — two
/// things genuinely can happen at once, and a calendar that refused to draw the second one would be
/// lying about the clash rather than showing it.
///
/// The grouping is by **cluster**, not by pair. Two blocks that do not touch each other can still
/// need separate columns because a third overlaps both, so widths are decided per connected run —
/// deciding them pairwise gives a layout that jumps as you scroll past the middle item.
public enum TimelineLayout {

    public static let minutesInDay = 24 * 60

    /// Nothing shorter than this gets drawn thinner — a five-minute block still needs to be tappable.
    public static let minBlockMinutes = 20

    public static func forDay(_ items: [DayItem], day: LocalDate) -> TimelineDay {
        var allDay: [DayItem] = []
        var timed: [(DayItem, Int, Int)] = []
        for item in items {
            if let span = spanOf(item, day: day) { timed.append((item, span.0, span.1)) } else { allDay.append(item) }
        }
        // Start first, then longest, so the thing that began earliest takes the leftmost column and
        // the eye can follow a day down its left edge.
        timed.sort { a, b in
            if a.1 != b.1 { return a.1 < b.1 }
            return (a.2 - a.1) > (b.2 - b.1)
        }

        var blocks: [TimedBlock] = []
        var i = 0
        while i < timed.count {
            // One cluster: keep taking while anything still overlaps the run so far.
            var clusterEnd = timed[i].2
            var j = i + 1
            while j < timed.count, timed[j].1 < clusterEnd {
                clusterEnd = max(clusterEnd, timed[j].2)
                j += 1
            }
            blocks += packCluster(Array(timed[i..<j]))
            i = j
        }
        return TimelineDay(allDay: allDay, blocks: blocks)
    }

    /// Lays one connected run into as few columns as it needs.
    ///
    /// Greedy by column: an item takes the first column whose last block has already finished. That
    /// is the standard interval-partitioning result — the number of columns comes out equal to the
    /// most things happening at any one instant, which is the fewest possible.
    static func packCluster(_ cluster: [(DayItem, Int, Int)]) -> [TimedBlock] {
        var columnEnds: [Int] = []
        var placed: [((DayItem, Int, Int), Int)] = []
        for entry in cluster {
            let col: Int
            if let free = columnEnds.firstIndex(where: { $0 <= entry.1 }) { columnEnds[free] = entry.2; col = free }
            else { columnEnds.append(entry.2); col = columnEnds.count - 1 }
            placed.append((entry, col))
        }
        let width = columnEnds.count
        return placed.map { TimedBlock(item: $0.0.0, startMinute: $0.0.1, endMinute: $0.0.2, column: $0.1, columns: width) }
    }

    /// The minutes of `day` an item covers, or nil if it belongs in the all-day bar.
    ///
    /// A thing with no length of its own is given `minBlockMinutes` — a task due at 14:00 with no
    /// block is still a mark on the day at 14:00, and a zero-height one could not be seen or touched.
    static func spanOf(_ item: DayItem, day: LocalDate) -> (Int, Int)? {
        let start: LocalDateTime, end: LocalDateTime
        switch item {
        case let .event(e):
            if e.allDay { return nil }
            start = e.start; end = e.end
        case let .device(d):
            if d.allDay { return nil }
            start = d.start; end = d.end
        case let .task(t):
            if !t.hasTime { return nil }
            start = t.at; end = t.at.adding(seconds: (t.durationMin ?? 0) * 60)
        }
        // Cross-day things go in the bar, the way every calendar does it: a block that began
        // yesterday has no honest top edge on today's ruler.
        if start.date != day { return nil }
        // Ending at exactly midnight is *this* day's last minute, not a spill into the next.
        let endsMidnightTonight = end.date == day.adding(days: 1) && end.isMidnight
        if end.date > day, !endsMidnightTonight { return nil }

        let from = start.secondOfDay / 60
        let rawTo = endsMidnightTonight ? minutesInDay : end.secondOfDay / 60
        let to = min(max(rawTo, from + minBlockMinutes), minutesInDay)
        return (from, to)
    }

    /// The days of the week `day` falls in, Monday first — matching `monthGrid`.
    public static func weekOf(_ day: LocalDate) -> [LocalDate] { span(day, 7) }

    /// The `count` days on screen beside `day`.
    ///
    /// Seven **snaps to the Monday** of the week it falls in, because a week is a thing with edges
    /// and one that started on a Wednesday would be a rolling seven days pretending to be one. Fewer
    /// than seven does not snap: three days is a window you push along, not a unit with a start, and
    /// anchoring it to Monday would make the selected day jump to the far side of the screen.
    public static func span(_ day: LocalDate, _ count: Int) -> [LocalDate] {
        var first = day
        if count >= 7 {
            let weekday = Calendar(identifier: .gregorian).component(.weekday, from: day.startOfDay())
            first = day.adding(days: -((weekday + 5) % 7))
        }
        return (0..<count).map { first.adding(days: $0) }
    }
}

// MARK: - the task rail

/// The four shelves the task rail pages between.
///
/// Buckets rather than a search box, because the rail answers "what should go in this day", and the
/// useful cuts of that question are few and known. A search box answers "where is that thing I am
/// already thinking of", which is a different need and one the app already has elsewhere.
public enum RailBucket: String, CaseIterable, Sendable {
    /// Due today. What you already said you would do.
    case today = "Today"
    /// A deadline inside the next few days. What is about to become today's problem.
    case soon = "Soon"
    /// No due date and no deadline — the backlog, and the reason the rail exists at all: a task with
    /// no date is invisible on a calendar, and it is exactly the task most in need of a time.
    case undated = "Undated"
    /// Dated, but none of the above — scheduled further out, or overdue and not yet faced.
    case other = "Other"

    public var label: String { rawValue }
}

/// How far ahead a deadline still counts as `soon`.
public let soonDays = 3

/// Which shelf a task belongs on today.
///
/// Order matters and is deliberate: **today beats soon, and soon beats everything else**, so a task
/// due today with a deadline on Friday appears once, under Today, where you would look for it. A
/// task can only be on one shelf — a rail where things appear twice is a rail you cannot count.
///
/// Overdue goes to `other` rather than Today. It is tempting to promote it, but "due last Tuesday"
/// is not a claim about today, and quietly relabelling it as today's work is how a planner starts
/// deciding things on your behalf.
public func railBucket(due: LocalDate?, deadline: LocalDate?, today: LocalDate) -> RailBucket {
    if due == today { return .today }
    if let d = deadline, d >= today, d <= today.adding(days: soonDays) { return .soon }
    if due == nil, deadline == nil { return .undated }
    return .other
}

/// Every shelf, in order, with its tasks — including the empty ones.
///
/// Empty shelves are kept so the rail's tabs do not move about as the day goes on. A control whose
/// buttons change position depending on your data is one you cannot learn.
public func railShelves(_ tasks: [Node], today: LocalDate = .today(), zone: TimeZone = .current) -> [RailBucket: [Node]] {
    var out: [RailBucket: [Node]] = [:]
    for b in RailBucket.allCases { out[b] = [] }
    for t in tasks {
        let due = t.dueDate.map { LocalDate.of($0) }
        out[railBucket(due: due, deadline: t.deadline, today: today), default: []].append(t)
    }
    // Soonest first where there is a date to sort by, then alphabetically, so the order is stable
    // between openings rather than following whatever was last edited.
    return out.mapValues { list in
        list.sorted { a, b in
            let ka = a.dueDate?.timeIntervalSince1970 ?? a.deadline?.startOfDay().timeIntervalSince1970 ?? .greatestFiniteMagnitude
            let kb = b.dueDate?.timeIntervalSince1970 ?? b.deadline?.startOfDay().timeIntervalSince1970 ?? .greatestFiniteMagnitude
            if ka != kb { return ka < kb }
            return (a.title ?? "").lowercased() < (b.title ?? "").lowercased()
        }
    }
}
