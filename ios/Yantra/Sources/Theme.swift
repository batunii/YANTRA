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
    static let buttonRadius: CGFloat = 13, cardRadius: CGFloat = 14, barRadius: CGFloat = 18, chipRadius: CGFloat = 10
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
    var icon: String? = nil
    var enabled: Bool = true
    let action: () -> Void
    @Environment(\.y) private var y
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon { Image(systemName: icon).icon(15, .semibold) }
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
    let action: () -> Void
    @Environment(\.y) private var y
    var body: some View {
        Button(action: action) {
            Text(label).font(Face.text(13, selected ? .bold : .medium))
                .padding(.horizontal, stretch ? 6 : 14).padding(.vertical, stretch ? 12 : 9)
                .frame(maxWidth: stretch ? .infinity : nil)
                .background(RoundedRectangle(cornerRadius: Layout.chipRadius).fill(selected ? y.accentFill : y.surfaceHigh))
                .overlay(RoundedRectangle(cornerRadius: Layout.chipRadius).stroke(selected ? y.accentBorder : y.tileBorder, lineWidth: 1))
                .foregroundStyle(selected ? y.accentText : y.secondary)
        }.buttonStyle(.plain)
    }
}

struct NavCircle: View {
    let icon: String
    var accent: Bool = false
    let action: () -> Void
    @Environment(\.y) private var y
    var body: some View {
        Button(action: action) {
            Image(systemName: icon).icon(17, .semibold)
                .foregroundStyle(accent ? y.accent : y.secondary)
                .frame(width: 38, height: 38)
                .background(Circle().fill(accent ? y.accentFill : y.ink.opacity(0.05)))
        }.buttonStyle(.plain)
    }
}
