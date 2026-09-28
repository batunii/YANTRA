package ie.shoonya.yantra

import ie.shoonya.yantra.data.sync.ConflictResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two people writing on one task keep both of their notes.
 *
 * The case this exists for is the ordinary one, not an edge: Sai opens a task and writes what he
 * found, I open the same task and write what I found, and we are both offline on the train. Neither
 * of us changed anything the other wrote — we each *added* something — but our additions land in the
 * same place in the file, so git calls it a conflict and hands it here.
 *
 * The old answer was last-writer-wins on the whole block, which threw one person's paragraph away
 * with no message and no mark on the screen. The only trace was a git history nobody was going to
 * read. A note that has to be written twice is worse than a merge marker.
 *
 * What tells an addition apart from a disagreement is the **base**: if the common ancestor had
 * nothing at that key, nothing was changed and two things were written. Only a block both sides
 * edited is a real disagreement, and only that one still has to lose.
 *
 * The other half of every test here is that the two machines agree **byte for byte**. `local` and
 * `remote` are opposite labels on the two devices, so every case is run twice with the sides
 * swapped and the results compared. A merge that resolves differently on each side has not merged
 * anything: both push, both conflict again, and the file ping-pongs forever.
 */
class TwoPeopleOnOneTaskTest {

    private fun page(stamp: String, device: String, body: String) = """
        |---
        |id: 7f754074-778a-4d6c-b359-61a2d821116d
        |type: task
        |parent: e9e70341-47b3-4f1b-975c-74d109c58f8e
        |modified_at: $stamp
        |device: $device
        |---
        |$body
    """.trimMargin().toByteArray()

    /** Resolve from both devices' points of view. They must produce the same file. */
    private fun bothWays(base: ByteArray?, mine: ByteArray, theirs: ByteArray): String {
        val here = ConflictResolver.resolve(
            "pages/7f754074-778a-4d6c-b359-61a2d821116d.md",
            local = mine, remote = theirs, device = "sm-s921b", otherDevice = "pixel-8", base = base,
        )
        val there = ConflictResolver.resolve(
            "pages/7f754074-778a-4d6c-b359-61a2d821116d.md",
            local = theirs, remote = mine, device = "pixel-8", otherDevice = "sm-s921b", base = base,
        )
        assertEquals(
            "the two devices resolved the same conflict differently",
            there.bytes?.decodeToString(), here.bytes?.decodeToString(),
        )
        return here.bytes!!.decodeToString()
    }

    @Test fun `two notes added at once are both kept`() {
        val base = page("2026-09-24T09:00:00.000Z", "sm-s921b", "")
        val mine = page("2026-09-24T09:31:00.000Z", "sm-s921b", "Asked legal about the contract")
        val theirs = page("2026-09-24T09:30:00.000Z", "pixel-8", "Chased the invoice")

        val merged = bothWays(base, mine, theirs)

        assertTrue("my note was dropped: $merged", "Asked legal about the contract" in merged)
        assertTrue("their note was dropped: $merged", "Chased the invoice" in merged)
    }

    /**
     * The same millisecond, which is what a pair of devices syncing on reconnection actually
     * produces — both pages were written offline and both carry the clock of the moment they were
     * typed. Ordering by timestamp alone has nothing to say here, and the fallback used to be "keep
     * mine", which is the one answer that cannot be the same on both machines.
     */
    @Test fun `two notes written in the same millisecond still agree on an order`() {
        val base = page("2026-09-24T09:00:00.000Z", "sm-s921b", "")
        val mine = page("2026-09-24T09:30:00.000Z", "sm-s921b", "Asked legal about the contract")
        val theirs = page("2026-09-24T09:30:00.000Z", "pixel-8", "Chased the invoice")

        val merged = bothWays(base, mine, theirs)

        assertTrue(merged, "Asked legal about the contract" in merged)
        assertTrue(merged, "Chased the invoice" in merged)
    }

    /**
     * Two people adding *tasks* under one task is the same event seen through ids. Both subtasks
     * survive, and neither inherits the other's id.
     */
    @Test fun `two subtasks added at once are both kept`() {
        val base = page("2026-09-24T09:00:00.000Z", "sm-s921b", "- [ ] Pay the invoice ^2b69047e-6173-4637-9055-129c157d361a")
        val mine = page(
            "2026-09-24T09:31:00.000Z", "sm-s921b",
            "- [ ] Pay the invoice ^2b69047e-6173-4637-9055-129c157d361a\n" +
                "- [ ] Ask legal ^a2d00125-686e-40c7-ada7-5a5e4a446521",
        )
        val theirs = page(
            "2026-09-24T09:30:00.000Z", "pixel-8",
            "- [ ] Pay the invoice ^2b69047e-6173-4637-9055-129c157d361a\n" +
                "- [ ] Chase the invoice ^f40f069a-efd2-4654-a54a-006f9f5518dc",
        )

        val merged = bothWays(base, mine, theirs)

        assertTrue(merged, "Ask legal" in merged)
        assertTrue(merged, "Chase the invoice" in merged)
        assertTrue("the untouched task went missing: $merged", "Pay the invoice" in merged)
    }

    /**
     * The line the rule is drawn on. Both sides rewrote **the same** note — that is a disagreement,
     * not two additions, and the base is what says so. Someone still loses, deterministically.
     */
    @Test fun `both editing one note is still a disagreement`() {
        val base = page("2026-09-24T09:00:00.000Z", "sm-s921b", "Draft the reply")
        val mine = page("2026-09-24T09:31:00.000Z", "sm-s921b", "Draft the reply by Friday")
        val theirs = page("2026-09-24T09:30:00.000Z", "pixel-8", "Draft the reply today")

        val merged = bothWays(base, mine, theirs)

        assertTrue("the newer edit should have won: $merged", "Draft the reply by Friday" in merged)
        assertTrue("both edits were kept, which invents a note nobody wrote: $merged",
            "Draft the reply today" !in merged)
    }

    /** One side touching a block and the other leaving it alone is not a conflict at all. */
    @Test fun `an edit one side made survives untouched`() {
        val base = page("2026-09-24T09:00:00.000Z", "sm-s921b", "Draft the reply")
        val mine = page("2026-09-24T09:31:00.000Z", "sm-s921b", "Draft the reply by Friday")
        val theirs = page("2026-09-24T09:30:00.000Z", "pixel-8", "Draft the reply")

        val merged = bothWays(base, mine, theirs)

        assertTrue(merged, "Draft the reply by Friday" in merged)
    }
}
