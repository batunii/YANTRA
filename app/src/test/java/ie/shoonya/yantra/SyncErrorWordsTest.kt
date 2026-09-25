package ie.shoonya.yantra

import ie.shoonya.yantra.data.sync.SyncEngine
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a failed sync says on screen.
 *
 * Two rules, and the second is the one that was broken.
 *
 * **Read the cause, not the wrapper.** JGit's outer message names the command, never the problem: a
 * failed `add` says "Exception caught during execution of add command" and keeps the reason one or
 * two causes down. Matching the outer message put the name of a git subcommand on screen.
 *
 * **Say what to do.** The lock case is the one that made this matter. An interrupted write leaves
 * `.git/index.lock` behind, every write fails while it is there, and it clears itself on the next
 * pass a minute later. The raw message is a 90-character absolute path into app-private storage
 * ending in "you may delete the lock file and retry" — advice the reader cannot take, on a phone,
 * about a file they cannot see. It also invites the one response that is exactly wrong: retrying
 * immediately, which fails again for the same minute.
 */
class SyncErrorWordsTest {

    /** The real shape: JGit wraps the reason, twice. */
    private fun addFailure(reason: String) =
        IOException("Exception caught during execution of add command", IOException(reason))

    @Test
    fun `a lock says to wait, and never shows the path`() {
        val said = SyncEngine.readable(
            addFailure(
                "Cannot lock /data/data/ie.shoonya.yantra/files/workspaces/local/.git/index. " +
                    "Ensure that no other process has an open file handle on the lock file " +
                    "/data/data/ie.shoonya.yantra/files/workspaces/local/.git/index.lock, then you " +
                    "may delete the lock file and retry."
            )
        )
        assertTrue("should tell the reader to wait: $said", said.contains("try again in a minute"))
        assertFalse("must not put a storage path on screen: $said", said.contains("/data/"))
        assertFalse("must not tell a phone user to delete a file: $said", said.contains("delete"))
    }

    @Test
    fun `the reason is read through the wrapper, not the wrapper itself`() {
        val said = SyncEngine.readable(addFailure("Authentication is required but no CredentialsProvider"))
        assertEquals("GitHub would not accept the sign-in — sign in again in Settings", said)
        assertFalse("the wrapper names a command, not a problem: $said", said.contains("add command"))
    }

    @Test
    fun `being offline is not an error the reader has to do anything about`() {
        val said = SyncEngine.readable(IOException(java.net.UnknownHostException("github.com")))
        assertTrue(said, said.contains("Could not reach GitHub"))
    }

    /**
     * The shape JGit really produces, from the phone on 2026-09-25: the transport error names the
     * repo and "connection failed", and the reason is only in the causes' class names.
     */
    private fun transportFailure(cause: Throwable) = org.eclipse.jgit.api.errors.TransportException(
        "https://github.com/batunii/yantra-tasks.git: connection failed",
        org.eclipse.jgit.errors.TransportException(
            "https://github.com/batunii/yantra-tasks.git: connection failed", cause,
        ),
    )

    @Test
    fun `a failed lookup reads as offline, not as a raw resolver message`() {
        val said = SyncEngine.readable(
            transportFailure(
                java.net.UnknownHostException(
                    "Unable to resolve host \"github.com\": No address associated with hostname"
                )
            )
        )
        assertTrue(said, said.contains("Could not reach GitHub"))
    }

    @Test
    fun `a network with no way to GitHub says to reconnect`() {
        val said = SyncEngine.readable(transportFailure(java.net.NoRouteToHostException("Host unreachable")))
        assertTrue(said, said.contains("turn Wi-Fi off and on"))
    }

    @Test
    fun `a refused connection reads as offline`() {
        val said = SyncEngine.readable(
            transportFailure(java.net.ConnectException("Failed to connect to github.com/4.208.26.197:443"))
        )
        assertTrue(said, said.contains("Could not reach GitHub"))
    }

    @Test
    fun `losing a race is described as ordinary, because it is`() {
        val said = SyncEngine.readable(addFailure("rejected - non-fast-forward"))
        assertTrue(said, said.contains("Someone else pushed first"))
    }

    @Test
    fun `an unrecognised failure falls back to the innermost message, not the wrapper`() {
        val said = SyncEngine.readable(addFailure("something nobody has seen before"))
        assertEquals("something nobody has seen before", said)
    }

    @Test
    fun `a bare throwable with no cause still says something`() {
        val said = SyncEngine.readable(IllegalStateException("plain"))
        assertEquals("plain", said)
    }
}
