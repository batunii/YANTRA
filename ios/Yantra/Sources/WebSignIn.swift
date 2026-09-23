import AuthenticationServices
import SwiftUI

/// GitHub's approval page, presented inside the app.
///
/// **Why this is not a real OAuth redirect.** GitHub's web OAuth flow requires a client secret to
/// exchange the code, and it does not support PKCE for OAuth apps — so a native app either ships a
/// secret, which is not a secret once it is in a binary anybody can download, or it runs a server to
/// hold one. Android reached the same conclusion in its own words: *"the web flow that would avoid
/// the code entirely needs a client secret even with PKCE, and a secret shipped inside an APK is not
/// a secret."*
///
/// So the **device flow** stays underneath: it is the flow GitHub designed for exactly this case and
/// it needs no secret. What changes is where it happens. The approval page opens in a sheet over
/// Yantra rather than throwing you into Safari, the code is already on the clipboard, and when the
/// page is done the sheet closes itself and the poll that was already running picks the token up.
///
/// `ASWebAuthenticationSession` rather than `SFSafariViewController` because it shares Safari's
/// cookies: somebody signed into GitHub in Safari is signed in here, which turns the whole thing
/// into one paste and a tap.
struct WebSignIn: UIViewControllerRepresentable {
    let url: URL
    /// Called when the sheet closes, however it closed — approved, cancelled, or dismissed. The
    /// poll is the authority on whether it worked, so this only has to say "it is over".
    let onFinish: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIViewController(context: Context) -> UIViewController {
        let host = UIViewController()
        context.coordinator.present(from: host)
        return host
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}

    final class Coordinator: NSObject, ASWebAuthenticationPresentationContextProviding {
        private let owner: WebSignIn
        private var session: ASWebAuthenticationSession?
        init(_ owner: WebSignIn) { self.owner = owner }

        func present(from host: UIViewController) {
            // The device flow has no redirect back to us — approval finishes on GitHub's own page —
            // so the callback scheme is one this app never registers and never receives. The session
            // ends when the person closes it, which is the signal that matters.
            let session = ASWebAuthenticationSession(url: owner.url, callbackURLScheme: nil) { [weak self] _, _ in
                self?.owner.onFinish()
            }
            session.presentationContextProvider = self
            // Shared cookies, deliberately: signed into GitHub in Safari means signed in here.
            session.prefersEphemeralWebBrowserSession = false
            self.session = session
            session.start()
        }

        func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
            UIApplication.shared.connectedScenes
                .compactMap { ($0 as? UIWindowScene)?.keyWindow }
                .first ?? ASPresentationAnchor()
        }
    }
}

/// So a `URL` can drive a `.sheet(item:)` directly.
extension URL: @retroactive Identifiable {
    public var id: String { absoluteString }
}
