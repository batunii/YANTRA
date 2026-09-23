package ie.shoonya.yantra

import ie.shoonya.yantra.data.people.assignableLogins
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Who a quick capture on Home may name.
 *
 * Home used to offer Personal's roster alone, so a task you wanted to put on a colleague could not
 * be captured where you actually think of it. It offers everyone now, from every open repository —
 * a task assigned to somebody without access to the list it lands in is already shown as exactly
 * that, so the check is downstream where it can explain itself rather than upstream where it just
 * loses the thought.
 */
class AssignableLoginsTest {

    @Test
    fun `personal comes first, because Home's Inbox is Personal's`() {
        val out = assignableLogins(personal = listOf("me"), shared = listOf(listOf("sai", "shrey")))
        assertEquals(listOf("me", "sai", "shrey"), out)
    }

    @Test
    fun `the same person on two repositories is one person`() {
        val out = assignableLogins(
            personal = listOf("me"),
            shared = listOf(listOf("sai", "shrey"), listOf("shrey", "dev")),
        )
        assertEquals(listOf("me", "sai", "shrey", "dev"), out)
    }

    @Test
    fun `a name already in Personal keeps its first position`() {
        val out = assignableLogins(personal = listOf("me", "sai"), shared = listOf(listOf("sai")))
        assertEquals(listOf("me", "sai"), out)
    }

    @Test
    fun `with no shared repositories it is exactly Personal's roster`() {
        // The single-workspace case, which is most people most of the time: nothing new appears
        // and nothing is reordered.
        val out = assignableLogins(personal = listOf("me", "sai"), shared = emptyList())
        assertEquals(listOf("me", "sai"), out)
    }

    @Test
    fun `an empty Personal roster still offers the shared ones`() {
        // A device signed in but with no assignee ever used locally: the shared repos are the only
        // source, and offering nothing would be the old bug with extra steps.
        val out = assignableLogins(personal = emptyList(), shared = listOf(listOf("sai")))
        assertEquals(listOf("sai"), out)
    }

    @Test
    fun `nothing anywhere is empty rather than a crash`() {
        assertEquals(emptyList<String>(), assignableLogins(emptyList(), emptyList()))
    }
}
