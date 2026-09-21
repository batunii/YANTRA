import SwiftUI
import YantraCore

/// The ruler and what sits against it, for one day or several.
///
/// Named `DayTimeline` rather than `TimelineView`, which is SwiftUI's own.
struct DayTimeline: View {
    @ObservedObject var cal: CalendarModel
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Binding var path: NavigationPath
    let railOpen: Bool
    /// True once the window is wide enough for the rail to be a column beside the day.
    let wide: Bool
    let onCloseRail: () -> Void
    let onOpenEvent: (String) -> Void
    /// A range marked on an empty day, waiting to be told what goes in it.
    let onMark: (LocalDate, LocalDateTime, LocalDateTime) -> Void

    /// The width of the hour column. Wide enough for "00:00" and no wider.
    private let gutter: CGFloat = 44

    private var days: [LocalDate] {
        cal.mode == .day ? [cal.selected] : TimelineLayout.span(cal.selected, cal.daysAcross)
    }

    /// Where the day opens. Midnight is seven hours of nothing before the first thing anybody has
    /// on, so the ruler starts at the top of the working day and you scroll *up* for the small hours.
    private let openingHour = 7

    var body: some View {
        HStack(spacing: 0) {
            VStack(spacing: 0) {
                dayHeaders
                allDayBar
                ScrollViewReader { proxy in
                    ScrollView {
                        HStack(alignment: .top, spacing: 0) {
                            HourGutter(hourHeight: cal.hourHeight).frame(width: gutter)
                            ForEach(days, id: \.self) { day in
                                DayColumn(day: day, items: cal.items(day), hourHeight: cal.hourHeight,
                                          onOpenEvent: onOpenEvent,
                                          onOpenNode: { path.append(Route.node($0)) },
                                          onMark: { from, to in onMark(day, from, to) })
                                if day != days.last { Divider().frame(width: 0.5).overlay(y.hairline) }
                            }
                        }
                        // One anchor per hour, so the opening scroll has something to land on.
                        .overlay(alignment: .top) {
                            VStack(spacing: 0) {
                                ForEach(0..<24, id: \.self) { h in
                                    Color.clear.frame(height: cal.hourHeight).id("h\(h)")
                                }
                            }
                            .allowsHitTesting(false)
                        }
                    }
                    .onAppear { proxy.scrollTo("h\(openingHour)", anchor: .top) }
                }
            }
            // Yield the width the rail needs rather than pushing it off the edge: a vertical
            // ScrollView will happily take every point offered, and the rail is a fixed column.
            .frame(maxWidth: .infinity)
            // Pinch to zoom the hour. Clamped, so a pinch can be enthusiastic without producing a
            // timeline nobody can read.
            .gesture(MagnifyGesture().onChanged { g in
                let next = CalendarModel.loadHourHeight() * g.magnification
                cal.hourHeight = min(max(next, CalendarModel.minHourHeight), CalendarModel.maxHourHeight)
            })

            if railOpen, wide {
                Divider().frame(width: 0.5).overlay(y.hairline)
                CalendarTaskRail(cal: cal).frame(width: 248)
            }
        }
        // On a phone the rail is a sheet, not a column. It is the same rail answering the same
        // question; 210 points of it beside a 390-point screen would leave the day it exists to
        // help with too little room to read, which is the opposite of the point.
        .sheet(isPresented: Binding(get: { railOpen && !wide }, set: { if !$0 { onCloseRail() } })) {
            CalendarTaskRail(cal: cal)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }

    /// Which day each column is. A week with no dates on it is a grid of identical columns, and the
    /// heading above only names the span.
    @ViewBuilder
    private var dayHeaders: some View {
        if days.count > 1 {
            HStack(alignment: .top, spacing: 0) {
                Color.clear.frame(width: gutter, height: 1)
                ForEach(days, id: \.self) { day in
                    let isToday = day == .today()
                    VStack(spacing: 2) {
                        Text(weekdayLetter(day)).font(Face.mono(9.5, bold: true)).foregroundStyle(y.dim)
                        Text("\(day.day)")
                            .font(Face.text(14, isToday ? .bold : .regular))
                            .foregroundStyle(isToday ? y.accentText : y.ink)
                            .frame(width: 26, height: 26)
                            .background(Circle().fill(isToday ? y.accentFill : .clear))
                    }
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                    // Tapping a column heading is how you get from a week to that day.
                    .onTapGesture { cal.selected = day; cal.mode = .day }
                }
            }
            .padding(.vertical, 6)
            Divider().frame(height: 0.5).overlay(y.hairline)
        }
    }

    private func weekdayLetter(_ d: LocalDate) -> String {
        let f = DateFormatter(); f.dateFormat = "EEE"
        return f.string(from: d.startOfDay()).uppercased()
    }

    /// All-day, cross-day, and anything with no time of its own, as chips above the ruler.
    private var allDayBar: some View {
        let chips = days.flatMap { d in TimelineLayout.forDay(cal.items(d), day: d).allDay }
        return Group {
            if !chips.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(chips) { item in
                            Text(inlinePlain(item.title) { model.index.title(of: $0) })
                                .font(Face.text(11.5, .medium)).lineLimit(1)
                                .foregroundStyle(y.ink)
                                .padding(.horizontal, 9).padding(.vertical, 5)
                                .background(RoundedRectangle(cornerRadius: 7).fill(y.surfaceHigh))
                                .onTapGesture {
                                    if case let .event(e) = item { onOpenEvent(e.nodeId) }
                                    if case let .task(t) = item { path.append(Route.node(t.nodeId)) }
                                }
                        }
                    }
                    .padding(.horizontal, Layout.pageMargin).padding(.vertical, 7)
                }
                Divider().frame(height: 0.5).overlay(y.hairline)
            }
        }
    }
}

/// The hours down the left. Drawn once for every column, because a ruler per column would be a
/// ruler you have to check against its neighbour.
struct HourGutter: View {
    let hourHeight: CGFloat
    @Environment(\.y) private var y
    var body: some View {
        VStack(alignment: .trailing, spacing: 0) {
            ForEach(0..<24, id: \.self) { h in
                // The label sits at the top of its hour, which is the line it names.
                Text(String(format: "%02d:00", h))
                    .font(Face.mono(9.5)).foregroundStyle(y.dim)
                    .frame(height: hourHeight, alignment: .top)
                    .padding(.trailing, 6)
            }
        }
    }
}

struct DayColumn: View {
    let day: LocalDate
    let items: [DayItem]
    let hourHeight: CGFloat
    let onOpenEvent: (String) -> Void
    let onOpenNode: (String) -> Void
    let onMark: (LocalDateTime, LocalDateTime) -> Void

    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @State private var markFrom: Int?
    @State private var markTo: Int?

    private var minuteHeight: CGFloat { hourHeight / 60 }

    var body: some View {
        let layout = TimelineLayout.forDay(items, day: day)
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                // The ruler, laid out rather than offset. Twenty-four rules positioned by hand at
                // half-point heights land on different sub-pixels as the hour grows and some of
                // them stop being drawn at all; a stack gives every hour the same treatment.
                VStack(spacing: 0) {
                    ForEach(0..<24, id: \.self) { _ in
                        Rectangle().fill(y.hairline).frame(height: 0.5)
                        Spacer(minLength: 0)
                    }
                }
                .frame(height: CGFloat(24) * hourHeight)
                // Now, on today only. A line across a day that is not today is a line about nothing.
                if day == .today() {
                    let minute = LocalDateTime.of(Date()).secondOfDay / 60
                    Rectangle().fill(y.crimson).frame(height: 1.5)
                        .offset(y: CGFloat(minute) * minuteHeight)
                }
                // The range being marked out, while a finger is down.
                if let a = markFrom, let b = markTo {
                    let top = CGFloat(min(a, b)) * minuteHeight
                    let height = CGFloat(abs(b - a)) * minuteHeight
                    RoundedRectangle(cornerRadius: 6).fill(y.accentFill)
                        .overlay(RoundedRectangle(cornerRadius: 6).stroke(y.accentBorder, lineWidth: 1))
                        .frame(height: max(height, 8)).offset(y: top)
                }
                ForEach(layout.blocks) { block in
                    let width = (geo.size.width - 6) / CGFloat(block.columns)
                    TimelineBlock(block: block, onOpenEvent: onOpenEvent, onOpenNode: onOpenNode)
                        .frame(width: max(width - 3, 24),
                               height: max(CGFloat(block.endMinute - block.startMinute) * minuteHeight - 2, 16))
                        .offset(x: 3 + CGFloat(block.column) * width, y: CGFloat(block.startMinute) * minuteHeight)
                }
            }
            .frame(height: CGFloat(24) * hourHeight)
            .contentShape(Rectangle())
            // Mark a range, then say what goes in it. The gesture only makes sense on empty space,
            // so a block swallows the tap before this sees it.
            .gesture(DragGesture(minimumDistance: 12)
                .onChanged { g in
                    let snap = { (p: CGFloat) in max(0, min(Int((p / minuteHeight) / 15) * 15, TimelineLayout.minutesInDay)) }
                    if markFrom == nil { markFrom = snap(g.startLocation.y) }
                    markTo = snap(g.location.y)
                }
                .onEnded { _ in
                    defer { markFrom = nil; markTo = nil }
                    guard let a = markFrom, let b = markTo, a != b else { return }
                    let lo = min(a, b), hi = max(a, b)
                    onMark(LocalDateTime(date: day, hour: lo / 60, minute: lo % 60),
                           LocalDateTime(date: day, hour: hi / 60, minute: hi % 60))
                })
        }
        .frame(height: CGFloat(24) * hourHeight)
        .frame(maxWidth: .infinity)
    }
}

struct TimelineBlock: View {
    let block: TimedBlock
    let onOpenEvent: (String) -> Void
    let onOpenNode: (String) -> Void
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y

    var body: some View {
        let short = block.endMinute - block.startMinute <= 30
        Button {
            switch block.item {
            case let .event(e): onOpenEvent(e.nodeId)
            case let .device(d): if let t = d.taskId { onOpenNode(t) }
            case let .task(t): onOpenNode(t.nodeId)
            }
        } label: {
            HStack(alignment: .top, spacing: 6) {
                RoundedRectangle(cornerRadius: 1.5).fill(accent).frame(width: 3)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(Face.text(short ? 11 : 12, .medium)).foregroundStyle(y.ink).lineLimit(short ? 1 : 2)
                    if !short, let sub { Text(sub).font(Face.text(10)).foregroundStyle(y.dim).lineLimit(1) }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 5).padding(.vertical, 3)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: 6).fill(fill))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(accent.opacity(0.35), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private var title: String {
        if case let .device(d) = block.item { return d.taskTitle ?? d.title }
        return inlinePlain(block.item.title) { model.index.title(of: $0) }
    }
    private var sub: String? {
        switch block.item {
        case let .event(e): return e.location
        case let .device(d): return d.location
        case .task: return nil
        }
    }
    private var accent: Color {
        switch block.item {
        case let .event(e): return e.tint.flatMap { swatchColor($0, dark: y.dark) } ?? y.accent
        case let .device(d): return deviceColor(d.color, y: y)
        case let .task(t): return t.done ? y.dim : y.secondary
        }
    }
    private var fill: Color { accent.opacity(y.dark ? 0.16 : 0.12) }
}

/// The shelf beside the day.
///
/// `CalendarTaskRail` rather than `TaskRail`, which the task page already uses for the list of
/// siblings beside a page. Different rails, different questions.
///
/// The rail is what the day view is *for*: a calendar can only draw what already has a date, so the
/// task most in need of a time is the one it cannot show, and a rail you have to go and find does
/// not answer that.
struct CalendarTaskRail: View {
    @ObservedObject var cal: CalendarModel
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @State private var shelf: RailBucket = .today

    private var shelves: [RailBucket: [Node]] {
        let open = model.index.nodes.values.filter { $0.type == NodeType.task && !$0.done }
        return railShelves(Array(open))
    }

    var body: some View {
        let all = shelves
        VStack(alignment: .leading, spacing: 0) {
            // Every shelf, including the empty ones, so the tabs do not move about as the day goes
            // on. A control whose buttons change position depending on your data cannot be learned.
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(RailBucket.allCases, id: \.self) { b in
                        Button { shelf = b } label: {
                            Text(b.label).font(Face.text(11.5, shelf == b ? .bold : .regular))
                                .lineLimit(1).fixedSize()
                                .foregroundStyle(shelf == b ? y.accentText : y.muted)
                                .padding(.horizontal, 7).padding(.vertical, 6)
                                .background(RoundedRectangle(cornerRadius: 7).fill(shelf == b ? y.accentFill : .clear))
                        }.buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 8).padding(.vertical, 8)
            }
            Divider().frame(height: 0.5).overlay(y.hairline)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    let list = all[shelf] ?? []
                    if list.isEmpty {
                        Text(shelf == .undated ? "Everything has a date" : "Nothing here")
                            .font(Face.text(12)).foregroundStyle(y.dim).padding(12)
                    }
                    ForEach(list) { t in
                        Button { give(t) } label: {
                            HStack(spacing: 8) {
                                Circle().stroke(y.dim, lineWidth: 1).frame(width: 9, height: 9)
                                Text(inlinePlain(t.title ?? "") { model.index.title(of: $0) })
                                    .font(Face.text(12.5)).foregroundStyle(y.ink).lineLimit(2)
                                    .multilineTextAlignment(.leading)
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, 10).padding(.vertical, 8)
                        }.buttonStyle(.plain)
                    }
                    Spacer().frame(height: 60)
                }
            }
        }
        .background(y.surface)
    }

    /// Gives a task the day on screen. The rail's whole job in one tap: a task with no date is
    /// invisible on a calendar, and this is how it stops being.
    private func give(_ task: Node) {
        model.write {
            // Nine in the morning on the selected day, which is where a day starts for most people,
            // and a time you can then drag rather than a date you have to open a sheet to set.
            let at = LocalDateTime(date: cal.selected, hour: 9)
            try model.writer.setDue(task.id, DueSpec(.at(at.instant()), duration: .hours(1)))
        }
    }
}
