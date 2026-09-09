import SwiftUI

/// The mark: a gated square from a 28-unit design space. One path; the checkbox, the focus glyph,
/// widgets and the icon all draw this. The centre point is the bindu.
func bhupuraPath(_ s: CGFloat) -> Path {
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

enum TaskGlyphState { case open, inProgress, done }

/// The task glyph, three states. Open = bhupura in neutral outline; in progress = accent ring
/// inside the frame; done = bare bindu (the strike lives on the title). Tap completes, tap a done
/// task reopens; in-progress is reached by a swipe on the row, never here. Completion is the
/// accent, never green: finishing is your effort, not a system status.
struct YantraCheckbox: View {
    let state: TaskGlyphState
    var size: CGFloat = 23
    /// Priority tints the enclosure only — and only on a list, never on a done task.
    var frameTint: Color? = nil
    let onTap: () -> Void
    @Environment(\.y) private var y
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pressed = false

    var body: some View {
        ZStack {
            if state != .done {
                bhupuraPath(size).stroke(frameTint ?? y.secondary.opacity(0.9), style: StrokeStyle(lineWidth: max(1.4, size / 16), lineJoin: .round))
                    .transition(.opacity)
            }
            if state == .inProgress {
                Circle().stroke(y.accent, lineWidth: size / 14).frame(width: size * 0.5, height: size * 0.5)
                Circle().fill(y.accent.opacity(0.18)).frame(width: size * 0.5, height: size * 0.5)
            }
            if state == .done {
                Circle().fill(y.accent).frame(width: size * 0.26, height: size * 0.26)
            }
        }
        .frame(width: size, height: size)
        .scaleEffect(pressed ? 0.88 : 1)
        .contentShape(Rectangle().inset(by: -8))
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.3, dampingFraction: 0.6), value: state)
        .onTapGesture {
            pressed = true
            let gen = UIImpactFeedbackGenerator(style: .rigid)
            gen.impactOccurred(intensity: 0.8)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { pressed = false }
            onTap()
        }
        .accessibilityLabel(state == .done ? "Mark not done" : "Mark done")
    }
}

/// A wobbly coral line through a finished title, seeded by the task id so it is the same each time.
struct InkStrike: View {
    let seed: Int
    var progress: CGFloat = 1
    @Environment(\.y) private var y
    var body: some View {
        GeometryReader { g in
            InkStrike.path(seed: seed, in: g.size).trimmedPath(from: 0, to: progress)
                .stroke(y.accent, style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
        }
    }

    static func path(seed: Int, in size: CGSize) -> Path {
        let w = size.width, h = size.height
        var rng = SeededRandom(seed: UInt64(bitPattern: Int64(seed)))
        let yy = h * 0.44
        var p = Path()
        p.move(to: CGPoint(x: 0, y: yy + CGFloat(rng.next(in: -1.5...1.5))))
        let segs = 3 + Int(rng.next(in: 0..<2))
        for i in 1...segs {
            let x = w * CGFloat(i) / CGFloat(segs)
            let cx = w * (CGFloat(i) - 0.5) / CGFloat(segs)
            p.addQuadCurve(to: CGPoint(x: x, y: yy + CGFloat(rng.next(in: -2...2))), control: CGPoint(x: cx, y: yy + CGFloat(rng.next(in: -4...4))))
        }
        return p
    }
}

struct SeededRandom {
    var state: UInt64
    init(seed: UInt64) { state = seed &+ 0x9E3779B97F4A7C15 }
    mutating func nextRaw() -> UInt64 { state ^= state << 13; state ^= state >> 7; state ^= state << 17; return state }
    mutating func next(in r: ClosedRange<Double>) -> Double { r.lowerBound + Double(nextRaw() % 10_000) / 10_000 * (r.upperBound - r.lowerBound) }
    mutating func next(in r: Range<Double>) -> Double { next(in: r.lowerBound...(r.upperBound.nextDown)) }
}

/// The mark with its bindu, for empty states and the splash.
struct YantraMark: View {
    var size: CGFloat = 34
    @Environment(\.y) private var y
    var body: some View {
        ZStack {
            bhupuraPath(size).stroke(y.secondary.opacity(0.55), style: StrokeStyle(lineWidth: 1.4, lineJoin: .round))
            Circle().fill(y.accent.opacity(0.45)).frame(width: size * 0.12, height: size * 0.12)
        }.frame(width: size, height: size)
    }
}
