import SwiftUI
import EventKit
import YantraCore

/// Somebody else's meeting, opened — `MeetingDetails.kt`.
///
/// **Driven by the block, enriched by the calendar.** The when, the where and the colour come from
/// the line the timeline already drew, so this is never empty: it opens even with the permission
/// since revoked, or the meeting cancelled, or the provider simply refusing. The guests, the
/// organiser, the notes and the way back to the Calendar app come from EventKit when it can be read
/// and are absent when it cannot. Android records getting that ordering the wrong way round — the
/// whole page was the provider's answer, so one failed lookup left a title and nothing else, with
/// no way to tell an empty meeting from a broken screen.
///
/// **Read-only, and it says so.** Yantra holds no permission to write anybody's calendar, so there
/// is no control here that offers to change the meeting. What you *can* do is the thing this screen
/// exists for: make a task about it, which is yours and lives in your repository.
struct MeetingHeader: View {
    /// The event node this page is about.
    let node: Node
    /// Opens the time editor. Given only for an event of your own — somebody else's is not yours to
    /// change, and a control offering to would be a promise this app cannot keep.
    var onEdit: (() -> Void)?

    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var details: DeviceEvents.Details?
    @State private var expanded = false

    private var event: EventRef? { node.event }
    /// Whose meeting this is. An event of yours has no `ext:`; one about somebody else's carries the
    /// meeting's own identity, which is also how the timeline knows to draw one block and not two.
    private var theirs: Bool { event?.external != nil }
    private var location: String? { details?.location ?? event?.location }
    private var call: MeetingText.Conference? { MeetingText.conference(location: location, description: details?.notes) }
    private var description: String { MeetingText.readable(details?.notes) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            whose
            when
            if let call { join(call) }
            if let where_ = location, where_.trimmingCharacters(in: .whitespaces) != call?.url {
                field("Where", where_)
            }
            if expanded { more }
            controls
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Layout.cardRadius).fill(y.cardBg))
        .padding(.top, 10)
        // Asked for once, when a person opens one meeting — not for every block on a month. An event
        // of your own has nothing to ask about.
        .task {
            guard let uid = event?.external?.uid else { return }
            details = DeviceEvents.details(externalUid: uid, store: DeviceCalendars.shared.store)
        }
    }

    /// Whose it is, said first. A header that looked like yours would invite an edit that cannot
    /// happen.
    private var whose: some View {
        HStack(spacing: 8) {
            Circle().fill(LabelPalette.swatchColor(event?.color, dark: y.dark) ?? y.accent)
                .frame(width: 8, height: 8)
            Text(theirs ? (details?.calendarName.map { "FROM \($0.uppercased())" } ?? "FROM YOUR CALENDAR") : "EVENT")
                .font(Face.mono(11, bold: true)).kerning(1.2).foregroundStyle(y.muted).lineLimit(1)
        }
    }

    /// The when, given the room it deserves: it is the one fact an event has that a note has not.
    private var when: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(event.map { dayWords($0.time.start.date) } ?? "Sometime")
                .font(Face.text(12.5, .semibold)).foregroundStyle(y.secondary)
            HStack(alignment: .bottom, spacing: 10) {
                Text(clockWords).font(Face.mono(19, bold: true)).foregroundStyle(y.ink)
                if event?.time.allDay == false, let length = lengthWords {
                    Text(length).font(Face.text(11.5)).foregroundStyle(y.dim).padding(.bottom, 2)
                }
            }
        }.padding(.top, 12)
    }

    /// The join link, first and biggest. On a meeting that has one it is the thing you came for, and
    /// every calendar worth using puts it where your thumb already is rather than three lines into a
    /// description.
    private func join(_ call: MeetingText.Conference) -> some View {
        Button { URL(string: call.url).map(openURL.callAsFunction) } label: {
            HStack {
                Text("Join \(call.name)").font(Face.text(15, .bold)).foregroundStyle(y.accentText)
                Spacer()
                YantraIcon(mark: .openOut, size: YantraIcons.small, tint: y.accentText)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: Layout.buttonRadius).fill(y.accentFill))
        }.buttonStyle(.plain).padding(.top, 14)
    }

    @ViewBuilder private var more: some View {
        if let organiser = details?.organiser { field("Organiser", organiser) }
        if let guests = details?.guests, !guests.isEmpty {
            // Capped even when expanded: a company-wide invite runs to three hundred names, and
            // this is a sheet you are passing through.
            field("Guests", guests.prefix(12).joined(separator: ", ")
                  + (guests.count > 12 ? "  +\(guests.count - 12) more" : ""))
        }
        if !description.isEmpty {
            Text(description).font(Face.text(13)).foregroundStyle(y.secondary)
                .lineSpacing(3).padding(.top, 12).textSelection(.enabled)
            // Every other link in it, as things you can press. An invitation routinely carries the
            // agenda, the deck and a dial-in page, and a URL you have to select and copy is a URL
            // nobody follows from a phone.
            let others = MeetingText.links(in: description).filter { $0 != call?.url }
            ForEach(others.prefix(4), id: \.self) { url in
                Button { URL(string: url).map(openURL.callAsFunction) } label: {
                    Text(MeetingText.shortURL(url)).font(Face.text(12, .semibold))
                        .foregroundStyle(y.accentText).lineLimit(1).padding(.vertical, 3)
                }.buttonStyle(.plain)
            }
        }
    }

    private var controls: some View {
        // "More details" is hidden entirely when there is nothing behind it, so it is never a
        // button that does nothing.
        let hasMore = details != nil
            && (details?.organiser != nil || !(details?.guests.isEmpty ?? true) || !description.isEmpty)
        return HStack(spacing: 16) {
            if hasMore {
                Button { withAnimation(.easeInOut(duration: 0.18)) { expanded.toggle() } } label: {
                    Text(expanded ? "Less" : "More details").font(Face.text(12.5, .bold)).foregroundStyle(y.accentText)
                }.buttonStyle(.plain)
            }
            // Only on an event of your own. Somebody else's is read-only and has to look it: this
            // app holds no permission to write their calendar, so a control offering to change the
            // time would be a promise it cannot keep.
            if let onEdit, !theirs {
                Button(action: onEdit) {
                    Text("Edit").font(Face.text(12.5, .bold)).foregroundStyle(y.accentText)
                }.buttonStyle(.plain)
            }
            if theirs {
                Button { openInCalendar() } label: {
                    HStack(spacing: 5) {
                        Text("Open in Calendar").font(Face.text(12.5, .bold)).foregroundStyle(y.secondary)
                        YantraIcon(mark: .openOut, size: YantraIcons.small, tint: y.dim)
                    }
                }.buttonStyle(.plain)
            }
            Spacer(minLength: 0)
        }.padding(.top, 12)
    }

    /// Hands the meeting back to the app that owns it.
    ///
    /// `calshow:` takes seconds since 2001, which is exactly what `timeIntervalSinceReferenceDate`
    /// is — it opens the Calendar app on the day the meeting is on. iOS offers no public way to open
    /// one specific event, so the day is the honest best, and the button says "Calendar" rather than
    /// promising the event itself.
    private func openInCalendar() {
        let when = details?.start ?? event?.time.start.instant() ?? Date()
        let seconds = Int(when.timeIntervalSinceReferenceDate)
        URL(string: "calshow:\(seconds)").map(openURL.callAsFunction)
    }

    private func field(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            SectionLabel(text: label)
            Text(value).font(Face.text(13.5)).foregroundStyle(y.ink).textSelection(.enabled)
        }.padding(.top, 12)
    }

    private var clockWords: String {
        guard let time = event?.time else { return "\u{2014}" }
        if time.allDay { return "All day" }
        let from = String(format: "%02d:%02d", time.start.hour, time.start.minute)
        let to = String(format: "%02d:%02d", time.end.hour, time.end.minute)
        return from == to ? from : "\(from)–\(to)"
    }

    private var lengthWords: String? {
        guard let time = event?.time else { return nil }
        let minutes = time.start.seconds(until: time.end) / 60
        guard minutes > 0 else { return nil }
        if minutes < 60 { return "\(minutes) min" }
        let h = minutes / 60, m = minutes % 60
        return m == 0 ? "\(h) hr" : "\(h) hr \(m) min"
    }

    private func dayWords(_ d: LocalDate) -> String {
        let f = DateFormatter(); f.dateFormat = "EEE d MMM"
        return f.string(from: d.startOfDay())
    }
}
