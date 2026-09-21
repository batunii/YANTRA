import UIKit
import UserNotifications
import WidgetKit
import YantraCore

/// Reminders and the completion bell — `ReminderScheduler` and `SessionNotification.showCompleted`.
///
/// iOS persists scheduled notifications across reboots, so there is no boot receiver; it runs no
/// code at delivery, so every Due edit reschedules; and it caps pending notifications at 64, so the
/// soonest are scheduled and the rest topped up on each foreground.
@MainActor
final class Notifications: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifications()
    static let reminderCategory = "reminder", bellCategory = "focus-complete", markDone = "mark-done"
    var onMarkDone: ((String) -> Void)?

    func install() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        let done = UNNotificationAction(identifier: Self.markDone, title: "Mark done", options: [])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.reminderCategory, actions: [done], intentIdentifiers: []),
            UNNotificationCategory(identifier: Self.bellCategory, actions: [UNNotificationAction(identifier: Self.markDone, title: "Done", options: [])], intentIdentifiers: []),
        ])
    }

    /// Asked at the moment it is honest to ask: when a session starts or a reminder is set.
    func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// How a scheduled reminder is named.
    ///
    /// The offset is part of the identifier, because a task now has more than one: keying on the
    /// node alone meant the day-before reminder and the half-hour-before one were the same request,
    /// and the second one scheduled silently replaced the first.
    static func requestId(node: String, offset: Int) -> String { "reminder:\(node):\(offset)" }

    /// Rebuilds every reminder from the index: fire instant = due − offset minutes, one per offset.
    func syncReminders(_ ix: WorkspaceIndex) {
        let center = UNUserNotificationCenter.current()
        var wanted: [(id: String, node: String, fire: Date, title: String, offset: Int)] = []
        for n in ix.nodes.values where n.type == NodeType.task && !n.done {
            guard let due = n.due, let at = n.dueDate else { continue }
            let title = inlinePlain(n.title ?? "").isEmpty ? "Reminder" : inlinePlain(n.title ?? "")
            for offset in due.reminders {
                let fire = at.addingTimeInterval(-TimeInterval(offset * 60))
                guard fire > Date() else { continue }
                wanted.append((Self.requestId(node: n.id, offset: offset), n.id, fire, title, offset))
            }
        }
        // Soonest first, because the cap below has to keep the ones that are about to matter. iOS
        // allows 64 pending notifications per app and silently drops the rest, so the choice of
        // which 60 to keep is ours to make rather than the system's to make badly.
        wanted.sort { $0.fire < $1.fire }
        let keep = Array(wanted.prefix(60))
        let keepIds = Set(keep.map(\.id))
        center.getPendingNotificationRequests { pending in
            let stale = pending.filter { $0.content.categoryIdentifier == Self.reminderCategory }.map(\.identifier)
            center.removePendingNotificationRequests(withIdentifiers: stale.filter { !keepIds.contains($0) })
            for r in keep {
                let content = UNMutableNotificationContent()
                content.title = r.title
                content.body = Self.offsetLabel(r.offset)
                content.categoryIdentifier = Self.reminderCategory
                content.userInfo = ["nodeId": r.node]
                content.sound = .default
                let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: r.fire)
                center.add(UNNotificationRequest(identifier: r.id, content: content,
                                                 trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)))
            }
        }
    }

    /// What a reminder says about itself. With more than one per task, "Reminder" twice in a day
    /// tells you nothing about which one you are looking at.
    static func offsetLabel(_ minutes: Int) -> String {
        if minutes == 0 { return "Due now" }
        if minutes < 0 { return "Was due \(spell(-minutes)) ago" }
        return "Due in \(spell(minutes))"
    }

    private static func spell(_ minutes: Int) -> String {
        if minutes % 1440 == 0 { let d = minutes / 1440; return d == 1 ? "a day" : "\(d) days" }
        if minutes % 60 == 0 { let h = minutes / 60; return h == 1 ? "an hour" : "\(h) hours" }
        return "\(minutes) min"
    }

    /// The bell, scheduled for the moment a committed session's promise comes due. Cancelled on pause
    /// or stop, rescheduled on resume. Rings whether or not the app is alive.
    func scheduleBell(for s: LiveSession?) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: ["focus-bell"])
        guard let s, !s.isOpen, !s.isPaused, let end = s.endDate(), end > Date() else { return }
        let content = UNMutableNotificationContent()
        content.title = s.title.isEmpty ? "Untitled" : s.title
        content.subtitle = "Focus"
        content.body = "Focus complete · \(sessionClock(s.plannedSecs))"
        content.categoryIdentifier = Self.bellCategory
        content.userInfo = ["nodeId": s.nodeId]
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        center.add(UNNotificationRequest(identifier: "focus-bell", content: content, trigger: UNTimeIntervalNotificationTrigger(timeInterval: max(1, end.timeIntervalSinceNow), repeats: false)))
    }

    // Foreground delivery still shows the banner.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions { [.banner, .sound] }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        let nodeId = info["nodeId"] as? String
        let category = response.notification.request.content.categoryIdentifier
        await MainActor.run {
            if response.actionIdentifier == Self.markDone, let nodeId {
                if category == Self.bellCategory { SessionCommands.done() } else { onMarkDone?(nodeId) }
                WidgetCenter.shared.reloadAllTimelines()
            } else if let nodeId, let url = URL(string: "yantra://open/\(nodeId)") {
                UIApplication.shared.open(url)
            }
        }
    }
}
