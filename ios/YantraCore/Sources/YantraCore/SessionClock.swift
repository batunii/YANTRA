import Foundation

/// The one clock format every surface that shows a running session uses: `m:ss`, growing an
/// hours field only when there is an hour. Matches Android's `Chronometer` and `sessionClock()`
/// so the digits do not change shape at the moment a session pauses.
public func sessionClock(_ secs: Int) -> String {
    let s = max(secs, 0)
    let h = s / 3600
    if h > 0 { return String(format: "%d:%02d:%02d", h, (s % 3600) / 60, s % 60) }
    return String(format: "%d:%02d", s / 60, s % 60)
}
