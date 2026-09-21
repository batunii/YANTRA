import SwiftUI
import YantraCore

/// One pen the kit holds.
///
/// **A slot is the only place a width and a colour live.** There is no separate size control and no
/// separate swatch row, because every one of those is a decision taken away from the page and made
/// in a panel instead. Change what a slot holds by holding it.
struct PenSlot: Equatable {
    var label: String
    var family: String
    var color: UInt32
    /// In **document units** — about 0.21 mm each on an A4-proportioned page. Not pixels: 2.6px is
    /// 0.87pt at 3× and 1.3pt at 2×, which would make the pen a different physical thickness on
    /// every screen.
    var width: CGFloat
}

/// What a touch on the canvas currently does.
enum InkMode: Equatable {
    /// The pen in hand. Freehand, and snapped to a shape on lift if recognition is on.
    case draw
    case erase
    case lasso
    /// Dragging out a shape directly — a line, a box, an oval, an arrow.
    case shape
}

/// Which set of controls the kit has opened, if any.
///
/// One panel at a time, and it belongs to the thing you tapped. Every tool that has a setting keeps
/// it here rather than in a tray of its own: the eraser's width sits behind the eraser, the shape
/// kinds behind the shape key, and nothing has to be found somewhere it is not.
enum KitPanel: Equatable {
    case slot(Int)
    case eraser
    case shape
}

enum InkPalette {
    // Warm graphite and warm off-white, matching the app's paper. A hand-drawn stroke is the one
    // place a mismatched ground shows immediately.
    static let blackInk: UInt32 = 0xFF23211C
    static let whiteInk: UInt32 = 0xFFF1EEE7

    static func defaultPen(dark: Bool) -> UInt32 { dark ? whiteInk : blackInk }

    /// The one colour that follows the theme. A stroke stores what it was drawn with, so this only
    /// ever affects the *next* stroke — nothing already on the page changes.
    static func display(_ stored: UInt32, dark: Bool) -> UInt32 {
        if dark, stored == blackInk { return whiteInk }
        if !dark, stored == whiteInk { return blackInk }
        return stored
    }

    /// Five colours that are only ever ink, plus the live accent in front of them.
    static let drawing: [UInt32] = [
        0xFF6FA8E4,  // blue
        0xFF4E9478,  // green
        0xFFE0A83E,  // amber
        0xFFC56A94,  // pink
        0xFF8B6BA8,  // purple
    ]

    /// The widths a slot offers, in document units. Four, because a nib is a choice between a few
    /// thicknesses you can tell apart, not a slider.
    static let widths: [CGFloat] = [1.4, 2.6, 5, 9]

    /// The starting kit: a pen and a translucent marker.
    ///
    /// Two, not three. A second pen differing only by being thicker is a slot spent on a setting,
    /// and a slot owns its width already. What the marker offers that a wide pen does not is
    /// translucent ink that layers, and that is worth a slot.
    static func defaultSlots(dark: Bool) -> [PenSlot] {
        [
            PenSlot(label: "PEN", family: StrokeCodec.familyPressurePen, color: defaultPen(dark: dark), width: 2.6),
            PenSlot(label: "MARK", family: StrokeCodec.familyHighlighter, color: 0xFFE0A83E, width: 9),
        ]
    }
}

/// The kit: the pen slots, a divider, then the tools that are not pens.
///
/// Fixed to one corner and never moved. `dimmed` fades it while the pen is down so the page can be
/// seen through it, but it stays exactly where it was — the whole point of a kit is that your hand
/// knows where it is without looking, and a control that moves out of the way has to be found again
/// on the way back.
struct PenKitBar: View {
    @Binding var slots: [PenSlot]
    @Binding var active: Int
    @Binding var mode: InkMode
    @Binding var snap: Bool
    @Binding var panel: KitPanel?
    @Binding var shapeKind: ShapeKind
    @Binding var eraserWidth: CGFloat
    var dimmed: Bool
    var canUndo: Bool
    var canRedo: Bool
    var onUndo: () -> Void
    var onRedo: () -> Void
    @Environment(\.y) private var y

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let panel { controls(panel) }
            HStack(spacing: 6) {
                ForEach(Array(slots.enumerated()), id: \.offset) { i, slot in
                    slotKey(i, slot)
                }
                divider
                key(.eraser, mark: .eraser, label: "Eraser", on: mode == .erase) {
                    mode = .erase
                    panel = panel == .eraser ? nil : .eraser
                }
                // Lasso has nothing to configure, so it is a mode and not a panel.
                Button { mode = .lasso; panel = nil } label: {
                    YantraIcon(mark: .lasso, size: YantraIcons.small, tint: mode == .lasso ? y.accentText : y.secondary)
                        .frame(width: 30, height: 30)
                        .background(RoundedRectangle(cornerRadius: 8).fill(mode == .lasso ? y.accentFill : .clear))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("ink.lasso").accessibilityLabel("Lasso")
                key(.shape, mark: .shapes, label: "Shapes", on: mode == .shape) {
                    mode = .shape
                    panel = panel == .shape ? nil : .shape
                }
                divider
                // Snap is a property of the pen, not a tool of its own: it changes what a freehand
                // stroke becomes on lift, and nothing else.
                Button { snap.toggle() } label: {
                    YantraIcon(mark: .shapes, size: YantraIcons.small, tint: snap ? y.accentText : y.dim)
                        .frame(width: 30, height: 30)
                        .background(RoundedRectangle(cornerRadius: 8).fill(snap ? y.accentFill : .clear))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("ink.snap")
                .accessibilityLabel("Snap to shapes")
                .accessibilityValue(snap ? "on" : "off")
                divider
                Button(action: onUndo) {
                    YantraIcon(mark: .undo, size: YantraIcons.small, tint: canUndo ? y.secondary : y.dim.opacity(0.4))
                        .frame(width: 30, height: 30)
                }
                .buttonStyle(.plain).disabled(!canUndo)
                .accessibilityIdentifier("ink.undo").accessibilityLabel("Undo")
                Button(action: onRedo) {
                    YantraIcon(mark: .redo, size: YantraIcons.small, tint: canRedo ? y.secondary : y.dim.opacity(0.4))
                        .frame(width: 30, height: 30)
                }
                .buttonStyle(.plain).disabled(!canRedo)
                .accessibilityIdentifier("ink.redo").accessibilityLabel("Redo")
            }
            .padding(6)
            .background(RoundedRectangle(cornerRadius: 14).fill(y.surface))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(y.tileBorder, lineWidth: 1))
        }
        .opacity(dimmed ? 0.35 : 1)
        .animation(.easeOut(duration: 0.15), value: dimmed)
    }

    private var divider: some View {
        Rectangle().fill(y.hairline).frame(width: 1, height: 20)
    }

    /// A slot is drawn as the mark it makes: its own ink, at its own width.
    private func slotKey(_ i: Int, _ slot: PenSlot) -> some View {
        Button {
            if active == i, mode == .draw {
                panel = panel == .slot(i) ? nil : .slot(i)
            } else {
                active = i; mode = .draw; panel = nil
            }
        } label: {
            ZStack {
                Capsule()
                    .fill(Color(argb: InkPalette.display(slot.color, dark: y.dark))
                        .opacity(slot.family == StrokeCodec.familyHighlighter ? 0.45 : 1))
                    .frame(width: 22, height: max(slot.width * 0.9, 2))
            }
            .frame(width: 34, height: 30)
            .background(RoundedRectangle(cornerRadius: 8)
                .fill(active == i && mode == .draw ? y.accentFill : .clear))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("ink.slot.\(i)")
        .accessibilityLabel(slot.label)
    }

    private func key(_ which: KitPanel, mark: YantraMark, label: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            YantraIcon(mark: mark, size: YantraIcons.small, tint: on ? y.accentText : y.secondary)
                .frame(width: 30, height: 30)
                .background(RoundedRectangle(cornerRadius: 8).fill(on ? y.accentFill : .clear))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("ink.\(mark.rawValue)")
        .accessibilityLabel(label)
    }

    // MARK: the panels

    @ViewBuilder
    private func controls(_ panel: KitPanel) -> some View {
        Group {
            switch panel {
            case let .slot(i) where i < slots.count:
                VStack(alignment: .leading, spacing: 8) {
                    swatches(i)
                    widths(i)
                }
            case .eraser:
                HStack(spacing: 6) {
                    ForEach([8, 16, 28, 44] as [CGFloat], id: \.self) { w in
                        Button { eraserWidth = w } label: {
                            Circle().fill(eraserWidth == w ? y.accent : y.secondary.opacity(0.4))
                                .frame(width: max(w * 0.4, 8), height: max(w * 0.4, 8))
                                .frame(width: 30, height: 30)
                        }.buttonStyle(.plain)
                    }
                }
            case .shape:
                HStack(spacing: 6) {
                    ForEach(ShapeKind.allCases, id: \.self) { k in
                        Button { shapeKind = k; mode = .shape } label: {
                            Text(k.rawValue.prefix(1).uppercased())
                                .font(Face.mono(12, bold: shapeKind == k))
                                .foregroundStyle(shapeKind == k ? y.accentText : y.secondary)
                                .frame(width: 30, height: 30)
                                .background(RoundedRectangle(cornerRadius: 8).fill(shapeKind == k ? y.accentFill : .clear))
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("ink.shape.\(k.rawValue)")
                        .accessibilityLabel(k.rawValue)
                    }
                }
            default:
                EmptyView()
            }
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 12).fill(y.surfaceHigh))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(y.tileBorder, lineWidth: 1))
    }

    /// The app's own ink first, then five colours that are only ever ink.
    private func swatches(_ i: Int) -> some View {
        HStack(spacing: 6) {
            ForEach([InkPalette.defaultPen(dark: y.dark)] + InkPalette.drawing, id: \.self) { c in
                Button { slots[i].color = c } label: {
                    Circle().fill(Color(argb: c))
                        .frame(width: 20, height: 20)
                        .overlay(Circle().stroke(slots[i].color == c ? y.ink : y.tileBorder, lineWidth: slots[i].color == c ? 2 : 1))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("ink.colour.\(String(format: "%06X", c & 0xFFFFFF))")
            }
        }
    }

    private func widths(_ i: Int) -> some View {
        HStack(spacing: 6) {
            ForEach(InkPalette.widths, id: \.self) { w in
                Button { slots[i].width = w } label: {
                    Capsule().fill(slots[i].width == w ? y.accent : y.secondary.opacity(0.5))
                        .frame(width: 22, height: max(w * 0.9, 2))
                        .frame(width: 30, height: 24)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("ink.width.\(Int(w * 10))")
            }
        }
    }
}
