import Foundation

/// What goes on a page's `device:` line — the iOS reading of Android's `Build.MODEL.lowercase()`.
///
/// Android stamps every page it writes with its model (`sm-s921b`, `sm-x700`): a name for the
/// *machine*, which is what the frontmatter field means and what `ConflictResolver` breaks a tie on.
/// The nearest iOS equivalent is the hardware identifier `uname` reports — `iPhone16,2` — lowercased
/// the same way. The marketing name ("iPhone 15 Pro Max") is not available without a lookup table
/// that goes stale every September, and a model code is what Android writes anyway.
///
/// In the simulator `uname` says `arm64`, which names nothing; the simulated model is in the
/// environment instead.
public enum DeviceModel {
    public static var pageStamp: String { stamp(machine: machine, environment: ProcessInfo.processInfo.environment) }

    /// `machine`, or the simulated device's when there is one, lowercased. Empty is nil — Android's
    /// `Build.MODEL?.lowercase().orEmpty()` gives "" for a missing model, and a page never carries
    /// an empty `device:` because the codec reads a blank one as absent.
    static func stamp(machine: String, environment: [String: String]) -> String {
        let raw = environment["SIMULATOR_MODEL_IDENTIFIER"].flatMap { $0.isEmpty ? nil : $0 } ?? machine
        return raw.lowercased()
    }

    static var machine: String {
        var u = utsname()
        uname(&u)
        return withUnsafeBytes(of: &u.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }
}
