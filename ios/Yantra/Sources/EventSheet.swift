import SwiftUI
import YantraCore

/// What the sheet opened on: an event to change, or a range waiting to be told what goes in it.
enum EventSheetTarget: Identifiable {
    case editing(String)
    /// A day, and optionally the range marked out on it.
    case creating(LocalDate, (LocalDateTime, LocalDateTime)?)

    var id: String {
        switch self {
        case let .editing(id): return "edit:\(id)"
        case let .creating(d, range): return "new:\(d)\(range.map { ":\($0.0)" } ?? "")"
        }
    }
}

/// Making or changing an event.
///
/// One sheet for both, because they are the same set of questions and a separate "new event" screen
/// is how the two drift into offering different fields.
struct EventSheet: View {
    let target: EventSheetTarget
    @ObservedObject var cal: CalendarModel
    let onDone: () -> Void

    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.dismiss) private var dismiss

    @State private var title = ""
    @State private var allDay = false
    @State private var start = Date()
    @State private var end = Date().addingTimeInterval(3600)
    @State private var location = ""
    @State private var color: String?
    @State private var reminder: Int?
    @State private var loaded = false

    private var editingId: String? { if case let .editing(id) = target { return id }; return nil }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    field("What") {
                        TextField("Event", text: $title)
                            .font(Face.text(16, .medium)).foregroundStyle(y.ink)
                            .textFieldStyle(.plain)
                    }

                    field("When") {
                        VStack(alignment: .leading, spacing: 10) {
                            Toggle(isOn: $allDay) {
                                Text("All day").font(Face.text(14)).foregroundStyle(y.secondary)
                            }
                            .tint(y.accent)
                            DatePicker("Starts", selection: $start,
                                       displayedComponents: allDay ? [.date] : [.date, .hourAndMinute])
                                .font(Face.text(14))
                            DatePicker("Ends", selection: $end, in: start...,
                                       displayedComponents: allDay ? [.date] : [.date, .hourAndMinute])
                                .font(Face.text(14))
                        }
                    }

                    field("Where") {
                        TextField("Optional", text: $location)
                            .font(Face.text(14)).foregroundStyle(y.ink).textFieldStyle(.plain)
                    }

                    field("Colour") {
                        // A closed set, and "none" is a real choice: an uncoloured event wears the
                        // accent, which is what an uncoloured thing looks like everywhere else.
                        HStack(spacing: 8) {
                            swatchButton(nil)
                            ForEach(LabelPalette.swatches, id: \.name) { s in swatchButton(s.name) }
                        }
                    }

                    field("Remind") {
                        HStack(spacing: 6) {
                            ForEach([nil, 0, 5, 15, 60] as [Int?], id: \.self) { m in
                                SelectChip(label: m == nil ? "Never" : m == 0 ? "At time" : "\(m!)m",
                                           selected: reminder == m) { reminder = m }
                            }
                        }
                    }

                    if let id = editingId {
                        Button(role: .destructive) {
                            model.write { try model.writerFor(id).deleteEvent(id) }
                            onDone(); dismiss()
                        } label: {
                            Text("Delete event").font(Face.text(14, .bold)).foregroundStyle(y.crimson)
                                .frame(maxWidth: .infinity).padding(.vertical, 13)
                                .background(RoundedRectangle(cornerRadius: Layout.buttonRadius).stroke(y.crimson.opacity(0.4), lineWidth: 1))
                        }.buttonStyle(.plain)
                    }
                    Spacer().frame(height: 30)
                }
                .padding(Layout.pageMargin)
            }
            .background(y.page.ignoresSafeArea())
            .navigationTitle(editingId == nil ? "New event" : "Event")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.foregroundStyle(y.secondary)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.font(Face.text(15, .bold)).foregroundStyle(y.accentText)
                        .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .task { load() }
    }

    private func field<C: View>(_ label: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            SectionLabel(text: label)
            content()
                .padding(.horizontal, 13).padding(.vertical, 11)
                .background(RoundedRectangle(cornerRadius: Layout.cardRadius).fill(y.surface))
                .overlay(RoundedRectangle(cornerRadius: Layout.cardRadius).stroke(y.tileBorder, lineWidth: 1))
        }
    }

    private func swatchButton(_ name: String?) -> some View {
        Button { color = name } label: {
            Circle()
                .fill(name.flatMap { swatchColor($0, dark: y.dark) } ?? y.accent)
                .frame(width: 22, height: 22)
                .overlay(Circle().stroke(color == name ? y.ink : .clear, lineWidth: 2))
        }.buttonStyle(.plain)
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        switch target {
        case let .editing(id):
            guard let e = model.index.nodes[id]?.event else { return }
            title = e.title
            allDay = e.time.allDay
            start = e.time.start.instant()
            // All-day ends are exclusive in the model and inclusive on screen, because the second is
            // what somebody picking "the 11th to the 13th" means.
            end = (e.time.allDay ? e.time.end.adding(days: -1) : e.time.end).instant()
            location = e.location ?? ""
            color = e.color
            reminder = e.reminderMin
        case let .creating(day, range):
            if let (from, to) = range {
                start = from.instant(); end = to.instant()
            } else {
                start = LocalDateTime(date: day, hour: 9).instant()
                end = LocalDateTime(date: day, hour: 10).instant()
            }
        }
    }

    private func save() {
        let t = title.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        let s = LocalDateTime.of(start)
        // Back the other way: inclusive on screen, exclusive in the model.
        let e = allDay ? LocalDateTime(date: LocalDate.of(end)).adding(days: 1)
                       : LocalDateTime.of(end)
        let time = EventTime(start: allDay ? LocalDateTime(date: s.date) : s,
                             end: max(e, allDay ? LocalDateTime(date: s.date).adding(days: 1) : s),
                             allDay: allDay)
        let loc = location.trimmingCharacters(in: .whitespaces)
        model.write {
            if let id = editingId {
                try model.writerFor(id).editEvent(id) { old in
                    var x = old
                    x.title = t; x.time = time
                    x.location = loc.isEmpty ? nil : loc
                    x.color = color; x.reminderMin = reminder
                    return x
                }
            } else {
                // An event belongs on a page like everything else. Inbox is where a thing with no
                // home goes, which is the same answer capture gives.
                guard let home = model.index.node(systemKey: SystemKey.inbox)?.id
                        ?? model.index.children(of: nil).first(where: { $0.type == NodeType.list })?.id else { return }
                _ = try model.writerFor(home).addEvent(to: home, title: t, time: time,
                                              location: loc.isEmpty ? nil : loc,
                                              color: color, reminderMin: reminder)
            }
        }
        onDone()
        dismiss()
    }
}
