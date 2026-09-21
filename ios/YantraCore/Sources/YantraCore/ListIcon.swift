import Foundation

/// The one character a list wears instead of its drawn mark.
///
/// **Why a string and not an index into a set.** The picker offers a curated grid, and a grid is a
/// set somebody chose — which is the right default and the wrong limit. Storing what was picked,
/// rather than which cell it came from, is what lets the field beside the grid accept anything the
/// keyboard can produce without the file format needing to know the grid exists. It also means the
/// grid can be re-ordered, extended or cut without touching a single stored page.
///
/// **Why exactly one grapheme.** An emoji is frequently several code points — a flag is two, a
/// profession is a person joined to an object by a zero-width joiner, a skin tone is a modifier
/// hanging off the end — so counting unicode scalars would cut 👩‍💻 into a woman and a laptop.
/// Swift's `Character` *is* the extended grapheme cluster, which is the unit the slot actually
/// holds; the Kotlin side reaches the same answer through `BreakIterator`.
///
/// Anything is allowed through, not only emoji. A letter or a digit renders perfectly well in the
/// slot and somebody may reasonably want one, and the alternative — a table of what counts as an
/// emoji — is a list that is wrong the week a new Unicode version ships.
public enum ListIcon {

    /// The first user-perceived character of `input`, or nil when there is nothing usable in it.
    ///
    /// Takes the first rather than refusing anything longer, because the field is fed by a keyboard:
    /// people paste a word, or type two emoji and change their mind about the second, and refusing
    /// the lot would mean a field that silently does nothing. Taking the first is the reading that
    /// matches what the preview beside it is already showing them.
    public static func clean(_ input: String?) -> String? {
        let text = (input ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = text.first else { return nil }
        let one = String(first)
        return one.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : one
    }

    /// The grid, which is the whole of the choice for almost everybody.
    ///
    /// Chosen for *what a list is about* rather than as a sample of Unicode: the things people
    /// actually name a list after — a place, a job, a subject, a habit. Ordered so the first row is
    /// the likeliest, because on a phone the first row is what gets looked at.
    ///
    /// Deliberately not "recently used". A remembered order would put the grid in a different shape
    /// every time it opened, and a picker you have to re-read is slower than a fixed one you learn.
    ///
    /// The same forty, in the same order, as the Kotlin — a list that wears 🎯 on one device has to
    /// find it in the same cell on the other.
    public static let suggested: [String] = [
        "📥", "💼", "🏠", "🎯", "📚", "🛒", "✈️", "💡",
        "🔥", "⭐", "🎨", "🧠", "🌱", "⚙️", "📎", "🎵",
        "❤️", "🧾", "🗓️", "🏃", "🍳", "🔧", "📦", "🎁",
        "🐾", "☕", "🧪", "🗺️", "🎧", "🏆", "🌍", "🔒",
        "📈", "🧹", "👥", "🩺", "🎬", "🪴", "🧩", "🌙",
    ]
}

/// The comma-separated reminder offsets, as they are written in the index.
///
/// One place rather than a `split` at each call site, because the two ends have to agree exactly:
/// the index is rebuilt from the file, the file is rewritten from the index, and a round trip that
/// loses or reorders an offset is a task that silently ends up with fewer reminders than its owner
/// set.
public enum Reminders {

    /// Whatever is stored, as offsets — canonical order, no repeats, nothing unreadable.
    ///
    /// Forgiving on the way in on purpose. This parses a value older builds never wrote and a file
    /// somebody edited by hand; refusing the lot because one element is nonsense would drop
    /// reminders that are perfectly readable beside it. The *file* parser is strict for the
    /// opposite reason — see `PageCodec.parseDue`.
    public static func parse(_ stored: String?) -> [Int] {
        guard let stored else { return [] }
        return DueSpec.reminders(stored.split(separator: ",").compactMap {
            Int($0.trimmingCharacters(in: .whitespaces))
        })
    }

    /// The inverse, or nil when there are none — empty is not a value.
    public static func store(_ offsets: [Int]) -> String? {
        let canonical = DueSpec.reminders(offsets)
        return canonical.isEmpty ? nil : canonical.map(String.init).joined(separator: ",")
    }
}
