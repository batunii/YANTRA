import SwiftUI
import YantraCore

/// One surface for date, optional time and reminder — `DueSheet`.
struct DueSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.dismiss) private var dismiss
    let node: Node
    @State private var date: Date
    @State private var hasTime: Bool
    /// Every offset chosen, not one. Kept canonical so what the sheet shows is what the file gets.
    @State private var reminders: [Int]

    static let onTheDay = -540   // 09:00 on the day, for an all-day due

    init(node: Node) {
        self.node = node
        let d = node.dueDate ?? LocalDate.today().startOfDay()
        _date = State(initialValue: d)
        _hasTime = State(initialValue: node.dueHasTime)
        _reminders = State(initialValue: node.due?.reminders ?? [])
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Due").font(Face.display(22)).foregroundStyle(y.ink)
            HStack(spacing: 8) {
                quick("Today", .today()); quick("Tomorrow", LocalDate.today().adding(days: 1)); quick("Next week", LocalDate.today().adding(days: 7))
            }
            DatePicker("", selection: $date, displayedComponents: hasTime ? [.date, .hourAndMinute] : [.date])
                .datePickerStyle(.graphical).tint(y.accent).labelsHidden()
            Toggle(isOn: $hasTime) { Label("Time", systemImage: "clock").font(Face.text(14, .medium)).foregroundStyle(y.ink) }.tint(y.accent)
                .onChange(of: hasTime) { _, on in
                    // The offsets that make sense change with the shape of the due date: "30 min
                    // before" needs a time to be before, and "on the day at nine" only means
                    // anything without one. Keeping a stale offset would schedule a reminder the
                    // sheet no longer offers and cannot show.
                    if on {
                        reminders = reminders.filter { $0 != Self.onTheDay }
                        if reminders.isEmpty { reminders = [0] }
                    } else {
                        reminders = reminders.contains(Self.onTheDay) ? [Self.onTheDay] : []
                    }
                }
            // Reminders are chips rather than a menu, because there can be several now and a menu
            // that has to say "1 day before, 30 min before" in its own label is a control that
            // cannot show what it is set to. Each one toggles; what is on is what is lit.
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Label("Reminders", systemImage: "bell").font(Face.text(14, .medium)).foregroundStyle(y.ink)
                    Spacer()
                    Text(remindersLabel).font(Face.text(12)).foregroundStyle(y.muted)
                }
                HStack(spacing: 6) {
                    ForEach(offered, id: \.0) { offset, label in
                        SelectChip(label: label, selected: reminders.contains(offset), stretch: true) {
                            reminders = DueSpec.reminders(reminders.contains(offset)
                                                          ? reminders.filter { $0 != offset }
                                                          : reminders + [offset])
                        }
                    }
                }
            }
            HStack(spacing: 10) {
                YantraButton(label: "Cancel", tone: .quiet) { dismiss() }
                if node.due != nil { YantraButton(label: "Clear", tone: .quiet) { model.write { try model.writer.setDue(node.id, nil) }; dismiss() } }
                YantraButton(label: "Set", tone: .soft) {
                    let value: DueValue = hasTime ? .at(date) : .allDay(.of(date))
                    model.write { try model.writer.setDue(node.id, DueSpec(value, reminders: reminders)) }
                    if !reminders.isEmpty { Task { _ = await Notifications.shared.requestPermission() } }
                    dismiss()
                }
            }
        }
        .padding(22).background(y.cardBg.ignoresSafeArea())
        .presentationDetents([.large])
    }

    /// The offsets worth offering, which depend on whether the due date has a time in it.
    private var offered: [(Int, String)] {
        hasTime
            ? [(0, "On time"), (30, "30 min"), (60, "1 hour"), (1440, "1 day")]
            : [(Self.onTheDay, "On the day")]
    }

    private var remindersLabel: String {
        if reminders.isEmpty { return "None" }
        return reminders.count == 1 ? "1 set" : "\(reminders.count) set"
    }

    private func quick(_ label: String, _ d: LocalDate) -> some View {
        SelectChip(label: label, selected: LocalDate.of(date) == d, stretch: true) {
            let c = Calendar.current.dateComponents([.hour, .minute], from: date)
            date = Calendar.current.date(bySettingHour: c.hour ?? 0, minute: c.minute ?? 0, second: 0, of: d.startOfDay()) ?? d.startOfDay()
        }
    }
}

/// Add a label: search or create — `LabelPickerDialog`.
struct LabelPicker: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.dismiss) private var dismiss
    let node: Node
    @State private var query = ""
    @FocusState private var focused: Bool

    private var known: [String] {
        var names = Set(model.index.labels.map(\.name))
        for n in model.index.nodes.values { for l in n.labels { names.insert(l) } }
        return names.sorted { $0.lowercased() < $1.lowercased() }
    }
    private var matches: [String] { known.filter { query.isEmpty || $0.localizedCaseInsensitiveContains(query) } }
    private var canCreate: Bool { !query.trimmingCharacters(in: .whitespaces).isEmpty && !known.contains { $0.caseInsensitiveCompare(query) == .orderedSame } }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Add label").font(Face.display(22)).foregroundStyle(y.ink)
            TextField("Search or create…", text: $query).font(Face.text(15)).foregroundStyle(y.ink).focused($focused).autocorrectionDisabled()
                .padding(14).background(RoundedRectangle(cornerRadius: 12).fill(y.surfaceHigh)).overlay(RoundedRectangle(cornerRadius: 12).stroke(y.tileBorder, lineWidth: 1))
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(matches, id: \.self) { name in
                        Button { attach(name) } label: {
                            HStack(spacing: 12) {
                                Circle().fill(LabelPalette.color(name, registry: model.index.labels, dark: y.dark)).frame(width: 10, height: 10)
                                Text(name).font(Face.text(15, .medium)).foregroundStyle(y.ink)
                                Spacer()
                                if node.labels.contains(name) { YantraIcon(mark: .check, size: YantraIcons.small, tint: y.accent) }
                            }.padding(.vertical, 10)
                        }.buttonStyle(.plain)
                    }
                    if canCreate {
                        Button { attach(query.trimmingCharacters(in: .whitespaces)) } label: {
                            HStack(spacing: 12) {
                                Circle().fill(LabelPalette.color(query, registry: [], dark: y.dark)).frame(width: 10, height: 10)
                                Text("Create \"\(query.trimmingCharacters(in: .whitespaces))\"").font(Face.text(15, .bold)).foregroundStyle(y.accentText)
                            }.padding(.vertical, 10)
                        }.buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(22).background(y.cardBg.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .onAppear { focused = true }
    }

    private func attach(_ name: String) {
        model.write {
            try model.writer.editTask(node.id) { t in var x = t; if !x.labels.contains(name) { x.labels.append(name) }; return x }
            try model.writer.upsertLabel(name)
        }
        dismiss()
    }
}

