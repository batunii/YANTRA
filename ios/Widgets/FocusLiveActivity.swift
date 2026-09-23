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
                    SessionMark(size: 30, running: !ctx.state.paused, accent: p.accent, outline: p.secondary)
                        .padding(.leading, 6)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    // The clock, and under it what the clock means. A number alone leaves you
                    // working out whether it is counting up or down.
                    VStack(alignment: .trailing, spacing: 1) {
                        Clock(state: ctx.state, size: 22).foregroundStyle(p.ink)
                        Text(ctx.state.paused ? "Paused" : ctx.state.isOpen ? "Stopwatch" : "Left")
                            .font(SharedFace.text(10)).foregroundStyle(p.secondary)
                    }.padding(.trailing, 6)
                }
                DynamicIslandExpandedRegion(.center) {
                    // Two lines, because a task called anything real does not fit on one at this
                    // width, and the expanded island is the one place there is room to say it.
                    Text(ctx.state.title)
                        .font(SharedFace.text(13, bold: true)).foregroundStyle(p.ink)
                        .lineLimit(2).multilineTextAlignment(.center)
                        .padding(.horizontal, 2)
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
                SessionMark(size: 17, running: !ctx.state.paused, accent: p.accent, outline: p.secondary)
                    .padding(.leading, 1)
            } compactTrailing: {
                // **Narrow on purpose.** The compact island grows outwards from the middle, so every
                // point the trailing side takes is a point taken off the status bar on *both* sides
                // — and the first casualty is the clock, which ends up half covered by a timer
                // saying roughly the same thing. This used to force `minWidth: 40` and then let a
                // stopwatch run past an hour into `1:02:03`, which is about as wide as this side can
                // be before the time disappears.
                CompactClock(state: ctx.state).foregroundStyle(p.accent)
            } minimal: {
                SessionMark(size: 16, running: !ctx.state.paused, accent: p.accent, outline: p.secondary)
            }
            .keylineTint(p.accent)
            .widgetURL(URL(string: "yantra://focus"))
        }
    }
}

/// The clock as the compact island can afford it.
///
/// Everything here is about width. A countdown is left as `Text(timerInterval:)`, which renders
/// `mm:ss` and is the common case; a **stopwatch** is not, because `style: .timer` grows a third
/// field after an hour and the island grows with it. Past the hour it says `1h04` instead, which is
/// the same fact in four characters, and a session that has been running for over an hour is not one
/// anybody is reading to the second.
struct CompactClock: View {
    let state: FocusAttributes.ContentState
    var body: some View {
        Group {
            if state.finished {
                Image(systemName: "checkmark").font(.system(size: 12, weight: .bold))
            } else if state.paused {
                Image(systemName: "pause.fill").font(.system(size: 11, weight: .bold))
            } else if state.isOpen {
                Text(state.startedAt, style: .timer)
            } else if let end = state.endAt {
                Text(timerInterval: state.startedAt...end, countsDown: true)
            }
        }
        .font(SharedFace.mono(13, bold: true))
        .monospacedDigit()
        .lineLimit(1)
        .multilineTextAlignment(.trailing)
        // Room for `59:59` and no more. A longer reading is scaled rather than allowed to push the
        // island out over the status bar.
        .frame(maxWidth: 46)
        .minimumScaleFactor(0.75)
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
