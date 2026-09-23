import BackgroundTasks
import Foundation
import WidgetKit
import YantraCore

/// Sync while the app is closed — `SyncWorker.kt`.
///
/// Everything else about sync happens because you did something: a task was finished, the app went
/// to the background, you pressed the button. This is the only path that brings **other people's**
/// work down without you asking, which is what makes a shared list feel shared rather than like a
/// thing you have to remember to refresh.
///
/// iOS decides when, and it decides by watching how you use the app; `earliestBeginDate` is a floor,
/// not a schedule, and a phone that is low on battery or has decided this app is idle will simply
/// not run it. That is survivable — the worst case is "syncs when you open it", which for a task app
/// is a delay rather than a failure — but it is exactly why "Sync now" exists and why nothing in the
/// app should imply this is prompt.
///
/// The identifier is declared in `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`; a task
/// registered without being declared there crashes at launch, and one declared without being
/// registered is silently never run.
enum BackgroundSync {
    static let identifier = "ie.shoonya.yantra.sync"

    /// Registered **before the app finishes launching**, which iOS requires: a handler installed
    /// later than that is never called, with no error to say so.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else { task.setTaskCompleted(success: false); return }
            run(refresh)
        }
    }

    /// Asks for the next pass. Called when the app goes to the background, because that is the
    /// moment there is something to come back to and the moment iOS is deciding what this app is
    /// worth.
    static func schedule(after seconds: TimeInterval = 15 * 60) {
        // Nothing to sync, nothing to ask for: an app with no repository connected would be asking
        // the system to wake it up to do nothing, which is how an app teaches iOS to stop waking it.
        guard AppGroup.openWorkspaces().contains(where: { SyncSettings.repo(for: $0.0.id) != nil }) else { return }
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: seconds)
        do { try BGTaskScheduler.shared.submit(request) } catch {
            // Refused, and it is not worth a word on screen: the app syncs on open regardless, and
            // the commonest refusal is a simulator, where background refresh does not exist.
            NSLog("Yantra: background sync not scheduled — \(error.localizedDescription)")
        }
    }

    /// One pass over every connected workspace, then the next request.
    ///
    /// Rescheduled **first**, so a pass that is killed part way through still leaves a successor:
    /// the system gives this a few tens of seconds and a slow repository can outlast them.
    private static func run(_ task: BGAppRefreshTask) {
        schedule()
        Diagnostics.log("bgsync.began")
        let work = Task {
            for (store, _) in AppGroup.openWorkspaces() where SyncSettings.repo(for: store.id) != nil {
                _ = await SyncSettings.syncNow(store: store, message: "scheduled")
            }
            // The index the app and the widgets read is rebuilt from files, so what arrived is on
            // the home screen before anybody opens anything.
            WidgetCenter.shared.reloadAllTimelines()
            Diagnostics.log("bgsync.finished")
            task.setTaskCompleted(success: true)
        }
        // Told to stop: leave the files as they are. A half-applied merge is not something to
        // hurry, and the next pass starts from a base that is still true.
        task.expirationHandler = {
            Diagnostics.log("bgsync.expired")
            work.cancel()
        }
    }
}
