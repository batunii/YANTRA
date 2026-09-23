import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// A record of what the app actually did, kept on the device so it can be read afterwards.
///
/// Written for a week of real use: the app is being lived with rather than tested, and the
/// interesting failures will be the ones nobody was watching for — a sync that stopped happening on
/// Thursday, a token that lapsed overnight, a write refused while the phone was in a pocket. None
/// of those leave a trace anywhere by default, and "it broke at some point" is not something you
/// can fix a week later.
///
/// ## What it is careful about
///
/// **No content.** Task titles, page text, labels and repository names are the person's; a log that
/// quotes them is a log that cannot be shared, mailed or pasted into a terminal. So this records
/// *shapes*: ids, counts, durations, outcomes and error text from GitHub. The one thing worth
/// knowing about a title is whether it was empty.
///
/// **Append-only, and cheap.** One line of JSON per event, opened and closed per write. A logger
/// that holds a handle or a buffer is a logger that loses the last few seconds — which is exactly
/// the part that matters when something goes wrong — and this is nowhere near hot enough for the
/// cost to show.
///
/// **Bounded.** A week of ordinary use is a few thousand lines; a sync loop gone wrong could be a
/// million. The file is rotated once it passes `maxBytes`, keeping the newer half, so a runaway
/// cannot fill the phone and cannot bury the beginning of the story either.
public enum Diagnostics {
    /// Where the log lives.
    ///
    /// The app's own Documents directory, not the shared group container: this build is signed with
    /// a free account, which strips App Groups, and Documents is also the one place `devicectl` can
    /// copy out of a development-signed app without the phone being unlocked into Xcode.
    public static var file: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("yantra-diagnostics.jsonl")
    }

    static let maxBytes = 4 * 1024 * 1024

    /// Records one thing that happened.
    ///
    /// `what` is a short stable slug so a week of lines can be counted and grouped — `sync.pass`,
    /// `launch`, `write.refused`. `detail` is whatever is worth knowing about this one, as plain
    /// values.
    public static func log(_ what: String, _ detail: [String: Any] = [:]) {
        guard let line = row(what, detail) else { return }
        append(line)
    }

    /// The same, but written before this call returns.
    ///
    /// For the handful of moments the process may not survive — `willTerminate` gives it one turn
    /// of the run loop — where an asynchronous write is a write that never happens, and the line
    /// that explains the ending is precisely the one worth having.
    public static func logNow(_ what: String, _ detail: [String: Any] = [:]) {
        guard let line = row(what, detail) else { return }
        queue.sync { appendNow(line) }
    }

    private static func row(_ what: String, _ detail: [String: Any]) -> String? {
        var row: [String: Any] = ["at": ISO8601DateFormatter().string(from: Date()), "what": what]
        row.merge(detail) { a, _ in a }
        guard let data = try? JSONSerialization.data(withJSONObject: row),
              let line = String(data: data, encoding: .utf8) else { return nil }
        return line + "\n"
    }

    /// Counts something that happens often, instead of writing a line each time.
    ///
    /// A line per keystroke-sized event would be a million lines in a week and would bury the rare
    /// things this log exists to find. These accumulate in memory and go out as one `counts` row
    /// when the app leaves the foreground, which is both the moment the numbers stop changing and
    /// the last moment this process is certain to be alive.
    public static func tick(_ what: String, _ by: Int = 1) {
        queue.async { counters[what, default: 0] += by }
    }

    private static var counters: [String: Int] = [:]

    /// Writes the counters out and starts again. Called when the app goes to the background, and
    /// again on the way back in so a session that iOS kills while suspended still leaves its total.
    public static func flushCounts() {
        queue.async {
            let snapshot = counters
            counters = [:]
            guard !snapshot.isEmpty else { return }
            log("counts", snapshot)
        }
    }

    /// The same, written before this call returns. See `logNow`.
    public static func flushCountsNow() {
        queue.sync {
            let snapshot = counters
            counters = [:]
            guard !snapshot.isEmpty, let line = row("counts", snapshot) else { return }
            appendNow(line)
        }
    }

    /// The screen somebody is on, recorded once rather than once per redraw.
    ///
    /// SwiftUI rebuilds a destination whenever anything it reads changes, so logging from the body
    /// would write the same screen dozens of times and say nothing about where the week was spent.
    /// Only a change of screen is a line.
    public static func screen(_ name: String) {
        queue.async {
            guard lastScreen != name else { return }
            lastScreen = name
            log("screen", ["name": name])
        }
    }

    private static var lastScreen: String?

    private static let queue = DispatchQueue(label: "ie.shoonya.yantra.diagnostics")

    private static func append(_ line: String) {
        queue.async { appendNow(line) }
    }

    /// Already on `queue`, or on the thread that is about to lose the process.
    private static func appendNow(_ line: String) {
        let url = file
        let fm = FileManager.default
        if !fm.fileExists(atPath: url.path) {
            try? line.data(using: .utf8)?.write(to: url)
            return
        }
        if let handle = try? FileHandle(forWritingTo: url) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: Data(line.utf8))
        }
        rotateIfHuge(url)
    }

    /// Keeps the newer half when the file outgrows its budget. Losing the oldest days beats losing
    /// the phone's storage, and beats a file too big to read.
    private static func rotateIfHuge(_ url: URL) {
        guard let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? Int,
              size > maxBytes,
              let text = try? String(contentsOf: url, encoding: .utf8) else { return }
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        let kept = lines.suffix(lines.count / 2).joined(separator: "\n")
        try? kept.data(using: .utf8)?.write(to: url)
    }

    /// How much has been recorded, for the row in Settings that offers to share it.
    public static func summary() -> (lines: Int, bytes: Int) {
        guard let text = try? String(contentsOf: file, encoding: .utf8) else { return (0, 0) }
        return (text.split(separator: "\n").count, text.utf8.count)
    }

    public static func clear() { try? FileManager.default.removeItem(at: file) }

    /// Notices the two ways the app goes away without saying so.
    ///
    /// An app that "just closed" is one of the reports a week of real use produces, and neither of
    /// its causes leaves a trace by default: iOS reclaiming memory from a suspended app looks
    /// exactly like a crash from the outside, and both look exactly like the person closing it. A
    /// `memoryWarning` line shortly before a fresh `launch` tells those apart afterwards. Counters
    /// are flushed with the warning, because the next thing that happens may be nothing at all.
    public static func watchForTheEnd() {
        #if canImport(UIKit)
        let centre = NotificationCenter.default
        centre.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification,
                           object: nil, queue: nil) { _ in
            log("memoryWarning")
            flushCounts()
        }
        centre.addObserver(forName: UIApplication.willTerminateNotification,
                           object: nil, queue: nil) { _ in
            logNow("terminating")
            flushCountsNow()
        }
        #endif
    }

    /// The build this log came from, so a week of lines can be tied to what was running.
    public static func logLaunch() {
        let info = Bundle.main.infoDictionary ?? [:]
        log("launch", [
            "version": info["CFBundleShortVersionString"] as? String ?? "?",
            "build": info["CFBundleVersion"] as? String ?? "?",
            "system": ProcessInfo.processInfo.operatingSystemVersionString,
            // Whether the shared container is actually available, which on a free-signed build it is
            // not — and which explains widgets and the share sheet looking empty.
            "appGroup": FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: AppGroup.id) != nil,
            "signedIn": SyncSettings.login != nil,
            // Which registration signed in, because what a token may do follows from it: an App
            // token is usually refused the collaborator list, an OAuth one usually is not.
            "method": SyncSettings.method.rawValue,
            "repo": SyncSettings.repo != nil,
        ])
    }
}
