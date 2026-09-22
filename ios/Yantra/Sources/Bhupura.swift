import SwiftUI

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
    /// How far a swipe-to-start has been dragged, 0...1, where 1 is the commit point.
    ///
    /// The gesture draws its progress **inside** the frame: the engagement ring traces around as
    /// your finger moves, so at the moment it closes the task is started. The row itself does not
    /// move. Sliding the whole row and revealing a mark behind it made the gesture about the row —
    /// a thing being pushed aside — when what it is actually doing is filling in the glyph.
    var swipeProgress: CGFloat = 0
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
            // The ring, whether it is there because the task is started or because a finger is
            // drawing it. `max` rather than a branch, so a swipe on an already-started task leaves
            // the ring whole while the gesture decides, instead of flickering it away and back.
            let traced = max(state == .inProgress ? 1 : 0, min(max(swipeProgress, 0), 1))
            if traced > 0 {
                Circle()
                    .trim(from: 0, to: traced)
                    // From the top, like every other thing that closes as it completes.
                    .stroke(y.accent, style: StrokeStyle(lineWidth: size / 14, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .frame(width: size * 0.5, height: size * 0.5)
                Circle().fill(y.accent.opacity(0.18 * traced)).frame(width: size * 0.5, height: size * 0.5)
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
///
/// `BhupuraMark` rather than `YantraMark`, which is the name the *icon set's* enum carries on both
/// platforms. This is the brand shape; that is the vocabulary of functional marks, and one name
/// cannot be both.
struct BhupuraMark: View {
    var size: CGFloat = 34
    @Environment(\.y) private var y
    var body: some View {
        ZStack {
            bhupuraPath(size).stroke(y.secondary.opacity(0.55), style: StrokeStyle(lineWidth: 1.4, lineJoin: .round))
            Circle().fill(y.accent.opacity(0.45)).frame(width: size * 0.12, height: size * 0.12)
        }.frame(width: size, height: size)
    }
}

/// Swipe right on a task to say "I am on this".
///
/// This replaced a long-press on the glyph: the glyph is a small target and press-and-hold is a
/// gesture you have to be told about, whereas a row that fills in the mark under your finger
/// explains itself.
///
/// **It toggles.** Swipe to start a task, swipe again to put it back down. Set-only was tried and is
/// worse — a task marked by accident could then only be cleared by completing it and un-completing
/// it, which is two writes that are both wrong on the way past.
///
/// **The row stays where it is.** What moves is the engagement ring inside the glyph, traced by your
/// finger. The gesture is filling in the mark, not shoving the row aside, and the feedback belongs
/// where the meaning is.
///
/// Rightward only. Left is deliberately left free rather than given a second meaning nobody asked
/// for. The gesture yields to a vertical drag, so the list still scrolls and the row's own tap and
/// long-press still work.
struct SwipeToProgress: ViewModifier {
    @Binding var progress: CGFloat
    var enabled: Bool = true
    var onCommit: () -> Void

    /// How far the finger travels before the ring closes.
    private let commitAt: CGFloat = 72
    /// A little past the commit, so pushing further has somewhere to go without running away.
    private var ceiling: CGFloat { commitAt * 1.25 }
    @State private var armed = false

    func body(content: Content) -> some View {
        content.gesture(enabled ? gesture : nil)
    }

    private var gesture: some Gesture {
        DragGesture(minimumDistance: 14)
            .onChanged { g in
                // Only once it is clearly sideways: a mostly-vertical drag is the list scrolling,
                // and claiming it would make a long list feel stuck.
                guard abs(g.translation.width) > abs(g.translation.height) else { return }
                let next = min(max(g.translation.width, 0), ceiling)
                if !armed, next >= commitAt {
                    armed = true
                    UIImpactFeedbackGenerator(style: .rigid).impactOccurred(intensity: 0.7)
                } else if armed, next < commitAt {
                    armed = false
                }
                progress = next / commitAt
            }
            .onEnded { _ in
                let commit = armed
                armed = false
                guard commit else {
                    // Nothing was claimed, so the mark is taken back.
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) { progress = 0 }
                    return
                }
                onCommit()
                // The written state draws the ring from here; dropping the traced one in the same
                // breath keeps it whole rather than blinking.
                progress = 0
            }
    }
}

extension View {
    func swipeToProgress(_ progress: Binding<CGFloat>, enabled: Bool = true,
                         onCommit: @escaping () -> Void) -> some View {
        modifier(SwipeToProgress(progress: progress, enabled: enabled, onCommit: onCommit))
    }
}
