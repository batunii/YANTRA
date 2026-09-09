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

    /// Rebuilds every reminder from the index: fire instant = due − offset minutes.
    func syncReminders(_ ix: WorkspaceIndex) {
        let center = UNUserNotificationCenter.current()
        var wanted: [(String, Date, String)] = []
        for n in ix.nodes.values where n.type == NodeType.task && !n.done {
            guard let due = n.due, let offset = due.reminderMin, let at = n.dueDate else { continue }
            let fire = at.addingTimeInterval(-TimeInterval(offset * 60))
            if fire > Date() { wanted.append((n.id, fire, inlinePlain(n.title ?? "").isEmpty ? "Reminder" : inlinePlain(n.title ?? ""))) }
        }
        wanted.sort { $0.1 < $1.1 }
        let keep = Array(wanted.prefix(60))
        center.getPendingNotificationRequests { pending in
            let stale = pending.filter { $0.content.categoryIdentifier == Self.reminderCategory }.map(\.identifier)
            center.removePendingNotificationRequests(withIdentifiers: stale.filter { id in !keep.contains { "reminder:\($0.0)" == id } })
            for (id, fire, title) in keep {
                let content = UNMutableNotificationContent()
                content.title = title
                content.body = "Reminder"
                content.categoryIdentifier = Self.reminderCategory
                content.userInfo = ["nodeId": id]
                content.sound = .default
                let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: fire)
                center.add(UNNotificationRequest(identifier: "reminder:\(id)", content: content, trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)))
            }
        }
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
