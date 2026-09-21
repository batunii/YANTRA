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

    func refreshAuthorization() {
        let s = EKEventStore.authorizationStatus(for: .event)
        // `.fullAccess` is what reading other people's events needs; `.writeOnly` is the grant iOS 17
        // hands an app that only adds events, and it cannot read, so it is not authorization here.
        if #available(iOS 17.0, *) { authorized = s == .fullAccess } else { authorized = s == .authorized }
    }

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
                         color: c.cgColor.map(Self.argb), visibleInOwner: true)
        }.sorted { ($0.account, $0.title) < ($1.account, $1.title) }
    }

    /// Occurrences overlapping a window, already expanded by EventKit.
    ///
    /// The window is asked for in instants because that is what the framework takes; which of *your*
    /// days a moment falls in is decided in `CalendarBucketer`, which is the one place that decision
    /// is made.
    func events(from: Date, to: Date, calendarIds: Set<String>?) -> [DeviceEvent] {
        guard authorized else { return [] }
        let all = store.calendars(for: .event)
        let chosen = calendarIds.map { ids in all.filter { ids.contains($0.calendarIdentifier) } } ?? all
        if chosen.isEmpty { return [] }
        let predicate = store.predicateForEvents(withStart: from, end: to, calendars: chosen)
        return store.events(matching: predicate).map { e in
            DeviceEvent(
                // An occurrence of a repeat shares its event identifier with every other occurrence,
                // so the start has to be part of the key or the claim map would hold one instance
                // for the whole series.
                instanceId: "\(e.eventIdentifier ?? e.calendarItemIdentifier)@\(e.startDate.timeIntervalSince1970)",
                // The identity the *sync source* gave it, never the local row id: that is a number
                // this device made up, different on another phone and gone after a reinstall.
                uid: e.calendarItemExternalIdentifier,
                eventId: e.eventIdentifier ?? e.calendarItemIdentifier,
                title: e.title ?? "Untitled",
                beginUtc: e.startDate, endUtc: e.endDate, allDay: e.isAllDay,
                location: e.location?.isEmpty == false ? e.location : nil,
                color: e.calendar.cgColor.map(Self.argb))
        }
    }

    /// Hands a meeting back to the app that owns it, which is the only thing we do *to* one.
    func ownerURL(eventId: String) -> URL? {
        guard let e = store.event(withIdentifier: eventId) else { return nil }
        // `calshow:` takes seconds since the reference date, which is what opens the day it is on.
        return URL(string: "calshow:\(e.startDate.timeIntervalSinceReferenceDate)")
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
/// The absence of a stored choice is not the same as choosing none. Until somebody has been asked,
/// `chosen` is nil and the caller falls back to everything the owning app shows, which is the answer
/// a person means by "my calendar".
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
