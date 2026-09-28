import XCTest
@testable import YantraCore

// MARK: - newer Android builds

/// PROMPT §1.2: bytes a newer Android build writes, which this build must read and preserve while
/// giving them no meaning — `by:`/`on:` on task lines in either position, unknown trailing tokens,
/// and a ninth field on focus-log lines.
final class NewerAndroidToleranceTests: XCTestCase {
    func task(_ line: String) -> TaskRef {
        guard case let .task(t) = PageCodec.decodeBlock(line) else { XCTFail("not a task: \(line)"); return TaskRef(id: "", title: "") }
        return t
    }
    func rebuilt(_ t: TaskRef) -> String { var x = t; x.raw = nil; return PageCodec.encodeBlock(.task(x)) }

    /// Where the current Android build writes them: straight after the title. 17d0beb reads the two
    /// words as title and writes the same bytes back; iOS reads them as tokens and does too.
    func testByAndOnAfterTheTitleAreTokensAndTheBytesDoNotMove() {
        let line = "- [~] Draft the doc on:sai by:sai ^t1 due:2026-10-01 @sai"
        let t = task(line)
        XCTAssertEqual(t.title, "Draft the doc")
        XCTAssertEqual(t.extras, ["on:sai", "by:sai"])
        XCTAssertEqual(t.id, "t1"); XCTAssertEqual(t.assignee, "sai"); XCTAssertEqual(t.status, .inProgress)
        XCTAssertEqual(t.due?.value, .allDay(LocalDate("2026-10-01")!))
        XCTAssertEqual(PageCodec.encodeBlock(.task(t)), line)
        XCTAssertEqual(rebuilt(t), line)
    }

    /// Where one day's build wrote them: at the very end. The reader that stopped there took the
    /// id, the due date and the assignee for title words — the failure this exists to prevent.
    func testByAndOnAtTheEndDoNotSwallowTheIdDueOrAssignee() {
        let line = "- [~] Draft the doc ^t1 due:2026-10-01 @sai on:sai by:sai"
        let t = task(line)
        XCTAssertEqual(t.title, "Draft the doc")
        XCTAssertEqual(t.id, "t1"); XCTAssertEqual(t.assignee, "sai")
        XCTAssertEqual(t.due?.value, .allDay(LocalDate("2026-10-01")!))
        XCTAssertEqual(t.extras, ["on:sai", "by:sai"])
        // Unedited, the line keeps its own bytes — the tokens stay exactly where they were.
        XCTAssertEqual(PageCodec.encodeBlock(.task(t)), line)
    }

    func testAnEditedLinePutsThemStraightAfterTheTitle() {
        var t = task("- [~] Draft the doc ^t1 due:2026-10-01 @sai on:sai by:sai")
        t.status = .done; t.doneAt = LocalDate("2026-10-02")
        XCTAssertEqual(PageCodec.encodeBlock(.task(t)), "- [x] Draft the doc on:sai by:sai ^t1 due:2026-10-01 done:2026-10-02 @sai")
        var u = task("- [ ] Draft the doc on:sai ^t1")
        u.title = "Draft the memo"
        XCTAssertEqual(PageCodec.encodeBlock(.task(u)), "- [ ] Draft the memo on:sai ^t1")
    }

    func testEitherTokenAloneInEitherPosition() {
        for (line, title, extras) in [
            ("- [ ] Added by:batunii ^t2", "Added", ["by:batunii"]),
            ("- [ ] Added ^t2 by:batunii", "Added", ["by:batunii"]),
            ("- [~] Mine on:sai ^t3 !High", "Mine", ["on:sai"]),
            ("- [~] Mine ^t3 !High on:sai", "Mine", ["on:sai"]),
            ("- [~] Ours ^t4 on:a on:b", "Ours", ["on:a", "on:b"]),
            ("- [ ] by:batunii", "", ["by:batunii"]),
        ] {
            let t = task(line)
            XCTAssertEqual(t.title, title, line); XCTAssertEqual(t.extras, extras, line)
            XCTAssertEqual(PageCodec.encodeBlock(.task(t)), line, line)
            XCTAssertNotEqual(t.id.isEmpty && line.contains("^"), true, "lost the id: \(line)")
        }
    }

    /// One author: a second `by:` further left stops the scan, as the newer build's `author == null`
    /// guard does. An empty value is not a token either.
    func testASecondByAndAnEmptyOneAreNotTokens() {
        let t = task("- [ ] x by:a by:b ^t1")
        XCTAssertEqual(t.extras, ["by:b"]); XCTAssertEqual(t.title, "x by:a")
        XCTAssertEqual(task("- [ ] x by: ^t1").title, "x by:")
        XCTAssertEqual(task("- [ ] x on: ^t1").title, "x on:")
    }

    /// The general rule: an unknown `key:value` word to the right of known tokens is a token, kept,
    /// so it cannot hide them. One with nothing known to its left was title all along — 17d0beb's
    /// reading, and the same bytes.
    func testAnUnknownTrailingTokenIsATokenOnlyWhenItWouldOtherwiseHideOne() {
        let t = task("- [ ] Ship it ^t1 !High foo:bar")
        XCTAssertEqual(t.id, "t1"); XCTAssertEqual(t.priority, "High"); XCTAssertEqual(t.extras, ["foo:bar"]); XCTAssertEqual(t.title, "Ship it")
        XCTAssertEqual(PageCodec.encodeBlock(.task(t)), "- [ ] Ship it ^t1 !High foo:bar")
        for line in ["- [ ] Fix login re:bug", "- [ ] Meet at 10:30", "- [ ] Read https://example.com/a", "- [ ] Standup zoom:abc123",
                     "- [ ] x re:a b ^t1", "- [ ] pay rent due:not-a-date ^a1"] {
            let u = task(line)
            XCTAssertEqual(u.extras, [], line)
            XCTAssertEqual(rebuilt(u), line, "a line with no tolerated token must render exactly as 17d0beb does: \(line)")
        }
        // A URL is never a token, even where a key:value word would be.
        XCTAssertEqual(task("- [ ] x ^t1 https://example.com").id, "")
    }

    /// iOS gives them no meaning: there is nowhere on `TaskRef` for an author or an owner to go, and
    /// the index never sees them.
    func testTheTokensMeanNothingHere() throws {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("yantra-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = WorkspaceStore(root: dir); store.scaffold(name: "T", now: 0)
        let writer = WorkspaceWriter(store: store, device: "iphone16,2")
        let l = try writer.createTopLevel(type: NodeType.list, title: "L")
        try writer.editPage(l) { var p = $0; p.blocks = [PageCodec.decodeBlock("- [~] X ^t1 @sai on:sai by:sai")]; return p }
        let n = WorkspaceIndex.read(store).nodes["t1"]
        XCTAssertEqual(n?.title, "X"); XCTAssertEqual(n?.assignee, "sai"); XCTAssertEqual(n?.inProgress, true)
    }

    /// The brief's tolerance test: a page carrying both positions and a nine-field focus line, an
    /// iPhone edit to a *different* line, and every one of those bytes is still there.
    func testAnEditElsewhereLeavesTheirBytesIntact() throws {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("yantra-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = WorkspaceStore(root: dir); store.scaffold(name: "T", now: 0)
        let writer = WorkspaceWriter(store: store, device: "iphone16,2")
        let lines = ["- [~] Draft the doc on:sai by:sai ^t1 due:2026-10-01 @sai",
                     "- [~] Other ^t2 due:2026-10-01 @sai on:batunii",
                     "- [ ] Mine ^t3"]
        let text = "---\nid: p1\ntype: list\ntitle: L\nmodified_at: 2026-09-01T00:00:00Z\ndevice: sm-s921b\n---\n" + lines.joined(separator: "\n") + "\n"
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("pages"), withIntermediateDirectories: true)
        try Data(text.utf8).write(to: store.pageFile("p1"))
        let nine = "773c\tt1\t1789693231980\t1789696367926\t0\t3133\t0\tstopped\tbatunii"
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("focus"), withIntermediateDirectories: true)
        try Data((nine + "\n").utf8).write(to: dir.appendingPathComponent("focus/2026-09.log"))

        try writer.setDone("t3", true)
        let after = try String(contentsOf: store.pageFile("p1"), encoding: .utf8)
        XCTAssertTrue(after.contains(lines[0] + "\n")); XCTAssertTrue(after.contains(lines[1] + "\n"))
        XCTAssertTrue(after.contains("- [x] Mine ^t3 done:"))
        // A session appended on this device leaves the other device's line exactly as it was.
        let s = FocusSession(id: "abcd", nodeId: "t3", startedAt: 1789693231980, plannedSecs: 0)
        try writer.appendFocus(s.line, month: "2026-09")
        XCTAssertEqual(store.readFocusLines(), [nine, s.line])
    }

    // focus

    func testANinthFocusFieldIsReadPastAndCarriedOn() {
        let nine = "773c\tf5c5\t1789693231980\t1789696367926\t0\t3133\t0\tstopped\tbatunii"
        let s = FocusSession.parse(nine)!
        XCTAssertEqual(s.outcome, "stopped"); XCTAssertEqual(s.actualSecs, 3133); XCTAssertEqual(s.extraFields, ["batunii"])
        XCTAssertEqual(s.line, nine)
        // Settling it keeps the field rather than truncating the line.
        let settled = FocusLedger.settle(s, actualSecs: 120, outcome: FocusOutcome.stopped, now: 1789693351980)
        XCTAssertTrue(settled.line.hasSuffix("\tstopped\tbatunii"))
        // And fields past the ninth, whatever they are.
        XCTAssertEqual(FocusSession.parse(nine + "\tx\t")!.line, nine + "\tx\t")
    }

    func testFocusLinesAreReadTheWayTheReconcilerReadsThem() {
        // Seven fields, the eighth derived from the old boolean.
        XCTAssertEqual(FocusSession.parse("a\tn\t1\t2\t0\t1\t1")?.outcome, FocusOutcome.ranOut)
        XCTAssertEqual(FocusSession.parse("a\tn\t1\t2\t0\t1\t0")?.outcome, FocusOutcome.stopped)
        XCTAssertEqual(FocusSession.parse("a\tn\t1\t2\t0\t1\t0\t")?.outcome, FocusOutcome.stopped)
        XCTAssertNil(FocusSession.parse("a\tn\t1\t2\t0\t1"))
        XCTAssertNil(FocusSession.parse("a\tn\tnot-a-time\t2\t0\t1\t0"))
        // A planned length that is not a number is 0, not a dropped session.
        XCTAssertEqual(FocusSession.parse("a\tn\t1\t\tx\t\t1\tran_out")?.plannedSecs, 0)
    }
}

// MARK: - the workspace's own files

final class WorkspaceFilesTests: XCTestCase {
    var dir: URL!
    var store: WorkspaceStore!
    var writer: WorkspaceWriter!
    let fm = FileManager.default

    override func setUpWithError() throws {
        dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("yantra-files-\(UUID().uuidString)")
        store = WorkspaceStore(root: dir, id: "ws")
        store.scaffold(name: "Personal", now: 1790187515872)
        writer = WorkspaceWriter(store: store, device: "iphone16,2")
    }
    override func tearDownWithError() throws { try? fm.removeItem(at: dir) }

    func bytes(_ rel: String) -> String { (try? String(contentsOf: dir.appendingPathComponent(rel), encoding: .utf8)) ?? "<missing>" }
    func write(_ rel: String, _ s: String) throws {
        try fm.createDirectory(at: dir.appendingPathComponent(rel).deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(s.utf8).write(to: dir.appendingPathComponent(rel))
    }

    // the Kotlin's own bytes, from conformance/meta/json.json

    struct JsonCase: Decodable { let name: String; let json: String }
    func kotlin(_ name: String) throws -> String {
        let url = ConformanceTests.root.appendingPathComponent("meta/json.json")
        let cases = try JSONDecoder().decode([JsonCase].self, from: Data(contentsOf: url))
        return try XCTUnwrap(cases.first { $0.name == name }?.json, name)
    }

    func testScaffoldWritesExactlyWhatAndroidScaffolds() throws {
        XCTAssertEqual(bytes(".yantra/manifest.json"), #"{"name":"Personal","createdAt":1790187515872}"#)
        XCTAssertEqual(bytes(".yantra/meta/properties.json"), try kotlin("properties.json"))
        XCTAssertEqual(bytes(".yantra/meta/labels.json"), "[]")
        XCTAssertEqual(bytes(".gitignore"), "# Half-written files, mid-rename. Never a real edit.\n*.tmp\n")
        var isDir: ObjCBool = false
        XCTAssertTrue(fm.fileExists(atPath: dir.appendingPathComponent(".yantra/meta/smartlists").path, isDirectory: &isDir) && isDir.boolValue)
    }

    func testHighPriorityIsTheColourAndroidStores() {
        let cfg = WorkspaceStore.builtInProperties()[0].selectConfig
        XCTAssertEqual(cfg.options.map(\.color), [0xFFFF4A1F, 0xFFFFB020, 0xFF4A90D9])
        XCTAssertEqual(cfg.options.first?.color, 4294920735)
    }

    func testSeededSmartListsAreTheKotlinBytes() throws {
        XCTAssertEqual(FilterJSON.encode(WorkspaceSeeder.todayDef(nodeId: "today-id", inbox: "inbox-id")), try kotlin("today.json"))
        XCTAssertEqual(FilterJSON.encode(WorkspaceSeeder.highPriorityDef(nodeId: "high-id", inbox: "inbox-id")), try kotlin("high.json"))
    }

    func testEveryFilterSortAndApplyShapeIsTheKotlinBytes() throws {
        let f: Filter = .all([
            .anyOf([.not(.inProgress(true)), .hasLabel(":label:x"), .inWorkspace("w1")]),
            .prop(defId: "d", op: .gte, number: 5.0), .prop(defId: "d", op: .lt, number: 0.5), .prop(defId: "d", op: .lt, number: 1.0e7),
            .prop(defId: "d", op: .lt, number: 1.25e-4), .prop(defId: "d", op: .neq, number: -3.0), .prop(defId: "d", op: .eq, date: 1788177735338),
            .prop(defId: "d", op: .eq, bool: true), .prop(defId: "d", op: .isSet), .prop(defId: "d", op: .notSet, text: "t"),
        ])
        XCTAssertEqual(FilterJSON.encode(f), try kotlin("filter-every-kind.json"))
        XCTAssertEqual(FilterJSON.encode([SortSpec(by: .propNumber, defId: "n", desc: true, nullsLast: false), SortSpec(by: .propText, defId: "t"),
                                          SortSpec(by: .title)]), try kotlin("sort-every-kind.json"))
        XCTAssertEqual(FilterJSON.encode([ApplyOnCreate(defId: "a", number: 2.0), ApplyOnCreate(defId: "b", date: 7),
                                          ApplyOnCreate(defId: "c", bool: false, dateRel: .todayStart)]), try kotlin("apply-every-kind.json"))
        // And what is read back is what was written.
        XCTAssertEqual(FilterJSON.decode(Filter.self, try kotlin("filter-every-kind.json")), f)
    }

    func testRegistriesAndSmallShapesAreTheKotlinBytes() throws {
        // Read back and rewritten: every escape kotlinx makes, and every one it does not (DEL, U+2028).
        let labels = try kotlin("labels.json")
        let decoded = try JSONDecoder().decode([LabelDef].self, from: Data(labels.utf8))
        XCTAssertEqual(Array(FilterJSON.encode(decoded).utf8), Array(labels.utf8))
        XCTAssertEqual(decoded.count, 4)
        XCTAssertEqual(Array(FilterJSON.encode(Array(decoded.prefix(3))).utf8), Array(FilterJSON.encode([
            LabelDef(id: ":label:sync", name: "sync", color: 0xFF5D8F52), LabelDef(id: "ws:label:bare", name: "bare"),
            LabelDef(id: "w:label:e", name: "\u{1b}\u{7f}\t\n\r\u{08}\u{0c} é / \u{2028} 😀 \\ \"", color: 0),
        ]).utf8))
        XCTAssertEqual(FilterJSON.encode([LabelDef]()), try kotlin("labels-empty.json"))
        XCTAssertEqual(FilterJSON.encode(SelectConfig()), try kotlin("select-empty.json"))
        XCTAssertEqual(FilterJSON.encode(SelectConfig(options: [SelectOption(name: "Just a word")])), try kotlin("select-bare-option.json"))
        XCTAssertEqual(FilterJSON.encode(SmartListDef(nodeId: "n", scopeRootId: "r", filterJson: #"{"kind":"done","value":true}"#)),
                       try kotlin("smartlist-scoped.json"))
    }

    func testNumbersArePrintedAsTheJVMPrintsThem() throws {
        let values: [Double] = [0.0, -0.0, 1.0, 0.1, 0.001, 0.0009999, 9999999.0, 10000000.0, 123456789.125, 1.0e21, 1.0e-7, 2.5e-10,
                                Double.greatestFiniteMagnitude, 1.0 / 3.0, 100.0, 12345.678]
        XCTAssertEqual(FilterJSON.encode(values.map { ApplyOnCreate(defId: "n", number: $0) }), try kotlin("numbers.json"))
    }

    // the manifest

    func testTheManifestOmitsEveryDefaultAndReadsAMissingVersionAsTwo() throws {
        XCTAssertEqual(Manifest(name: "v2-tasks", createdAt: 1788177735338).compact(), try kotlin("manifest-new.json"))
        XCTAssertEqual(Manifest(formatVersion: 3, name: "x", createdAt: 1, epoch: 4, archiveAfterDays: 30).compact(), try kotlin("manifest-full.json"))
        let missing = try JSONDecoder().decode(Manifest.self, from: Data(#"{"name":"a","createdAt":1}"#.utf8))
        XCTAssertEqual(String(missing.formatVersion), try kotlin("manifest-missing-version-reads-as"))
        XCTAssertTrue(missing.readable)
        // Unknown keys are ignored, as `ignoreUnknownKeys = true` ignores them.
        XCTAssertEqual(try JSONDecoder().decode(Manifest.self, from: Data(#"{"name":"a","createdAt":1,"future":[1]}"#.utf8)).name, "a")
    }

    func testUpgradeFormatRewritesAVersionOneManifestWithoutTheKey() throws {
        try write(".yantra/manifest.json", #"{"formatVersion":1,"name":"old","createdAt":5}"#)
        XCTAssertTrue(store.upgradeFormat())
        XCTAssertEqual(bytes(".yantra/manifest.json"), try kotlin("manifest-upgraded.json"))
        XCTAssertFalse(store.upgradeFormat())
        // A workspace already current is not rewritten, even one that spells its version out.
        try write(".yantra/manifest.json", #"{"formatVersion":2,"name":"Personal","createdAt":1790187515872}"#)
        XCTAssertFalse(store.upgradeFormat())
        XCTAssertEqual(bytes(".yantra/manifest.json"), #"{"formatVersion":2,"name":"Personal","createdAt":1790187515872}"#)
    }

    func testAWorkspaceFromTheFutureRefusesEveryWrite() throws {
        try write(".yantra/manifest.json", #"{"formatVersion":3,"name":"new","createdAt":5}"#)
        XCTAssertTrue(store.isReadOnly)
        store.writeLabels([LabelDef(id: "x", name: "x")])
        XCTAssertEqual(bytes(".yantra/meta/labels.json"), "[]")
        XCTAssertFalse(store.upgradeFormat())
        store.writeManifest(Manifest(name: "clobbered", createdAt: 0))
        XCTAssertEqual(bytes(".yantra/manifest.json"), #"{"formatVersion":3,"name":"new","createdAt":5}"#)
    }

    // upkeep on open

    func testEnsureGitignoreAppendsWhatIsMissingAndNormalisesTheEnd() throws {
        try write(".gitignore", "node_modules\n*.tmp")
        XCTAssertTrue(store.ensureGitignore())
        XCTAssertEqual(bytes(".gitignore"), "node_modules\n*.tmp\n# Half-written files, mid-rename. Never a real edit.\n")
        XCTAssertFalse(store.ensureGitignore())
        // Compared trimmed.
        try write(".gitignore", "  *.tmp  \r\n# Half-written files, mid-rename. Never a real edit.")
        XCTAssertFalse(store.ensureGitignore())
    }

    func testEnsureBuiltInPropertiesFillsGapsAtTheEndAndTouchesNothingElse() throws {
        let custom = #"[{"id":"mine","name":"Mine","kind":"text"},{"id":"builtin-due","name":"When","kind":"date"}]"#
        try write(".yantra/meta/properties.json", custom)
        XCTAssertTrue(store.ensureBuiltInProperties())
        let ids = store.readProperties().map(\.id)
        XCTAssertEqual(ids, ["mine", "builtin-due", "builtin-priority", "builtin-deadline", "builtin-assignee"])
        XCTAssertEqual(store.readProperties()[1].name, "When")
        XCTAssertFalse(store.ensureBuiltInProperties())
    }

    func testOpeningAnExistingWorkspaceRunsTheUpkeepAndNothingMore() throws {
        try fm.removeItem(at: dir.appendingPathComponent(".gitignore"))
        let before = bytes(".yantra/meta/properties.json")
        XCTAssertFalse(store.open(name: "Ignored", now: 0))
        XCTAssertEqual(bytes(".gitignore"), "# Half-written files, mid-rename. Never a real edit.\n*.tmp\n")
        XCTAssertEqual(bytes(".yantra/meta/properties.json"), before)
        XCTAssertEqual(store.readManifest()?.name, "Personal")
        let fresh = WorkspaceStore(root: dir.appendingPathComponent("new"))
        XCTAssertTrue(fresh.open(name: "New", now: 7))
        XCTAssertEqual(fresh.readManifest()?.name, "New")
    }

    func testTheLegacyPomodoroDirectoryIsMigratedIntoFocus() throws {
        try write("pomodoro/2026-08.log", "a\tn\t1\t2\t0\t1\t1\n")
        try write("pomodoro/2026-09.log", "b\tn\t3\t4\t0\t1\t0\n")
        try write("focus/2026-09.log", "c\tn\t5\t6\t0\t1\t0\tstopped\n")
        // Read together first, sorted by file name across both directories, focus/ first on a tie.
        XCTAssertEqual(store.readFocusLines().map { String($0.prefix(1)) }, ["a", "c", "b"])
        store.migrateLegacyFocusDir()
        XCTAssertFalse(fm.fileExists(atPath: dir.appendingPathComponent("pomodoro").path))
        XCTAssertEqual(bytes("focus/2026-08.log"), "a\tn\t1\t2\t0\t1\t1\n")
        XCTAssertEqual(bytes("focus/2026-09.log"), "c\tn\t5\t6\t0\t1\t0\tstopped\nb\tn\t3\t4\t0\t1\t0\n")
        // Something unexpected in there is left alone, and so is the directory holding it.
        try write("pomodoro/notes.txt", "keep")
        store.migrateLegacyFocusDir()
        XCTAssertEqual(bytes("pomodoro/notes.txt"), "keep")
    }

    func testFocusLogsReadWithCRLFAndBlankLines() throws {
        try write("focus/2026-09.log", "a\tn\t1\t2\t0\t1\t1\r\n\r\n  \nb\tn\t3\t4\t0\t1\t0\n")
        XCTAssertEqual(store.readFocusLines(), ["a\tn\t1\t2\t0\t1\t1", "b\tn\t3\t4\t0\t1\t0"])
    }

    // pages

    func testDeletingASmartListTakesItsDefinitionWithIt() throws {
        let id = try writer.createTopLevel(type: NodeType.smartList, title: "Mine")
        try writer.writeSmartList(WorkspaceSeeder.highPriorityDef(nodeId: id, inbox: "x"))
        XCTAssertTrue(fm.fileExists(atPath: store.smartListFile(id).path))
        try writer.deletePage(id)
        XCTAssertFalse(fm.fileExists(atPath: store.smartListFile(id).path))
    }

    func testDeletingAPageThatNamesItselfDoesNotRecurseForever() throws {
        try write("pages/loop.md", "---\nid: loop\ntype: task\nparent: loop\nmodified_at: 2026-01-01T00:00:00Z\n---\n- [ ] me ^loop\n- [ ] child ^kid\n")
        try write("pages/kid.md", "---\nid: kid\ntype: task\nparent: loop\nmodified_at: 2026-01-01T00:00:00Z\n---\n- [ ] back ^loop\n")
        store.deletePage("loop")
        XCTAssertFalse(fm.fileExists(atPath: store.pageFile("loop").path))
        XCTAssertFalse(fm.fileExists(atPath: store.pageFile("kid").path))
    }

    func testAnEventGetsAnEventPage() throws {
        let l = try writer.createTopLevel(type: NodeType.list, title: "L")
        let e = try writer.addEvent(to: l, title: "Standup", time: EventTime(start: LocalDateTime("2026-09-11T09:00")!, end: LocalDateTime("2026-09-11T09:15")!))
        _ = try writer.addBlock(to: e, type: NodeType.paragraph, text: "agenda")
        let page = try XCTUnwrap(store.readPage(e))
        XCTAssertEqual(page.type, NodeType.event); XCTAssertEqual(page.parent, l); XCTAssertNil(page.title)
        XCTAssertEqual(page.device, "iphone16,2")
        XCTAssertEqual(page.blocks.map(\.text), ["agenda"])
    }

    func testAPageThatIsNotValidUTF8IsStillAPage() throws {
        var d = Data("---\nid: bad\ntype: list\ntitle: Caf".utf8); d.append(0xE9); d.append(contentsOf: Array("\nmodified_at: 2026-01-01T00:00:00Z\n---\n- [ ] x ^t1\n".utf8))
        try fm.createDirectory(at: dir.appendingPathComponent("pages"), withIntermediateDirectories: true)
        try d.write(to: store.pageFile("bad"))
        let p = try XCTUnwrap(store.readPage("bad"))
        XCTAssertEqual(p.title, "Caf\u{FFFD}"); XCTAssertEqual(p.blocks.count, 1)
    }

    func testAWriteLeavesNoTemporaryFileBehind() throws {
        _ = try writer.createTopLevel(type: NodeType.list, title: "L")
        let names = try fm.contentsOfDirectory(atPath: dir.appendingPathComponent("pages").path)
        XCTAssertFalse(names.contains { $0.hasSuffix(".tmp") })
    }

    // labels

    func testUpsertingALabelMovesItToTheEndAsAndroidDoes() throws {
        try writer.upsertLabel("alpha"); try writer.upsertLabel("beta")
        try writer.setLabelColor("ALPHA", 0xFF00948E)
        XCTAssertEqual(store.readLabels().map(\.name), ["beta", "alpha"])
        XCTAssertEqual(store.readLabels().map(\.id), ["ws:label:beta", "ws:label:alpha"])
        XCTAssertEqual(bytes(".yantra/meta/labels.json"),
                       #"[{"id":"ws:label:beta","name":"beta","color":"# + String(LabelPalette.defaultFor("beta"))
                        + #"},{"id":"ws:label:alpha","name":"alpha","color":4278228110}]"#)
        // getOrCreate: a name it knows, in any case or padding, writes nothing.
        try writer.upsertLabel("  Beta ")
        XCTAssertEqual(store.readLabels().map(\.name), ["beta", "alpha"])
    }

    func testDeletingALabelStripsItFromEveryTaskLine() throws {
        let l = try writer.createTopLevel(type: NodeType.list, title: "L")
        try writer.editPage(l) { var p = $0; p.blocks = [
            PageCodec.decodeBlock("- [ ] one ^t1 #Errands #home"),
            PageCodec.decodeBlock("- [ ] two ^t2 #home"),
            PageCodec.decodeBlock("@ 2026-09-11 event #errands"),
        ]; return p }
        try writer.upsertLabel("errands")
        XCTAssertEqual(try writer.deleteLabel("errands"), 1)
        XCTAssertTrue(store.readLabels().isEmpty)
        let text = try String(contentsOf: store.pageFile(l), encoding: .utf8)
        XCTAssertTrue(text.contains("- [ ] one ^t1 #home\n"))
        XCTAssertTrue(text.contains("- [ ] two ^t2 #home\n"))
        // Only task lines, as on Android: an event's tag is left where it is.
        XCTAssertTrue(text.contains("@ 2026-09-11 event #errands\n"))
    }

    // device

    func testThePageStampIsTheLowercasedHardwareModel() {
        XCTAssertEqual(DeviceModel.stamp(machine: "iPhone16,2", environment: [:]), "iphone16,2")
        XCTAssertEqual(DeviceModel.stamp(machine: "arm64", environment: ["SIMULATOR_MODEL_IDENTIFIER": "iPhone17,1"]), "iphone17,1")
        XCTAssertFalse(DeviceModel.pageStamp.isEmpty)
    }
}

// MARK: - java.time, spelled out

final class JavaTimeTests: XCTestCase {
    func testInstantTextIsJavasToString() {
        XCTAssertEqual(JavaInstant.parse("2026-09-24T22:27:24.207Z")?.description, "2026-09-24T22:27:24.207Z")
        XCTAssertEqual(JavaInstant.parse("2026-09-24T22:27:24.207123Z")?.description, "2026-09-24T22:27:24.207123Z")
        XCTAssertEqual(JavaInstant.parse("2026-09-24T22:27:24.2Z")?.description, "2026-09-24T22:27:24.200Z")
        XCTAssertEqual(JavaInstant.parse("2026-09-24T22:27:24.000000001Z")?.description, "2026-09-24T22:27:24.000000001Z")
        XCTAssertEqual(JavaInstant.parse("2026-09-24T22:27:24Z")?.description, "2026-09-24T22:27:24Z")
        XCTAssertEqual(JavaInstant.parse("2026-09-24T23:27:24+01:00")?.description, "2026-09-24T22:27:24Z")
        XCTAssertEqual(JavaInstant.parse("1969-12-31T23:59:59.5Z")?.description, "1969-12-31T23:59:59.500Z")
        XCTAssertNil(JavaInstant.parse("2026-09-24T22:27Z"))
        XCTAssertNil(JavaInstant.parse("2026-09-24 22:27:24Z"))
        XCTAssertEqual(InstantText.format(Date(timeIntervalSince1970: 1_790_187_515.872)), "2026-09-23T18:18:35.872Z")
        XCTAssertEqual(JavaInstant(Date(timeIntervalSince1970: -0.25)).description, "1969-12-31T23:59:59.750Z")
    }

    func testDurationTextAndGrammarAreJavas() {
        XCTAssertEqual(ISODuration("PT1.5S")?.description, "PT1.5S")
        XCTAssertEqual(ISODuration("pt1h")?.description, "PT1H")
        XCTAssertEqual(ISODuration("P2D")?.description, "PT48H")
        XCTAssertEqual(ISODuration("-PT1H")?.description, "PT-1H")
        XCTAssertEqual(ISODuration("-PT-1H")?.description, "PT1H")
        XCTAssertEqual(ISODuration("PT1H-30M")?.description, "PT30M")
        XCTAssertEqual(ISODuration("PT-0.5S")?.description, "PT-0.5S")
        XCTAssertEqual(ISODuration("-PT1H0.5S")?.description, "PT-1H-0.5S")
        XCTAssertEqual(ISODuration("PT1,5S")?.description, "PT1.5S")
        XCTAssertEqual(ISODuration("PT1.S")?.description, "PT1S")
        for bad in ["P", "PT", "P1DT", "P1W", "PT30M1H", "PT99999999999999999999H", "PT\u{FF11}H", "1H", "PT1.5H", "PT1.1234567891S"] {
            XCTAssertNil(ISODuration(bad), bad)
        }
    }

    func testDatesAreJavasStrictISO() {
        XCTAssertNil(LocalDate("2026-02-29")); XCTAssertNotNil(LocalDate("2028-02-29"))
        XCTAssertNil(LocalDate("+2026-01-01")); XCTAssertEqual(LocalDate("+12026-01-01")?.description, "+12026-01-01")
        XCTAssertEqual(LocalDate("-2026-01-01")?.description, "-2026-01-01"); XCTAssertEqual(LocalDate("0000-01-01")?.description, "0000-01-01")
        XCTAssertNil(LocalDate("12026-01-01")); XCTAssertNil(LocalDate("2026-1-01")); XCTAssertNil(LocalDate("\u{FF12}026-01-01"))
        XCTAssertEqual(LocalDate(year: -5, month: 1, day: 1).description, "-0005-01-01")
        XCTAssertEqual(LocalDate(epochDay: 0).description, "1970-01-01")
        XCTAssertEqual(LocalDate("2026-09-24")!.epochDay, 20720)
        XCTAssertEqual(LocalDateTime("2026-09-11T14:00:30.")?.description, "2026-09-11T14:00:30")
        XCTAssertEqual(LocalDateTime("2026-09-11T14:00:30.5")?.description, "2026-09-11T14:00:30.500")
        XCTAssertNil(LocalDateTime("2026-09-11T24:00")); XCTAssertNil(LocalDateTime("2026-09-11T1:00"))
    }

    func testZonesAreJavasIds() {
        XCTAssertEqual(JavaZone.normalize("Europe/Dublin"), "Europe/Dublin")
        XCTAssertEqual(JavaZone.normalize("GMT+1"), "GMT+01:00")
        XCTAssertEqual(JavaZone.normalize("+1"), "+01:00")
        XCTAssertEqual(JavaZone.normalize("+00:00"), "Z")
        XCTAssertEqual(JavaZone.normalize("UTC"), "UTC")
        XCTAssertEqual(JavaZone.normalize("UTC+00:00"), "UTC")
        XCTAssertNil(JavaZone.normalize("europe/dublin")); XCTAssertNil(JavaZone.normalize("EST")); XCTAssertNil(JavaZone.normalize("Nowhere/Fake"))
    }

    func testKotlinIntegersAndWhitespace() {
        XCTAssertEqual(KText.toInt("+5"), 5); XCTAssertEqual(KText.toInt("\u{0663}"), 3); XCTAssertNil(KText.toInt("3000000000"))
        XCTAssertNil(KText.toInt("+")); XCTAssertNil(KText.toInt("")); XCTAssertEqual(KText.toInt("-2147483648"), Int(Int32.min))
        XCTAssertEqual(KText.trim("\u{00A0}\u{2028}x\u{001F}"), "x")
        XCTAssertEqual(KText.trim("\u{0085}x"), "\u{0085}x")
        XCTAssertEqual(KText.readLines("a\r\nb\rc\n\nd\n"), ["a", "b", "c", "", "d"])
    }
}

// MARK: - the label palette

/// `LabelPaletteTest.kt`: the palette's promises are numeric, so they are checked, not eyeballed.
final class LabelPalettePortTests: XCTestCase {
    struct Lch { let l: Double, c: Double, h: Double }
    func lin(_ c: Double) -> Double { c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
    func lch(_ argb: Int64) -> Lch {
        let r = lin(Double((argb >> 16) & 0xFF) / 255), g = lin(Double((argb >> 8) & 0xFF) / 255), b = lin(Double(argb & 0xFF) / 255)
        let lp = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        let mp = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        let sp = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        let l = 0.2104542553 * lp + 0.7936177850 * mp - 0.0040720468 * sp
        let a = 1.9779984951 * lp - 2.4285922050 * mp + 0.4505937099 * sp
        let bb = 0.0259040371 * lp + 0.7827717662 * mp - 0.8086757660 * sp
        return Lch(l: l, c: hypot(a, bb), h: (atan2(bb, a) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360))
    }
    func gap(_ a: Double, _ b: Double) -> Double { let d = abs(a - b).truncatingRemainder(dividingBy: 360); return d > 180 ? 360 - d : d }

    func testEverySwatchSharesOneLightnessAndChroma() {
        for s in LabelPalette.swatches {
            XCTAssertEqual(lch(s.light).l, 0.600, accuracy: 0.01); XCTAssertEqual(lch(s.light).c, 0.104, accuracy: 0.008)
            XCTAssertEqual(lch(s.dark).l, 0.730, accuracy: 0.01); XCTAssertEqual(lch(s.dark).c, 0.125, accuracy: 0.008)
        }
    }
    func testASwatchKeepsItsHueAcrossThemes() {
        for s in LabelPalette.swatches { XCTAssertLessThan(gap(lch(s.light).h, lch(s.dark).h), 2, s.name) }
    }
    func testNoSwatchEntersTheHuesTheColourLawHasSpokenFor() {
        for s in LabelPalette.swatches { for h in [25.0, 38.0, 71.0] { XCTAssertGreaterThan(gap(lch(s.light).h, h), 35, s.name) } }
    }
    func testNoTwoSwatchesAreCloseEnoughToConfuse() {
        let hs = LabelPalette.swatches.map { lch($0.light).h }
        for i in hs.indices { for j in hs.indices where j > i { XCTAssertGreaterThan(gap(hs[i], hs[j]), 40) } }
    }
    func testDisplaySwapsASwatchForItsTwinAndLeavesEverythingElseAlone() {
        let s = LabelPalette.swatches[0]
        XCTAssertEqual(LabelPalette.display(s.light, dark: true), s.dark); XCTAssertEqual(LabelPalette.display(s.dark, dark: false), s.light)
        XCTAssertEqual(LabelPalette.display(s.light, dark: false), s.light)
        XCTAssertEqual(LabelPalette.display(0xFFFF4A1F, dark: true), 0xFFFF4A1F)
    }
    func testDisplayRoundTrips() {
        for s in LabelPalette.swatches { XCTAssertEqual(LabelPalette.display(LabelPalette.display(s.light, dark: true), dark: false), s.light) }
    }
    func testTheDefaultColourIsStableAndIgnoresCaseAndPadding() {
        XCTAssertEqual(LabelPalette.defaultFor("groceries"), LabelPalette.defaultFor("  Groceries "))
        // Kotlin's trim, which a no-break space and a line separator are inside.
        XCTAssertEqual(LabelPalette.defaultFor("groceries"), LabelPalette.defaultFor("\u{00A0}groceries\u{2028}"))
    }
    func testTheDefaultColourIsAlwaysALightSwatchValue() {
        let lights = Set(LabelPalette.swatches.map(\.light))
        for n in ["a", "work", "home", "errands", "reading", "", "ünïcode", "12345"] { XCTAssertTrue(lights.contains(LabelPalette.defaultFor(n)), n) }
    }
    func testDifferentNamesGenerallyGetDifferentColours() {
        XCTAssertNotEqual(Set(["work", "home", "errands", "reading", "health"].map(LabelPalette.defaultFor)).count, 1)
    }
}
