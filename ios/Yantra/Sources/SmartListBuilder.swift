import SwiftUI
import YantraCore

/// Create or edit a smart list — `ui/smart/SmartListBuilder.kt`.
///
/// Editing is not a second screen: the same controls decide the same things, so an edit sheet
/// cannot drift from the one that made the list.
struct SmartListBuilder: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.dismiss) private var dismiss

    var editing: Node? = nil
    @Binding var path: NavigationPath

    @State private var name: String
    @State private var show: ShowMode = .open
    @State private var conds: [Cond] = []
    /// Branches the form has no control for, carried through untouched. See `DecodedRule.extras`.
    ///
    /// Mutable because choosing a starting point replaces the whole rule, extras included. Merely
    /// *opening* the sheet must preserve them, but "Start from" is an explicit request to begin
    /// again, and silently keeping a clause the form cannot show would be worse.
    @State private var extras: [Filter] = []
    /// Empty means everywhere — the absence of a clause, not a clause naming none.
    @State private var reach: Set<String> = []
    @State private var homeId: String?
    @State private var pickingLabelsFor: Int?
    @State private var loaded = false

    init(editing: Node? = nil, path: Binding<NavigationPath>, initialName: String = "") {
        self.editing = editing
        _path = path
        _name = State(initialValue: editing.flatMap { inlinePlain($0.title ?? "") } ?? initialName)
    }

    private var defs: [PropertyDef] { model.index.properties }
    private var labels: [LabelDef] { model.index.labels }
    private var lists: [Node] { model.index.children(of: nil).filter { $0.type == NodeType.list } }
    private var defsById: [String: PropertyDef] { Dictionary(defs.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a }) }
    private var labelsById: [String: LabelDef] { Dictionary(labels.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a }) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                YField(text: $name, placeholder: "Name — e.g. This week")
                    .accessibilityIdentifier("smart.name")
                startFrom
                showSection
                conditions
                homeSection
                YantraButton(label: editing == nil ? "Create smart list" : "Save changes", tone: .soft,
                             enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty) { save() }
                    .accessibilityIdentifier("smart.save")
            }
            .padding(.horizontal, 22).padding(.top, 20).padding(.bottom, 28)
        }
        .background(y.cardBg.ignoresSafeArea())
        .presentationDetents([.large])
        .onAppear(perform: load)
        .sheet(item: Binding(get: { pickingLabelsFor.map { LabelPick(index: $0) } },
                             set: { pickingLabelsFor = $0?.index })) { pick in
            LabelChecklist(
                all: labels,
                checked: Set(conds[safe: pick.index]?.labelIds ?? []),
                onCreate: { nm in
                    model.write { try model.writer.upsertLabel(nm) }
                    return model.index.labels.first { $0.name.caseInsensitiveCompare(nm) == .orderedSame }
                },
                onCancel: {
                    // A freshly-added card cancelled before picking anything is just noise.
                    if conds[safe: pick.index]?.labelIds.isEmpty == true { conds.remove(at: pick.index) }
                    pickingLabelsFor = nil
                },
                onApply: { selected in
                    if selected.isEmpty { conds.remove(at: pick.index) }
                    else { conds[pick.index].labelIds = Array(selected) }
                    pickingLabelsFor = nil
                })
        }
    }

    private struct LabelPick: Identifiable { let index: Int; var id: Int { index } }

    private var header: some View {
        HStack(spacing: 10) {
            YantraIcon(mark: .smartList, size: YantraIcons.medium, tint: y.accent)
            Text(editing == nil ? "New smart list" : "Edit smart list")
                .font(Face.display(22)).foregroundStyle(y.ink)
        }
    }

    // MARK: start from

    /// Quick-fills Show and the conditions below; nothing is locked in until Create.
    private var startFrom: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel(text: "Start from")
            YFlow(spacing: 8) {
                ForEach(SmartTemplate.allCases, id: \.self) { t in
                    let preset = t.preset(defs)
                    // A starting point lights up while the form still holds exactly it — computed
                    // from the live form, not from "which did you last press", so editing anything
                    // afterwards dims it again. It reports what the rule currently is.
                    //
                    // Only "current" when the form holds nothing this sheet cannot show; otherwise
                    // a rule with hidden clauses would claim to be a bare preset.
                    let selected = extras.isEmpty && show == preset.0 && conds == preset.1
                    SelectChip(label: t.label, selected: selected,
                               mark: selected ? .check : nil) {
                        show = preset.0; conds = preset.1; extras = []
                    }
                    .accessibilityIdentifier("smart.preset.\(t.rawValue)")
                }
            }
        }
    }

    private var showSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel(text: "Show")
            // Wrapping, because this is four and a fixed row would push the last off a narrow screen.
            YFlow(spacing: 8) {
                ForEach(ShowMode.allCases, id: \.self) { m in
                    SelectChip(label: m.label, selected: show == m) { show = m }
                        .accessibilityIdentifier("smart.show.\(m.rawValue)")
                }
            }
        }
    }

    // MARK: conditions

    private var conditions: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel(text: "Match all of")
            ForEach(Array(conds.enumerated()), id: \.element.id) { i, c in
                if c.isLabelCond {
                    LabelConditionCard(
                        names: c.labelIds.compactMap { labelsById[$0]?.name },
                        match: c.labelMatch,
                        onEdit: { pickingLabelsFor = i },
                        onMode: { conds[i].labelMatch = $0 },
                        onRemove: { conds.remove(at: i) })
                } else if let def = defsById[c.defId ?? ""] {
                    ConditionRow(def: def, cond: c,
                                 onChange: { conds[i] = $0 },
                                 onRemove: { conds.remove(at: i) })
                }
            }
            addCondition
            footnote
        }
    }

    private var addCondition: some View {
        Menu {
            if defs.isEmpty { Text("No properties yet") }
            ForEach(defs, id: \.id) { d in
                Button(d.name) { conds.append(SmartListRule.defaultCond(d)) }
            }
            Button("Labels") {
                conds.append(Cond())
                pickingLabelsFor = conds.count - 1
            }
        } label: {
            HStack(spacing: 6) {
                YantraIcon(mark: .add, size: YantraIcons.small, tint: y.accent)
                Text("Add condition").font(Face.text(14, .bold)).foregroundStyle(y.accentText)
            }.padding(.vertical, 4)
        }
        .accessibilityIdentifier("smart.addCondition")
    }

    @ViewBuilder
    private var footnote: some View {
        if conds.isEmpty, !extras.isEmpty {
            // The rule has clauses with no control here — Today's "due OR deadline". Saying "no
            // conditions" would be a plain lie about the person's own list, and "shows every open
            // task" doubly so. Name what is being kept, and say where it goes if they start over.
            Text("This view also uses a rule that can't be edited here — it is kept as it is. "
                 + "Choosing a starting point above replaces it.")
                .font(Face.text(12)).foregroundStyle(y.dim)
                .accessibilityIdentifier("smart.extrasNote")
        } else if conds.isEmpty {
            Text("No conditions — this list will show every \(show == .done ? "completed" : "open") task.")
                .font(Face.text(12)).foregroundStyle(y.dim)
        }
    }

    @ViewBuilder
    private var homeSection: some View {
        if !lists.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(text: "New tasks land in")
                Menu {
                    ForEach(lists) { l in
                        Button(inlinePlain(l.title ?? "Untitled")) { homeId = l.id }
                    }
                } label: {
                    DropChipLabel(homeId.flatMap { id in lists.first { $0.id == id } }
                        .map { inlinePlain($0.title ?? "Untitled") } ?? "Choose a list")
                }
                .accessibilityIdentifier("smart.home")
                Text("Quick-add here auto-tags new tasks to match this view.")
                    .font(Face.text(12)).foregroundStyle(y.dim)
            }
        }
    }

    // MARK: load and save

    private func load() {
        guard !loaded else { return }
        loaded = true
        homeId = editing.flatMap { model.index.smartLists[$0.id]?.homeParentId } ?? lists.first?.id
        guard let e = editing, let f = model.index.smartLists[e.id]?.filter else { return }
        let d = SmartListRule.decode(f)
        show = d.show; conds = d.conds; extras = d.extras; reach = Set(d.workspaces)
    }

    private func save() {
        let title = name.trimmingCharacters(in: .whitespaces)
        let filter = SmartListRule.encode(show: show, conds: conds, extras: extras, workspaces: reach)
        let sort = SmartListRule.sort(conds)
        let home = homeId ?? model.index.node(systemKey: SystemKey.inbox)?.id
        let apply = deriveApplyOnCreate(filter)
        model.write {
            let id: String
            if let e = editing {
                id = e.id
                try model.writer.renamePage(id, title)
            } else {
                id = try model.writer.createTopLevel(type: NodeType.smartList, title: title)
            }
            try model.writer.writeSmartList(SmartListDef(
                nodeId: id, filterJson: FilterJSON.encode(filter),
                sortJson: FilterJSON.encode(sort), homeParentId: home,
                applyOnCreateJson: apply.isEmpty ? nil : FilterJSON.encode(apply)))
            if editing == nil { path.append(Route.smart(id)) }
        }
        dismiss()
    }
}

// MARK: - one condition

private struct ConditionRow: View {
    let def: PropertyDef
    let cond: Cond
    var onChange: (Cond) -> Void
    var onRemove: () -> Void
    @Environment(\.y) private var y

    private var ops: [OpOption] { SmartListRule.ops(for: def.kind) }
    private var current: OpOption {
        ops.first { $0.op == cond.op && $0.dateRel == cond.dateRel && $0.bool == cond.bool }
            ?? ops.first ?? OpOption("is set", .isSet)
    }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 8) {
                Text(def.name).font(Face.text(15, .bold)).foregroundStyle(y.ink)
                YFlow(spacing: 8) {
                    Menu {
                        ForEach(ops, id: \.label) { o in Button(o.label) { pick(o) } }
                    } label: { DropChipLabel(current.label) }
                        .accessibilityIdentifier("smart.op.\(def.id)")
                    if SmartListRule.showsValue(cond.op) { value }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onRemove) {
                YantraIcon(mark: .close, size: YantraIcons.small, tint: y.dim).frame(width: 28, height: 28)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Remove condition")
            .accessibilityIdentifier("smart.remove.\(def.id)")
        }
        .padding(.leading, 14).padding(.trailing, 6).padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: Layout.cardRadius).fill(y.surfaceHigh))
    }

    @ViewBuilder
    private var value: some View {
        switch def.kind {
        case PropertyKind.select:
            Menu {
                ForEach(def.selectConfig.options, id: \.name) { o in
                    Button(o.name) { onChange(with { $0.text = o.name }) }
                }
            } label: { DropChipLabel(cond.text ?? "value") }
                .accessibilityIdentifier("smart.value.\(def.id)")
        case PropertyKind.number:
            ValueField(text: Binding(
                get: { cond.number.map { $0 == $0.rounded() ? String(Int($0)) : String($0) } ?? "" },
                set: { s in onChange(with { $0.number = Double(s) }) }),
                       placeholder: "0", numeric: true)
                .accessibilityIdentifier("smart.value.\(def.id)")
        case PropertyKind.checkbox, PropertyKind.date, PropertyKind.datetime:
            EmptyView()
        default:
            ValueField(text: Binding(
                get: { cond.text ?? "" },
                set: { s in onChange(with { $0.text = s.isEmpty ? nil : s }) }),
                       placeholder: "value", numeric: false)
                .accessibilityIdentifier("smart.value.\(def.id)")
        }
    }

    private func with(_ edit: (inout Cond) -> Void) -> Cond {
        var c = cond; edit(&c); return c
    }

    private func pick(_ o: OpOption) {
        // "is set" and "is empty" ask about presence, so any value beside them is dropped rather
        // than left to contradict the operator on screen.
        let clears = o.op == .isSet || o.op == .notSet
        onChange(with {
            $0.op = o.op; $0.dateRel = o.dateRel; $0.bool = o.bool
            if clears { $0.text = nil; $0.number = nil }
            else if def.kind == PropertyKind.select, $0.text == nil {
                $0.text = def.selectConfig.options.first?.name
            }
        })
    }
}

/// One or more labels, matched by Any/All. Tapping the body reopens the checklist; the mode toggle
/// only matters — and only shows — once two or more labels are picked.
private struct LabelConditionCard: View {
    let names: [String]
    let match: LabelMatchMode
    var onEdit: () -> Void
    var onMode: (LabelMatchMode) -> Void
    var onRemove: () -> Void
    @Environment(\.y) private var y

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Labels").font(Face.text(15, .bold)).foregroundStyle(y.ink)
                if names.isEmpty {
                    Text("Tap to choose labels…").font(Face.text(12)).foregroundStyle(y.dim)
                } else {
                    YFlow(spacing: 6) {
                        if names.count > 1 {
                            SelectChip(label: "Any", selected: match == .any) { onMode(.any) }
                            SelectChip(label: "All", selected: match == .all) { onMode(.all) }
                        }
                        ForEach(names, id: \.self) { n in
                            Text(n).font(Face.text(12, .semibold)).foregroundStyle(y.secondary)
                                .padding(.horizontal, 9).padding(.vertical, 5)
                                .background(RoundedRectangle(cornerRadius: Layout.blockRadius).fill(y.cardBg))
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture(perform: onEdit)
            Button(action: onRemove) {
                YantraIcon(mark: .close, size: YantraIcons.small, tint: y.dim).frame(width: 28, height: 28)
            }
            .buttonStyle(.plain).accessibilityLabel("Remove condition")
        }
        .padding(.leading, 14).padding(.trailing, 6).padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: Layout.cardRadius).fill(y.surfaceHigh))
        .accessibilityIdentifier("smart.labels")
    }
}

/// Multi-select checklist plus inline get-or-create — no need to leave the builder to make a new
/// label, and one made here is immediately usable to tag real tasks elsewhere.
private struct LabelChecklist: View {
    let all: [LabelDef]
    @State var checked: Set<String>
    var onCreate: (String) -> LabelDef?
    var onCancel: () -> Void
    var onApply: (Set<String>) -> Void
    @Environment(\.y) private var y
    @State private var query = ""
    @State private var local: [LabelDef] = []

    private var matches: [LabelDef] {
        query.isEmpty ? local : local.filter { $0.name.localizedCaseInsensitiveContains(query) }
    }
    private var exact: Bool {
        local.contains { $0.name.caseInsensitiveCompare(query.trimmingCharacters(in: .whitespaces)) == .orderedSame }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    YField(text: $query, placeholder: "Search or create…")
                    ForEach(matches, id: \.id) { l in
                        Button {
                            if checked.contains(l.id) { checked.remove(l.id) } else { checked.insert(l.id) }
                        } label: {
                            HStack(spacing: 10) {
                                YantraIcon(mark: checked.contains(l.id) ? .check : .task,
                                           size: YantraIcons.small,
                                           tint: checked.contains(l.id) ? y.accent : y.dim)
                                Text(l.name).font(Face.text(15)).foregroundStyle(y.ink)
                                Spacer()
                            }.padding(.vertical, 6).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                    let trimmed = query.trimmingCharacters(in: .whitespaces)
                    if !trimmed.isEmpty, !exact {
                        Button {
                            if let made = onCreate(trimmed) {
                                local.append(made); checked.insert(made.id); query = ""
                            }
                        } label: {
                            Text("Create \"\(trimmed)\"").font(Face.text(15, .bold))
                                .foregroundStyle(y.accentText).padding(.vertical, 10)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("smart.createLabel")
                    }
                }.padding(22)
            }
            .background(y.cardBg.ignoresSafeArea())
            .navigationTitle("Filter by labels")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") { onApply(checked) }.accessibilityIdentifier("smart.applyLabels")
                }
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear { if local.isEmpty { local = all } }
    }
}

// MARK: - small building blocks

private struct DropChipLabel: View {
    let text: String
    @Environment(\.y) private var y
    init(_ text: String) { self.text = text }
    var body: some View {
        HStack(spacing: 2) {
            Text(text).font(Face.text(13, .semibold)).foregroundStyle(y.secondary)
            YantraIcon(mark: .down, size: YantraIcons.small, tint: y.dim)
        }
        .padding(.leading, 12).padding(.trailing, 8).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: Layout.blockRadius).fill(y.cardBg))
        .overlay(RoundedRectangle(cornerRadius: Layout.blockRadius).stroke(y.tileBorder, lineWidth: 1))
    }
}

/// The sheet's text field. Full width, because sized-to-content leaves an empty field a few pixels
/// wide and only a tap at the very left edge reaches it.
private struct YField: View {
    @Binding var text: String
    let placeholder: String
    @Environment(\.y) private var y
    var body: some View {
        TextField(placeholder, text: $text)
            .font(Face.text(15)).foregroundStyle(y.ink).tint(y.accent)
            .submitLabel(.done)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 14).padding(.vertical, 13)
            .background(RoundedRectangle(cornerRadius: Layout.panelRadius).fill(y.surfaceHigh))
            .overlay(RoundedRectangle(cornerRadius: Layout.panelRadius).stroke(y.tileBorder, lineWidth: 1))
    }
}

private struct ValueField: View {
    @Binding var text: String
    let placeholder: String
    let numeric: Bool
    @Environment(\.y) private var y
    var body: some View {
        TextField(placeholder, text: $text)
            .font(Face.text(13)).foregroundStyle(y.ink).tint(y.accent)
            .keyboardType(numeric ? .decimalPad : .default)
            .frame(width: numeric ? 84 : 150)
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: Layout.blockRadius).fill(y.cardBg))
            .overlay(RoundedRectangle(cornerRadius: Layout.blockRadius).stroke(y.tileBorder, lineWidth: 1))
    }
}
