import Foundation

/// One occurrence read from the phone's own calendars.
///
/// Deliberately not an EventKit type: the core stays free of the framework so it can be tested on
/// the JVM-equivalent — a plain `swift test` with no device — and so the same bucketing runs in the
/// widget process, which reads the store but has no business opening the event store.
public struct DeviceEvent: Equatable, Sendable {
    /// The occurrence's own identity, unique within one read. Keys the claim map.
    public var instanceId: String
    /// The event's identity as its *sync source* gave it. Nil when the provider offers none.
    public var uid: String?
    /// The identity to hand back to the app that owns it.
    public var eventId: String
    public var title: String
    public var beginUtc: Date
    public var endUtc: Date
    public var allDay: Bool
    public var location: String?
    /// The owning calendar's own colour, as an ARGB value, drawn as-is: it is that calendar's
    /// identity, not ours.
    public var color: Int64?

    public init(instanceId: String, uid: String?, eventId: String, title: String, beginUtc: Date, endUtc: Date,
                allDay: Bool, location: String? = nil, color: Int64? = nil) {
        self.instanceId = instanceId; self.uid = uid; self.eventId = eventId; self.title = title
        self.beginUtc = beginUtc; self.endUtc = endUtc; self.allDay = allDay; self.location = location; self.color = color
    }
}

/// One thing on a day, whatever kind of thing it is.
///
/// Events and due tasks share a list because they share a question — "what is happening on the
/// eleventh" — and answering it from two lists that the screen then has to interleave is how the two
/// end up sorted differently.
public enum DayItem: Identifiable, Equatable, Sendable {
    case event(EventItem)
    case device(DeviceItem)
    case task(TaskItem)

    public struct EventItem: Equatable, Sendable {
        public var nodeId: String, title: String
        public var start: LocalDateTime, end: LocalDateTime
        public var allDay: Bool
        public var location: String?
        /// True when this is one line of a repeat — the screen marks it.
        public var repeating: Bool
        /// The task this block is time for, when it is a sitting rather than an appointment.
        public var forTaskId: String?
        /// The colour the **block** wears, as a palette name. Nil paints it in the accent.
        public var tint: String?
        /// The repository this block came from — the **spine**. Nil while only one is open.
        public var workspaceTint: String?
        public var sortKey: Int
    }

    /// Somebody else's event. A separate kind rather than an `EventItem` with a flag, because the
    /// difference is not cosmetic: this one has no node, no file and no id of ours, and **nothing
    /// may write to it**. `nodeId` carries the instance id only so a list can key on it, and is
    /// prefixed so anything that treats it as a node id fails loudly rather than writing somewhere
    /// strange.
    public struct DeviceItem: Equatable, Sendable {
        public var nodeId: String, title: String
        public var start: LocalDateTime, end: LocalDateTime
        public var allDay: Bool
        public var location: String?
        public var color: Int64?
        /// The line of yours that links this meeting to a task — a line **about** their meeting,
        /// never a copy of it. Its presence is also what tells the note's own line to stand aside,
        /// which is how one meeting stays one block.
        public var noteId: String?
        public var taskId: String?
        /// What that task is called, when it has been renamed to something other than the meeting.
        public var taskTitle: String?
        public var uid: String?
        /// Inferred rather than asked for: the provider expands a rule into instances, so two
        /// occurrences sharing a uid in one window is what "repeats" looks like from here.
        public var repeating: Bool
        public var eventId: String
        public var beginUtc: Date, endUtc: Date
        public var sortKey: Int
    }

    public struct TaskItem: Equatable, Sendable {
        public var nodeId: String, title: String
        public var at: LocalDateTime
        public var hasTime: Bool
        public var done: Bool
        /// Minutes blocked out for it, or nil for a moment. What makes it drawable to scale.
        public var durationMin: Int?
        public var sortKey: Int
    }

    public var nodeId: String {
        switch self { case let .event(e): return e.nodeId; case let .device(d): return d.nodeId; case let .task(t): return t.nodeId }
    }
    public var id: String { nodeId }
    public var title: String {
        switch self { case let .event(e): return e.title; case let .device(d): return d.title; case let .task(t): return t.title }
    }
    /// Where it sorts within the day. All-day things come first, then by time.
    public var sortKey: Int {
        switch self { case let .event(e): return e.sortKey; case let .device(d): return d.sortKey; case let .task(t): return t.sortKey }
    }
    /// All-day things sort before everything timed.
    static let allDayKey = Int.min
    /// A task with no time of day sorts after everything timed, because "today" is weaker than
    /// "today at three" and a list that mixes them reads as if it were not.
    static let undatedKey = Int.max
}

/// A line of ours that is about somebody else's meeting.
///
/// `occurrence` is the instance it was written for, present only on a repeat. `remembered` is the
/// time the line itself carries, which is what a one-off has instead — and what lets a line still
/// find its meeting after somebody moved it.
struct LinkedLine {
    var nodeId: String
    var title: String?
    var occurrence: LocalDateTime?
    var remembered: LocalDateTime

    /// How far this line's idea of when is from one of their instances, in minutes.
    func gap(to d: DeviceEvent, zone: TimeZone) -> Int {
        let mine = occurrence ?? remembered
        let theirs = CalendarBucketer.occurrence(of: d, zone: zone)
        return abs(mine.seconds(until: theirs)) / 60
    }
}

/// What a month's worth of days holds, keyed by date. Days with nothing on them are absent.
public typealias CalendarDays = [LocalDate: [DayItem]]

/// Buckets events and due tasks into the days they land on.
///
/// Pure, and deliberately so: this is where the off-by-one lives — a multi-day event has to appear
/// on every day it covers, an all-day event's exclusive end must not add a phantom day, and a task
/// due at midnight belongs to that day rather than the one before. None of that needs a screen to be
/// checked, and all of it is easy to get wrong once and never notice.
public enum CalendarBucketer {

    /// When a device event is, in local terms — the one place that decision is made.
    ///
    /// **All-day is read in UTC, everything else in the reader's zone.** The provider stores an
    /// all-day event as UTC midnight to UTC midnight — a date wearing an instant's clothes — so
    /// resolving it locally puts a birthday at nine in the morning in Tokyo and at seven the evening
    /// *before* in New York. Shared by the drawing and by the cache refresh, because those two
    /// disagreeing would mean a line being rewritten on every read, for ever.
    public static func localSpan(_ d: DeviceEvent, zone: TimeZone) -> (LocalDateTime, LocalDateTime) {
        let readIn = d.allDay ? TimeZone(identifier: "UTC")! : zone
        return (LocalDateTime.of(d.beginUtc, in: readIn), LocalDateTime.of(d.endUtc, in: readIn))
    }

    /// Which occurrence a device event is, in the form a note writes down.
    static func occurrence(of d: DeviceEvent, zone: TimeZone) -> LocalDateTime {
        LocalDateTime.of(d.beginUtc, in: zone)
    }

    /// How a line and a meeting are matched. Identity plus occurrence, because a weekly standup is
    /// one UID and fifty-two meetings. Kept for *writing* a line down; matching no longer uses it.
    public static func externalKey(uid: String, occurrence: LocalDateTime?) -> String {
        occurrence.map { "\(uid)@\($0)" } ?? uid
    }

    /// The last day a span covers. The exclusive end means a one-day all-day event ends at 00:00 the
    /// next morning, and an hour-long meeting ending at exactly midnight belongs to the day it
    /// started.
    static func lastDay(start: LocalDateTime, end: LocalDateTime) -> LocalDate {
        if end == start { return start.date }
        if end.isMidnight { return end.date.adding(days: -1) }
        return end.date
    }

    public static func bucket(
        nodes: [Node],
        /// Occurrences read from the phone's own calendars, already expanded by the provider.
        device: [DeviceEvent] = [],
        /// Workspace id → the palette name that repository wears, when more than one is open.
        /// Carried through to `workspaceTint` and drawn as the spine.
        workspaceTints: [String: String] = [:],
        /// Task id → the colour of the list that task lives on. Only a sitting reads this, and only
        /// when its own line says nothing: the block is drawn as the task, so it is coloured as one.
        listTints: [String: String] = [:],
        from: LocalDate,
        toExclusive: LocalDate,
        zone: TimeZone = .current
    ) -> CalendarDays {
        var out: CalendarDays = [:]

        let events = nodes.filter { $0.event != nil }
        let tasks = nodes.filter { $0.type == NodeType.task && $0.dueDate != nil }

        // Lines of ours about somebody else's meetings.
        //
        // **Each line chooses its instance; an instance does not choose a line.** That direction is
        // the whole of it. Matching on identity-plus-occurrence deadlocked — the occurrence is the
        // start, the start is what moves, so a line whose remembered time had gone stale could never
        // meet its meeting again and never be corrected, and the day drew the meeting twice for
        // ever. Matching on identity alone went too far the other way: one line about the sixteenth
        // claimed every Monday of a weekly standup.
        //
        // Nearest wins, and only the nearest: a line picks the instance closest to what it
        // remembers, so a meeting moved an hour — or to another day — keeps the page written for it,
        // while the other fifty-one Mondays are left alone.
        var lines: [String: [LinkedLine]] = [:]
        for t in tasks {
            guard let x = t.external, let due = t.dueDate else { continue }
            lines[x.uid, default: []].append(LinkedLine(nodeId: t.id, title: t.title, occurrence: x.occurrence,
                                                        remembered: LocalDateTime.of(due, in: zone)))
        }
        for e in events {
            guard let ev = e.event, let x = ev.external else { continue }
            lines[x.uid, default: []].append(LinkedLine(nodeId: e.id, title: e.title, occurrence: x.occurrence,
                                                        remembered: ev.time.start))
        }

        let instances = Dictionary(grouping: device.filter { $0.uid != nil }, by: { $0.uid! })
        var claimed: [String: LinkedLine] = [:]
        for (uid, ours) in lines {
            guard let theirs = instances[uid] else { continue }
            for line in ours {
                guard let target = theirs.min(by: { line.gap(to: $0, zone: zone) < line.gap(to: $1, zone: zone) }) else { continue }
                // Two lines wanting the same instance is a repeat somebody wrote about twice. The
                // nearer keeps it; the other falls back to drawing its own remembered time.
                if let sitting = claimed[target.instanceId], sitting.gap(to: target, zone: zone) <= line.gap(to: target, zone: zone) { continue }
                claimed[target.instanceId] = line
            }
        }
        func note(for d: DeviceEvent) -> LinkedLine? { claimed[d.instanceId] }

        // Which notes have a meeting on screen to be drawn *as*. Computed before anything is
        // emitted, because the note's own line has to know whether to stand aside.
        let annotated = Set(device.compactMap { note(for: $0)?.nodeId })

        func emit(_ item: DayItem, start: LocalDateTime, end: LocalDateTime) {
            var day = start.date
            let last = lastDay(start: start, end: end)
            while day <= last {
                if day >= from, day < toExclusive { out[day, default: []].append(item) }
                day = day.adding(days: 1)
            }
        }

        for n in events {
            guard let e = n.event else { continue }
            if e.cancelled { continue }   // a cancelled occurrence is an absence, not an entry
            // A note whose meeting is on screen is drawn *as* that meeting, below, so its own line
            // stands aside — one meeting, one block. Its cached times are a fallback for when the
            // meeting cannot be read at all (the permission is off, or it has been deleted), and
            // then it does draw, so notes you wrote never become unreachable.
            if annotated.contains(n.id) { continue }
            emit(.event(DayItem.EventItem(
                nodeId: n.id,
                title: (n.title?.isEmpty == false ? n.title! : "Event"),
                start: e.time.start, end: e.time.end, allDay: e.time.allDay, location: e.location,
                repeating: e.rrule != nil,
                forTaskId: e.forTaskId,
                tint: e.color ?? e.forTaskId.flatMap { listTints[$0] },
                workspaceTint: workspaceTints[n.workspaceId],
                // All-day first, then by clock. A day reads top to bottom as it happens.
                sortKey: e.time.allDay ? DayItem.allDayKey : e.time.start.secondOfDay)),
                 start: e.time.start, end: e.time.end)
        }

        // A uid seen more than once in this window is a rule the provider expanded. That is the only
        // evidence of repetition available here — the provider hands back occurrences, not rules.
        var repeats: [String: Int] = [:]
        for u in device.compactMap(\.uid) { repeats[u, default: 0] += 1 }

        for d in device {
            let (start, end) = localSpan(d, zone: zone)
            let linked = note(for: d)
            emit(.device(DayItem.DeviceItem(
                nodeId: "device:\(d.instanceId)",
                title: d.title,
                start: start, end: end, allDay: d.allDay, location: d.location, color: d.color,
                // One node, not two: the line about this meeting *is* the task.
                noteId: linked?.nodeId, taskId: linked?.nodeId,
                taskTitle: linked?.title.flatMap { $0.isEmpty || $0 == d.title ? nil : $0 },
                uid: d.uid,
                repeating: (d.uid.flatMap { repeats[$0] } ?? 0) > 1,
                eventId: d.eventId, beginUtc: d.beginUtc, endUtc: d.endUtc,
                sortKey: d.allDay ? DayItem.allDayKey : start.secondOfDay)),
                 start: start, end: end)
        }

        for t in tasks {
            // A task about a meeting that is on screen is drawn *as* that meeting, above — one
            // meeting, one block. Its own due date is what draws when the meeting cannot be read.
            if annotated.contains(t.id) { continue }
            guard let dueDate = t.dueDate else { continue }
            let at = LocalDateTime.of(dueDate, in: zone)
            if at.date < from || at.date >= toExclusive { continue }
            out[at.date, default: []].append(.task(DayItem.TaskItem(
                nodeId: t.id,
                title: (t.title?.isEmpty == false ? t.title! : "Untitled"),
                at: at, hasTime: t.dueHasTime, done: t.done,
                durationMin: t.due?.duration?.minutes,
                sortKey: t.dueHasTime ? at.secondOfDay : DayItem.undatedKey)))
        }

        return out.mapValues { items in
            items.sorted { ($0.sortKey, $0.title) < ($1.sortKey, $1.title) }
        }
    }
}

/// The six-week grid a month is drawn on: the Monday on or before the 1st, then 42 days.
public func monthGrid(_ month: LocalDate) -> [LocalDate] {
    let first = LocalDate(year: month.year, month: month.month, day: 1)
    // `weekday` is 1 for Sunday in Foundation, so this maps Monday to 0 and backs up to it.
    let cal = Calendar(identifier: .gregorian)
    let weekday = cal.component(.weekday, from: first.startOfDay())
    let backUp = (weekday + 5) % 7
    let start = first.adding(days: -backUp)
    return (0..<42).map { start.adding(days: $0) }
}
