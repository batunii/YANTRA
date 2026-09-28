import XCTest
@testable import YantraCore

/// Recolouring a label, and what "no colour" means — `LabelRepository.getOrCreate`/`setColor`.
extension WorkspaceTests {

    private func def(_ name: String) -> LabelDef? { store.readLabels().first { $0.name == name } }

    func testANewLabelIsWrittenWithTheColourItsNameSeeds() throws {
        try writer.upsertLabel("errands")
        // Not nil: on Android a stored nil is the neutral chip somebody chose, so a label made here
        // with no colour came out grey on every other device.
        XCTAssertEqual(def("errands")?.color, LabelPalette.defaultFor("errands"))
    }

    func testRecolouringChangesTheOneEntryAndKeepsItsIdentity() throws {
        try writer.upsertLabel("errands")
        let id = def("errands")?.id
        try writer.setLabelColor("Errands", LabelPalette.swatches[1].light)
        XCTAssertEqual(store.readLabels().filter { $0.name.lowercased() == "errands" }.count, 1)
        XCTAssertEqual(def("errands")?.id, id)
        XCTAssertEqual(def("errands")?.color, LabelPalette.swatches[1].light)
        XCTAssertEqual(LabelPalette.stored("errands", registry: index.labels), LabelPalette.swatches[1].light)
    }

    func testNoColourIsAChoiceAndSurvivesAReadBack() throws {
        try writer.upsertLabel("errands")
        try writer.setLabelColor("errands", nil)
        XCTAssertNil(def("errands")?.color)
        // Neutral, not the seed: the seed is only for a label the registry has never heard of.
        XCTAssertNil(LabelPalette.stored("errands", registry: index.labels))
        XCTAssertEqual(LabelPalette.stored("never-registered", registry: index.labels),
                       LabelPalette.defaultFor("never-registered"))
        // And a later upsert with no colour does not quietly re-seed it.
        try writer.upsertLabel("errands")
        XCTAssertNil(def("errands")?.color)
    }

    func testRecolouringALabelTheRegistryHasNotSeenAddsIt() throws {
        try writer.setLabelColor("fresh", LabelPalette.swatches[3].light)
        XCTAssertEqual(def("fresh")?.color, LabelPalette.swatches[3].light)
    }
}
