import Foundation
import EventKit
import YantraCore

/// Reading somebody else's calendars, for whichever process needs it.
///
/// In `Shared/` rather than in the app because the calendar widget needs the same answer, and an
/// extension inherits the container app's calendar permission. Plain functions and no actor: a
/// widget's timeline provider is not on the main thread and has no observable object to watch.
///
/// **Read-only, and not by omission.** Nothing here writes. A calendar belongs to an account, not to
/// this workspace, and a repository promised to be plain files in git has no business reaching into
/// one it does not own.
enum DeviceEvents {

    /// Whether this process may actually read events.
    ///
    /// `.writeOnly` is the grant iOS 17 hands an app that only adds events, and it cannot read — so
    /// it is deliberately not treated as authorization here.
    static var authorized: Bool {
        let s = EKEventStore.authorizationStatus(for: .event)
        if #available(iOS 17.0, *) { return s == .fullAccess }
        return s == .authorized
    }

    /// Occurrences overlapping a window, already expanded by EventKit.
    ///
    /// The window is asked for in instants because that is what the framework takes; which of *your*
    /// days a moment falls in is decided in `CalendarBucketer`, the one place that decision is made.
    static func read(from: Date, to: Date, calendarIds: Set<String>?, store: EKEventStore) -> [DeviceEvent] {
        guard authorized else { return [] }
        let all = store.calendars(for: .event)
        // No stored choice is not the same as choosing none: until somebody has ticked something,
        // everything the owning app shows is what a person means by "my calendar".
        let chosen = calendarIds.map { ids in all.filter { ids.contains($0.calendarIdentifier) } } ?? all
        if chosen.isEmpty { return [] }
        return store.events(matching: store.predicateForEvents(withStart: from, end: to, calendars: chosen)).map { e in
            DeviceEvent(
                // An occurrence of a repeat shares its event identifier with every other occurrence,
                // so the start has to be part of the key — otherwise one instance would stand for
                // the whole series when a note goes looking for the one it was written about.
                instanceId: "\(e.eventIdentifier ?? e.calendarItemIdentifier)@\(e.startDate.timeIntervalSince1970)",
                // The identity the *sync source* gave it, never the local row id: that is a number
                // this device made up, different on another phone and gone after a reinstall.
                uid: e.calendarItemExternalIdentifier,
                eventId: e.eventIdentifier ?? e.calendarItemIdentifier,
                title: e.title ?? "Untitled",
                beginUtc: e.startDate, endUtc: e.endDate, allDay: e.isAllDay,
                location: e.location?.isEmpty == false ? e.location : nil,
                color: e.calendar.cgColor.map(argb))
        }
    }

    /// Everything a meeting page needs that the timeline does not: who called it, who is coming,
    /// what it says, and which calendar it is in.
    ///
    /// Asked for one event at a time, when a person opens one. Reading the guest list of every
    /// meeting in a month to draw a timeline would be a great deal of work for a line of text
    /// nobody is looking at.
    struct Details: Equatable, Sendable {
        var calendarName: String?
        var organiser: String?
        var guests: [String] = []
        var notes: String?
        var location: String?
        var url: String?
        var start: Date?
        var end: Date?
    }

    /// The details for one event, or nil when it cannot be read.
    ///
    /// Nil is an ordinary answer, not a failure: the permission may be off, the meeting may have
    /// been cancelled since the timeline was drawn, and a repeating occurrence may no longer exist.
    /// The sheet is built from the line it already has and enriched by this, so it is never empty —
    /// the mistake Android records making, where a failed lookup left a title and nothing else.
    static func details(eventId: String, store: EKEventStore) -> Details? {
        guard authorized, let event = store.event(withIdentifier: eventId) else { return nil }
        // A name is what a person recognises; an address is what the framework has when nobody set
        // one. Both beaten by neither, which is what an empty row would be.
        func name(_ p: EKParticipant?) -> String? {
            guard let p else { return nil }
            if let n = p.name, !n.isEmpty { return n }
            let address = p.url.absoluteString
            return address.hasPrefix("mailto:") ? String(address.dropFirst(7)) : address
        }
        return Details(
            calendarName: event.calendar?.title,
            organiser: name(event.organizer),
            // The organiser is already named above, and a list that repeats them reads as a
            // stranger who happens to share their name.
            guests: (event.attendees ?? []).compactMap(name).filter { $0 != name(event.organizer) },
            notes: event.notes,
            location: event.location?.isEmpty == false ? event.location : nil,
            url: event.url?.absoluteString,
            start: event.startDate, end: event.endDate)
    }

    /// The same, found by the identity a Yantra event line carries in `ext:`.
    ///
    /// A page about a meeting stores the **sync source's** uid, not this device's row id, because
    /// the row id is a number this phone made up and is different on the next one. So the lookup
    /// goes back the same way: by external identifier, with the `local:` fallback for providers
    /// that offer no uid at all.
    static func details(externalUid uid: String, store: EKEventStore) -> Details? {
        guard authorized else { return nil }
        if uid.hasPrefix("local:") {
            return details(eventId: String(uid.dropFirst(6)), store: store)
        }
        // One rule can answer with fifty-two occurrences; any of them carries the details a page
        // shows, so the first is as good as the last.
        guard let item = store.calendarItems(withExternalIdentifier: uid).first as? EKEvent,
              let id = item.eventIdentifier else { return nil }
        return details(eventId: id, store: store)
    }

    static func argb(_ c: CGColor) -> Int64 {
        let comps = c.converted(to: CGColorSpace(name: CGColorSpace.sRGB)!, intent: .defaultIntent, options: nil)?.components ?? [0, 0, 0, 1]
        func byte(_ i: Int) -> Int64 { Int64((min(max(comps.count > i ? comps[i] : 0, 0), 1) * 255).rounded()) }
        let a = Int64((min(max(comps.count > 3 ? comps[3] : 1, 0), 1) * 255).rounded())
        return (a << 24) | (byte(0) << 16) | (byte(1) << 8) | byte(2)
    }
}

/// Which device calendars to draw, remembered on **this device**.
///
/// Device-local, deliberately. A phone and a tablet signed into different accounts have different
/// answers, and calendar identifiers are local: writing this choice into the repository would make
/// one device's calendars appear on the other as ids that mean nothing there.
///
/// In `Shared/` so the widget reads the same choice the app was given.
enum CalendarChoice {
    private static let key = "device_calendars"

    /// The chosen ids, or nil when nobody has chosen yet.
    static var chosen: Set<String>? {
        get {
            guard let s = AppGroup.defaults.string(forKey: key) else { return nil }
            return Set(s.split(separator: "\n").map(String.init).filter { !$0.isEmpty })
        }
        set {
            guard let v = newValue else { AppGroup.defaults.removeObject(forKey: key); return }
            AppGroup.defaults.set(v.sorted().joined(separator: "\n"), forKey: key)
        }
    }

    /// Whether the calendar area is on at all. Off until somebody turns it on, because reading
    /// somebody's meetings is not a thing to start doing because an app was updated.
    static var enabled: Bool {
        get { AppGroup.defaults.bool(forKey: "device_calendars_on") }
        set { AppGroup.defaults.set(newValue, forKey: "device_calendars_on") }
    }
}
