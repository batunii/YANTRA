import XCTest
@testable import YantraCore

/// Git's own object hash, checked against hashes `git hash-object` actually produced.
///
/// This is the one part of the transport that can be wrong **silently**. Every push builds a tree
/// out of these ids; if the formula drifts, GitHub accepts a tree pointing at blobs that do not
/// exist under those names and the failure surfaces later as missing content, not as a rejected
/// request. The fake remote in `SyncEngineTests` uses this function for both sides, so a wrong
/// answer there is self-consistent and invisible — only a real hash catches it.
final class BlobShaTests: XCTestCase {

    func sha(_ s: String) -> String { gitBlobSha(Data(s.utf8)) }

    func testKnownHashes() {
        // Produced by `git hash-object` on the fixtures, not by this implementation.
        XCTAssertEqual(sha("hello"), "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0")
        XCTAssertEqual(sha(""), "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391",
                       "the empty blob is the one every git repository already contains")
        XCTAssertEqual(sha("# Yantra\n\n- [ ] a task\n"), "1b0b738082b914c4ae6afb0fd03a27b2cdcc77ff")
    }

    /// The header counts **bytes**, not characters. A length taken from `String.count` would be
    /// right for every ASCII page and wrong for the first one with an accent or an emoji in it.
    func testTheLengthIsBytesNotCharacters() {
        XCTAssertEqual(sha("café ☕\n"), "df113425d6f29a3f8cfb2ca897bebf8703e56d20")
    }

    func testTheHashIsOverTheBytesGiven() {
        // Binary content — ink and images go through the same path as pages.
        let bytes = Data((0...255).map { UInt8($0) })
        XCTAssertEqual(gitBlobSha(bytes).count, 40)
        XCTAssertNotEqual(gitBlobSha(bytes), gitBlobSha(Data()))
    }
}
