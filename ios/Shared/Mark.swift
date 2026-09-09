import SwiftUI

/// The mark: a gated square from a 28-unit design space. One path; the checkbox, the focus glyph,
/// widgets, the Live Activity and the icon all draw this. The centre point is the bindu.
public func bhupuraPath(_ s: CGFloat) -> Path {
    let u = s / 28
    var p = Path()
    p.move(to: CGPoint(x: 8 * u, y: 4 * u))
    p.addLine(to: CGPoint(x: 11 * u, y: 4 * u)); p.addLine(to: CGPoint(x: 11 * u, y: 2 * u))
    p.addLine(to: CGPoint(x: 17 * u, y: 2 * u)); p.addLine(to: CGPoint(x: 17 * u, y: 4 * u))
    p.addLine(to: CGPoint(x: 20 * u, y: 4 * u))
    p.addQuadCurve(to: CGPoint(x: 24 * u, y: 8 * u), control: CGPoint(x: 24 * u, y: 4 * u))
    p.addLine(to: CGPoint(x: 24 * u, y: 11 * u)); p.addLine(to: CGPoint(x: 26 * u, y: 11 * u))
    p.addLine(to: CGPoint(x: 26 * u, y: 17 * u)); p.addLine(to: CGPoint(x: 24 * u, y: 17 * u))
    p.addLine(to: CGPoint(x: 24 * u, y: 20 * u))
    p.addQuadCurve(to: CGPoint(x: 20 * u, y: 24 * u), control: CGPoint(x: 24 * u, y: 24 * u))
    p.addLine(to: CGPoint(x: 17 * u, y: 24 * u)); p.addLine(to: CGPoint(x: 17 * u, y: 26 * u))
    p.addLine(to: CGPoint(x: 11 * u, y: 26 * u)); p.addLine(to: CGPoint(x: 11 * u, y: 24 * u))
    p.addLine(to: CGPoint(x: 8 * u, y: 24 * u))
    p.addQuadCurve(to: CGPoint(x: 4 * u, y: 20 * u), control: CGPoint(x: 4 * u, y: 24 * u))
    p.addLine(to: CGPoint(x: 4 * u, y: 17 * u)); p.addLine(to: CGPoint(x: 2 * u, y: 17 * u))
    p.addLine(to: CGPoint(x: 2 * u, y: 11 * u)); p.addLine(to: CGPoint(x: 4 * u, y: 11 * u))
    p.addLine(to: CGPoint(x: 4 * u, y: 8 * u))
    p.addQuadCurve(to: CGPoint(x: 8 * u, y: 4 * u), control: CGPoint(x: 4 * u, y: 4 * u))
    p.closeSubpath()
    return p
}

/// The session mark for surfaces outside the app: the bhupura with a bindu that is filled while
/// running and hollow while paused. Carries state, not progress.
public struct SessionMark: View {
    public var size: CGFloat
    public var running: Bool
    public var accent: Color
    public var outline: Color
    public init(size: CGFloat, running: Bool, accent: Color, outline: Color) { self.size = size; self.running = running; self.accent = accent; self.outline = outline }
    public var body: some View {
        ZStack {
            bhupuraPath(size).stroke(outline, style: StrokeStyle(lineWidth: max(1.2, size / 18), lineJoin: .round))
            if running { Circle().fill(accent).frame(width: size * 0.26, height: size * 0.26) }
            else { Circle().stroke(accent, lineWidth: max(1, size / 22)).frame(width: size * 0.22, height: size * 0.22) }
        }.frame(width: size, height: size)
    }
}

/// The palette the extensions share with the app: read from the same App Group prefs.
public struct SharedPalette {
    public let dark: Bool
    public let page, surface, ink, secondary, dim, accent, onAccent, overdue: Color
    public init(dark: Bool = true) {
        self.dark = dark
        let accentName = AppGroup.defaults.string(forKey: "theme_accent") ?? "Coral"
        let accents: [String: (UInt32, UInt32)] = [
            "Coral": (0xFFD85A30, 0xFFE8865F), "Jade": (0xFF00A072, 0xFF3BBE8F), "Azure": (0xFF0097B1, 0xFF00B8D6),
            "Indigo": (0xFF5480EB, 0xFF7BA1F7), "Orchid": (0xFFA864CF, 0xFFC08BE0),
        ]
        let a = accents[accentName] ?? accents["Coral"]!
        accent = Color(argb: dark ? a.1 : a.0)
        page = dark ? Color(red: 0.102, green: 0.098, blue: 0.09) : Color(red: 0.972, green: 0.965, blue: 0.95)
        surface = dark ? Color(red: 0.145, green: 0.141, blue: 0.133) : .white
        ink = dark ? Color(red: 0.945, green: 0.933, blue: 0.906) : Color(red: 0.16, green: 0.155, blue: 0.14)
        secondary = dark ? Color(red: 0.72, green: 0.70, blue: 0.66) : Color(red: 0.40, green: 0.39, blue: 0.36)
        dim = dark ? Color(red: 0.50, green: 0.49, blue: 0.46) : Color(red: 0.62, green: 0.61, blue: 0.58)
        onAccent = dark ? Color(red: 0.12, green: 0.115, blue: 0.10) : .white
        overdue = dark ? Color(argb: 0xFFE24B4A) : Color(argb: 0xFFA32D2D)
    }
}

public extension Color {
    init(argb: UInt32) {
        self.init(red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255,
                  blue: Double(argb & 0xFF) / 255, opacity: Double((argb >> 24) & 0xFF) / 255)
    }
}

public enum SharedFace {
    public static func display(_ size: CGFloat) -> Font { .custom("BricolageGrotesque-96ptExtraBold", size: size) }
    public static func text(_ size: CGFloat, bold: Bool = false) -> Font { .custom(bold ? "SpaceGroteskLight-Bold" : "SpaceGroteskLight-Regular", size: size) }
    public static func mono(_ size: CGFloat, bold: Bool = false) -> Font { .custom(bold ? "SpaceMono-Bold" : "SpaceMono-Regular", size: size) }
}
