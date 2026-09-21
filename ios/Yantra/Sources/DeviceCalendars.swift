import Foundation
import EventKit
import YantraCore

/// Somebody else's calendars, read through EventKit — `data/device/DeviceCalendars.kt`.
///
/// **Read-only, and not by omission.** Nothing here writes: a calendar belongs to an account, not to
/// this workspace, and a repository that is promised to be plain files in git has no business
/// reaching into an account it does not own. What Yantra keeps about a meeting is a line of its own
/// that points at one — see `ExternalRef` — and the meeting itself stays the property of the app
/// that made it.
@MainActor
final class DeviceCalendars: ObservableObject {
    static let shared = DeviceCalendars()

    private let store = EKEventStore()

    /// The calendars this device can see, for the settings list.
    struct CalendarInfo: Identifiable, Equatable {
        let id: String, title: String, account: String
        let color: Int64?
        /// Whether the owning app itself shows it — the fallback when nobody has chosen here.
        let visibleInOwner: Bool
    }

    @Published private(set) var authorized = false
    /// Nil until a read has happened, so "no calendars" and "not asked yet" stay different.
    @Published private(set) var available: [CalendarInfo]?

    private init() { refreshAuthorization() }

    func refreshAuthorization() { authorized = DeviceEvents.authorized }

    /// Asks once. Returns what the person said, and never asks again on its own: a screen that
    /// re-prompts is a screen that trains people to refuse.
    @discardableResult
    func requestAccess() async -> Bool {
        let granted: Bool
        if #available(iOS 17.0, *) { granted = (try? await store.requestFullAccessToEvents()) ?? false }
        else { granted = await withCheckedContinuation { c in store.requestAccess(to: .event) { ok, _ in c.resume(returning: ok) } } }
        refreshAuthorization()
        if granted { loadCalendars() }
        return granted
    }

    func loadCalendars() {
        guard authorized else { available = nil; return }
        available = store.calendars(for: .event).map { c in
            CalendarInfo(id: c.calendarIdentifier, title: c.title, account: c.source?.title ?? "",
                         color: c.cgColor.map(DeviceEvents.argb), visibleInOwner: true)
        }.sorted { ($0.account, $0.title) < ($1.account, $1.title) }
    }

    /// Occurrences overlapping a window. The read itself is in `DeviceEvents`, shared with the
    /// widget, so both processes answer the same question the same way.
    func events(from: Date, to: Date, calendarIds: Set<String>?) -> [DeviceEvent] {
        DeviceEvents.read(from: from, to: to, calendarIds: calendarIds, store: store)
    }

    /// Hands a meeting back to the app that owns it, which is the only thing we do *to* one.
    func ownerURL(eventId: String) -> URL? {
        guard let e = store.event(withIdentifier: eventId) else { return nil }
        // `calshow:` takes seconds since the reference date, which is what opens the day it is on.
        return URL(string: "calshow:\(e.startDate.timeIntervalSinceReferenceDate)")
    }

}
