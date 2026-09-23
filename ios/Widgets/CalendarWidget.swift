import AppIntents
import EventKit
import SwiftUI
import WidgetKit
import YantraCore

/// The calendar on the home screen.
///
/// The shape follows the family rather than a toggle: a small widget is one day, a medium one is
/// three, a large one is the month with the selected day beneath it. On iOS the size somebody chose
/// when they placed the widget already says how much room there is, so a button that changed view
/// would spend a scarce tap answering a question the layout has answered.
struct CalendarWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "ie.shoonya.yantra.calendar",
                               intent: CalendarWidgetIntent.self,
                               provider: CalendarWidgetProvider()) { entry in
            CalendarWidgetBody(entry: entry)
                .containerBackground(SharedPalette().surface.opacity(entry.opacity / 100), for: .widget)
        }
        .configurationDisplayName("Calendar")
        .description("The days ahead, and what is on them.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct CalendarWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Calendar"
    static var description = IntentDescription("The days ahead, and what is on them")

    /// Meetings are opt-in twice: once in the app, where the permission is asked for, and once here.
    /// A widget on a shared home screen is a more public surface than a screen you opened, and
    /// somebody may reasonably want their tasks on it and not their meetings.
    @Parameter(title: "Show my calendars", default: true) var showDeviceEvents: Bool
    @Parameter(title: "Opacity", default: 94.0, controlStyle: .slider, inclusiveRange: (50.0, 100.0)) var opacity: Double
}

struct CalendarWidgetEntry: TimelineEntry {
    let date: Date
    let data: CalendarWidgetData
    let opacity: Double
}

struct CalendarWidgetProvider: AppIntentTimelineProvider {
    /// One store for the provider's life. Making an `EKEventStore` is not free, and a widget that
    /// built one per refresh would pay for it on every timeline reload.
    private static let store = EKEventStore()

    func placeholder(in context: Context) -> CalendarWidgetEntry {
        CalendarWidgetEntry(date: Date(), data: .placeholder, opacity: 94)
    }

    func snapshot(for intent: CalendarWidgetIntent, in context: Context) async -> CalendarWidgetEntry {
        entry(intent: intent, family: context.family)
    }

    func timeline(for intent: CalendarWidgetIntent, in context: Context) async -> Timeline<CalendarWidgetEntry> {
        let e = entry(intent: intent, family: context.family)
        // Refresh at the next midnight. Everything this widget says is a fact about *which day it
        // is*, so the moment worth waking for is the one where that changes — not a fixed interval
        // that would spend the day's budget being right about nothing.
        let midnight = LocalDate.today().adding(days: 1).startOfDay()
        return Timeline(entries: [e], policy: .after(midnight))
    }

    private func entry(intent: CalendarWidgetIntent, family: WidgetFamily) -> CalendarWidgetEntry {
        let shape: CalendarWidgetShape
        let rowLimit: Int
        switch family {
        case .systemSmall: shape = .day; rowLimit = 4
        case .systemMedium: shape = .threeDay; rowLimit = 4
        default: shape = .month; rowLimit = 4
        }
        let index = AppGroup.readIndex()
        let today = LocalDate.today()
        let device: [DeviceEvent] = (intent.showDeviceEvents && CalendarChoice.enabled)
            ? DeviceEvents.read(from: today.adding(days: -7).startOfDay(),
                                to: today.adding(days: 45).startOfDay(),
                                calendarIds: CalendarChoice.chosen, store: Self.store)
            : []
        let data = CalendarWidgetBuilder.build(shape: shape, anchor: today, index: index,
                                               device: device, rowLimit: rowLimit)
        return CalendarWidgetEntry(date: Date(), data: data, opacity: intent.opacity)
    }
}

// MARK: - the body

struct CalendarWidgetBody: View {
    let entry: CalendarWidgetEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var scheme

    private var y: SharedPalette { SharedPalette(dark: scheme == .dark) }

    var body: some View {
        let d = entry.data
        VStack(alignment: .leading, spacing: family == .systemLarge ? 6 : 4) {
            header(d)
            if family == .systemLarge {
                MonthGridSmall(cells: d.cells, y: y)
                if let col = d.columns.first { AgendaColumn(column: col, y: y, showsHeader: false) }
            } else if family == .systemMedium {
                HStack(alignment: .top, spacing: 10) {
                    ForEach(d.columns) { c in
                        AgendaColumn(column: c, y: y, showsHeader: true).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            } else if let col = d.columns.first {
                AgendaColumn(column: col, y: y, showsHeader: false)
            }
            Spacer(minLength: 0)
            if let n = d.nextUp {
                // The empty day's one useful line: not "nothing on", but when that stops being true.
                Text("\(n.label) · \(n.title)")
                    .font(.system(size: 10.5, weight: .medium)).foregroundStyle(y.dim).lineLimit(1)
            } else if d.ready, d.columns.allSatisfy(\.rows.isEmpty), family != .systemLarge {
                Text("Nothing on").font(.system(size: 10.5)).foregroundStyle(y.dim)
            }
        }
        .widgetURL(URL(string: "yantra://calendar/\(LocalDate.today())"))
    }

    private func header(_ d: CalendarWidgetData) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(d.eyebrow).font(.system(size: 9, weight: .bold, design: .monospaced))
                .kerning(1.2).foregroundStyle(y.accent)
            Text(d.ready ? d.heading : "…").font(.system(size: family == .systemSmall ? 15 : 16, weight: .bold))
                .foregroundStyle(y.ink).lineLimit(1)
        }
    }
}

/// The month, small enough to read at a glance and no smaller.
///
/// Dots rather than counts, the way the app's month does it: the question a month answers is "is
/// that day busy", and a numeral invites comparing two days by a number that means nothing.
struct MonthGridSmall: View {
    let cells: [WidgetDayCell]
    let y: SharedPalette

    var body: some View {
        VStack(spacing: 1) {
            ForEach(0..<6, id: \.self) { row in
                HStack(spacing: 1) {
                    ForEach(0..<7, id: \.self) { col in
                        let i = row * 7 + col
                        if i < cells.count {
                            let c = cells[i]
                            VStack(spacing: 1) {
                                Text("\(c.date.day)")
                                    .font(.system(size: 10, weight: c.isToday ? .bold : .regular))
                                    .foregroundStyle(c.isToday ? y.accent : (c.inMonth ? y.secondary : y.dim))
                                Circle().fill(c.count > 0 ? y.accent.opacity(0.8) : .clear).frame(width: 3, height: 3)
                            }
                            .frame(maxWidth: .infinity)
                        }
                    }
                }
            }
        }
    }
}

struct AgendaColumn: View {
    let column: WidgetDayColumn
    let y: SharedPalette
    let showsHeader: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if showsHeader {
                HStack(spacing: 4) {
                    Text(column.weekday).font(.system(size: 8.5, weight: .bold, design: .monospaced))
                        .foregroundStyle(column.isToday ? y.accent : y.dim)
                    Text(column.dayNumber).font(.system(size: 11, weight: .bold))
                        .foregroundStyle(column.isToday ? y.accent : y.secondary)
                }
            }
            if column.rows.isEmpty {
                Text("—").font(.system(size: 10)).foregroundStyle(y.dim)
            }
            ForEach(column.rows) { r in
                HStack(alignment: .top, spacing: 5) {
                    RoundedRectangle(cornerRadius: 1).fill(tint(r)).frame(width: 2.5, height: 11)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(r.title).font(.system(size: 11, weight: .medium))
                            .strikethrough(r.done, color: y.dim)
                            .foregroundStyle(r.done ? y.dim : y.ink).lineLimit(1)
                        if let t = r.time {
                            Text(t).font(.system(size: 9, design: .monospaced)).foregroundStyle(y.dim)
                        }
                    }
                }
            }
            if column.more > 0 {
                Text("+\(column.more)").font(.system(size: 9.5, weight: .medium)).foregroundStyle(y.dim)
            }
        }
    }

    /// The block's own colour, their calendar's snapped into our palette, or the accent.
    private func tint(_ r: WidgetAgendaRow) -> Color {
        if let name = r.tint, let s = LabelPalette.byName(name) {
            return Color(argb: UInt32(truncatingIfNeeded: y.dark ? s.dark : s.light))
        }
        if let raw = r.deviceColor, let snapped = LabelPalette.nearest(argb: raw, dark: y.dark) {
            return Color(argb: UInt32(truncatingIfNeeded: snapped))
        }
        return r.isTask ? y.secondary : y.accent
    }
}
