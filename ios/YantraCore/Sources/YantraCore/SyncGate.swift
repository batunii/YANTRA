import Foundation

/// **One sync pass per workspace at a time** — the `Mutex` in `SyncEngine.kt`, lifted out of the
/// engine because on iOS the engine is rebuilt for every pass and several callers each build one: the
/// app's own flushes, pull-to-sync, the background refresh task, the sign-in and add-workspace
/// screens.
///
/// Two passes on one workspace race on its base snapshot and its working tree: each reads the tree,
/// each writes what it pulled, and whichever saves its base last decides what the next pass thinks
/// the common ancestor was — which is how an edit ends up looking like it was already pushed.
///
/// Waiters are served **in order**, so a flush asked for while a pass is running runs after it
/// rather than being dropped. Workspaces do not wait for each other.
public final class SyncGate: @unchecked Sendable {
    public static let shared = SyncGate()
    public init() {}

    private let lock = NSLock()
    private var busy: Set<String> = []
    private var waiters: [String: [CheckedContinuation<Void, Never>]] = [:]

    public func run<T>(_ key: String, _ body: () async throws -> T) async rethrows -> T {
        await acquire(key)
        defer { release(key) }
        return try await body()
    }

    /// Whether a pass holds this workspace right now.
    public func isBusy(_ key: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return busy.contains(key)
    }

    private func acquire(_ key: String) async {
        if take(key) { return }
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            // Checked again under the lock: the holder may have let go between the two.
            if queue(c, for: key) { c.resume() }
        }
    }

    /// Takes the workspace if nobody holds it.
    private func take(_ key: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return busy.insert(key).inserted
    }

    /// Joins the queue, or — if the workspace came free meanwhile — takes it and says so.
    private func queue(_ c: CheckedContinuation<Void, Never>, for key: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if busy.insert(key).inserted { return true }
        waiters[key, default: []].append(c)
        return false
    }

    /// Hands the workspace straight to the next waiter, so nothing can slip in between.
    private func release(_ key: String) {
        lock.lock()
        if var queue = waiters[key], !queue.isEmpty {
            let next = queue.removeFirst()
            waiters[key] = queue.isEmpty ? nil : queue
            lock.unlock()
            next.resume()
        } else {
            busy.remove(key)
            lock.unlock()
        }
    }
}

/// Runs one piece of work at a time and gives every caller who arrives meanwhile **its** answer,
/// rather than starting another.
///
/// The work runs in a task of its own, so a caller that is cancelled — a pull-to-sync whose view
/// went away, a background task told to stop — does not cancel it for everybody else, or halfway.
public final class SingleFlight<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Task<T, Never>?

    public init() {}

    public func run(_ body: @escaping @Sendable () async -> T) async -> T {
        let task = joinOrStart(body)
        let value = await task.value
        finished(task)
        return value
    }

    private func joinOrStart(_ body: @escaping @Sendable () async -> T) -> Task<T, Never> {
        lock.lock(); defer { lock.unlock() }
        if let c = current { return c }
        let task = Task { await body() }
        current = task
        return task
    }

    private func finished(_ task: Task<T, Never>) {
        lock.lock(); defer { lock.unlock() }
        if current == task { current = nil }
    }
}
