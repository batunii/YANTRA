import Foundation

/// The three amounts of calendar a home screen can hold.
///
/// The same three the app has, and deliberately the same three: a widget that showed a fourth shape
/// would be a second calendar to learn.
///
/// Unlike the Android widget, nothing here toggles between them. On iOS the *size* the person chose
/// when they placed the widget already says how much room there is, so the shape follows the family
/// — a small widget is a day, a medium one is three, a large one is the month. A button that
/// changed view would be spending a scarce tap on a question the layout has already answered.
public enum CalendarWidgetShape: String, Sendable {
    case day, threeDay, month

    public static let daysAcross = 3

    /// What "next" means here. Paging steps by what you are looking at.
    public func step(from: LocalDate, forward: Int) -> LocalDate {
        switch self {
        case .day: return from.adding(days: forward)
        case .threeDay: return from.adding(days: forward * Self.daysAcross)
        case .month:
            let d = LocalDate(year: from.year, month: from.month, day: 1)
            return forward >= 0
                ? d.adding(days: LocalDate.daysIn(month: d.month, year: d.year))
                : d.adding(days: -1)
        }
    }
}

/// One square of the month grid.
public struct WidgetDayCell: Identifiable, Equatable, Sendable {
    public var date: LocalDate
    /// False for the neighbouring months' days, which are drawn dim so the grid keeps its shape.
    public var inMonth: Bool
    public var isToday: Bool
    /// How many things land on it, for the marks under the numeral.
    public var count: Int
    public var id: String { date.description }
}

/// One line of an agenda: an event, somebody else's meeting, or a task that is due.
public struct WidgetAgendaRow: Identifiable, Equatable, Sendable {
    /// What tapping it should open, or nil when there is nothing of ours behind it.
    ///
    /// A device event has no node and never gets a synthetic id. Those rows open the day instead,
    /// which is the honest answer: we can show their meeting and we cannot open it.
    public var nodeId: String?
    public var title: String
    /// `9:30`, `2:00p`, or nil for something that takes the whole day.
    public var time: String?
    /// The same hour as minutes from midnight — nil when `time` is.
    ///
    /// Carried rather than re-read off `time`, because `time` is *formatted*: it is `2:00p` in a
    /// twelve-hour locale, and anything parsing it back to decide which line is next would read
    /// that as two o'clock in the morning. A formatted string is for a person.
    public var startMin: Int?
    public var allDay: Bool
    public var done: Bool
    /// A palette name, resolved to its twin for the theme at render.
    public var tint: String?
    /// Their calendar's own colour, drawn as-is: it is that calendar's identity, not ours.
    public var deviceColor: Int64?
    public var isTask: Bool
    public var id: String { "\(nodeId ?? title)@\(startMin ?? -1)" }
}

/// One day of an agenda — the whole widget in Day, a third of it in three-day.
public struct WidgetDayColumn: Identifiable, Equatable, Sendable {
    public var date: LocalDate
    /// `FRI`
    public var weekday: String
    public var dayNumber: String
    public var isToday: Bool
    public var rows: [WidgetAgendaRow]
    /// How many more there were than would fit, so the column can say `+2` rather than lie.
    public var more: Int
    public var id: String { date.description }
}

/// When the quiet ends: the first thing after the days on screen.
///
/// The empty day is this widget's most common state, and "Nothing on." answers half a question. The
/// half that is worth the line is *when does that stop being true* — so an empty Friday says what
/// Tuesday holds, which is the fact a person is actually reaching for.
public struct WidgetNextUp: Equatable, Sendable {
    public var date: LocalDate
    /// `Tue 22` — formatted here, never in a view, because the model is where a Locale is in scope.
    public var label: String
    public var title: String
}

/// Everything one render of the widget needs, with no WidgetKit or SwiftUI type in sight.
public struct CalendarWidgetData: Equatable, Sendable {
    public var heading: String
    /// The word above the heading — the container the heading names a position inside.
    public var eyebrow: String
    /// The month grid — empty in the agenda shapes.
    public var cells: [WidgetDayCell]
    /// The day columns — one in Day, three in three-day, and the selected day alone under a month.
    public var columns: [WidgetDayColumn]
    /// The first thing after the days on screen, for an empty day to point at.
    public var nextUp: WidgetNextUp?
    /// False until the first real read lands, so the widget can say "…" rather than "nothing on".
    public var ready: Bool

    public static let placeholder = CalendarWidgetData(
        heading: "", eyebrow: "", cells: [], columns: [], nextUp: nil, ready: false)
}

public enum CalendarWidgetBuilder {

    /// The time as a newspaper listing prints it, not as a clock does.
    ///
    /// A right-aligned gutter already carries the column; the leading zero on `09:30` is a character
    /// that changes nothing, and in a 12-hour locale `9:30 AM` is three. So `9:30` and `14:00` where
    /// the day has twenty-four hours, `9:30a` and `2:00p` where it has twelve.
    static func listingTime(_ t: LocalDateTime, locale: Locale) -> String {
        let twelve = (DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: locale) ?? "H").contains("h")
        if twelve {
            let h = t.hour % 12 == 0 ? 12 : t.hour % 12
            return String(format: "%d:%02d%@", h, t.minute, t.hour < 12 ? "a" : "p")
        }
        return String(format: "%d:%02d", t.hour, t.minute)
    }

    static func row(_ item: DayItem, locale: Locale) -> WidgetAgendaRow {
        switch item {
        case let .event(e):
            return WidgetAgendaRow(
                nodeId: e.nodeId, title: e.title.isEmpty ? "Event" : e.title,
                time: e.allDay ? nil : listingTime(e.start, locale: locale),
                startMin: e.allDay ? nil : e.start.secondOfDay / 60,
                allDay: e.allDay, done: false, tint: e.tint, deviceColor: nil, isTask: false)
        case let .device(d):
            return WidgetAgendaRow(
                // No node, and never a synthetic one: a row that cannot be opened says so by
                // carrying nothing, rather than by carrying an id that writes somewhere strange.
                nodeId: d.taskId, title: d.taskTitle ?? d.title,
                time: d.allDay ? nil : listingTime(d.start, locale: locale),
                startMin: d.allDay ? nil : d.start.secondOfDay / 60,
                allDay: d.allDay, done: false, tint: nil, deviceColor: d.color, isTask: false)
        case let .task(t):
            return WidgetAgendaRow(
                nodeId: t.nodeId, title: t.title,
                time: t.hasTime ? listingTime(t.at, locale: locale) : nil,
                startMin: t.hasTime ? t.at.secondOfDay / 60 : nil,
                allDay: !t.hasTime, done: t.done, tint: nil, deviceColor: nil, isTask: true)
        }
    }

    /// Everything one render needs, for a shape and an anchor day.
    ///
    /// `rowLimit` is how many lines a column has room for, which only the view knows; the model
    /// takes it rather than guessing, and reports what it had to drop so the column can say `+2`.
    public static func build(
        shape: CalendarWidgetShape,
        anchor: LocalDate,
        index: WorkspaceIndex,
        device: [DeviceEvent] = [],
        rowLimit: Int,
        today: LocalDate = .today(),
        locale: Locale = .current,
        zone: TimeZone = .current
    ) -> CalendarWidgetData {
        let days: [LocalDate]
        switch shape {
        case .day: days = [anchor]
        case .threeDay: days = (0..<CalendarWidgetShape.daysAcross).map { anchor.adding(days: $0) }
        case .month: days = [anchor]
        }

        // Read wider than the days on screen: the month needs its whole grid, and every shape needs
        // somewhere to look for `nextUp`. Two weeks past the end is far enough that "nothing until
        // the 4th" is nearly always answerable and short enough to stay cheap.
        let grid = monthGrid(LocalDate(year: anchor.year, month: anchor.month, day: 1))
        let from = min(grid.first!, days.first!)
        let to = max(grid.last!.adding(days: 1), days.last!.adding(days: 15))
        let bucketed = CalendarBucketer.bucket(nodes: Array(index.nodes.values), device: device,
                                               from: from, toExclusive: to, zone: zone)

        let weekdayFmt = DateFormatter(); weekdayFmt.locale = locale; weekdayFmt.dateFormat = "EEE"
        let nextUpFmt = DateFormatter(); nextUpFmt.locale = locale; nextUpFmt.dateFormat = "EEE d"
        let monthFmt = DateFormatter(); monthFmt.locale = locale; monthFmt.dateFormat = "MMMM"
        let headingFmt = DateFormatter(); headingFmt.locale = locale; headingFmt.dateFormat = "EEEE d"

        var columns: [WidgetDayColumn] = []
        for d in days {
            // A finished task is still a fact about the day, but it is not what the day is *for*,
            // so it sorts last rather than being dropped.
            let items = (bucketed[d] ?? []).sorted { a, b in
                let ad = { if case let .task(t) = a { return t.done }; return false }()
                let bd = { if case let .task(t) = b { return t.done }; return false }()
                if ad != bd { return !ad }
                return a.sortKey < b.sortKey
            }
            let rows = items.prefix(rowLimit).map { row($0, locale: locale) }
            columns.append(WidgetDayColumn(
                date: d, weekday: weekdayFmt.string(from: d.startOfDay()).uppercased(),
                dayNumber: "\(d.day)", isToday: d == today,
                rows: Array(rows), more: max(0, items.count - rows.count)))
        }

        var cells: [WidgetDayCell] = []
        if shape == .month {
            cells = grid.map { d in
                WidgetDayCell(date: d, inMonth: d.month == anchor.month, isToday: d == today,
                              count: (bucketed[d] ?? []).count)
            }
        }

        // The first thing after the days on screen — only worth computing when there is nothing on
        // them, because that is the only time the widget has a line spare to say it.
        var nextUp: WidgetNextUp?
        if columns.allSatisfy({ $0.rows.isEmpty }), let last = days.last {
            var probe = last.adding(days: 1)
            let horizon = last.adding(days: 14)
            while probe <= horizon {
                if let first = (bucketed[probe] ?? []).first {
                    nextUp = WidgetNextUp(date: probe,
                                          label: nextUpFmt.string(from: probe.startOfDay()),
                                          title: first.title)
                    break
                }
                probe = probe.adding(days: 1)
            }
        }

        let heading: String, eyebrow: String
        switch shape {
        case .month:
            heading = monthFmt.string(from: anchor.startOfDay())
            eyebrow = "\(anchor.year)"
        case .day:
            heading = headingFmt.string(from: anchor.startOfDay())
            eyebrow = monthFmt.string(from: anchor.startOfDay()).uppercased()
        case .threeDay:
            heading = "\(anchor.day)–\(days.last!.day)"
            eyebrow = monthFmt.string(from: anchor.startOfDay()).uppercased()
        }

        return CalendarWidgetData(heading: heading, eyebrow: eyebrow, cells: cells,
                                  columns: columns, nextUp: nextUp, ready: true)
    }
}
