import Foundation

/// A lock on one workspace's files that every **process** sees: the app, a widget intent and the
/// share extension all write the same App Group directory.
///
/// Held for exactly as long as it takes to read a file, decide, and rename a replacement over it —
/// never across a network call. That is what keeps L3 true ("capture is never blocked"): a keystroke
/// can wait a few milliseconds behind sync's rename, never behind a push. Android makes the same
/// separation by giving the writer and the git work different locks; here the git work has no local
/// lock at all (the transport is REST), so the only thing the two need to agree on is the moment a
/// file is swapped.
///
/// `flock(2)` on a file beside the workspace directory, not inside it, so it is never listed as a
/// workspace file and never pushed. A lock is taken per acquisition on a freshly opened descriptor:
/// flock excludes between open file descriptions, so that is also what makes it exclude between
/// threads of one process and between two `WorkspaceLock`s on the same workspace.
///
/// If the lock file cannot be opened at all, this degrades to an in-process lock rather than
/// refusing: a write that cannot be coordinated is still better than a write that cannot happen.
public final class WorkspaceLock: @unchecked Sendable {
    public let file: URL
    private let local = NSLock()
    private var fd: Int32 = -1

    public init(root: URL) {
        let r = root.standardizedFileURL
        file = r.deletingLastPathComponent().appendingPathComponent(r.lastPathComponent + ".lock")
    }

    public func lock() {
        local.lock()
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        fd = open(file.path, O_CREAT | O_RDWR | O_CLOEXEC, 0o644)
        if fd >= 0 {
            while flock(fd, LOCK_EX) != 0, errno == EINTR {}
        }
    }

    public func unlock() {
        if fd >= 0 {
            flock(fd, LOCK_UN)
            close(fd)
            fd = -1
        }
        local.unlock()
    }

    public func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock(); defer { unlock() }
        return try body()
    }
}

/// Swapping a file in place, the way a crash cannot tear.
public enum AtomicFile {
    /// Writes `data` beside `url` under a unique `.tmp` name, then renames it over `url`. A crash
    /// leaves either the old file or the new one, never half of either; the `.tmp` is excluded from
    /// sync exactly as Android's `*.tmp` gitignore line excludes its own.
    ///
    /// A unique name rather than `<file>.tmp`, because the writer uses that one: two processes
    /// writing the same page at once must not write into each other's temporary file.
    public static func write(_ data: Data, to url: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let tmp = url.deletingLastPathComponent()
            .appendingPathComponent(".\(url.lastPathComponent).\(UUID().uuidString.prefix(8)).tmp")
        do {
            try data.write(to: tmp)
            guard rename(tmp.path, url.path) == 0 else {
                throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno), userInfo: [NSFilePathErrorKey: url.path])
            }
        } catch {
            try? fm.removeItem(at: tmp)
            throw error
        }
    }
}
