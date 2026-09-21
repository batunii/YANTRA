import SwiftUI
import YantraCore

/// The running row's clock — the same reading the notification and the widget show.
func elapsedLabel(_ seconds: Int) -> String { sessionClock(seconds) }

/// The glyph's running state, drawn once and not animated: the neutral bhupura with the ring closed
/// inside it.
///
/// `YantraCheckbox` is the interactive one, and it carries a swipe and a completion choreography.
/// None of that belongs on a bar that is only reporting; this is the same two layers of the design
/// language with nothing behind them.
struct RunningGlyph: View {
    /// The gate. Neutral, like every other frame in the app — structure is not a hue.
    var frameTint: Color
    /// The ring inside it. The accent, because being on something is your own effort.
    var ringTint: Color
    var size: CGFloat = 20

    var body: some View {
        Canvas { ctx, size in
            let s = min(size.width, size.height)
            // Proportional, not the checkbox's flat 1.6. That figure is tuned against a 30pt row
            // glyph; carried onto a 22pt one it is half again as heavy relative to the shape, and
            // the ring thickens into the frame until the two read as one blob rather than a mark
            // inside a gate. Scaling it keeps the drawing the same drawing at any size.
            let style = StrokeStyle(lineWidth: s * 1.6 / 30, lineCap: .round, lineJoin: .round)
            ctx.stroke(bhupuraPath(s), with: .color(frameTint), style: style)
            let r = s * 7.5 / 28
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                       with: .color(ringTint), style: style)
        }
        .frame(width: size, height: size)
    }
}

/// The player: a thin bar at the very bottom holding whatever you are on.
///
/// Shaped as the header's reflection — full bleed, rounded at the top where the band is rounded at
/// the bottom — so the screen is a sheet of paper held between two folds. Much thinner than the
/// header, because the header names where you are and this only reports what is running.
///
/// **Two targets, two meanings, and the split is the point.** The button starts an *open* stopwatch
/// right here, because "start counting" is a control you press in passing and it promises nothing
/// about how long. The body opens the focus screen, where you commit to a length.
///
/// **Only the running task wears the accent.** The colour law gives it to effort, and a task you
/// have merely picked up is not that yet — paint the whole bar coral and the one task actually
/// counting has nothing left to distinguish it.
struct NowPlayer: View {
    let stack: [RunningStack.Now]
    /// The live clock, read where it is drawn rather than carried in the stack: the set changes
    /// rarely and the number changes every second, so binding them together would put every card on
    /// a one-second loop to re-render "still not me".
    let elapsed: Int?
    var onOpen: (RunningStack.Now) -> Void
    var onToggleClock: (RunningStack.Now) -> Void
    @Environment(\.y) private var y

    /// Which task is showing, held **by id rather than by position**.
    ///
    /// The list re-sorts whenever a clock starts or stops — the timed task is dealt to the front —
    /// so an index is a pointer into a list that moves underneath it. Held positionally, pressing
    /// play reordered the stack and slot 2 quietly became a different task: the button then acted on
    /// whatever had slid under it, which is how a press meant to start one task stops another. An id
    /// cannot drift. If the task leaves the stack entirely, the front of it is the honest fallback.
    @State private var selected: String?
    @State private var dragX: CGFloat = 0

    /// How far a drag must travel to change cards.
    private let commit: CGFloat = 56

    private var index: Int { stack.firstIndex { $0.nodeId == selected } ?? 0 }
    private var current: RunningStack.Now? { stack.indices.contains(index) ? stack[index] : nil }
    private var live: Bool { current?.hasSession == true }

    var body: some View {
        if let current {
            HStack(spacing: 0) {
                // **The card moves; the frame does not.**
                //
                // The glyph is the app's mark for "a task is up" and belongs to the bar; the rings
                // are a position indicator, and an indicator that slides away with the thing it is
                // indicating has stopped indicating anything — no pager moves its own dots. Only
                // the words travel, which is what makes the movement legible: one object crossing a
                // fixed frame, rather than the whole bar sliding sideways inside itself.
                RunningGlyph(frameTint: y.secondary, ringTint: y.accent, size: 20)
                Spacer().frame(width: 11)
                VStack(alignment: .leading, spacing: 2) {
                    Text(inlinePlain(current.title).isEmpty ? "Untitled" : inlinePlain(current.title))
                        .font(Face.text(15, .medium)).foregroundStyle(y.ink)
                        .lineLimit(1).truncationMode(.tail)
                        .offset(x: dragX).opacity(cardOpacity)
                    eyebrow(current)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { onOpen(current) }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("now.card")
                .accessibilityLabel(inlinePlain(current.title))
                .accessibilityHint("Opens the focus screen")

                Spacer().frame(width: 8)
                TransportKey(live: live) { onToggleClock(current) }
            }
            .padding(.leading, 18).padding(.trailing, 10).padding(.vertical, 11)
            .frame(maxWidth: .infinity)
            // Running is a wash and a spine, not a flood. The bar filling solid with the accent made
            // every word on it a reversed colour and the whole surface the loudest thing on screen.
            .background(live ? y.accentFill : .clear)
            .overlay(alignment: .leading) { spine }
            .contentShape(Rectangle())
            .gesture(stack.count > 1 ? swipe : nil)
            // A bare identifier on a stack names nothing: SwiftUI only makes an element where one
            // is declared, so the bar was on screen and absent from the tree. `.contain` gives it
            // an element of its own while leaving the card, the key and the deck reachable inside.
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("now.player")
        }
    }

    private var cardOpacity: Double {
        1 - min(abs(dragX) / (commit * 2.4), 0.85)
    }

    /// The spine is the workspace, here and everywhere it appears. One device, one meaning.
    ///
    /// **Nothing at all while only one workspace is open**, rather than a neutral rule: a mark that
    /// always means the same thing means nothing, so when there is no repository to name there is no
    /// mark. Inset, because the dock above it has rounded top corners and a block does not.
    @ViewBuilder
    private var spine: some View {
        if let c = current?.workspaceColour, let ink = LabelPalette.swatchColor(c, dark: y.dark) {
            RoundedRectangle(cornerRadius: 1.5).fill(ink)
                .frame(width: 3).padding(.vertical, 10)
        }
    }

    private var swipe: some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { g in dragX = max(min(g.translation.width, commit * 1.8), -commit * 1.8) }
            .onEnded { g in
                // A flick counts even if it did not travel far — waiting for the full distance makes
                // a bar feel stuck to anyone who flicks rather than drags.
                let v = g.predictedEndTranslation.width - g.translation.width
                let fwd = dragX <= -commit || v <= -120
                let back = dragX >= commit || v >= 120
                guard fwd || back else {
                    withAnimation(.spring(response: 0.25, dampingFraction: 0.8)) { dragX = 0 }
                    return
                }
                // Out the way it was going, then the next one in from the other side: two halves of
                // one movement rather than a jump, so the bar never shows a card arriving at a
                // position it did not travel to.
                let out: CGFloat = 400
                withAnimation(.easeOut(duration: 0.13)) { dragX = fwd ? -out : out }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.13) {
                    let next = ((index + (fwd ? 1 : -1)) % stack.count + stack.count) % stack.count
                    selected = stack[next].nodeId
                    dragX = fwd ? out : -out
                    withAnimation(.easeInOut(duration: 0.17)) { dragX = 0 }
                }
            }
    }

    /// The eyebrow: which list, then what is happening on it.
    ///
    /// The list leads, because it is the fact the bar could not otherwise give you — a title alone
    /// does not say whether "Draft the deck" is work or the side project.
    ///
    /// **Only what the line cannot say without a word.** It said "ON THE GO" when idle and
    /// "RUNNING · 3:45" when counting, and both were labels on something already said: a clock that
    /// is ticking, in the accent, beside a ■, *is* the state. IT IS TIME keeps its words, because it
    /// is the one state with no numeral to carry it — the hour has come and nothing is counting.
    private func eyebrow(_ c: RunningStack.Now) -> some View {
        let state: String? = {
            if c.hasSession { return elapsedLabel(elapsed ?? c.elapsedSecs ?? 0) }
            if c.scheduled { return "IT IS TIME" }
            // Only when the name is missing — a task whose list could not be resolved would
            // otherwise have a blank eyebrow and look broken.
            return (c.listName?.isEmpty ?? true) ? "ON THE GO" : nil
        }()
        // Held back to 72%: a palette swatch is mixed to one lightness across every hue so no colour
        // out-shouts another *at full strength*, which is right for a label you are meant to find and
        // too much for a line telling you where you already are. The hue survives; the shout does not.
        let listInk = (LabelPalette.swatchColor(c.listColour, dark: y.dark) ?? y.muted).opacity(0.72)

        return HStack(spacing: 0) {
            HStack(spacing: 0) {
                if let list = c.listName, !list.isEmpty {
                    // The bottom of the scale, at the regular weight: the app's quietest voice,
                    // which is right for a line that tells you where you already are. The state
                    // keeps the bold — on one line the list is the standing fact and the clock is
                    // the news, and that is a hierarchy rather than an inconsistency.
                    Text(list.uppercased())
                        .font(Face.mono(10)).tracking(1).foregroundStyle(listInk)
                        // Yields first: the clock and the rings are fixed-width facts, and a list
                        // name is the only thing here that can be shortened and still be read.
                        .lineLimit(1).truncationMode(.tail).layoutPriority(0)
                    if state != nil {
                        Text("  ·  ").font(Face.mono(11)).foregroundStyle(y.dim)
                    }
                }
                if let state {
                    Text(state).font(Face.mono(11, bold: true)).tracking(1.2)
                        .foregroundStyle(live ? y.accent : y.muted)
                        .lineLimit(1).layoutPriority(1)
                        .accessibilityIdentifier("now.state")
                }
                Spacer(minLength: 0)
            }
            .offset(x: dragX).opacity(cardOpacity)
            DeckRings(stack: stack, index: index)
        }
        // Capped, so a wide window does not fling the rings across it. The words take the whole line
        // on a phone; on a tablet the dock is a thousand points wide and the same rule would put the
        // indicator an arm's length from the state it qualifies.
        .frame(maxWidth: 300, alignment: .leading)
    }
}

/// The deck counter — one ring per card you can reach by swiping.
///
/// Three readings out of one shape: rest weight is on the go and not where you are, full weight is
/// the card you are looking at, and the live ring in the accent is the one the clock is on.
///
/// **Beside the eyebrow, not beside the transport key.** On the right it reads fine at two rings and
/// has nowhere to go at four: the key is fixed to the edge and the rings would have had to grow into
/// the title. Here they grow into a line that is already short, and they sit with the state they
/// qualify.
///
/// The bindu was rejected for this. A row of dots with one filled is a gauge, and the bindu is the
/// centre and never a gauge; it is also the done state of the task glyph, so a bare filled dot among
/// rings would read as "finished" on the one card that is running.
///
/// **The rings hold still and the mark slides between them.** An indicator that leaves the screen
/// with the thing it indicates has stopped indicating; a full-ink ring rides from position to
/// position instead, so a state is watched changing rather than noticed having changed.
private struct DeckRings: View {
    let stack: [RunningStack.Now]
    let index: Int
    @Environment(\.y) private var y

    /// How many rings before the deck is counted instead of drawn. Five — below this you read the
    /// row; above it you would be counting, and counting is what the numeral is for.
    private static let max = 5

    var body: some View {
        if stack.count >= 2 {
            HStack(spacing: 4) {
                if stack.count <= Self.max { rings } else { counted }
            }
            .padding(.leading, 8)
            .accessibilityElement(children: .ignore)
            .accessibilityIdentifier("now.deck")
            .accessibilityLabel("Card \(index + 1) of \(stack.count)")
        }
    }

    /// One ring's width plus the gap: what the mark travels to move one place.
    private var pitch: CGFloat { YantraIcons.small + 4 }

    private var rings: some View {
        ZStack(alignment: .leading) {
            HStack(spacing: 4) {
                ForEach(stack) { card in
                    // A card that is running but not the one you are looking at still says so.
                    YantraIcon(mark: card.hasSession ? .ringLive : .ring, size: YantraIcons.small,
                               // Lifted rather than thinned: a heavier rest ring would stop being
                               // the mark.
                               tint: card.hasSession ? y.accent : y.ink.opacity(0.55))
                }
            }
            // Where you are, riding over the row rather than being one of it. Full ink on top of a
            // rest ring reads as the same mark picked out, which is what selection is.
            YantraIcon(mark: stack[index].hasSession ? .ringLive : .ring, size: YantraIcons.small,
                       tint: stack[index].hasSession ? y.accent : y.ink)
                .offset(x: pitch * CGFloat(index))
                .animation(.spring(response: 0.34, dampingFraction: 0.82), value: index)
        }
    }

    /// Past the point where a row of rings can be read at a glance, one ring and a numeral. A
    /// diagram you have to count is doing a table's job, and eight rings is counting.
    private var counted: some View {
        let live = stack.contains { $0.hasSession }
        return HStack(spacing: 4) {
            YantraIcon(mark: live ? .ringLive : .ring, size: YantraIcons.small,
                       tint: live ? y.accent : y.ink)
            Text("\(index + 1)/\(stack.count)").font(Face.mono(12, bold: true)).foregroundStyle(y.muted)
        }
    }
}

/// The one control: play, or stop.
private struct TransportKey: View {
    let live: Bool
    var action: () -> Void
    @Environment(\.y) private var y

    var body: some View {
        Button(action: action) {
            // Both are filled, which is the second and last fill exception.
            YantraIcon(mark: live ? .stop : .play, size: YantraIcons.medium, tint: y.accent)
                .frame(width: 44, height: 44)
                .background(Circle().fill(y.accentFill))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("now.transport")
        .accessibilityLabel(live ? "Stop the clock" : "Start the clock")
    }
}

/// The seam between two rows of the dock.
///
/// One surface holding two things needs to say they are two things. Without it the player's words
/// and the keys beneath them float on one ground with nothing between them, which is the opposite
/// failure to the one the dock fixed: it read as two objects, and then as none.
struct NowDockSeam: View {
    @Environment(\.y) private var y
    var body: some View { Rectangle().fill(y.hairline).frame(height: 1) }
}

/// The dock: one surface at the foot of a screen, holding whatever that screen puts there.
///
/// The player used to be its own panel — its own ground, its own rounded top — stacked above the tab
/// bar's or the capture field's. Two surfaces, one above the other, read as two objects that happen
/// to be adjacent, and the player looked bolted on rather than part of the furniture.
///
/// One ground, one rounded top, and the contents sit inside it. The screen's permanent bar and the
/// thing that is running are then the same object.
struct NowDock<Content: View>: View {
    @ViewBuilder var content: Content
    @Environment(\.y) private var y

    var body: some View {
        VStack(spacing: 0) { content }
            .frame(maxWidth: .infinity)
            .background(y.band)
            .clipShape(UnevenRoundedRectangle(topLeadingRadius: Layout.sheetRadius,
                                              topTrailingRadius: Layout.sheetRadius))
    }
}

/// The bottom of a screen that can capture: the field, and above it the player when something is on
/// the go.
///
/// **Capture is always open.** It used to hide behind a key whenever anything was running, which
/// made the bottom of the screen mean two different things depending on state, and put a tap in
/// front of the highest-frequency action in the app. Writing something down should never cost a mode.
///
/// **The player sits directly above the field**, which is the same rule Home follows with its nav
/// strip: the player is never the outermost thing on a screen that has a permanent bar. It was the
/// other way round once, on the argument that the field should stay where the thumb expects it — and
/// did the opposite, because a player appearing *underneath* pushes the field up by its own height.
///
/// **Both stand down while the keyboard is up.** A bar over the line you are typing is worse than no
/// bar: what is running is a thing you can check in a moment, and what you are writing is a thing
/// you lose.
struct BottomBar<Capture: View>: View {
    var onOpenNow: (RunningStack.Now) -> Void
    /// False where the screen has no business showing it — a task's own page.
    var showNow: Bool = true
    @ViewBuilder var capture: Capture

    @EnvironmentObject private var model: AppModel
    /// The keyboard's own report, because a bar has to stand down for the line being typed.
    @State private var keyboard = false
    /// The task that already has the clock, while the person decides. Its presence is the dialog.
    @State private var occupiedBy: String?
    /// What they were trying to start when they met it.
    @State private var wanted: RunningStack.Now?

    private var shown: [RunningStack.Now] { showNow && !keyboard ? model.running : [] }

    /// The key's behaviour lives here rather than at each call site: every screen that shows a
    /// player shows the same one, and four copies of "press play" is four places for them to drift.
    private func toggleClock(_ now: RunningStack.Now) {
        guard !now.hasSession else { model.stopTiming(); return }
        switch model.startTiming(now.nodeId, title: now.title) {
        case .started: break
        case let .occupied(_, byTitle): wanted = now; occupiedBy = byTitle
        }
    }

    var body: some View {
        NowDock {
            if !shown.isEmpty {
                NowPlayer(stack: shown,
                          elapsed: model.timer.state.flatMap { $0.isFinished ? nil : $0.elapsedSecs },
                          onOpen: onOpenNow, onToggleClock: toggleClock)
                NowDockSeam()
            }
            capture
        }
        .modifier(SwitchHereDialog(runningTitle: $occupiedBy) {
            if let w = wanted { model.switchTimingTo(w.nodeId, title: w.title) }
            wanted = nil
        })
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
            keyboard = true
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            keyboard = false
        }
    }
}

/// The offer made when you start a focus while one is already running.
///
/// This is the one exclusivity the app has left. Several tasks can be on the go at once — that is
/// what the deck is for — but a focus session measures attention, and there is one of that. So a
/// second start has to take the clock from the first, which closes that session as interrupted in a
/// ledger someone will read later. Doing it silently is how a day's record ends up holding sessions
/// the person does not remember ending.
///
/// Nothing is offered here except switching. A second clock is not on the table, which is the point.
struct SwitchHereDialog: ViewModifier {
    @Binding var runningTitle: String?
    var onConfirm: () -> Void

    func body(content: Content) -> some View {
        content.alert("A focus is already running", isPresented: Binding(
            get: { runningTitle != nil }, set: { if !$0 { runningTitle = nil } })) {
                Button("SWITCH HERE") { onConfirm(); runningTitle = nil }
                Button("Leave it running", role: .cancel) { runningTitle = nil }
            } message: {
                let t = (runningTitle?.isEmpty ?? true) ? "Untitled" : runningTitle!
                Text("“\(t)” has the clock. Starting this one stops that session — the time it has "
                     + "already taken still counts.")
            }
    }
}
