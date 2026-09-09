import ActivityKit
import SwiftUI
import WidgetKit

/// The running session on the lock screen and in the Dynamic Island.
///
/// Android had to choose between the status-bar chip and its custom transport; here the compact
/// island shows the mark and a ticking clock, the expanded island and the lock-screen banner carry
/// the full transport, and the clock ticks with no process alive. Paused shows a frozen readout,
/// because there is no clock to run.
struct FocusLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: FocusAttributes.self) { ctx in
            LockScreenBanner(state: ctx.state)
                .activityBackgroundTint(SharedPalette().surface)
                .activitySystemActionForegroundColor(SharedPalette().ink)
        } dynamicIsland: { ctx in
            let p = SharedPalette()
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    SessionMark(size: 34, running: !ctx.state.paused, accent: p.accent, outline: p.secondary).padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Clock(state: ctx.state, size: 24).foregroundStyle(p.ink).padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(ctx.state.title).font(SharedFace.text(14, bold: true)).foregroundStyle(p.ink).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 8) {
                        if !ctx.state.isOpen, let end = ctx.state.endAt, !ctx.state.paused {
                            ProgressView(timerInterval: ctx.state.startedAt...end, countsDown: false, label: { EmptyView() }, currentValueLabel: { EmptyView() })
                                .tint(p.accent).frame(height: 4)
                        }
                        Transport(state: ctx.state)
                    }.padding(.top, 4)
                }
            } compactLeading: {
                SessionMark(size: 18, running: !ctx.state.paused, accent: p.accent, outline: p.secondary)
            } compactTrailing: {
                Clock(state: ctx.state, size: 13).foregroundStyle(p.ink).frame(minWidth: 40)
            } minimal: {
                SessionMark(size: 16, running: !ctx.state.paused, accent: p.accent, outline: p.secondary)
            }
            .keylineTint(p.accent)
            .widgetURL(URL(string: "yantra://focus"))
        }
    }
}

/// `Text(timerInterval:)` ticks without a reload; paused shows the frozen string, as the Android
/// notification does the moment a session pauses.
struct Clock: View {
    let state: FocusAttributes.ContentState
    var size: CGFloat
    var body: some View {
        Group {
            if state.finished {
                Text("Done").font(SharedFace.mono(size, bold: true))
            } else if state.paused {
                Text(state.frozen).font(SharedFace.mono(size, bold: true))
            } else if state.isOpen {
                Text(state.startedAt, style: .timer).font(SharedFace.mono(size, bold: true))
            } else if let end = state.endAt {
                Text(timerInterval: state.startedAt...end, countsDown: true).font(SharedFace.mono(size, bold: true))
            }
        }
        .monospacedDigit().multilineTextAlignment(.trailing)
    }
}

struct Transport: View {
    let state: FocusAttributes.ContentState
    var body: some View {
        let p = SharedPalette()
        HStack(spacing: 10) {
            if state.paused {
                Button(intent: FocusIntent(.resume)) { Label("Resume", systemImage: "play.fill") }.tint(p.accent)
            } else {
                Button(intent: FocusIntent(.pause)) { Label("Pause", systemImage: "pause.fill") }.tint(p.secondary)
            }
            Button(intent: FocusIntent(.stop)) { Label("Stop", systemImage: "stop.fill") }.tint(p.secondary)
            Button(intent: FocusIntent(.done)) { Label("Done", systemImage: "checkmark") }.tint(p.accent)
        }
        .font(SharedFace.text(13, bold: true))
        .buttonStyle(.bordered).controlSize(.small)
    }
}

struct LockScreenBanner: View {
    let state: FocusAttributes.ContentState
    var body: some View {
        let p = SharedPalette()
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 12) {
                SessionMark(size: 38, running: !state.paused, accent: p.accent, outline: p.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(state.title).font(SharedFace.text(15, bold: true)).foregroundStyle(p.ink).lineLimit(1)
                    Text(state.paused ? "Paused" : state.isOpen ? "Stopwatch" : "Focusing").font(SharedFace.text(12)).foregroundStyle(p.secondary)
                }
                Spacer()
                Clock(state: state, size: 26).foregroundStyle(p.ink)
            }
            if !state.isOpen, let end = state.endAt, !state.paused {
                ProgressView(timerInterval: state.startedAt...end, countsDown: false, label: { EmptyView() }, currentValueLabel: { EmptyView() })
                    .tint(p.accent).frame(height: 4)
            }
            Transport(state: state)
        }
        .padding(14)
    }
}
