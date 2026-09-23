import SwiftUI
import YantraCore

/// What a list looks like: its mark or its emoji, wearing its colour.
///
/// One view because the answer has to be the same in both places it is asked. The row on Home and
/// the list's own page header have to agree, or a list looks like one thing in the list of lists
/// and another thing when you open it.
///
/// **The colour applies differently depending on whether there is an emoji, and that is the point
/// rather than a compromise.** A drawn mark is a single-colour shape, so the colour is its tint. An
/// emoji brings its own colours and cannot be tinted at all — so the colour becomes a disc behind
/// it. Without that, picking a colour for a list that has an emoji would be accepted, stored, and
/// invisible: the app would have taken a choice and done nothing with it, which is worse than not
/// offering it.
struct ListGlyph: View {
    let icon: String?
    let color: String?
    var smart: Bool = false
    var size: CGFloat = 34
    @Environment(\.y) private var y

    private var tint: Color { LabelPalette.swatchColor(color, dark: y.dark) ?? y.accent }

    var body: some View {
        ZStack {
            if let icon, !icon.isEmpty {
                // The disc is the colour's only way to show on something it cannot tint.
                Circle().fill(tint.opacity(y.dark ? 0.22 : 0.16))
                Text(icon).font(.system(size: size * 0.52))
            } else {
                RoundedRectangle(cornerRadius: size * 0.29).fill(tint.opacity(0.18))
                YantraIcon(mark: smart ? .smartList : .list, size: size * 0.62, tint: tint)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// How a list looks: its icon and its colour, in one sheet.
///
/// One sheet rather than two, because they are one decision — you are deciding what the list looks
/// like, and seeing the colour without the emoji it sits behind would be choosing half of it blind.
struct ListLookSheet: View {
    let nodeId: String
    let title: String
    let smart: Bool
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.dismiss) private var dismiss

    @State private var icon: String?
    @State private var color: String?
    @State private var typed = ""
    @State private var loaded = false

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 8)

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack(spacing: 12) {
                        ListGlyph(icon: icon, color: color, smart: smart, size: 44)
                        Text(title).font(Face.display(18)).foregroundStyle(y.ink).lineLimit(1)
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        SectionLabel(text: "Icon")
                        LazyVGrid(columns: columns, spacing: 8) {
                            // "None" first, drawn as the mark it goes back to, so the choice is
                            // between two things you can see rather than a thing and an absence.
                            cell(selected: icon == nil) { icon = nil } content: {
                                ListGlyph(icon: nil, color: color, smart: smart, size: 30)
                            }
                            ForEach(ListIcon.suggested, id: \.self) { emoji in
                                cell(selected: icon == emoji) { icon = emoji } content: {
                                    Text(emoji).font(.system(size: 20))
                                }
                            }
                        }
                        // The grid is a set somebody chose, which is the right default and the wrong
                        // limit — so anything the keyboard can produce is allowed beside it.
                        HStack(spacing: 8) {
                            TextField("Or type one", text: $typed)
                                .font(Face.text(15)).textFieldStyle(.plain)
                                .onChange(of: typed) { _, t in
                                    if let cleaned = ListIcon.clean(t) { icon = cleaned; typed = cleaned }
                                }
                            if icon != nil {
                                Button("Clear") { icon = nil; typed = "" }
                                    .font(Face.text(13, .bold)).foregroundStyle(y.accentText)
                            }
                        }
                        .padding(.horizontal, 12).padding(.vertical, 10)
                        .background(RoundedRectangle(cornerRadius: Layout.cardRadius).fill(y.surface))
                        .overlay(RoundedRectangle(cornerRadius: Layout.cardRadius).stroke(y.tileBorder, lineWidth: 1))
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        SectionLabel(text: "Colour")
                        Text(icon != nil ? "Sits behind the emoji" : "Colours the mark")
                            .font(Face.text(12)).foregroundStyle(y.muted)
                        HStack(spacing: 10) {
                            swatch(nil)
                            ForEach(LabelPalette.swatches, id: \.name) { s in swatch(s.name) }
                        }
                    }
                    Spacer().frame(height: 20)
                }
                .padding(Layout.pageMargin)
            }
            .background(y.page.ignoresSafeArea())
            .navigationTitle("How it looks")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.foregroundStyle(y.secondary)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.font(Face.text(15, .bold)).foregroundStyle(y.accentText)
                }
            }
        }
        .task {
            guard !loaded else { return }
            loaded = true
            let page = model.storeFor(nodeId).readPage(nodeId)
            icon = page?.icon
            color = page?.color
            typed = page?.icon ?? ""
        }
    }

    private func cell<C: View>(selected: Bool, action: @escaping () -> Void, @ViewBuilder content: () -> C) -> some View {
        Button(action: action) {
            content()
                .frame(width: 38, height: 38)
                .background(RoundedRectangle(cornerRadius: 10).fill(selected ? y.accentFill : y.surface))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? y.accentBorder : y.tileBorder, lineWidth: selected ? 1.5 : 1))
        }.buttonStyle(.plain)
    }

    private func swatch(_ name: String?) -> some View {
        Button { color = name } label: {
            Circle()
                .fill(LabelPalette.swatchColor(name, dark: y.dark) ?? y.accent)
                .frame(width: 26, height: 26)
                .overlay(Circle().stroke(color == name ? y.ink : .clear, lineWidth: 2))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(name ?? "Accent")
    }

    private func save() {
        model.write {
            try model.writerFor(nodeId).setPageIcon(nodeId, icon)
            try model.writerFor(nodeId).setPageColor(nodeId, color)
        }
        dismiss()
    }
}
