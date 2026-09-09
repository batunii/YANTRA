import SwiftUI
import WidgetKit
import YantraCore

struct FocusEntry: TimelineEntry {
    let date: Date
    let live: LiveSession?
    let lastTitle: String?
    let hasLast: Bool
}

struct FocusProvider: TimelineProvider {
    func placeholder(in context: Context) -> FocusEntry { FocusEntry(date: Date(), live: nil, lastTitle: "Pick a task in the app", hasLast: false) }
    func getSnapshot(in context: Context, completion: @escaping (FocusEntry) -> Void) { completion(entry()) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<FocusEntry>) -> Void) {
        let e = entry()
        var entries = [e]
        // When a committed session will end, add an entry at that moment so the panel flips to DONE
        // without anyone reloading it.
        if let s = e.live, let end = s.endDate() { entries.append(FocusEntry(date: end, live: s, lastTitle: e.lastTitle, hasLast: e.hasLast)) }
        completion(Timeline(entries: entries, policy: .after(Date().addingTimeInterval(1800))))
    }
    func entry() -> FocusEntry {
        let live = LiveSession.load()
        let last = AppGroup.defaults.string(forKey: "last_focus_title")
        return FocusEntry(date: Date(), live: live, lastTitle: last, hasLast: AppGroup.defaults.string(forKey: "last_focus_node") != nil)
    }
}

/// The focus panel — FOCUS / FOCUSING / PAUSED / DONE, a live clock, transport keys.
struct FocusWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "ie.shoonya.yantra.focus", provider: FocusProvider()) { e in
            FocusPanel(entry: e).containerBackground(SharedPalette().surface, for: .widget)
        }
        .configurationDisplayName("Focus")
        .description("Your focus timer, live on the home screen")
        .supportedFamilies([.systemMedium])
    }
}

struct FocusPanel: View {
    let entry: FocusEntry
    var body: some View {
        let p = SharedPalette()
        VStack(alignment: .leading, spacing: 8) {
            if let s = entry.live {
                let spent = s.isSpent
                Text(spent ? "DONE" : s.isPaused ? "PAUSED" : "FOCUSING").font(SharedFace.mono(11, bold: true)).kerning(1.2).foregroundStyle(s.isPaused ? p.accent : p.dim)
                Text(s.title.isEmpty ? "Focus" : s.title).font(SharedFace.text(15, bold: true)).foregroundStyle(p.ink).lineLimit(1)
                HStack(spacing: 10) {
                    SessionMark(size: 30, running: !s.isPaused && !spent, accent: p.accent, outline: p.secondary)
                    if spent { Text(sessionClock(s.plannedSecs)).font(SharedFace.mono(30, bold: true)).foregroundStyle(p.accent) }
                    else if s.isPaused { Text(sessionClock(s.isOpen ? s.elapsed() : s.remaining())).font(SharedFace.mono(30, bold: true)).foregroundStyle(p.ink) }
                    else if s.isOpen { Text(s.effectiveStart(), style: .timer).font(SharedFace.mono(30, bold: true)).foregroundStyle(p.ink).monospacedDigit() }
                    else if let end = s.endDate() { Text(timerInterval: s.effectiveStart()...end, countsDown: true).font(SharedFace.mono(30, bold: true)).foregroundStyle(p.ink).monospacedDigit() }
                    Spacer()
                    if spent {
                        Button(intent: FocusIntent(.stop)) { Text("Dismiss").font(SharedFace.text(13, bold: true)) }.tint(p.accent).buttonStyle(.borderedProminent)
                    } else {
                        Key(icon: s.isPaused ? "play.fill" : "pause.fill", primary: s.isPaused, intent: FocusIntent(s.isPaused ? .resume : .pause))
                        Key(icon: "stop.fill", primary: false, intent: FocusIntent(.stop))
                    }
                }
                if !s.isOpen, !s.isPaused, !spent, let end = s.endDate() {
                    ProgressView(timerInterval: s.effectiveStart()...end, countsDown: false, label: { EmptyView() }, currentValueLabel: { EmptyView() }).tint(p.accent).frame(height: 4)
                }
            } else {
                Text("FOCUS").font(SharedFace.mono(11, bold: true)).kerning(1.2).foregroundStyle(p.dim)
                Text(entry.lastTitle ?? "Pick a task in the app").font(SharedFace.text(15, bold: true)).foregroundStyle(p.ink).lineLimit(2)
                Spacer(minLength: 0)
                if entry.hasLast {
                    Button(intent: FocusIntent(.startLast)) { Text("Start 25m").font(SharedFace.text(13, bold: true)) }.tint(p.accent).buttonStyle(.borderedProminent)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .widgetURL(URL(string: "yantra://focus"))
    }
}

/// A transport key: the solid bhupura tinted accent (or accent at 16%) with a glyph inside.
struct Key: View {
    let icon: String
    let primary: Bool
    let intent: FocusIntent
    var body: some View {
        let p = SharedPalette()
        Button(intent: intent) {
            ZStack {
                bhupuraPath(42).fill(primary ? p.accent : p.accent.opacity(0.16))
                Image(systemName: icon).font(.system(size: 15, weight: .bold)).foregroundStyle(primary ? p.onAccent : p.accent)
            }.frame(width: 42, height: 42)
        }.buttonStyle(.plain)
    }
}

/// The mark itself, unframed, with the running session inside it. Transparent container so the
/// wallpaper shows through everywhere but the gated square.
struct BhupuraWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "ie.shoonya.yantra.bhupura", provider: FocusProvider()) { e in
            BhupuraTile(entry: e).containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Bhupura")
        .description("The mark itself, with your focus inside it")
        .supportedFamilies([.systemSmall])
        .contentMarginsDisabled()
    }
}

struct BhupuraTile: View {
    let entry: FocusEntry
    var body: some View {
        let p = SharedPalette()
        GeometryReader { g in
            let side = min(g.size.width, g.size.height)
            ZStack {
                bhupuraPath(side).fill(p.surface)
                bhupuraPath(side).stroke(p.ink, lineWidth: 1.4)
                VStack(spacing: 6) {
                    if let s = entry.live, !s.isSpent {
                        Group {
                            if s.isPaused { Text(sessionClock(s.isOpen ? s.elapsed() : s.remaining())) }
                            else if s.isOpen { Text(s.effectiveStart(), style: .timer) }
                            else if let end = s.endDate() { Text(timerInterval: s.effectiveStart()...end, countsDown: true) }
                        }
                        .font(SharedFace.mono(min(max(side * 0.125, 11), 30), bold: true)).foregroundStyle(p.accent).monospacedDigit().multilineTextAlignment(.center)
                    }
                    let live = entry.live
                    let cmd: FocusCommand = live == nil ? .startLast : (live!.isPaused ? .resume : .pause)
                    let key = max(side * (live == nil ? 0.26 : 0.20), 30)
                    Button(intent: FocusIntent(cmd)) {
                        ZStack {
                            bhupuraPath(key).fill(p.accent)
                            Image(systemName: live == nil ? "play.fill" : live!.isPaused ? "play.fill" : "pause.fill").font(.system(size: key * 0.36, weight: .bold)).foregroundStyle(p.onAccent)
                        }.frame(width: key, height: key)
                    }.buttonStyle(.plain)
                    if side > 120 {
                        Text((live?.title ?? entry.lastTitle) ?? "").font(SharedFace.text(min(max(side * 0.062, 9), 13))).foregroundStyle(p.ink.opacity(0.65)).lineLimit(1)
                    }
                }.frame(width: side * 0.56)
            }
            .frame(width: side, height: side).position(x: g.size.width / 2, y: g.size.height / 2)
        }
        .widgetURL(URL(string: "yantra://focus"))
    }
}
