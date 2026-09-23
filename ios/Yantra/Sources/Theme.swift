import SwiftUI
import YantraCore

// MARK: - OKLCH, the way YantraColors.kt builds every ground and ink

func oklch(_ l: Double, _ c: Double, _ hueDeg: Double) -> Color {
    let h = hueDeg / 180 * .pi
    let a = c * cos(h), b = c * sin(h)
    let lp = l + 0.3963377774 * a + 0.2158037573 * b
    let mp = l - 0.1055613458 * a - 0.0638541728 * b
    let sp = l - 0.0894841775 * a - 1.2914855480 * b
    let l3 = lp * lp * lp, m3 = mp * mp * mp, s3 = sp * sp * sp
    let r = 4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3
    let g = -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3
    let bl = -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3
    func srgb(_ x: Double) -> Double { let c = min(max(x, 0), 1); return c <= 0.0031308 ? 12.92 * c : 1.055 * pow(c, 1 / 2.4) - 0.055 }
    return Color(red: srgb(r), green: srgb(g), blue: srgb(bl))
}

enum ThemeMode: String, CaseIterable { case system = "System", dark = "Dark", oled = "OLED", light = "Light" }

/// The five accents, a closed set. The accent moves only the effort layer.
enum Accent: String, CaseIterable {
    case coral = "Coral", jade = "Jade", azure = "Azure", indigo = "Indigo", orchid = "Orchid"
    func ink(dark: Bool) -> Color {
        switch self {
        case .coral: return dark ? Color(argb: 0xFFE8865F) : Color(argb: 0xFFD85A30)
        case .jade: return dark ? Color(argb: 0xFF3BBE8F) : Color(argb: 0xFF00A072)
        case .azure: return dark ? Color(argb: 0xFF00B8D6) : Color(argb: 0xFF0097B1)
        case .indigo: return dark ? Color(argb: 0xFF7BA1F7) : Color(argb: 0xFF5480EB)
        case .orchid: return dark ? Color(argb: 0xFFC08BE0) : Color(argb: 0xFFA864CF)
        }
    }
}

/// The palette — `yantraColors(mode, accent)`. Neutral is structure, the accent is your effort,
/// crimson and amber are the world asking, grey is rest.
struct YantraColors {
    static let paperHue = 80.0
    let dark: Bool
    let page, surface, surfaceHigh, rail, band, cardBg: Color
    let ink, secondary, muted, dim, hairline, tileBorder: Color
    /// The fold between two pages of a drawing — `inkPageSep`. A shade stronger than a hairline,
    /// because it is the edge of a sheet rather than a rule inside one.
    let inkPageSep: Color
    let accent, accentText, accentFill, accentBorder, onAccent: Color
    let crimson, amber, overdue, due, warning: Color

    init(mode: ThemeMode, accent: Accent) {
        let m = mode == .system ? ThemeMode.dark : mode
        let light = m == .light, oled = m == .oled
        dark = !light
        let H = YantraColors.paperHue
        page = oled ? .black : light ? oklch(0.972, 0.006, H) : oklch(0.171, 0.005, H)
        surface = oled ? oklch(0.188, 0.006, H) : light ? oklch(0.995, 0.003, H) : oklch(0.216, 0.007, H)
        surfaceHigh = oled ? oklch(0.232, 0.008, H) : light ? oklch(0.958, 0.007, H) : oklch(0.257, 0.009, H)
        rail = oled ? oklch(0.102, 0.005, H) : light ? oklch(0.935, 0.007, H) : oklch(0.137, 0.005, H)
        band = surface
        cardBg = surface
        ink = light ? oklch(0.26, 0.012, H) : oklch(0.948, 0.005, H)
        secondary = light ? oklch(0.44, 0.011, H) : oklch(0.735, 0.008, H)
        muted = light ? oklch(0.545, 0.010, H) : oklch(0.638, 0.009, H)
        dim = light ? oklch(0.655, 0.009, H) : oklch(0.510, 0.010, H)
        hairline = ink.opacity(0.08)
        inkPageSep = ink.opacity(0.095)
        tileBorder = ink.opacity(0.10)
        let a = accent.ink(dark: !light)
        self.accent = a
        accentText = a
        accentFill = a.opacity(0.16)
        accentBorder = a.opacity(0.45)
        onAccent = light ? .white : oklch(0.16, 0.010, H)
        crimson = light ? Color(argb: 0xFFA32D2D) : Color(argb: 0xFFE24B4A)
        amber = light ? Color(argb: 0xFFBA7517) : Color(argb: 0xFFEF9F27)
        overdue = crimson
        due = a
        warning = amber
    }

    func priority(_ p: String?) -> Color? {
        switch p?.lowercased() { case "high": return crimson; case "medium": return amber; default: return nil }
    }
}

/// Persisted theme choice — the same two prefs Android keeps (mode + accent), default Dark + Coral.
final class ThemeController: ObservableObject {
    @AppStorage("theme_mode", store: AppGroup.defaults) var modeRaw: String = ThemeMode.dark.rawValue
    @AppStorage("theme_accent", store: AppGroup.defaults) var accentRaw: String = Accent.coral.rawValue
    var mode: ThemeMode { ThemeMode(rawValue: modeRaw) ?? .dark }
    var accent: Accent { Accent(rawValue: accentRaw) ?? .coral }
    func colors(systemDark: Bool) -> YantraColors {
        let m = mode == .system ? (systemDark ? .dark : .light) : mode
        return YantraColors(mode: m, accent: accent)
    }
}

private struct YantraColorsKey: EnvironmentKey { static let defaultValue = YantraColors(mode: .dark, accent: .coral) }
extension EnvironmentValues { var y: YantraColors { get { self[YantraColorsKey.self] } set { self[YantraColorsKey.self] = newValue } } }

// MARK: - type: three voices

enum Face {
    // The subset TTFs from app/src/main/res/font, by their PostScript names. The Space Grotesk file is
    // a variable font with named instances; Bricolage ships one instance. Missing → system fallback.
    //
    // **Every face scales with Dynamic Type.** `Font.custom(_:size:)` alone is a fixed point size
    // that ignores the reader's text-size setting entirely, which on a custom-font app means the
    // whole interface ignores it. The `relativeTo:` form keeps these exact sizes as the metric at
    // the default setting and scales from there, so the type stays Yantra's and the size stays the
    // reader's. It is also the accessibility obligation the European Accessibility Act now carries.
    //
    // The text style each face is measured against is chosen by role, not by size: a display title
    // scales like a title, body text like body, and the instrument like a caption — so a large
    // setting grows the words you read faster than the numerals beside them, which is the ratio
    // that keeps a screen legible rather than merely bigger.

    /// Display — Bricolage Grotesque, every big title.
    static func display(_ size: CGFloat, _ weight: Font.Weight = .bold) -> Font {
        .custom("BricolageGrotesque-96ptExtraBold", size: size, relativeTo: .title)
    }

    /// Text — Space Grotesk, the UI body.
    static func text(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        // Below the body metric the smaller sizes are labels and captions, and scaling them as body
        // makes a caption outgrow the line it annotates.
        let style: Font.TextStyle = size >= 20 ? .title3 : size >= 15 ? .body : size >= 12.5 ? .subheadline : .caption
        switch weight {
        case .bold, .heavy, .black, .semibold: return .custom("SpaceGroteskLight-Bold", size: size, relativeTo: style)
        case .medium: return .custom("SpaceGroteskLight-Medium", size: size, relativeTo: style)
        default: return .custom("SpaceGroteskLight-Regular", size: size, relativeTo: style)
        }
    }

    /// Instrument — Space Mono, the focus countdown and eyebrows, nothing else.
    static func mono(_ size: CGFloat, bold: Bool = false) -> Font {
        .custom(bold ? "SpaceMono-Bold" : "SpaceMono-Regular", size: size, relativeTo: size >= 20 ? .title2 : .caption)
    }
}

/// The palette as colours.
///
/// `LabelPalette` itself is in the core and free of SwiftUI, the way the Kotlin one is free of
/// Compose: nothing in the data layer may reach up into the views. This is that call site.
extension LabelPalette {
    /// What a label named `name` is painted, honouring a colour somebody chose for it in the
    /// registry and otherwise the one its name seeds to.
    static func color(_ name: String, registry: [LabelDef], dark: Bool) -> Color {
        let stored = registry.first { $0.name.lowercased() == name.lowercased() }?.color ?? defaultFor(name)
        return Color(argb: UInt32(truncatingIfNeeded: display(stored, dark: dark)))
    }

    /// A palette name as a colour for the current theme, or nil when nothing is called that.
    static func swatchColor(_ name: String?, dark: Bool) -> Color? {
        guard let s = byName(name) else { return nil }
        return Color(argb: UInt32(truncatingIfNeeded: dark ? s.dark : s.light))
    }
}

/// An SF Symbol that grows with the reader's text size.
///
/// `Font.system(size:)` is a fixed point size, so an icon set that way stays exactly as small as it
/// started while every word beside it grows — which is the accessibility failure that reads as the
/// app half-ignoring the setting. There is no `relativeTo:` for the system font, so the scaling is
/// done with `@ScaledMetric`, which is the same metric `relativeTo:` uses.
///
/// Widgets deliberately do not use this: WidgetKit gives a widget a fixed box, and type that grows
/// inside one truncates rather than helps.
private struct ScaledIcon: ViewModifier {
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1
    let size: CGFloat
    let weight: Font.Weight
    func body(content: Content) -> some View {
        content.font(.system(size: size * scale, weight: weight))
    }
}

extension View {
    /// The icon metric, scaled. `size` is what it measures at the default text size.
    func icon(_ size: CGFloat, _ weight: Font.Weight = .regular) -> some View {
        modifier(ScaledIcon(size: size, weight: weight))
    }
}

// MARK: - layout constants

enum Layout {
    static let pageMargin: CGFloat = 22

    /// The measure a column of rows or prose is allowed — `PAGE_MEASURE` on Android.
    ///
    /// **The column stays a column.** A task row drawn across the full width of a tablet is a title
    /// at one edge and a compass at the other with an ocean between them, and a paragraph set that
    /// wide is one nobody finishes — the eye loses the line on the way back. So content is centred
    /// at this width and the rest of the glass is margin, which looks like waste and is the entire
    /// reason the page is readable.
    ///
    /// A phone is narrower than this, so on a phone it does nothing at all.
    static let measure: CGFloat = 720

    /// Where a window stops being a phone and starts having room for a rail beside a page.
    ///
    /// Deliberately a width test, not a device test: a tablet in split screen is a phone-shaped
    /// window, and the layout has to follow the glass it actually has.
    static let wideAt: CGFloat = 840
    static let buttonRadius: CGFloat = 13, cardRadius: CGFloat = 14, barRadius: CGFloat = 18, chipRadius: CGFloat = 10

    // The named scale, matching `YantraRadius` on Android so a surface is the same shape on both.
    /// The smallest mark that still has corners — a swatch, a dot with a square shoulder.
    static let tinyRadius: CGFloat = 4
    /// A block on the timeline, a row in the rail: small, clipped, many of them at once.
    static let blockRadius: CGFloat = 8
    /// A secondary surface inside something else — a panel in the ink kit, a tool tray.
    static let panelRadius: CGFloat = 12
    /// A band, a bottom sheet, the header's rounded foot.
    static let sheetRadius: CGFloat = 18
    /// The largest: a full-height surface that still wants a corner.
    static let heroRadius: CGFloat = 28
}

/// Lays its children out in a row, wrapping to the next line when one will not fit.
///
/// The chips in a builder are as many as the person's own properties, so a fixed row would push the
/// last of them off a narrow screen with nothing to say it had gone.
struct YFlow: SwiftUI.Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat? = nil
    private var vGap: CGFloat { lineSpacing ?? spacing }

    func sizeThatFits(proposal: ProposedViewSize, subviews: SwiftUI.LayoutSubviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let rows = layout(subviews, in: width)
        let height = rows.last.map { $0.y + $0.height } ?? 0
        return CGSize(width: proposal.width ?? rows.map(\.width).max() ?? 0, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: SwiftUI.LayoutSubviews, cache: inout ()) {
        for row in layout(subviews, in: bounds.width) {
            var x = bounds.minX
            for i in row.range {
                let size = subviews[i].sizeThatFits(.unspecified)
                subviews[i].place(at: CGPoint(x: x, y: bounds.minY + row.y),
                                  proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
        }
    }

    private struct Row { var range: Range<Int>; var y: CGFloat; var width: CGFloat; var height: CGFloat }

    private func layout(_ subviews: SwiftUI.LayoutSubviews, in width: CGFloat) -> [Row] {
        var rows: [Row] = []
        var start = 0, x: CGFloat = 0, y: CGFloat = 0, lineHeight: CGFloat = 0
        for i in subviews.indices {
            let size = subviews[i].sizeThatFits(.unspecified)
            // A single child wider than the line still gets its own line rather than none.
            if x > 0, x + size.width > width {
                rows.append(Row(range: start..<i, y: y, width: x - spacing, height: lineHeight))
                y += lineHeight + vGap
                start = i; x = 0; lineHeight = 0
            }
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
        if start < subviews.count {
            rows.append(Row(range: start..<subviews.count, y: y, width: max(x - spacing, 0), height: lineHeight))
        }
        return rows
    }
}

// MARK: - small shared pieces

struct SectionLabel: View {
    let text: String
    var color: Color? = nil
    @Environment(\.y) private var y
    var body: some View {
        Text(text.uppercased()).font(Face.text(11, .bold)).kerning(1.4).foregroundStyle(color ?? y.muted)
    }
}

struct Eyebrow: View {
    let text: String
    @Environment(\.y) private var y
    var body: some View { Text(text.uppercased()).font(Face.mono(10.5, bold: true)).kerning(1.4).foregroundStyle(y.accentText) }
}

enum ButtonTone { case solid, soft, quiet }

struct YantraButton: View {
    let label: String
    var tone: ButtonTone = .soft
    var mark: YantraMark? = nil
    var enabled: Bool = true
    let action: () -> Void
    @Environment(\.y) private var y
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let mark { YantraIcon(mark: mark, size: YantraIcons.small, tint: fg) }
                Text(label).font(Face.text(15, .bold))
            }
            .padding(.horizontal, 20).padding(.vertical, 13)
            .frame(maxWidth: .infinity)
            .background(RoundedRectangle(cornerRadius: Layout.buttonRadius).fill(fill))
            .overlay(RoundedRectangle(cornerRadius: Layout.buttonRadius).stroke(border, lineWidth: 1))
            .foregroundStyle(fg)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }
    private var fill: Color { switch tone { case .solid: return y.accent; case .soft: return y.accentFill; case .quiet: return .clear } }
    private var border: Color { switch tone { case .solid: return .clear; case .soft: return y.accentBorder; case .quiet: return y.tileBorder } }
    private var fg: Color { switch tone { case .solid: return y.onAccent; case .soft: return y.accentText; case .quiet: return y.secondary } }
}

struct SelectChip: View {
    let label: String
    let selected: Bool
    var stretch: Bool = false
    /// The mark beside the word, where the kind has one. Task and Note deliberately have none: the
    /// task's mark is the checkbox the row already draws, and a paragraph is the absence of a kind.
    var mark: YantraMark? = nil
    let action: () -> Void
    @Environment(\.y) private var y
    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let mark {
                    YantraIcon(mark: mark, size: YantraIcons.small,
                               tint: selected ? y.accentText : y.secondary)
                }
                Text(label).font(Face.text(13, selected ? .bold : .medium))
            }
                .padding(.horizontal, stretch ? 6 : 14).padding(.vertical, stretch ? 12 : 9)
                .frame(maxWidth: stretch ? .infinity : nil)
                .background(RoundedRectangle(cornerRadius: Layout.chipRadius).fill(selected ? y.accentFill : y.surfaceHigh))
                .overlay(RoundedRectangle(cornerRadius: Layout.chipRadius).stroke(selected ? y.accentBorder : y.tileBorder, lineWidth: 1))
                .foregroundStyle(selected ? y.accentText : y.secondary)
        }
        .buttonStyle(.plain)
        // Colour is the only thing that says "chosen" here, which VoiceOver cannot see. The trait
        // is how the state reaches anyone not looking at the coral.
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

struct NavCircle: View {
    let mark: YantraMark
    var accent: Bool = false
    let action: () -> Void
    @Environment(\.y) private var y
    var body: some View {
        Button(action: action) {
            YantraIcon(mark: mark, size: YantraIcons.medium, tint: accent ? y.accent : y.secondary)
                .frame(width: 38, height: 38)
                .background(Circle().fill(accent ? y.accentFill : y.ink.opacity(0.05)))
        }.buttonStyle(.plain)
    }
}

extension View {
    /// Keeps a column of rows or prose to a readable measure, centred, with the glass either side
    /// left as margin. See `Layout.measure` for why that is not waste.
    ///
    /// Applied to the *content* inside a scroll view rather than to the scroll view itself, so the
    /// bar still scrolls under the full width of the screen and only what is read is narrowed.
    func readableColumn() -> some View {
        frame(maxWidth: Layout.measure).frame(maxWidth: .infinity)
    }
}
