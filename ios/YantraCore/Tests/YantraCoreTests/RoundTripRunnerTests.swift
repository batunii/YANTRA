import XCTest
@testable import YantraCore

/// The differential round trip, in the spirit of `web/scripts/roundtrip.ts`: point it at a real
/// workspace clone and it decodes and re-encodes every file this build reads — pages, archive
/// lines, focus-log lines, the manifest and the registries — and expects the bytes Android wrote.
///
/// The unit suite pins behaviour the port was *written* to have; this pins the behaviour it must
/// actually have against somebody's repository. Skipped unless pointed at one:
///
///     YANTRA_ROUNDTRIP_DIR=~/clones/yantra-tasks swift test --filter RoundTripRunnerTests
///
/// `YANTRA_ROUNDTRIP_OUT=<dir>` also writes every re-encoded file there under its own path, which
/// is what `conformance/tools/roundtrip.sh` diffs against the Kotlin's re-encoding of the same clone.
/// Nothing in the clone is ever written to.
final class RoundTripRunnerTests: XCTestCase {

    struct Outcome { var checked = 0; var changed: [(String, String, String)] = [] }

    func testEveryFileInTheCloneRoundTripsByteForByte() throws {
        guard let path = ProcessInfo.processInfo.environment["YANTRA_ROUNDTRIP_DIR"], !path.isEmpty else {
            throw XCTSkip("set YANTRA_ROUNDTRIP_DIR to a workspace clone")
        }
        let root = URL(fileURLWithPath: (path as NSString).expandingTildeInPath)
        let out = ProcessInfo.processInfo.environment["YANTRA_ROUNDTRIP_OUT"].map { URL(fileURLWithPath: ($0 as NSString).expandingTildeInPath) }
        let result = Self.run(root, writingTo: out)

        var report = "\n\(result.checked - result.changed.count)/\(result.checked) files round-trip byte-for-byte in \(root.path)\n"
        for (file, before, after) in result.changed {
            report += "--- CHANGED \(file)\n" + Self.firstDifference(before, after)
        }
        print(report)
        XCTAssertGreaterThan(result.checked, 0, "nothing to check in \(root.path)")
        XCTAssertTrue(result.changed.isEmpty, report)
    }

    static func run(_ root: URL, writingTo out: URL?) -> Outcome {
        var o = Outcome()
        let fm = FileManager.default
        func read(_ rel: String) -> String? { (try? Data(contentsOf: root.appendingPathComponent(rel))).map(WorkspaceStore.decodeText) }
        func list(_ rel: String, _ suffix: String) -> [String] {
            ((try? fm.contentsOfDirectory(atPath: root.appendingPathComponent(rel).path)) ?? [])
                .filter { $0.hasSuffix(suffix) }.sorted().map { rel.isEmpty ? $0 : "\(rel)/\($0)" }
        }
        func check(_ rel: String, _ before: String, _ after: String) {
            o.checked += 1
            if !before.utf8.elementsEqual(after.utf8) { o.changed.append((rel, before, after)) }
            if let out {
                let f = out.appendingPathComponent(rel)
                try? fm.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
                try? Data(after.utf8).write(to: f)
            }
        }

        // Pages, live and archived: `encode(decode(text))`.
        for rel in list("pages", ".md") + list("archive/pages", ".md") {
            guard let text = read(rel) else { continue }
            check(rel, text, PageCodec.encode(PageCodec.decode(text)))
        }
        // Archived lines: each one an ordinary block line, the file `lines + "\n"` with blanks gone.
        for rel in list("archive", ".md") {
            guard let text = read(rel) else { continue }
            let lines = KText.readLines(text).filter { !KText.isBlank($0) }
            check(rel, text, lines.map { PageCodec.encodeBlock(PageCodec.decodeBlock($0)) }.joined(separator: "\n") + "\n")
        }
        // Focus logs: every line this build can read must write back as itself. A seven-field line
        // from before the outcome existed is the one exception by construction — the eighth field
        // is derived, and iOS never rewrites a line it did not write — so it is compared as is.
        for rel in list("focus", ".log") + list("pomodoro", ".log") {
            guard let text = read(rel) else { continue }
            let rebuilt = KText.readLines(text).map { line -> String in
                guard let s = FocusSession.parse(line), KText.split(line, "\t").count >= 8 else { return line }
                return s.line
            }
            check(rel, text, rebuilt.map { $0 + "\n" }.joined())
        }
        // The manifest and the registries, through the types the store reads them into.
        if let m = read(".yantra/manifest.json") {
            check(".yantra/manifest.json", m, (try? JSONDecoder().decode(Manifest.self, from: Data(m.utf8)))?.compact() ?? "<unreadable>")
        }
        if let l = read(".yantra/meta/labels.json") {
            check(".yantra/meta/labels.json", l, (try? JSONDecoder().decode([LabelDef].self, from: Data(l.utf8))).map(FilterJSON.encode) ?? "<unreadable>")
        }
        if let p = read(".yantra/meta/properties.json") {
            check(".yantra/meta/properties.json", p, (try? JSONDecoder().decode([PropertyDef].self, from: Data(p.utf8))).map(FilterJSON.encode) ?? "<unreadable>")
        }
        for rel in list(".yantra/meta/smartlists", ".json") {
            guard let s = read(rel) else { continue }
            check(rel, s, (try? JSONDecoder().decode(SmartListDef.self, from: Data(s.utf8))).map(FilterJSON.encode) ?? "<unreadable>")
        }
        // `.gitignore`: what `ensureGitignore` would make of it, without writing it.
        let gi = read(".gitignore")
        let lines = gi.map(KText.readLines) ?? []
        let missing = WorkspaceStore.gitignore.filter { want in !lines.contains { KText.same(KText.trim($0), want) } }
        check(".gitignore", gi ?? "<absent>", missing.isEmpty ? (gi ?? "") : (lines + missing).joined(separator: "\n") + "\n")
        return o
    }

    static func firstDifference(_ a: String, _ b: String) -> String {
        let x = KText.split(a, "\n"), y = KText.split(b, "\n")
        var s = ""
        for i in 0..<max(x.count, y.count) {
            let l = i < x.count ? x[i] : "<none>", r = i < y.count ? y[i] : "<none>"
            if !l.utf8.elementsEqual(r.utf8) { s += "  line \(i + 1)\n    file: \(l.debugDescription)\n    ios:  \(r.debugDescription)\n" }
        }
        return s
    }

    /// The runner itself, over the conformance pages, so it is exercised on every run and not only
    /// when somebody remembers to point it at a clone.
    func testTheRunnerAgreesWithTheKotlinOnTheFixturePages() throws {
        let dir = ConformanceTests.root.appendingPathComponent("pages/roundtrip")
        let tmp = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("yantra-rt-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: tmp) }
        try FileManager.default.createDirectory(at: tmp.appendingPathComponent("pages"), withIntermediateDirectories: true)
        for n in try FileManager.default.contentsOfDirectory(atPath: dir.appendingPathComponent("out").path) where n.hasSuffix(".md") {
            // Kotlin's output is a fixed point: re-encoding it changes nothing, on either side.
            try FileManager.default.copyItem(at: dir.appendingPathComponent("out/\(n)"), to: tmp.appendingPathComponent("pages/\(n)"))
        }
        let r = Self.run(tmp, writingTo: nil)
        XCTAssertEqual(r.changed.map(\.0), [".gitignore"], "only the absent .gitignore should differ")
        XCTAssertGreaterThan(r.checked, 20)
    }
}
