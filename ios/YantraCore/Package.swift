// swift-tools-version: 5.9
import PackageDescription

// The portable core of Yantra: the file format, the ordering, the clock, the merge rules.
// No UIKit, no SwiftUI, no persistence — every extension (widgets, share, intents) links this,
// and every behaviour here is pinned by the golden files in ../../conformance, which the
// Android app writes and checks on its own CI.
let package = Package(
    name: "YantraCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "YantraCore", targets: ["YantraCore"])],
    targets: [
        .target(name: "YantraCore"),
        .testTarget(name: "YantraCoreTests", dependencies: ["YantraCore"]),
    ]
)
