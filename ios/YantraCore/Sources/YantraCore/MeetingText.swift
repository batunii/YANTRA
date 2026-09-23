import Foundation

/// Making sense of what a calendar invitation actually contains — `MeetingText.kt`.
///
/// Pure string work, in the core and away from EventKit, because it is the part with rules worth
/// pinning down: which link is the meeting, what a description says once the HTML is off it, and
/// which links in it are worth offering.
public enum MeetingText {

    /// A video call somebody can actually press.
    public struct Conference: Equatable, Sendable {
        public let url: String, name: String
        public init(url: String, name: String) { self.url = url; self.name = name }
    }

    /// The video call for a meeting, if there is one.
    ///
    /// Looked for in the **location first**, because a provider that knows the meeting is a video
    /// call puts it there, and only then in the description, where it is one line among thirty of
    /// dial-in numbers and legal boilerplate.
    public static func conference(location: String?, description: String?) -> Conference? {
        for url in links(in: location) + links(in: description) {
            if let name = hostName(url) { return Conference(url: url, name: name) }
        }
        return nil
    }

    /// Every http(s) link in a piece of text, in the order they appear, without duplicates.
    public static func links(in text: String?) -> [String] {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        var out: [String] = []
        let ns = text as NSString
        for match in Self.url.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            var found = ns.substring(with: match.range)
            while let last = found.last, ".,)]>;\"'".contains(last) { found.removeLast() }
            if !found.isEmpty, !out.contains(found) { out.append(found) }
        }
        return out
    }

    /// The name of the thing a link joins, or nil if it is an ordinary link.
    ///
    /// A closed list rather than a guess. "Join meeting" on a link to a shared document is worse
    /// than no button at all, because it is a promise about what pressing it will do.
    public static func hostName(_ url: String) -> String? {
        let afterScheme = url.contains("://") ? String(url[url.range(of: "://")!.upperBound...]) : url
        let host = afterScheme.split(separator: "/", maxSplits: 1).first.map(String.init)?.lowercased() ?? ""
        switch true {
        case host.hasSuffix("meet.google.com"): return "Google Meet"
        case host.hasSuffix("zoom.us"), host.contains(".zoom."): return "Zoom"
        case host.hasSuffix("teams.microsoft.com"), host.hasSuffix("teams.live.com"): return "Microsoft Teams"
        case host.hasSuffix("webex.com"): return "Webex"
        case host.hasSuffix("whereby.com"): return "Whereby"
        case host.hasSuffix("meet.jit.si"): return "Jitsi"
        case host.hasSuffix("around.co"): return "Around"
        case host.hasSuffix("slack.com") && url.contains("/huddle"): return "Slack huddle"
        default: return nil
        }
    }

    /// A calendar description as something a person can read.
    ///
    /// Google's web client writes HTML, so a description arrives as `<br>`s, `<a href>`s and
    /// `&nbsp;`s, and drawn raw it is unreadable. This is deliberately **not** an HTML parser: it
    /// unwraps the handful of things a calendar actually emits and leaves everything else alone. A
    /// description that was already plain text passes through untouched, which is the common case
    /// and the one that must not be damaged.
    public static func readable(_ raw: String?) -> String {
        guard let raw, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return "" }
        if !raw.contains("<"), !raw.contains("&") { return raw.trimmingCharacters(in: .whitespacesAndNewlines) }
        var s = raw
        // Line structure first, while the tags that carry it are still there.
        s = s.replacingOccurrences(of: "<br\\s*/?>", with: "\n", options: [.regularExpression, .caseInsensitive])
        s = s.replacingOccurrences(of: "</p>|</div>|</li>", with: "\n", options: [.regularExpression, .caseInsensitive])
        s = s.replacingOccurrences(of: "<li[^>]*>", with: "\u{2022} ", options: [.regularExpression, .caseInsensitive])
        s = s.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        for (entity, char) in [("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "'")] {
            s = s.replacingOccurrences(of: entity, with: char)
        }
        // Three blank lines in a row is the boilerplate separator every invitation has.
        s = s.replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression)
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// A link as a person reads it: the host and enough of the path to tell two apart.
    public static func shortURL(_ url: String) -> String {
        let afterScheme = url.contains("://") ? String(url[url.range(of: "://")!.upperBound...]) : url
        let trimmed = afterScheme.hasPrefix("www.") ? String(afterScheme.dropFirst(4)) : afterScheme
        return trimmed.count <= 44 ? trimmed : String(trimmed.prefix(43)) + "\u{2026}"
    }

    // Deliberately conservative: a scheme, a host, and whatever follows until whitespace. Anything
    // cleverer starts matching prose, and a false link in a description is a button that goes
    // somewhere surprising.
    private static let url = try! NSRegularExpression(pattern: "https?://[^\\s<>\"']+")
}
