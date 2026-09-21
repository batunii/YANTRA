import SwiftUI
import YantraCore

/// What the calendar screen can put in front of you. One sheet, so one value.
enum CalendarPresentation: Identifiable, Equatable {
    case event(EventSheetTarget)
    /// The task rail, on a window too narrow to give it a column.
    case rail

    var id: String {
        switch self { case let .event(t): return t.id; case .rail: return "rail" }
    }
    static func == (l: CalendarPresentation, r: CalendarPresentation) -> Bool { l.id == r.id }
}

/// Month, week, or one day. The switcher is three letters because the word for each is longer than
/// the control needs to be, and a phone is only so wide.
enum CalendarMode: String, CaseIterable { case month = "M", week = "W", day = "D" }

/// What the calendar screen is looking at, and everything it needs to draw it.
///
/// A model of its own rather than more state on `AppModel`: the month on screen, the selected day
/// and the device-calendar read are the calendar's business, and the read is the expensive part —
/// it goes to EventKit and must not run again because an unrelated list changed somewhere.
@MainActor
final class CalendarModel: ObservableObject {
    @Published var month: LocalDate = { let t = LocalDate.today(); return LocalDate(year: t.year, month: t.month, day: 1) }()
    @Published var selected: LocalDate = .today()
    @Published var mode: CalendarMode = .month
    @Published var daysAcross: Int = 3
    @Published private(set) var days: CalendarDays = [:]
    /// How tall an hour is. Remembered on this device — re-pinching on every visit would be worse
    /// than having no zoom at all, which is what makes persisting it the point rather than a nicety.
    @Published var hourHeight: CGFloat = CalendarModel.loadHourHeight() {
        didSet { CalendarModel.saveHourHeight(hourHeight) }
    }

    static let defaultHourHeight: CGFloat = 60
    /// Small enough that a working day fits a phone without scrolling. Below this the words stop
    /// fitting and it becomes a chart.
    static let minHourHeight: CGFloat = 26
    /// Large enough that a fifteen-minute block is a real target rather than a line.
    static let maxHourHeight: CGFloat = 180

    private static let hourKey = "calendar_hour_height"
    static func loadHourHeight() -> CGFloat {
        let v = AppGroup.defaults.double(forKey: hourKey)
        return v <= 0 ? defaultHourHeight : min(max(CGFloat(v), minHourHeight), maxHourHeight)
    }
    static func saveHourHeight(_ h: CGFloat) { AppGroup.defaults.set(Double(h), forKey: hourKey) }

    /// The window currently on screen, which is what the device read is asked for.
    var window: (LocalDate, LocalDate) {
        switch mode {
        case .month:
            let g = monthGrid(month)
            return (g.first!, g.last!.adding(days: 1))
        case .week:
            let s = TimelineLayout.span(selected, daysAcross)
            return (s.first!, s.last!.adding(days: 1))
        case .day:
            return (selected, selected.adding(days: 1))
        }
    }

    /// Rebuilds the day buckets from the index, plus whatever the phone's calendars say.
    ///
    /// The device read happens here rather than in the bucketer because the bucketer is pure: it is
    /// given occurrences and decides which day each lands on, and that decision is the part worth
    /// testing without a device in the room.
    func rebuild(index: WorkspaceIndex, listColor: (String) -> String?) {
        let (from, to) = window
        // A month's grid already overhangs both ends; a week or a day is widened so a meeting that
        // began yesterday still reaches today's all-day bar.
        let readFrom = from.adding(days: -1), readTo = to.adding(days: 1)
        let device = CalendarChoice.enabled
            ? DeviceCalendars.shared.events(from: readFrom.startOfDay(), to: readTo.startOfDay(), calendarIds: CalendarChoice.chosen)
            : []
        var listTints: [String: String] = [:]
        for n in index.nodes.values where n.type == NodeType.task {
            if let c = listColor(n.id) { listTints[n.id] = c }
        }
        days = CalendarBucketer.bucket(nodes: Array(index.nodes.values), device: device,
                                       listTints: listTints, from: readFrom, toExclusive: readTo)
    }

    func items(_ day: LocalDate) -> [DayItem] { days[day] ?? [] }

    /// Steps by whatever the view is a view *of* — a month in Month, a screenful in Week, a day in Day.
    func step(_ direction: Int) {
        switch mode {
        case .month:
            let d = month.adding(days: direction > 0 ? LocalDate.daysIn(month: month.month, year: month.year) : -1)
            month = LocalDate(year: d.year, month: d.month, day: 1)
        case .week: selected = selected.adding(days: direction * daysAcross)
        case .day: selected = selected.adding(days: direction)
        }
    }

    func select(_ day: LocalDate) {
        selected = day
        month = LocalDate(year: day.year, month: day.month, day: 1)
    }

    /// Whether today is already on screen, which is the only thing that makes a Today key worth drawing.
    var showsToday: Bool {
        let t = LocalDate.today()
        switch mode {
        case .month: return t.year == month.year && t.month == month.month
        case .day: return t == selected
        case .week: return TimelineLayout.span(selected, daysAcross).contains(t)
        }
    }
}

/// The month, and what is on the day you tapped.
///
/// Events and due tasks share one list, because they answer the same question and interleaving two
/// lists in the view is how the two end up sorted differently. The bucketing that decides which day
/// a thing lands on is `CalendarBucketer`, kept out of here so the off-by-ones can be tested.
struct CalendarView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Binding var path: NavigationPath
    /// A day to land on, as an ISO date — how the calendar widget points at one.
    var startOn: String? = nil

    @StateObject private var cal = CalendarModel()
    /// What the screen is presenting, if anything. One value, because there is one sheet.
    @State private var presented: CalendarPresentation?
    /// Open by default where it is a column, closed where it is a sheet: a sheet thrown over the
    /// day the moment you arrive would hide the thing you came to look at.
    @State private var railOpen = false
    /// Whether the default above has been applied yet.
    ///
    /// The default can only be chosen once the window has been measured, so it has to happen in
    /// `onAppear` — but `onAppear` fires again on re-layout and whenever a sheet is dismissed, and
    /// an unguarded assignment there would keep overwriting the choice the person just made. On a
    /// phone that made the Tasks key look broken: it set `railOpen` true, the sheet began to
    /// present, the re-layout fired `onAppear`, and the default put it straight back to false.
    @State private var railDefaulted = false
    /// The last measured width class, so the sheet modifiers outside the GeometryReader can read it.
    @State private var isWide = false
    @Environment(\.scenePhase) private var phase

    /// How wide the calendar gets before it stops growing. A month is a fixed amount of information
    /// and a seven-column grid stretched across an iPad gives cells the size of playing cards.
    private let contentMaxWidth: CGFloat = 460
    /// Where a screen stops being a phone, and where seven columns start to fit.
    private let tabletWidth: CGFloat = 600
    /// Where the month stops being one column and becomes two. Higher than `tabletWidth` on purpose:
    /// the panes split the width evenly, so side by side at 600 would give the grid 300 — cells
    /// barely wider than the numerals in them — and the day list the same. Two cramped panes are
    /// worse than one good column.
    private let monthTwoPane: CGFloat = 720

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width >= tabletWidth
            let twoPaneMonth = cal.mode == .month && geo.size.width >= monthTwoPane
            VStack(spacing: 0) {
                VStack(spacing: 0) {
                    header
                    MonthBar(cal: cal)
                    content(twoPane: twoPaneMonth, wide: wide)
                }
                .frame(maxWidth: cal.mode == .month && !twoPaneMonth ? contentMaxWidth : .infinity)
                .frame(maxWidth: .infinity)
                CalendarBar(cal: cal, railOpen: $railOpen, onAdd: { presented = .event(.creating(cal.selected, nil)) })
            }
            .onAppear {
                isWide = wide
                cal.daysAcross = wide ? 7 : 3
                if !railDefaulted { railDefaulted = true; railOpen = wide }
            }
            .onChange(of: wide) { _, w in
                isWide = w
                cal.daysAcross = w ? 7 : 3
                // Growing into a column retires the sheet; shrinking out of one brings it back.
                if w, presented == .rail { presented = nil }
                if !w, railOpen, presented == nil { presented = .rail }
            }
        }
        .background(y.page.ignoresSafeArea())
        .navigationBarBackButtonHidden(true)
        .task {
            if let iso = startOn, let d = LocalDate(iso) { cal.select(d) }
            // `-calmode week|day` — the same launch-argument scaffolding `-route` uses, so a view
            // that needs a tap to reach can still be driven for screenshots and UI tests.
            let args = CommandLine.arguments
            if let i = args.firstIndex(of: "-calmode"), i + 1 < args.count,
               let m = CalendarMode.allCases.first(where: { $0.rawValue.lowercased() == String(args[i + 1].prefix(1)) }) {
                cal.mode = m
            }
            refresh()
        }
        // A permission granted in Settings, or a calendar ticked there, cannot reach this screen as
        // a publisher. Asking again on resume is the cheap and correct answer: it is one query.
        .onChange(of: phase) { _, p in if p == .active { DeviceCalendars.shared.refreshAuthorization(); refresh() } }
        .onChange(of: model.index.nodes.count) { _, _ in refresh() }
        .onChange(of: cal.month) { _, _ in refresh() }
        .onChange(of: cal.selected) { _, _ in refresh() }
        .onChange(of: cal.mode) { _, _ in refresh() }
        .onChange(of: cal.daysAcross) { _, _ in refresh() }
        // One sheet for the screen, chosen by what is being presented.
        //
        // Two `.sheet` modifiers on the *same* view do not give you two sheets: SwiftUI honours one
        // and silently drops the other, with no error anywhere. That is what made the Tasks key a
        // button that flipped its own state and opened nothing.
        .sheet(item: $presented) { what in
            switch what {
            case let .event(target):
                EventSheet(target: target, cal: cal) { refresh() }
            case .rail:
                CalendarTaskRail(cal: cal)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
                    .environment(\.y, y)
            }
        }
        .onChange(of: railOpen) { _, open in
            if open, !isWide { presented = .rail }
            if !open, presented == .rail { presented = nil }
        }
        .onChange(of: presented) { _, what in
            // Dragging the rail away is the same as turning it off.
            if what == nil, railOpen, !isWide { railOpen = false }
        }
    }

    private func refresh() {
        cal.rebuild(index: model.index) { taskId in
            // A sitting is drawn as its task, so it is coloured as its task: the list the task lives
            // on is where that colour comes from.
            model.index.ancestors(of: taskId).last { $0.type == NodeType.list }
                .flatMap { model.listColor($0.id) }
        }
    }

    private var header: some View {
        HStack {
            Button { path.removeLast() } label: {
                Image(systemName: "chevron.left").icon(17, .semibold).foregroundStyle(y.secondary)
                    .frame(width: 38, height: 38)
            }.buttonStyle(.plain)
            Text("Calendar").font(Face.display(22)).tracking(-0.3).foregroundStyle(y.ink)
            Spacer()
            ModeSwitch(mode: $cal.mode)
        }
        .padding(.horizontal, Layout.pageMargin).padding(.top, 6).padding(.bottom, 2)
    }

    @ViewBuilder
    private func content(twoPane: Bool, wide: Bool) -> some View {
        switch cal.mode {
        case .month:
            if twoPane {
                HStack(alignment: .top, spacing: 18) {
                    // The grid keeps its metric; the day list takes the rest of the width and the
                    // whole height, because a day with twenty things on it is what the space is for.
                    MonthGrid(cal: cal).frame(maxWidth: 460)
                    DayList(cal: cal, path: $path, onOpenEvent: { presented = .event(.editing($0)) })
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
                .frame(maxHeight: .infinity, alignment: .top)
                .padding(.horizontal, Layout.pageMargin)
            } else {
                VStack(spacing: 0) {
                    MonthGrid(cal: cal)
                    DayList(cal: cal, path: $path, onOpenEvent: { presented = .event(.editing($0)) })
                }
                .frame(maxHeight: .infinity, alignment: .top)
                .padding(.horizontal, Layout.pageMargin)
            }
        case .week, .day:
            DayTimeline(cal: cal, path: $path, railOpen: railOpen, wide: wide,
                         onOpenEvent: { presented = .event(.editing($0)) },
                         onMark: { day, from, to in presented = .event(.creating(day, (from, to))) })
        }
    }
}

/// Three letters, and the whole switcher fits where the word "Month" alone would not.
struct ModeSwitch: View {
    @Binding var mode: CalendarMode
    @Environment(\.y) private var y
    var body: some View {
        HStack(spacing: 2) {
            ForEach(CalendarMode.allCases, id: \.self) { m in
                Button { mode = m } label: {
                    Text(m.rawValue).font(Face.mono(12, bold: mode == m))
                        .foregroundStyle(mode == m ? y.accentText : y.muted)
                        .frame(width: 30, height: 30)
                        .background(RoundedRectangle(cornerRadius: 8).fill(mode == m ? y.accentFill : .clear))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("calendar.mode.\(m.rawValue)")
                .accessibilityLabel(m == .month ? "Month" : m == .week ? "Week" : "Day")
            }
        }
        .padding(2)
        .background(RoundedRectangle(cornerRadius: 10).fill(y.surfaceHigh))
    }
}

/// What is on screen, said out loud.
///
/// The month name alone was fine while a month was all there was; in the day view it left nothing
/// saying *which* day you were looking at, which is the one thing a day view has to answer.
struct MonthBar: View {
    @ObservedObject var cal: CalendarModel
    @Environment(\.y) private var y

    private var heading: String {
        switch cal.mode {
        case .month:
            let f = DateFormatter(); f.dateFormat = "MMM yyyy"
            return f.string(from: cal.month.startOfDay())
        case .day:
            let f = DateFormatter(); f.dateFormat = "EEE d MMM"
            return f.string(from: cal.selected.startOfDay())
        case .week:
            let span = TimelineLayout.span(cal.selected, cal.daysAcross)
            let f = DateFormatter(); f.dateFormat = "d MMM"
            return "\(span.first!.day)–\(f.string(from: span.last!.startOfDay()))"
        }
    }

    var body: some View {
        HStack {
            Text(heading).font(Face.text(15, .bold)).foregroundStyle(y.ink)
                .accessibilityIdentifier("calendar.heading")
            Spacer()
            // Only worth drawing when today is somewhere else.
            if !cal.showsToday {
                Button { cal.select(.today()) } label: {
                    Text("Today").font(Face.text(12.5, .bold)).foregroundStyle(y.accentText)
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background(Capsule().fill(y.accentFill))
                }.buttonStyle(.plain).accessibilityIdentifier("calendar.today")
            }
        }
        .padding(.horizontal, Layout.pageMargin).padding(.vertical, 8)
        // Swipe to page. This is what replaced a pair of chevrons, and it has to exist before they
        // can go: a band with no way forward is not a simplification.
        .contentShape(Rectangle())
        .gesture(DragGesture(minimumDistance: 24).onEnded { g in
            if g.translation.width < -24 { withAnimation(.snappy) { cal.step(1) } }
            if g.translation.width > 24 { withAnimation(.snappy) { cal.step(-1) } }
        })
    }
}

/// The six-week grid. Every month is six rows whatever its length, so the screen below it does not
/// jump by a row as you page through the year.
struct MonthGrid: View {
    @ObservedObject var cal: CalendarModel
    @Environment(\.y) private var y

    private let cellHeight: CGFloat = 52
    private let weekdays = ["M", "T", "W", "T", "F", "S", "S"]

    var body: some View {
        VStack(spacing: 2) {
            HStack(spacing: 2) {
                ForEach(Array(weekdays.enumerated()), id: \.offset) { _, d in
                    Text(d).font(Face.mono(10, bold: true)).foregroundStyle(y.dim).frame(maxWidth: .infinity)
                }
            }
            .padding(.bottom, 2)
            let grid = monthGrid(cal.month)
            ForEach(0..<6, id: \.self) { row in
                HStack(spacing: 2) {
                    ForEach(0..<7, id: \.self) { col in
                        let day = grid[row * 7 + col]
                        DayCell(day: day,
                                inMonth: day.month == cal.month.month,
                                selected: day == cal.selected,
                                items: cal.items(day))
                            .frame(maxWidth: .infinity, minHeight: cellHeight)
                            .contentShape(Rectangle())
                            .onTapGesture { cal.selected = day }
                            .accessibilityElement(children: .combine)
                            .accessibilityAddTraits(.isButton)
                            .accessibilityIdentifier("calendar.cell.\(day)")
                    }
                }
            }
        }
        .gesture(DragGesture(minimumDistance: 30).onEnded { g in
            if g.translation.width < -30 { withAnimation(.snappy) { cal.step(1) } }
            if g.translation.width > 30 { withAnimation(.snappy) { cal.step(-1) } }
        })
    }
}

struct DayCell: View {
    let day: LocalDate
    let inMonth: Bool
    let selected: Bool
    let items: [DayItem]
    @Environment(\.y) private var y

    private var isToday: Bool { day == .today() }

    var body: some View {
        VStack(spacing: 3) {
            Text("\(day.day)")
                .font(Face.text(13, isToday ? .bold : .regular))
                // Out-of-month days are dim rather than absent: a grid with holes in it is harder to
                // read than one where the edges are quiet.
                .foregroundStyle(inMonth ? (isToday ? y.accentText : y.ink) : y.dim)
                .frame(width: 24, height: 24)
                .background(Circle().fill(isToday ? y.accentFill : .clear))
            // Dots, not counts. The question a month answers is "is that day busy", and a numeral
            // invites you to compare two days by a number that means nothing.
            HStack(spacing: 2.5) {
                ForEach(Array(items.prefix(3).enumerated()), id: \.offset) { _, item in
                    Circle().fill(dotColor(item)).frame(width: 4, height: 4)
                }
                if items.count > 3 { Circle().fill(y.dim).frame(width: 3, height: 3) }
            }
            .frame(height: 5)
        }
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 9).fill(selected ? y.surfaceHigh : .clear))
        .overlay(RoundedRectangle(cornerRadius: 9).stroke(selected ? y.accentBorder : .clear, lineWidth: 1))
    }

    private func dotColor(_ item: DayItem) -> Color {
        switch item {
        case let .event(e): return e.tint.flatMap { swatchColor($0, dark: y.dark) } ?? y.accent
        case let .device(d): return deviceColor(d.color, y: y)
        case let .task(t): return t.done ? y.dim : y.secondary
        }
    }
}

/// A palette name as a colour for the current theme.
func swatchColor(_ name: String, dark: Bool) -> Color? { LabelPalette.swatchColor(name, dark: dark) }

/// Somebody else's calendar colour, snapped into our palette — the one way a third-party hue may
/// reach a surface of this app. Grey and near-black snap to nothing and come back as frame ink.
func deviceColor(_ argb: Int64?, y: YantraColors) -> Color {
    guard let raw = argb, let snapped = LabelPalette.nearest(argb: raw, dark: y.dark) else { return y.muted }
    return Color(argb: UInt32(truncatingIfNeeded: snapped))
}

/// What is on the selected day, as a list. The month's companion.
struct DayList: View {
    @ObservedObject var cal: CalendarModel
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Binding var path: NavigationPath
    let onOpenEvent: (String) -> Void

    var body: some View {
        let items = cal.items(cal.selected)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                let f = DateFormatter(); let _ = (f.dateFormat = "EEEE d MMMM")
                Text(f.string(from: cal.selected.startOfDay()).uppercased())
                    .font(Face.text(11, .bold)).kerning(1.4).foregroundStyle(y.muted)
                    .padding(.top, 14).padding(.bottom, 8)
                if items.isEmpty {
                    Text("Nothing on this day").accessibilityIdentifier("calendar.empty")
                        .font(Face.text(13.5)).foregroundStyle(y.dim).padding(.vertical, 18)
                }
                ForEach(items) { item in
                    DayItemRow(item: item, onOpenEvent: onOpenEvent, onOpenNode: { path.append(Route.node($0)) })
                }
                Spacer().frame(height: 100)
            }
        }
    }
}

struct DayItemRow: View {
    let item: DayItem
    let onOpenEvent: (String) -> Void
    let onOpenNode: (String) -> Void
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y

    private var timeText: String {
        let f = DateFormatter(); f.dateFormat = "HH:mm"
        switch item {
        case let .event(e): return e.allDay ? "All day" : f.string(from: e.start.instant())
        case let .device(d): return d.allDay ? "All day" : f.string(from: d.start.instant())
        case let .task(t): return t.hasTime ? f.string(from: t.at.instant()) : "—"
        }
    }

    var body: some View {
        Button {
            switch item {
            case let .event(e): onOpenEvent(e.nodeId)
            case let .device(d): if let t = d.taskId { onOpenNode(t) }
            case let .task(t): onOpenNode(t.nodeId)
            }
        } label: {
            HStack(spacing: 12) {
                Text(timeText).font(Face.mono(11)).foregroundStyle(y.muted).frame(width: 48, alignment: .leading)
                // The spine: which repository, or — for somebody else's meeting — whose calendar.
                RoundedRectangle(cornerRadius: 2).fill(spine).frame(width: 3)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(title).font(Face.text(14, .medium)).foregroundStyle(titleColor).lineLimit(1)
                        if case let .device(d) = item, d.repeating {
                            Image(systemName: "repeat").icon(9).foregroundStyle(y.dim)
                        }
                        if case let .event(e) = item, e.repeating {
                            Image(systemName: "repeat").icon(9).foregroundStyle(y.dim)
                        }
                    }
                    if let sub { Text(sub).font(Face.text(11.5)).foregroundStyle(y.dim).lineLimit(1) }
                }
                Spacer()
            }
            .padding(.vertical, 9)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("calendar.item.\(title)")
    }

    private var title: String {
        switch item {
        // A meeting somebody wrote a note about shows the note's name when it has its own.
        case let .device(d): return d.taskTitle ?? d.title
        default: return inlinePlain(item.title) { model.index.title(of: $0) }
        }
    }
    private var titleColor: Color {
        if case let .task(t) = item, t.done { return y.dim }
        return y.ink
    }
    private var sub: String? {
        switch item {
        case let .event(e): return e.location
        case let .device(d): return d.location ?? "From your calendar"
        case .task: return nil
        }
    }
    private var spine: Color {
        switch item {
        case let .event(e):
            if let w = e.workspaceTint, let c = swatchColor(w, dark: y.dark) { return c }
            return e.tint.flatMap { swatchColor($0, dark: y.dark) } ?? y.accent
        case let .device(d): return deviceColor(d.color, y: y)
        case let .task(t): return t.done ? y.dim : y.secondary
        }
    }
}

/// The bottom bar: the rail toggle and the one key that makes something.
///
/// The `+` lives here rather than in the title bar, where an earlier version parked it "in space the
/// title bar already had spare" — the primary action of the screen in the one corner a thumb cannot
/// reach, at a target under the minimum.
struct CalendarBar: View {
    @ObservedObject var cal: CalendarModel
    @Binding var railOpen: Bool
    let onAdd: () -> Void
    @Environment(\.y) private var y

    var body: some View {
        HStack(spacing: 14) {
            if cal.mode != .month {
                Button { withAnimation(.snappy) { railOpen.toggle() } } label: {
                    HStack(spacing: 7) {
                        Image(systemName: "tray.full").icon(13, .semibold)
                        Text("Tasks").font(Face.text(13, .bold))
                    }
                    .foregroundStyle(railOpen ? y.accentText : y.secondary)
                    .padding(.horizontal, 14).padding(.vertical, 11)
                    .background(Capsule().fill(railOpen ? y.accentFill : y.surfaceHigh))
                }
                .buttonStyle(.plain)
                // A control that toggles should say which way it is set, both to VoiceOver and to
                // anything driving the app.
                .accessibilityIdentifier("calendar.tasks")
                .accessibilityLabel("Tasks")
                .accessibilityValue(railOpen ? "shown" : "hidden")
            }
            Spacer()
            Button(action: onAdd) {
                Image(systemName: "plus").icon(19, .semibold)
                    .foregroundStyle(y.onAccent)
                    .frame(width: 52, height: 52)
                    .background(Circle().fill(y.accent))
            }.buttonStyle(.plain).accessibilityIdentifier("calendar.add").accessibilityLabel("New event")
        }
        .padding(.horizontal, Layout.pageMargin).padding(.bottom, 6)
    }
}
