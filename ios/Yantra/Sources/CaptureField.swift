import SwiftUI
import UIKit
import YantraCore

/// The capture bar's field, tinting what the app has understood as you type — `CaptureHighlight.kt`.
///
/// **This is the safety half of inline capture, not decoration.** "buy milk tomorrow" quietly losing
/// its last word would be alarming; the same word tinted as you type says *this became a date, and
/// it will not be in the title* — before you commit to it, while it can still be edited away. The
/// app understood `#label`, `~list`, `@name`, `!priority` and a date all along; it just never said
/// so until after the task existed, which is the wrong end of the decision.
///
/// **A label is tinted the colour it is about to become.** An existing tag wears its own; a new one
/// wears the colour its name seeds to. So typing `#home` shows the chip you are about to make, and
/// typing a tag you already use shows that you are adding to it rather than starting something new.
///
/// **UIKit, because SwiftUI's `TextField` cannot do this.** It takes a `String` and draws it one
/// way. A `UITextView` takes attributes, and — importantly — the text itself is never altered here,
/// only coloured, so the caret lands exactly where it was put. A transformation that rewrote the
/// string would have to map every cursor position across the edit and would move the caret under
/// the typing finger.
struct CaptureField: UIViewRepresentable {
    @Binding var text: String
    /// Where the caret is, so `[[` and `~` know what is being typed *now*.
    @Binding var caret: Int
    let placeholder: String
    let onSubmit: () -> Void
    /// Reported upward so the bar can be as tall as the words and no taller.
    var onHeight: ((CGFloat) -> Void)?

    /// Everything the parser will be given for real. Tinting with less would promise readings the
    /// commit does not make — an `@name` glowing for somebody who cannot be assigned is worse than
    /// one that never lit up.
    let lists: [String]
    let people: [String]
    let labelColor: (String) -> UIColor
    let palette: Palette

    struct Palette {
        var ink: UIColor, dim: UIColor, date: UIColor, priority: UIColor, list: UIColor
        var link: UIColor, assignee: UIColor
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.delegate = context.coordinator
        // Never first responder of its own accord. The bar is on every list screen; a field that
        // claimed the keyboard on appear would mean arriving at a list with half of it covered.
        view.backgroundColor = .clear
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.font = UIFont.systemFont(ofSize: 15.5, weight: .medium)
        view.isScrollEnabled = false
        view.autocorrectionType = .no
        view.returnKeyType = .done
        // Capture is one line of intent. A return key that inserted a newline would make a task
        // whose title has a line break in it rather than committing the thing you just typed.
        view.textContainer.maximumNumberOfLines = 0
        view.setContentHuggingPriority(.defaultLow, for: .horizontal)
        // **Hugs its text vertically.** A `UITextView` with scrolling off will happily take every
        // point offered and sit there as a tall empty box — which is what it did, turning a one-line
        // capture bar into a slab. Hugging makes it ask for the height of what is in it, which for
        // one line is one line.
        view.setContentHuggingPriority(.required, for: .vertical)
        view.setContentCompressionResistancePriority(.required, for: .vertical)
        context.coordinator.placeholderLabel(on: view, text: placeholder, colour: palette.dim)
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        context.coordinator.owner = self
        if view.text != text {
            // The text changed from *outside* — a suggestion was picked — so the caret goes where
            // the binding says, which is the end of what was just inserted. Restoring the old
            // selection instead left it sitting between the `@` and the name that had just been
            // written after it, so the next keystroke landed in the middle of a login.
            view.text = text
            let end = min(max(0, caret), (text as NSString).length)
            view.selectedRange = NSRange(location: end, length: 0)
        }
        context.coordinator.restyle(view)
        DispatchQueue.main.async { context.coordinator.reportHeight(view) }
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        var owner: CaptureField
        private weak var placeholder: UILabel?
        init(_ owner: CaptureField) { self.owner = owner }

        func placeholderLabel(on view: UITextView, text: String, colour: UIColor) {
            let label = UILabel()
            label.text = text
            label.font = view.font
            label.textColor = colour
            label.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(label)
            NSLayoutConstraint.activate([
                label.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                label.topAnchor.constraint(equalTo: view.topAnchor),
            ])
            placeholder = label
        }

        /// Applies the parse to what is on screen, keeping the selection exactly where it was.
        func restyle(_ view: UITextView) {
            placeholder?.isHidden = !view.text.isEmpty
            let parsed = CaptureParse.parse(view.text, lists: owner.lists, people: owner.people)
            let attributed = NSMutableAttributedString(string: view.text, attributes: [
                .font: view.font ?? UIFont.systemFont(ofSize: 15.5),
                .foregroundColor: owner.palette.ink,
            ])
            for span in parsed.spans {
                guard span.range.location + span.range.length <= attributed.length else { continue }
                attributed.addAttribute(.foregroundColor, value: colour(for: span, in: view.text), range: span.range)
                attributed.addAttribute(.font,
                                        value: UIFont.systemFont(ofSize: 15.5, weight: .semibold),
                                        range: span.range)
            }
            let selected = view.selectedRange
            view.attributedText = attributed
            view.selectedRange = selected
        }

        private func colour(for span: Captured.Span, in text: String) -> UIColor {
            switch span.kind {
            case .date, .time: return owner.palette.date
            case .priority: return owner.palette.priority
            case .list: return owner.palette.list
            case .link: return owner.palette.link
            case .assignee: return owner.palette.assignee
            case .label:
                // The colour it is about to become, which is knowable before the label exists.
                let ns = text as NSString
                let name = ns.substring(with: span.range).trimmingCharacters(in: CharacterSet(charactersIn: "#"))
                return owner.labelColor(name)
            }
        }

        func textViewDidChange(_ view: UITextView) {
            owner.text = view.text
            owner.caret = view.selectedRange.location
            restyle(view)
            reportHeight(view)
        }

        func reportHeight(_ view: UITextView) {
            let fits = view.sizeThatFits(CGSize(width: view.bounds.width, height: .greatestFiniteMagnitude))
            owner.onHeight?(max(22, fits.height))
        }

        func textViewDidChangeSelection(_ view: UITextView) {
            owner.caret = view.selectedRange.location
        }

        func textView(_ view: UITextView, shouldChangeTextIn range: NSRange, replacementText t: String) -> Bool {
            guard t == "\n" else { return true }
            owner.onSubmit()
            // Done means done: the keyboard goes when the task is made. It used to stay up over the
            // list you had just added to, hiding the thing you were looking at to check it landed.
            view.resignFirstResponder()
            return false
        }
    }
}
