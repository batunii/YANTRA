import SwiftUI
import YantraCore

/// What `[[` offers — `ui/components/LinkSuggestions.kt`.
///
/// A link's id is never typed. `[[` opens this, you pick a task, and the file gets
/// `[[Call Bob|^9f1e…]]` — which is why requiring the id costs nothing even though resolving links
/// by title would be easier to write. Titles are not unique, two tasks called "Call Bob" is a
/// Tuesday, and a link that quietly points at the wrong one is worse than a link never made.
struct LinkSuggestions: View {
    /// The text being edited and where the caret is; both are needed to find the draft.
    let text: String
    let caret: Int
    /// Replaces the draft's range with a finished link.
    let choose: (NSRange, String) -> Void

    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y

    /// The tasks worth offering for what has been typed so far.
    ///
    /// Tasks and pages both: a link to a list is as ordinary as a link to a task. Sorted by how the
    /// match was made — a title that *starts* with what you typed before one that merely contains
    /// it — because the first few are all anybody reads.
    private var matches: [Node] {
        guard let (_, typed) = Links.draft(text, caret: caret) else { return [] }
        let needle = typed.trimmingCharacters(in: .whitespaces).lowercased()
        let all = model.index.nodes.values.filter { $0.type == NodeType.task || $0.type == NodeType.list }
        let named = all.compactMap { node -> (Node, String)? in
            let title = inlinePlain(node.title ?? "") { model.index.title(of: $0) }
            return title.isEmpty ? nil : (node, title.lowercased())
        }
        let hits = needle.isEmpty ? named : named.filter { $0.1.contains(needle) }
        return hits.sorted { a, b in
            let sa = a.1.hasPrefix(needle), sb = b.1.hasPrefix(needle)
            return sa != sb ? sa : a.1 < b.1
        }.prefix(8).map(\.0)
    }

    var body: some View {
        let found = matches
        let draft = Links.draft(text, caret: caret)
        // **Always a view, never nothing.** Returning an `EmptyView` when there is nothing to offer
        // changes the number of children in the stack this sits in, and SwiftUI re-identifies the
        // text field beside it — which drops what is being typed and the focus with it. So the row
        // is always here and collapses to no height instead.
        Group {
            if !found.isEmpty, let (range, _) = draft {
                suggestions(found, range)
            }
        }
        .frame(height: found.isEmpty || draft == nil ? 0 : 38)
        .clipped()
    }

    private func suggestions(_ found: [Node], _ range: NSRange) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(found) { node in
                        let title = inlinePlain(node.title ?? "") { model.index.title(of: $0) }
                        Button { choose(range, Links.encode(label: title, targetId: node.id)) } label: {
                            HStack(spacing: 5) {
                                YantraIcon(mark: node.type == NodeType.list ? .list : .task,
                                           size: YantraIcons.small, tint: y.secondary)
                                Text(title).font(Face.text(12.5, .medium)).foregroundStyle(y.ink).lineLimit(1)
                            }
                            .padding(.horizontal, 10).padding(.vertical, 7)
                            .background(Capsule().fill(y.surfaceHigh))
                            .overlay(Capsule().stroke(y.tileBorder, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("link.suggestion.\(title)")
                    }
            }.padding(.horizontal, Layout.pageMargin)
        }
        .frame(height: 38)
    }
}

/// Text with its links drawn as links, and tappable.
///
/// The words are the target's *current* title, looked up by id — the stored label is only what the
/// file says when nothing resolves it. So a renamed task reads correctly everywhere it is mentioned
/// without any file but its own being rewritten, which is the whole reason the label is a fallback
/// rather than the answer.
struct LinkedText: View {
    let text: String
    var font: Font = Face.text(15)
    var color: Color?
    let open: (String) -> Void

    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y

    var body: some View {
        let links = Links.links(text)
        if links.isEmpty {
            Text(inlinePlain(text) { model.index.title(of: $0) }).font(font).foregroundStyle(color ?? y.ink)
        } else {
            // Laid out as a run of pieces rather than one attributed string, because a tap has to
            // know *which* link it landed on and an AttributedString's link handling would take the
            // tap out of the app and into a URL.
            FlowRun(pieces: pieces(links)) { piece in
                switch piece {
                case let .words(s):
                    Text(s).font(font).foregroundStyle(color ?? y.ink)
                case let .link(label, id):
                    Text(label).font(font).foregroundStyle(y.accentText)
                        .underline(true, pattern: .solid)
                        .onTapGesture { open(id) }
                        .accessibilityIdentifier("link.\(label)")
                }
            }
        }
    }

    enum Piece: Hashable { case words(String), link(String, String) }

    private func pieces(_ links: [Links.Link]) -> [Piece] {
        let ns = text as NSString
        var out: [Piece] = []
        var at = 0
        for l in links {
            if l.range.location > at {
                out.append(.words(ns.substring(with: NSRange(location: at, length: l.range.location - at))))
            }
            out.append(.link(model.index.title(of: l.targetId).map { inlinePlain($0) } ?? l.label, l.targetId))
            at = l.range.location + l.range.length
        }
        if at < ns.length { out.append(.words(ns.substring(from: at))) }
        return out
    }
}

/// A line of pieces that wraps, which `HStack` will not do.
struct FlowRun<Piece: Hashable, Content: View>: View {
    let pieces: [Piece]
    @ViewBuilder let content: (Piece) -> Content

    var body: some View {
        // `Text` concatenation would wrap properly but cannot carry a tap per piece, so the pieces
        // are laid out and allowed to wrap by line instead. Good enough for a title or a paragraph;
        // a page of prose with fifty links in it is not a case this app has.
        FlowLayout(spacing: 0) { ForEach(Array(pieces.enumerated()), id: \.offset) { content($0.element) } }
    }
}

/// The smallest wrapping layout that will do: place each piece, wrap when the line is full.
///
/// `SwiftUI.Layout` spelled out, because this app has a `Layout` of its own — the one that holds
/// `pageMargin` — and the bare name resolves to that.
struct FlowLayout: SwiftUI.Layout {
    var spacing: CGFloat = 0

    func sizeThatFits(proposal: ProposedViewSize, subviews: LayoutSubviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, lineHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x + size.width > width, x > 0 { x = 0; y += lineHeight + spacing; lineHeight = 0 }
            x += size.width; lineHeight = max(lineHeight, size.height)
        }
        return CGSize(width: proposal.width ?? x, height: y + lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: LayoutSubviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, lineHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX {
                x = bounds.minX; y += lineHeight + spacing; lineHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width; lineHeight = max(lineHeight, size.height)
        }
    }
}

/// Everything the capture bar can offer while you type: a list after `~`, a task after `[[`.
///
/// One row, because there is one line being typed and at most one thing being named in it. Which
/// picker is showing is decided by what is under the caret, the same way the parser decides what
/// the word will mean.
struct CaptureSuggestions: View {
    @Binding var text: String
    let caret: Int
    let lists: [String]
    /// The workspace the thing being captured will land in — whose roster `@` should offer.
    let workspaceId: String
    /// Where the caret should go once something has been picked.
    let onCaret: (Int) -> Void

    @EnvironmentObject var model: AppModel
    /// Observed, so the row redraws when a roster arrives rather than staying on "only me".
    @StateObject private var people = People.shared
    @Environment(\.y) private var y

    var body: some View {
        if Links.draft(text, caret: caret) != nil {
            LinkSuggestions(text: text, caret: caret) { range, link in replace(range, with: link) }
        } else if let (range, draft) = CaptureParse.assigneeDraft(text, caret: caret) {
            // `@` offers who can be given this. Without it the mark was a trap: the parser only
            // reads `@name` as an assignment when the name is somebody real, so typing a login from
            // memory — or one the roster has not fetched yet — silently stayed in the title.
            let who = People.shared.candidates(workspaceId: workspaceId, index: model.index)
                .filter { draft.isEmpty || $0.login.localizedCaseInsensitiveContains(draft) }
            people(who, range)
        } else if let (range, draft) = CaptureParse.listDraft(text), caret >= range.location {
            let matches = CaptureParse.listSuggestions(draft, lists)
            // Nothing to offer is not the same as nothing typed: `~` with no match yet still makes a
            // list when you commit, and saying so would be a picker that lies about what it has.
            if !matches.isEmpty { row(matches, range) }
        }
    }

    private func people(_ who: [Person], _ range: NSRange) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                // Why there is nobody else, and the way to find out.
                //
                // "Only me" has two very different causes and they look identical from here: nobody
                // else can push to this repository, or GitHub has not been asked yet. Offering the
                // ask is the difference between a picker that seems broken and one that is telling
                // you something true.
                if who.count <= 1 {
                    Button { Task { await People.shared.refresh(workspaceId: workspaceId) } } label: {
                        HStack(spacing: 5) {
                            YantraIcon(mark: People.shared.fetching ? .refresh : .person,
                                       size: YantraIcons.small, tint: y.accentText)
                            Text(People.shared.fetching ? "Asking GitHub…" : "Who else can push?")
                                .font(Face.text(12.5, .semibold)).foregroundStyle(y.accentText).lineLimit(1)
                        }
                        .padding(.horizontal, 10).padding(.vertical, 7)
                        .background(Capsule().fill(y.accentFill))
                    }
                    .buttonStyle(.plain)
                    .disabled(People.shared.fetching)
                    .accessibilityIdentifier("person.fetch")
                }
                ForEach(who.prefix(8)) { person in
                    Button { replace(range, with: "@" + person.login) } label: {
                        HStack(spacing: 5) {
                            YantraIcon(mark: .person, size: YantraIcons.small, tint: y.secondary)
                            Text(person.isYou ? "\(person.login) · you" : person.login)
                                .font(Face.text(12.5, .medium)).foregroundStyle(y.ink).lineLimit(1)
                        }
                        .padding(.horizontal, 10).padding(.vertical, 7)
                        .background(Capsule().fill(y.surfaceHigh))
                        .overlay(Capsule().stroke(y.tileBorder, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("person.suggestion.\(person.login)")
                }
                if who.count <= 1, let note = People.shared.note {
                    Text(note).font(Face.text(11.5)).foregroundStyle(y.muted).lineLimit(1)
                }
            }.padding(.horizontal, Layout.pageMargin)
        }
        .frame(height: 38)
    }

    private func row(_ names: [String], _ range: NSRange) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(names.prefix(8), id: \.self) { name in
                    Button { replace(range, with: "~" + name) } label: {
                        HStack(spacing: 5) {
                            YantraIcon(mark: .list, size: YantraIcons.small, tint: y.secondary)
                            Text(name).font(Face.text(12.5, .medium)).foregroundStyle(y.ink).lineLimit(1)
                        }
                        .padding(.horizontal, 10).padding(.vertical, 7)
                        .background(Capsule().fill(y.surfaceHigh))
                        .overlay(Capsule().stroke(y.tileBorder, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("list.suggestion.\(name)")
                }
            }.padding(.horizontal, Layout.pageMargin)
        }
        .frame(height: 38)
    }

    private func replace(_ range: NSRange, with s: String) {
        let ns = NSMutableString(string: text)
        guard range.location + range.length <= ns.length else { return }
        ns.replaceCharacters(in: range, with: s)
        text = ns as String
        // The caret follows the words: after the name that was just picked, with a space, so the
        // line can simply be carried on. Leaving it where the mark was put it between `@` and the
        // login.
        onCaret(range.location + (s as NSString).length)
    }
}
