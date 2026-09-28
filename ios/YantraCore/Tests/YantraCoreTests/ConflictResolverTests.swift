import XCTest
@testable import YantraCore

/// `ConflictResolver.mergePage` against the Kotlin at 17d0beb.
///
/// The first five cases are `TwoPeopleOnOneTaskTest.kt`, ported. The rest pin the places the Swift
/// port used to answer differently from Android — each one a way two devices resolving the same
/// conflict would land on different bytes, which is not a merge: both push, both conflict again, and
/// the file ping-pongs forever.
///
/// Every case is resolved **from both devices' points of view** and the two results compared, because
/// `local` and `remote` are opposite labels on the two machines.
final class ConflictResolverTests: XCTestCase {

    let path = "pages/7f754074-778a-4d6c-b359-61a2d821116d.md"

    func page(_ stamp: String, _ device: String, _ body: String) -> Data {
        Data("""
        ---
        id: 7f754074-778a-4d6c-b359-61a2d821116d
        type: task
        parent: e9e70341-47b3-4f1b-975c-74d109c58f8e
        modified_at: \(stamp)
        device: \(device)
        ---
        \(body)
        """.utf8)
    }

    /// Resolve on both devices. They must produce the same file.
    func bothWays(_ base: Data?, _ mine: Data, _ theirs: Data, path: String? = nil,
                  file: StaticString = #filePath, line: UInt = #line) -> String {
        let p = path ?? self.path
        let here = ConflictResolver.resolve(path: p, local: mine, remote: theirs, device: "sm-s921b", otherDevice: "pixel-8", base: base)
        let there = ConflictResolver.resolve(path: p, local: theirs, remote: mine, device: "pixel-8", otherDevice: "sm-s921b", base: base)
        let a = here.bytes.map { String(decoding: $0, as: UTF8.self) }
        let b = there.bytes.map { String(decoding: $0, as: UTF8.self) }
        XCTAssertEqual(a, b, "the two devices resolved the same conflict differently", file: file, line: line)
        return a ?? ""
    }

    // MARK: TwoPeopleOnOneTaskTest.kt

    func testTwoNotesAddedAtOnceAreBothKept() {
        let base = page("2026-09-24T09:00:00.000Z", "sm-s921b", "")
        let mine = page("2026-09-24T09:31:00.000Z", "sm-s921b", "Asked legal about the contract")
        let theirs = page("2026-09-24T09:30:00.000Z", "pixel-8", "Chased the invoice")
        let merged = bothWays(base, mine, theirs)
        // Exactly Kotlin's bytes: the leading side's frontmatter, both notes, the leading note first.
        XCTAssertEqual(merged, """
        ---
        id: 7f754074-778a-4d6c-b359-61a2d821116d
        type: task
        parent: e9e70341-47b3-4f1b-975c-74d109c58f8e
        modified_at: 2026-09-24T09:31:00Z
        device: sm-s921b
        ---
        Asked legal about the contract

        Chased the invoice

        """)
    }

    func testTwoNotesWrittenInTheSameMillisecondStillAgreeOnAnOrder() {
        let base = page("2026-09-24T09:00:00.000Z", "sm-s921b", "")
        let mine = page("2026-09-24T09:30:00.000Z", "sm-s921b", "Asked legal about the contract")
        let theirs = page("2026-09-24T09:30:00.000Z", "pixel-8", "Chased the invoice")
        let merged = bothWays(base, mine, theirs)
        // "sm-s921b" sorts after "pixel-8", so its page leads on both devices.
        let legal = merged.range(of: "Asked legal")!, invoice = merged.range(of: "Chased the invoice")!
        XCTAssertLessThan(legal.lowerBound, invoice.lowerBound, merged)
        XCTAssertTrue(merged.contains("device: sm-s921b"), merged)
    }

    func testTwoSubtasksAddedAtOnceAreBothKept() {
        let base = page("2026-09-24T09:00:00.000Z", "sm-s921b", "- [ ] Pay the invoice ^2b69047e-6173-4637-9055-129c157d361a")
        let mine = page("2026-09-24T09:31:00.000Z", "sm-s921b",
                        "- [ ] Pay the invoice ^2b69047e-6173-4637-9055-129c157d361a\n- [ ] Ask legal ^a2d00125-686e-40c7-ada7-5a5e4a446521")
        let theirs = page("2026-09-24T09:30:00.000Z", "pixel-8",
                          "- [ ] Pay the invoice ^2b69047e-6173-4637-9055-129c157d361a\n- [ ] Chase the invoice ^f40f069a-efd2-4654-a54a-006f9f5518dc")
        let merged = bothWays(base, mine, theirs)
        XCTAssertTrue(merged.contains("Ask legal"), merged)
        XCTAssertTrue(merged.contains("Chase the invoice"), merged)
        XCTAssertTrue(merged.contains("Pay the invoice"), merged)
    }

    func testBothEditingOneNoteIsStillADisagreement() {
        let base = page("2026-09-24T09:00:00.000Z", "sm-s921b", "Draft the reply")
        let mine = page("2026-09-24T09:31:00.000Z", "sm-s921b", "Draft the reply by Friday")
        let theirs = page("2026-09-24T09:30:00.000Z", "pixel-8", "Draft the reply today")
        let merged = bothWays(base, mine, theirs)
        XCTAssertTrue(merged.contains("Draft the reply by Friday"), merged)
        XCTAssertFalse(merged.contains("Draft the reply today"), merged)
    }

    func testAnEditOneSideMadeSurvivesUntouched() {
        let base = page("2026-09-24T09:00:00.000Z", "sm-s921b", "Draft the reply")
        let mine = page("2026-09-24T09:31:00.000Z", "sm-s921b", "Draft the reply by Friday")
        let theirs = page("2026-09-24T09:30:00.000Z", "pixel-8", "Draft the reply")
        XCTAssertTrue(bothWays(base, mine, theirs).contains("Draft the reply by Friday"))
    }

    // MARK: where the port used to differ

    /// (a) A timestamp tie used to go to whichever side was local (`>=`), so each device kept its own
    /// block. The device name breaks it now, on both.
    func testATimestampTieIsBrokenByDeviceNotBySide() {
        let base = page("2026-09-24T09:00:00.000Z", "sm-s921b", "Draft the reply")
        let mine = page("2026-09-24T09:30:00.000Z", "pixel-8", "Draft the reply by Friday")
        let theirs = page("2026-09-24T09:30:00.000Z", "sm-s921b", "Draft the reply today")
        let merged = bothWays(base, mine, theirs)
        XCTAssertTrue(merged.contains("Draft the reply today"), "sm-s921b sorts higher and leads: \(merged)")
        XCTAssertTrue(merged.contains("device: sm-s921b"), merged)
    }

    /// (b) The blocks come out in the **leading** side's order, not local-first. Here the remote page
    /// is newer, so its new subtask sits above the local one — on both devices.
    func testBlocksComeOutInTheLeadingSidesOrder() {
        let base = page("2026-09-24T09:00:00.000Z", "a", "- [ ] One ^t1")
        let older = page("2026-09-24T09:10:00.000Z", "a", "- [ ] One ^t1\n- [ ] Older ^t2")
        let newer = page("2026-09-24T09:20:00.000Z", "b", "- [ ] One ^t1\n- [ ] Newer ^t3")
        let merged = bothWays(base, older, newer)
        XCTAssertTrue(merged.hasSuffix("---\n- [ ] One ^t1\n- [ ] Newer ^t3\n- [ ] Older ^t2\n"), merged)
    }

    /// (c) Both sides added a block at the same positional key, with nothing there in the base: two
    /// additions, both kept, leading first. The port used to keep one and lose the other.
    func testBothSidesAddingAtOneKeyKeepsBoth() {
        let base = page("2026-09-24T09:00:00.000Z", "a", "# Notes")
        let older = page("2026-09-24T09:10:00.000Z", "a", "# Notes\n\nFrom the older side")
        let newer = page("2026-09-24T09:20:00.000Z", "b", "# Notes\n\nFrom the newer side")
        let merged = bothWays(base, older, newer)
        XCTAssertTrue(merged.hasSuffix("---\n# Notes\n\nFrom the newer side\n\nFrom the older side\n"), merged)
    }

    /// (d) An archive file has no frontmatter and so no page id. Kotlin merges it block by block like
    /// any page; the port declined, fell through to whole-file last-writer-wins, and kept one side's
    /// archived lines while dropping the other's.
    func testAnArchiveFileWithNoIdStillMergesBlockByBlock() {
        let archive = "archive/e9e70341-47b3-4f1b-975c-74d109c58f8e.md"
        let base = Data("- [x] First ^a1 done:2026-01-01\n".utf8)
        let mine = Data("- [x] First ^a1 done:2026-01-01\n- [x] Mine ^a2 done:2026-01-02\n".utf8)
        let theirs = Data("- [x] First ^a1 done:2026-01-01\n- [x] Theirs ^a3 done:2026-01-03\n".utf8)
        let res = ConflictResolver.resolve(path: archive, local: mine, remote: theirs, device: "x", base: base)
        XCTAssertEqual(res.reason, "merged the page around the disagreement")
        // Not `bothWays`: with no stamp and no device on either side the tie goes to local on both
        // machines, exactly as in Kotlin. That is harmless in practice — only the device that syncs
        // second ever resolves, and the other fast-forwards to its answer — and it is what Android does.
        let merged = String(decoding: res.bytes!, as: UTF8.self)
        // Kotlin encodes the merged page with the leading side's (empty) frontmatter. Surprising, but
        // it is what Android writes, and a different answer here is a file the two apps disagree on.
        XCTAssertEqual(merged, "---\nid: \ntype: \nmodified_at: 1970-01-01T00:00:00Z\n---\n"
                       + "- [x] First ^a1 done:2026-01-01\n- [x] Mine ^a2 done:2026-01-02\n- [x] Theirs ^a3 done:2026-01-03\n")
    }

    /// (e) Blocks compare **whole**, raw line included, as Kotlin's data-class equality does. A block
    /// one side merely re-spelled (`[X]` for `[x]`, same meaning, different line) counts as touched —
    /// so when the other side edited it too, it is both-edited and the leading side wins.
    func testBlocksCompareWithTheirRawLine() {
        let base = page("2026-09-24T09:00:00.000Z", "a", "- [x] Filed ^t1")
        let respelled = page("2026-09-24T09:20:00.000Z", "b", "- [X] Filed ^t1")     // newer, leads
        let renamed = page("2026-09-24T09:10:00.000Z", "a", "- [x] Filed the report ^t1")
        let merged = bothWays(base, respelled, renamed)
        XCTAssertTrue(merged.hasSuffix("---\n- [x] Filed ^t1\n"), "the leading side's block, re-rendered: \(merged)")
    }

    /// The merged output is re-rendered, never the raw lines of either file.
    func testTheMergedPageIsReRendered() {
        let base = page("2026-09-24T09:00:00.000Z", "a", "- [X] Kept ^t1\n- [ ] Two ^t2")
        let mine = page("2026-09-24T09:20:00.000Z", "b", "- [X] Kept ^t1\n- [ ] Two, edited ^t2")
        let theirs = page("2026-09-24T09:10:00.000Z", "a", "- [X] Kept ^t1\n- [ ] Two ^t2\n- [ ] Three ^t3")
        let merged = bothWays(base, mine, theirs)
        XCTAssertTrue(merged.hasSuffix("---\n- [x] Kept ^t1\n- [ ] Two, edited ^t2\n- [ ] Three ^t3\n"), merged)
    }

    func testNoBaseFallsBackToLastWriterWins() {
        let mine = page("2026-09-24T09:31:00.000Z", "sm-s921b", "Mine")
        let theirs = page("2026-09-24T09:30:00.000Z", "pixel-8", "Theirs")
        let res = ConflictResolver.resolve(path: path, local: mine, remote: theirs, device: "sm-s921b")
        XCTAssertEqual(res.bytes, mine)
        XCTAssertEqual(res.reason, "local page was newer")
    }

    // MARK: the other rules

    /// §12: a conflicted manifest keeps the local side, like every non-page file. There is no
    /// field-by-field merge on Android.
    func testTheManifestKeepsTheLocalSide() {
        let base = Data(#"{"formatVersion":1,"name":"Shared","createdAt":1000}"#.utf8)
        let local = Data(#"{"name":"Shared","createdAt":1000}"#.utf8)
        let remote = Data(#"{"formatVersion":1,"name":"Shared","createdAt":1000,"archive_after_days":30}"#.utf8)
        let res = ConflictResolver.resolve(path: WorkspaceStore.manifestPath, local: local, remote: remote, device: "a", base: base)
        XCTAssertEqual(res.bytes, local)
        XCTAssertEqual(res.reason, "kept the local side of an unmergeable file")
    }

    func testAnEditBeatsADelete() {
        let edit = page("2026-09-24T09:31:00.000Z", "a", "Kept")
        XCTAssertEqual(ConflictResolver.resolve(path: path, local: nil, remote: edit).bytes, edit)
        XCTAssertEqual(ConflictResolver.resolve(path: path, local: edit, remote: nil).bytes, edit)
        XCTAssertNil(ConflictResolver.resolve(path: path, local: nil, remote: nil).bytes)
    }

    /// Kotlin's `lines()` ends a line at `\r\n`, `\n` or `\r`. Swift's `split(separator: "\n")` does
    /// not split a CRLF at all — `\r\n` is one Character — so a log written with CRLF collapsed into
    /// a single line and a single key.
    func testAFocusLogSplitsOnEveryLineEndingKotlinDoes() {
        let remote = Data("s1\t100\topen\r\ns2\t200\topen\r\ns3\t300\topen\r".utf8)
        let local = Data("s1\t100\tclosed\n".utf8)
        let res = ConflictResolver.resolve(path: "focus/2026-09.log", local: local, remote: remote)
        XCTAssertEqual(res.reason, "merged an append-only log")
        XCTAssertEqual(String(decoding: res.bytes!, as: UTF8.self), "s1\t100\tclosed\ns2\t200\topen\ns3\t300\topen\n")
    }

    func testAFocusLogDropsBlanksAndMarkers() {
        let remote = Data("<<<<<<< HEAD\ns1\ta\n=======\n  \n>>>>>>> x\n".utf8)
        let local = Data("s2\tb\n".utf8)
        let res = ConflictResolver.resolve(path: "pomodoro/2026-09.log", local: local, remote: remote)
        XCTAssertEqual(String(decoding: res.bytes!, as: UTF8.self), "s1\ta\ns2\tb\n")
    }
}
