package ie.shoonya.yantra.data.device

/**
 * Making a calendar description readable, and its links usable — CALENDAR_PLAN.md §24.
 *
 * Two things are true of almost every invitation a real calendar hands back, and neither is obvious
 * until you draw one: **the description is usually HTML**, because that is what the web client
 * writes; and **the single most useful thing on a meeting is the join link**, buried somewhere in
 * the middle of it.
 *
 * Pure, and deliberately in `data/` with no Android or Compose in sight, because the parsing is
 * where the fiddly cases live and they are worth checking without a screen.
 */
object MeetingText {

    /** A video call somebody can actually press. */
    data class Conference(val url: String, val name: String)

    /**
     * The video call for a meeting, if there is one.
     *
     * Looked for in the **location first**, because a provider that knows the meeting is a video
     * call puts it there, and only then in the description, where it is one line among thirty of
     * dial-in numbers and legal boilerplate.
     */
    fun conferenceIn(location: String?, description: String?): Conference? =
        (linksIn(location).asSequence() + linksIn(description).asSequence())
            .mapNotNull { url -> hostName(url)?.let { Conference(url, it) } }
            .firstOrNull()

    /** Every http(s) link in a piece of text, in the order they appear, without duplicates. */
    fun linksIn(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return URL.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '>', ';', '"', '\'') }
            .distinct()
            .toList()
    }

    /**
     * The name of the thing a link joins, or null if it is an ordinary link.
     *
     * A closed list rather than a guess. "Join meeting" on a link to a shared document is worse
     * than no button at all, because it is a promise about what pressing it will do.
     */
    fun hostName(url: String): String? {
        val host = url.substringAfter("://", url).substringBefore('/').lowercase()
        return when {
            host.endsWith("meet.google.com") -> "Google Meet"
            host.endsWith("zoom.us") || host.contains(".zoom.") -> "Zoom"
            host.endsWith("teams.microsoft.com") || host.endsWith("teams.live.com") -> "Microsoft Teams"
            host.endsWith("webex.com") -> "Webex"
            host.endsWith("whereby.com") -> "Whereby"
            host.endsWith("meet.jit.si") -> "Jitsi"
            host.endsWith("around.co") -> "Around"
            host.endsWith("slack.com") && url.contains("/huddle") -> "Slack huddle"
            else -> null
        }
    }

    /**
     * A calendar description as something a person can read.
     *
     * Google's web client writes HTML, so a description arrives as `<br>`s, `<a href>`s and
     * `&nbsp;`s, and drawn raw it is unreadable. This is deliberately **not** a HTML parser: it
     * unwraps the handful of things a calendar actually emits and leaves everything else alone. A
     * description that was already plain text passes through untouched, which is the common case and
     * the one that must not be damaged.
     */
    fun readable(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        if (!raw.contains('<') && !raw.contains("&")) return raw.trim()
        return raw
            // Line structure first, while the tags that carry it are still there.
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>|</div>|</li>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "• ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            // Three blank lines in a row is the boilerplate separator every invitation has.
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    // Deliberately conservative: a scheme, a host with a dot, and whatever follows until whitespace.
    // Anything cleverer starts matching prose, and a false link in a description is a button that
    // goes somewhere surprising.
    private val URL = Regex("""https?://[^\s<>"']+""")
}
