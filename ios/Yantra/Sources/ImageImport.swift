import SwiftUI
import UIKit

/// Turning a picked photo into something a git repository can carry — `data/image/ImageImport.kt`.
///
/// A phone photo is several megabytes, git keeps every version of a binary forever, and the repo is
/// cloned to every device — so what goes in is a downscaled copy, and the original stays where it
/// was.
///
/// **Re-encoding is also how the location is removed**, which is the part that matters most and
/// shows least. A photo carries GPS coordinates in its EXIF, and committing one to a shared
/// workspace publishes where you were, to everyone with access, permanently — git does not forget.
/// Drawing the pixels into a fresh bitmap and compressing that produces a file with no metadata at
/// all.
///
/// Which creates the trap this exists to avoid: **orientation is EXIF too.** A phone almost never
/// rotates the pixels it captures; it writes them as the sensor saw them and records a flag saying
/// which way is up. Drop the metadata without acting on it first and every photo taken in portrait
/// arrives on its side. `UIImage.draw(in:)` applies the flag as it draws, so redrawing is what
/// both normalises the pixels and discards the rest.
enum ImageImport {

    /// Long edge, in pixels. Comfortably beyond what any phone screen can show.
    static let maxEdge: CGFloat = 2048

    /// High enough that the difference is invisible on a photograph, low enough to matter in a repo.
    static let quality: CGFloat = 0.85

    /// The bytes to write, or nil for data that is not an image this device can decode.
    static func prepare(_ data: Data) -> Data? {
        guard let source = UIImage(data: data) else { return nil }
        let size = source.size
        guard size.width > 0, size.height > 0 else { return nil }

        let longest = max(size.width, size.height)
        let scale = longest > maxEdge ? maxEdge / longest : 1
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())

        // `opaque: true` and scale 1: a photograph has no transparency to keep, and a @3x-scaled
        // context would write three times the pixels asked for straight into the repository.
        let format = UIGraphicsImageRendererFormat.default()
        format.opaque = true
        format.scale = 1
        let flattened = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            source.draw(in: CGRect(origin: .zero, size: target))
        }
        return flattened.jpegData(compressionQuality: quality)
    }
}
