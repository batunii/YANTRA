import SwiftUI
import YantraCore

/// Focus is a ledger, not a timer. Two instruments — a committed countdown and an open stopwatch —
/// write the same record.
struct FocusView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Binding var path: NavigationPath
    let requestedNodeId: String?
    @State private var minutes = 25
    @State private var customText = ""
    @State private var askShort: (() -> Void)? = nil
    @State private var switchTo: Int? = nil

    private var live: FocusTimer.State? { model.timer.state }
    private var requested: Node? { requestedNodeId.flatMap { model.index.nodes[$0] } }

    var body: some View {
        VStack(spacing: 0) {
            HStack { NavCircle(mark: .back) { path.removeLast() }; Spacer() }.padding(.horizontal, Layout.pageMargin).padding(.top, 8)
            if let s = live, s.isFinished {
                done(s)
            } else if let s = live, requestedNodeId == nil || s.nodeId == requestedNodeId {
                active(s)
            } else if let n = requested {
                ScrollView { setup(n) }
            } else {
                Spacer()
                VStack(spacing: 18) {
                    BhupuraMark(size: 60)
                    Text("Nothing in focus.\nStart a session from any task.").font(Face.text(14.5)).foregroundStyle(y.muted).multilineTextAlignment(.center)
                }
                Spacer()
            }
        }
        .background(y.page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .alert("Too short to record", isPresented: Binding(get: { askShort != nil }, set: { if !$0 { askShort = nil } })) {
            Button("End anyway", role: .destructive) { askShort?(); askShort = nil }
            Button("Keep going", role: .cancel) { askShort = nil }
        } message: { Text("Nothing has been focused on for long enough to keep. Stopping now ends the session without adding it to your history.") }
        .alert("A focus is already running", isPresented: Binding(get: { switchTo != nil }, set: { if !$0 { switchTo = nil } })) {
            Button("SWITCH HERE") { if let s = switchTo, let n = requested { model.timer.start(nodeId: n.id, title: n.title ?? "", plannedSecs: s) }; switchTo = nil }
            Button("Leave it running", role: .cancel) { switchTo = nil }
        } message: { Text("“\(live?.nodeTitle.isEmpty == false ? live!.nodeTitle : "Untitled")” has the clock. Starting this one stops that session — the time it has already taken still counts.") }
    }

    // MARK: states

    private func done(_ s: FocusTimer.State) -> some View {
        VStack(spacing: 18) {
            Spacer()
            ZStack {
                Circle().fill(y.accent.opacity(0.16)).frame(width: 74, height: 74)
                Circle().fill(y.accent).frame(width: 46, height: 46)
                YantraIcon(mark: .check, size: YantraIcons.large, tint: y.onAccent)
            }
            Text("Session complete").font(Face.display(24)).tracking(-0.4).foregroundStyle(y.ink)
            Text("\((s.isOpen ? s.elapsedSecs : s.plannedSecs) / 60) min on \(s.nodeTitle.isEmpty ? "your task" : s.nodeTitle)").font(Face.text(13.5)).foregroundStyle(y.muted).multilineTextAlignment(.center)
            HStack(spacing: 10) {
                YantraButton(label: "Start another", tone: .quiet) { model.timer.dismissFinished() }
                YantraButton(label: "Done", tone: .soft) { model.timer.dismissFinished(); path.removeLast() }
            }
            Spacer()
        }.padding(.horizontal, 30)
    }

    private func active(_ s: FocusTimer.State) -> some View {
        let history = model.sessions(for: s.nodeId)
        return VStack(spacing: 0) {
            Spacer()
            SectionLabel(text: "Focusing", color: y.accentText)
            Button { path.append(Route.node(s.nodeId)) } label: {
                HStack(spacing: 6) {
                    Text(s.nodeTitle.isEmpty ? "Untitled task" : s.nodeTitle).font(Face.text(16, .bold)).foregroundStyle(y.ink).lineLimit(2).multilineTextAlignment(.center)
                    YantraIcon(mark: .forward, size: YantraIcons.small, tint: y.dim)
                }
            }.buttonStyle(.plain).padding(.top, 6).padding(.horizontal, 30)
            FocusGlyph(dayCounts: dayCounts(history), progress: s.progress).frame(width: 272, height: 272).padding(.top, 18)
            Text(String(format: "%d:%02d", (s.isOpen ? s.elapsedSecs : s.remainingSecs) / 60, (s.isOpen ? s.elapsedSecs : s.remainingSecs) % 60))
                .font(Face.mono(46, bold: true)).foregroundStyle(y.ink).monospacedDigit().padding(.top, 26)
            Text((s.isRunning ? "focus" : "paused") + (history.isEmpty ? "" : " · session \(history.count + 1) · day \(dayCounts(history).count)"))
                .font(Face.mono(11)).kerning(1).foregroundStyle(y.dim).padding(.top, 6)
            Spacer()
            Button { s.isRunning ? model.timer.pause() : model.timer.resume() } label: {
                YantraIcon(mark: s.isRunning ? .pause : .play, size: 28, tint: y.accent)
                    .frame(width: 76, height: 76).background(Circle().fill(y.accentFill)).overlay(Circle().stroke(y.accentBorder, lineWidth: 1))
            }.buttonStyle(.plain)
            HStack(spacing: 10) {
                YantraButton(label: "Finish", tone: .soft, mark: .focus) { stopping(s) { model.timer.finish() } }
                YantraButton(label: "Drop", tone: .quiet) { stopping(s) { model.timer.abandon() } }
            }.padding(.horizontal, 30).padding(.top, 16)
            Spacer().frame(height: 32)
        }
    }

    private func stopping(_ s: FocusTimer.State, _ end: @escaping () -> Void) {
        if FocusOutcome.wouldBeKept(elapsed: s.elapsedSecs, planned: s.plannedSecs) { end() } else { askShort = end }
    }

    private func setup(_ n: Node) -> some View {
        let history = model.sessions(for: n.id)
        return VStack(alignment: .leading, spacing: 0) {
            SectionLabel(text: "Focus on").padding(.top, 16)
            Button { path.append(Route.node(n.id)) } label: {
                HStack(alignment: .top) {
                    Text(inlinePlain(n.title ?? "").isEmpty ? "Untitled task" : inlinePlain(n.title ?? "")).font(Face.display(32)).tracking(-0.4).foregroundStyle(y.ink).lineLimit(3).multilineTextAlignment(.leading)
                    Spacer()
                    YantraIcon(mark: .forward, size: YantraIcons.medium, tint: y.dim).padding(.top, 10)
                }
            }.buttonStyle(.plain).padding(.top, 6)
            if !history.isEmpty {
                FocusGlyph(dayCounts: dayCounts(history), progress: nil).frame(width: 180, height: 180).frame(maxWidth: .infinity).padding(.top, 18)
                Text("\(history.count) session\(history.count == 1 ? "" : "s") · \(dayCounts(history).count) day\(dayCounts(history).count == 1 ? "" : "s")")
                    .font(Face.text(12.5)).foregroundStyle(y.muted).frame(maxWidth: .infinity).padding(.top, 8)
            }
            SectionLabel(text: "How long").padding(.top, 28)
            HStack(spacing: 10) {
                ForEach([15, 25, 50], id: \.self) { m in durationChip("\(m)", selected: minutes == m) { minutes = m } }
                durationChip([15, 25, 50].contains(minutes) ? "···" : "\(minutes)", selected: ![15, 25, 50].contains(minutes)) { customText = "" }
            }.padding(.top, 12)
            HStack(spacing: 10) {
                TextField("minutes", text: $customText).keyboardType(.numberPad).font(Face.text(15)).foregroundStyle(y.ink)
                    .padding(.horizontal, 14).padding(.vertical, 13).background(RoundedRectangle(cornerRadius: 12).fill(y.surfaceHigh)).overlay(RoundedRectangle(cornerRadius: 12).stroke(y.tileBorder, lineWidth: 1))
                YantraButton(label: "Set", tone: .quiet, enabled: (Int(customText) ?? 0) > 0) { minutes = Int(customText) ?? minutes }.frame(width: 90)
            }.padding(.top, 10)
            Text("Anything up to a few hours. 90 for a long stretch, 5 for a nudge.").font(Face.text(11.5)).foregroundStyle(y.dim).padding(.top, 6)
            YantraButton(label: "Focus for \(minutes) min", tone: .soft) { start(n, minutes * 60) }.padding(.top, 24)
            YantraButton(label: "Just start the clock", tone: .quiet) { start(n, 0) }.padding(.top, 10)
            Text("A set length is a promise to yourself; the clock just records what you gave. Both go into the same history.")
                .font(Face.text(12)).foregroundStyle(y.dim).padding(.top, 10)
            if !history.isEmpty {
                SectionLabel(text: "History").padding(.top, 28).padding(.bottom, 6)
                ForEach(history.reversed()) { s in SessionRow(session: s) }
            }
            Spacer().frame(height: 24)
        }.padding(.horizontal, 30)
    }

    private func start(_ n: Node, _ secs: Int) {
        if let s = live, !s.isFinished, s.nodeId != n.id { switchTo = secs; return }
        model.timer.start(nodeId: n.id, title: n.title ?? "", plannedSecs: secs)
    }

    private func durationChip(_ label: String, selected: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(Face.text(15, selected ? .heavy : .bold)).foregroundStyle(selected ? y.accentText : y.secondary)
                .frame(maxWidth: .infinity).padding(.vertical, 16)
                .background(RoundedRectangle(cornerRadius: 14).fill(selected ? y.accentFill : y.cardBg))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(selected ? y.accent : .clear, lineWidth: 1.5))
        }.buttonStyle(.plain)
    }

    private func dayCounts(_ sessions: [FocusSession]) -> [Int] {
        var byDay: [LocalDate: Int] = [:]
        for s in sessions where s.counts { byDay[.of(Date(timeIntervalSince1970: TimeInterval(s.startedAt) / 1000)), default: 0] += 1 }
        return byDay.keys.sorted().map { byDay[$0]! }
    }
}

struct SessionRow: View {
    let session: FocusSession
    @Environment(\.y) private var y
    var body: some View {
        HStack(spacing: 12) {
            if FocusOutcome.keptItsPromise(session.outcome, planned: session.plannedSecs) {
                YantraIcon(mark: .focus, size: YantraIcons.small, tint: y.accent).frame(width: 16)
            } else { Text("◌").font(Face.text(16)).foregroundStyle(y.dim).frame(width: 16) }
            VStack(alignment: .leading, spacing: 2) {
                Text(Date(timeIntervalSince1970: TimeInterval(session.startedAt) / 1000), format: .dateTime.month(.abbreviated).day().hour().minute()).font(Face.text(13.5, .medium)).foregroundStyle(y.ink)
                Text(session.endedAt == nil ? "In progress" : "\(how) · \(durationLabel(session.actualSecs ?? 0))").font(Face.text(12)).foregroundStyle(y.muted)
            }
            Spacer()
            Text("\(session.plannedSecs / 60)m planned").font(Face.text(11, .semibold)).foregroundStyle(y.dim)
        }.padding(.vertical, 8)
    }
    private var how: String {
        switch session.outcome {
        case FocusOutcome.ranOut: return "Ran its course"
        case FocusOutcome.stopped: return session.plannedSecs > 0 ? "Stopped early" : "Stopped"
        case FocusOutcome.lost: return "Ended by itself"
        default: return "Interrupted"
        }
    }
}

/// The focus glyph: a bhupura frame, a track, one trikona per day and one ring per session in a
/// band between them, a live arc for the running session, and the constant bindu at the centre.
struct FocusGlyph: View {
    let dayCounts: [Int]
    let progress: Double?
    @Environment(\.y) private var y

    var body: some View {
        Canvas { ctx, size in
            let s = min(size.width, size.height), u = s / 28
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            ctx.stroke(bhupuraPath(s).offsetBy(dx: c.x - s / 2, dy: c.y - s / 2), with: .color(y.secondary.opacity(0.9)), style: StrokeStyle(lineWidth: 1.4, lineJoin: .round))
            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - 8 * u, y: c.y - 8 * u, width: 16 * u, height: 16 * u)), with: .color(y.secondary.opacity(0.25)), lineWidth: 0.8 * u)
            // Strata: one trikona per day (alternating orientation), then that day's rings.
            var marks: [(tri: Bool, up: Bool)] = []
            for (d, n) in dayCounts.enumerated() { marks.append((true, d % 2 == 1)); for _ in 0..<n { marks.append((false, false)) } }
            let total = marks.count
            for (i, m) in marks.enumerated() {
                let r = total <= 1 ? 2.8 * u : (2.8 + (7.2 - 2.8) * Double(i) / Double(total - 1)) * u
                if m.tri {
                    var p = Path()
                    for k in 0..<3 {
                        let a = (Double(k) * 120 + (m.up ? -90 : 90)) * .pi / 180
                        let pt = CGPoint(x: c.x + r * cos(a), y: c.y + r * sin(a))
                        if k == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
                    }
                    p.closeSubpath()
                    ctx.stroke(p, with: .color(y.secondary.opacity(0.55)), lineWidth: 0.28 * u)
                } else {
                    ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)), with: .color(y.accent.opacity(0.42)), lineWidth: 0.16 * u)
                }
            }
            if let p = progress {
                var arc = Path()
                arc.addArc(center: c, radius: 8 * u, startAngle: .degrees(-90), endAngle: .degrees(-90 + 360 * p), clockwise: false)
                ctx.stroke(arc, with: .color(y.accent), style: StrokeStyle(lineWidth: 1.7 * u, lineCap: .round))
            }
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - 1.6 * u, y: c.y - 1.6 * u, width: 3.2 * u, height: 3.2 * u)), with: .color(y.accent))
        }
    }
}
