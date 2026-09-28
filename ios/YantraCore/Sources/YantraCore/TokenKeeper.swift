import Foundation

/// The account token, renewed **once** however many callers want it at the same moment —
/// `TokenRenewal.renewIfNeeded` on Android, with the one thing a REST client needs that JGit's
/// single engine lock gave Android for free.
///
/// GitHub rotates the refresh token on every use. Two renewals racing each other spend the same
/// refresh token twice: one wins and stores a new pair, the other is refused — and a refusal means
/// "the sign-in is scrap", so it clears the credential and signs the person out, a few seconds after
/// the first renewal had already fixed everything. On iOS three passes, a pull-to-sync and a screen
/// loading repositories can all ask at once, so the renewal is one flight that everybody waits on.
///
/// And a refusal only clears the token it was about. If what is stored is no longer the token that
/// was sent — a sign-in finished meanwhile, or another renewal already rotated it — the refusal is
/// about a credential that no longer exists, and the one that replaced it is left alone.
public final class TokenKeeper: @unchecked Sendable {
    public enum Outcome: Equatable {
        case ok(String)
        /// Nobody is signed in, or the sign-in is scrap and only signing in again will fix it.
        case needsSignIn(String)
    }

    /// What happened, for the diagnostics log.
    public enum Event: Equatable { case renewed, couldNotAsk, refused(String) }

    private let load: @Sendable () -> GitHubAuth.Token?
    private let save: @Sendable (GitHubAuth.Token?) -> Void
    private let refresh: @Sendable (GitHubAuth.Token) async -> GitHubAuth.Renewal
    private let note: @Sendable (Event) -> Void
    private let flight = SingleFlight<Outcome>()

    public init(load: @escaping @Sendable () -> GitHubAuth.Token?,
                save: @escaping @Sendable (GitHubAuth.Token?) -> Void,
                refresh: @escaping @Sendable (GitHubAuth.Token) async -> GitHubAuth.Renewal,
                note: @escaping @Sendable (Event) -> Void = { _ in }) {
        self.load = load; self.save = save; self.refresh = refresh; self.note = note
    }

    /// A token good for the next call, or why there is not one. Cheap when nothing needs doing: a
    /// pasted token and a non-expiring App token carry no refresh token and return with no request.
    public func credential() async -> Outcome {
        guard let t = load() else { return .needsSignIn("Sign in to sync") }
        guard t.needsRenewal else { return .ok(t.accessToken) }
        return await flight.run { [self] in await renew() }
    }

    private func renew() async -> Outcome {
        // Read again inside the flight: a flight that finished a moment ago may already have stored
        // a fresh token, and renewing that one would spend its refresh token for nothing.
        guard let t = load() else { return .needsSignIn("Sign in to sync") }
        guard t.needsRenewal else { return .ok(t.accessToken) }
        switch await refresh(t) {
        case let .renewed(fresh):
            // GitHub rotates the refresh token, so what came back replaces what was sent.
            save(fresh); note(.renewed)
            return .ok(fresh.accessToken)
        case .nothingToDo:
            return .ok(t.accessToken)
        case .couldNotAsk:
            // Nothing was said, so nothing is changed. A tunnel must not sign anybody out.
            note(.couldNotAsk)
            return .ok(t.accessToken)
        case let .needsSignIn(why):
            note(.refused(why))
            // Only if it is still the token that was refused.
            if let now = load(), now != t { return .ok(now.accessToken) }
            save(nil)
            return .needsSignIn(why)
        }
    }
}
