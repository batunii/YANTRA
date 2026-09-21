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
    @State private var reminder: Int?

    static let onTheDay = -540   // 09:00 on the day, for an all-day due

    init(node: Node) {
        self.node = node
        let d = node.dueDate ?? LocalDate.today().startOfDay()
        _date = State(initialValue: d)
        _hasTime = State(initialValue: node.dueHasTime)
        _reminder = State(initialValue: node.due?.reminderMin)
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
                    // Switching to all-day resets a timed offset; setting a time defaults to "On time".
                    if on { if reminder == nil || reminder == Self.onTheDay { reminder = 0 } } else if reminder != nil, reminder != Self.onTheDay { reminder = nil }
                }
            HStack {
                Label("Reminder", systemImage: "bell").font(Face.text(14, .medium)).foregroundStyle(y.ink)
                Spacer()
                Menu {
                    Button("None") { reminder = nil }
                    if hasTime {
                        Button("On time") { reminder = 0 }
                        Button("30 min before") { reminder = 30 }
                        Button("1 hour before") { reminder = 60 }
                        Button("1 day before") { reminder = 1440 }
                    } else {
                        Button("On the day (9:00)") { reminder = Self.onTheDay }
                    }
                } label: {
                    Text(reminderLabel).font(Face.text(13, .bold)).foregroundStyle(y.accentText).padding(.horizontal, 12).padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 10).fill(y.accentFill))
                }
            }
            HStack(spacing: 10) {
                YantraButton(label: "Cancel", tone: .quiet) { dismiss() }
                if node.due != nil { YantraButton(label: "Clear", tone: .quiet) { model.write { try model.writer.setDue(node.id, nil) }; dismiss() } }
                YantraButton(label: "Set", tone: .soft) {
                    let value: DueValue = hasTime ? .at(date) : .allDay(.of(date))
                    model.write { try model.writer.setDue(node.id, DueSpec(value, reminderMin: reminder)) }
                    if reminder != nil { Task { _ = await Notifications.shared.requestPermission() } }
                    dismiss()
                }
            }
        }
        .padding(22).background(y.cardBg.ignoresSafeArea())
        .presentationDetents([.large])
    }

    private var reminderLabel: String {
        switch reminder {
        case nil: return "None"; case 0: return "On time"; case 30: return "30 min before"; case 60: return "1 hour before"; case 1440: return "1 day before"
        case Self.onTheDay: return "On the day (9:00)"; case let m?: return "\(m) min before"
        }
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
                                if node.labels.contains(name) { Image(systemName: "checkmark").foregroundStyle(y.accent) }
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

/// Create or edit a smart list — `SmartListBuilderSheet`: presets, Show, conditions, where new tasks land.
struct SmartListBuilder: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.dismiss) private var dismiss
    var editing: Node? = nil
    @Binding var path: NavigationPath
    @State private var name: String
    @State private var show = 0                    // Open, Started, All, Completed
    @State private var dueToday = false
    @State private var priority: String? = nil
    @State private var labels: [String] = []
    @State private var landsIn: String?

    init(editing: Node? = nil, path: Binding<NavigationPath>, initialName: String = "") {
        self.editing = editing; _path = path
        _name = State(initialValue: editing?.title ?? initialName)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 8) { Image(systemName: "sparkles").foregroundStyle(y.accent); Text(editing == nil ? "New smart list" : "Edit smart list").font(Face.display(22)).foregroundStyle(y.ink) }
                TextField("Name — e.g. This week", text: $name).font(Face.text(15)).foregroundStyle(y.ink)
                    .padding(14).background(RoundedRectangle(cornerRadius: 12).fill(y.surfaceHigh)).overlay(RoundedRectangle(cornerRadius: 12).stroke(y.tileBorder, lineWidth: 1))
                SectionLabel(text: "Start from")
                HStack(spacing: 8) {
                    SelectChip(label: "Due today", selected: dueToday && priority == nil && labels.isEmpty && show == 0) { dueToday = true; priority = nil; labels = []; show = 0 }
                    SelectChip(label: "High priority", selected: priority == "High" && !dueToday && labels.isEmpty && show == 0) { priority = "High"; dueToday = false; labels = []; show = 0 }
                    SelectChip(label: "All open tasks", selected: !dueToday && priority == nil && labels.isEmpty && show == 0) { dueToday = false; priority = nil; labels = []; show = 0 }
                }
                SectionLabel(text: "Show")
                HStack(spacing: 8) { ForEach(Array(["Open", "Started", "All", "Completed"].enumerated()), id: \.offset) { i, l in SelectChip(label: l, selected: show == i, stretch: true) { show = i } } }
                SectionLabel(text: "Match all of")
                VStack(alignment: .leading, spacing: 8) {
                    condition("Due", value: dueToday ? "today or earlier" : "any", options: ["any", "today or earlier"]) { dueToday = $0 == "today or earlier" }
                    condition("Priority", value: priority ?? "any", options: ["any", "High", "Medium", "Low"]) { priority = $0 == "any" ? nil : $0 }
                    labelsCondition
                }
                SectionLabel(text: "New tasks land in")
                Menu {
                    ForEach(model.index.children(of: nil).filter { $0.type == NodeType.list }) { l in Button(inlinePlain(l.title ?? "Untitled")) { landsIn = l.id } }
                } label: {
                    HStack { Text(landsIn.flatMap { model.index.nodes[$0]?.title }.map { inlinePlain($0) } ?? "Inbox").font(Face.text(14, .bold)).foregroundStyle(y.accentText); Image(systemName: "chevron.down").font(.system(size: 12, weight: .bold)).foregroundStyle(y.accentText) }
                        .padding(.horizontal, 12).padding(.vertical, 9).background(RoundedRectangle(cornerRadius: 10).fill(y.accentFill))
                }
                Text("Quick-add here auto-tags new tasks to match this view.").font(Face.text(12)).foregroundStyle(y.dim)
                YantraButton(label: editing == nil ? "Create smart list" : "Save changes", tone: .soft, enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty) { save() }
            }.padding(22)
        }
        .background(y.cardBg.ignoresSafeArea())
        .presentationDetents([.large])
        .onAppear { load() }
    }

    private var labelsCondition: some View {
        HStack {
            Text("Labels").font(Face.text(14, .semibold)).foregroundStyle(y.ink)
            Spacer()
            Menu {
                let known = Set(model.index.nodes.values.flatMap(\.labels)).sorted()
                ForEach(known, id: \.self) { l in Button(labels.contains(l) ? "✓ \(l)" : l) { if let i = labels.firstIndex(of: l) { labels.remove(at: i) } else { labels.append(l) } } }
            } label: {
                Text(labels.isEmpty ? "Tap to choose labels…" : labels.map { "#\($0)" }.joined(separator: " ")).font(Face.text(13, .bold)).foregroundStyle(y.accentText)
                    .padding(.horizontal, 12).padding(.vertical, 8).background(RoundedRectangle(cornerRadius: 10).fill(y.accentFill))
            }
        }.padding(14).background(RoundedRectangle(cornerRadius: 14).fill(y.surfaceHigh))
    }

    private func condition(_ title: String, value: String, options: [String], pick: @escaping (String) -> Void) -> some View {
        HStack {
            Text(title).font(Face.text(14, .semibold)).foregroundStyle(y.ink)
            Spacer()
            Menu { ForEach(options, id: \.self) { o in Button(o) { pick(o) } } } label: {
                Text(value).font(Face.text(13, .bold)).foregroundStyle(y.accentText).padding(.horizontal, 12).padding(.vertical, 8).background(RoundedRectangle(cornerRadius: 10).fill(y.accentFill))
            }
        }.padding(14).background(RoundedRectangle(cornerRadius: 14).fill(y.surfaceHigh))
    }

    private func load() {
        guard let e = editing, let def = model.index.smartLists[e.id], let f = def.filter else { return }
        landsIn = def.homeParentId
        func walk(_ f: Filter) {
            switch f {
            case let .all(fs): fs.forEach(walk)
            case let .anyOf(fs): fs.forEach(walk)
            case .done(true): show = 3
            case .inProgress(true): show = 1
            case let .prop(defId, op, text, _, _, _, dateRel):
                if defId == BuiltIns.due, op == .lte, dateRel == .todayEnd { dueToday = true }
                if defId == BuiltIns.priority, op == .eq, let t = text { priority = t }
            case let .hasLabel(id): labels.append(id.components(separatedBy: ":label:").last ?? id)
            default: break
            }
        }
        walk(f)
        if case let .all(fs) = f, !fs.contains(where: { if case .done = $0 { return true }; if case .inProgress = $0 { return true }; return false }) { show = 2 }
    }

    private func save() {
        var clauses: [Filter] = [.type(NodeType.task)]
        switch show { case 0: clauses.append(.done(false)); case 1: clauses.append(.inProgress(true)); case 3: clauses.append(.done(true)); default: break }
        if dueToday { clauses.append(.anyOf([.prop(defId: BuiltIns.due, op: .lte, dateRel: .todayEnd), .prop(defId: BuiltIns.deadline, op: .lte, dateRel: .todayEnd)])) }
        if let p = priority { clauses.append(.prop(defId: BuiltIns.priority, op: .eq, text: p)) }
        if !labels.isEmpty { clauses.append(.anyOf(labels.map { .hasLabel(":label:\($0.lowercased())") })) }
        let filter: Filter = .all(clauses)
        let sort: [SortSpec] = dueToday ? [SortSpec(by: .propDate, defId: BuiltIns.due)] : [SortSpec(by: .created, desc: true)]
        let home = landsIn ?? model.index.node(systemKey: SystemKey.inbox)?.id
        let apply = deriveApplyOnCreate(filter)
        model.write {
            let id: String
            if let e = editing { id = e.id; try model.writer.renamePage(id, name.trimmingCharacters(in: .whitespaces)) }
            else { id = try model.writer.createTopLevel(type: NodeType.smartList, title: name.trimmingCharacters(in: .whitespaces)) }
            try model.writer.writeSmartList(SmartListDef(nodeId: id, filterJson: FilterJSON.encode(filter), sortJson: FilterJSON.encode(sort), homeParentId: home,
                                                          applyOnCreateJson: apply.isEmpty ? nil : FilterJSON.encode(apply)))
            if editing == nil { path.append(Route.smart(id)) }
        }
        dismiss()
    }
}
