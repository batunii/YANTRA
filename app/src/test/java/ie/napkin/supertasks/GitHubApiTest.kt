package ie.napkin.supertasks

import ie.napkin.supertasks.data.sync.GitHubApi
import ie.napkin.supertasks.data.sync.RepoCheck
import ie.napkin.supertasks.data.sync.InstallState
import ie.napkin.supertasks.data.sync.RepoRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The API slice, against a real HTTP server.
 *
 * Two of these guard decisions that are silent when wrong. Read access reported as push access means
 * a workspace that commits happily for a week and can never push any of it. And a valid token with no
 * App installation can see nothing at all while looking perfectly signed in — the single most
 * confusing state this app can be in, and the one the install check exists to name.
 */
class GitHubApiTest {

    private val ref = RepoRef("batunii", "YANTRA")

    private fun api(server: FakeGitHub) = GitHubApi(base = server.base)

    @Test
    fun `the viewer is the token's owner`() {
        FakeGitHub().use { server ->
            server.on("/user", 200, """{"login":"batunii","id":1}""")
            assertEquals("batunii", api(server).viewer("ghp_x"))
            // Bearer, not `token` — the latter is the old form and is being retired.
            assertEquals("Bearer ghp_x", server.seen.single().auth)
        }
    }

    @Test
    fun `a rejected token has no viewer`() {
        FakeGitHub().use { server ->
            server.on("/user", 401, """{"message":"Bad credentials"}""")
            assertEquals(null, api(server).viewer("ghp_bad"))
        }
    }

    @Test
    fun `push access is push access and read access is not`() {
        FakeGitHub().use { server ->
            server.on(
                "/repos/batunii/YANTRA", 200,
                """{"default_branch":"main","permissions":{"push":true,"admin":false}}""",
            )
            val ok = api(server).check(ref, "t") as RepoCheck.Ok
            assertTrue(ok.canPush)
            assertEquals("main", ok.defaultBranch)
        }
        FakeGitHub().use { server ->
            server.on(
                "/repos/batunii/YANTRA", 200,
                """{"default_branch":"trunk","permissions":{"push":false,"admin":false}}""",
            )
            assertFalse((api(server).check(ref, "t") as RepoCheck.Ok).canPush)
        }
    }

    @Test
    fun `admin implies push`() {
        FakeGitHub().use { server ->
            // An owner's own repo can come back with admin set and push absent.
            server.on("/repos/batunii/YANTRA", 200, """{"permissions":{"admin":true}}""")
            assertTrue((api(server).check(ref, "t") as RepoCheck.Ok).canPush)
        }
    }

    @Test
    fun `each refusal is told apart`() {
        FakeGitHub().use { server ->
            server.on("/repos/batunii/YANTRA", 404, "{}")
            assertEquals(RepoCheck.NotFound, api(server).check(ref, "t"))
        }
        FakeGitHub().use { server ->
            server.on("/repos/batunii/YANTRA", 403, "{}")
            assertEquals(RepoCheck.Unauthorized, api(server).check(ref, "t"))
        }
        FakeGitHub().use { server ->
            server.on("/repos/batunii/YANTRA", 500, "{}")
            assertTrue(api(server).check(ref, "t") is RepoCheck.Failed)
        }
    }

    @Test
    fun `an installed app is found by its slug`() {
        FakeGitHub().use { server ->
            server.on(
                "/user/installations", 200,
                """{"total_count":2,"installations":[
                     {"id":1,"app_slug":"some-other-app"},
                     {"id":2,"app_slug":"yantra"}]}""",
            )
            assertEquals(InstallState.Installed, api(server).installState("t", "yantra"))
        }
    }

    @Test
    fun `someone else's apps do not count as ours`() {
        FakeGitHub().use { server ->
            // Matching on the count rather than the slug would read any installed app as ours, and
            // then every repository request would come back empty for no stated reason.
            server.on(
                "/user/installations", 200,
                """{"total_count":1,"installations":[{"id":1,"app_slug":"dependabot"}]}""",
            )
            assertEquals(InstallState.Absent, api(server).installState("t", "yantra"))
        }
    }

    @Test
    fun `no installation at all is absent rather than an error`() {
        FakeGitHub().use { server ->
            // This is the trap the whole check exists for: the token is perfectly valid and can see
            // nothing, so it must read as "one more step" and never as "something went wrong".
            server.on("/user/installations", 200, """{"total_count":0,"installations":[]}""")
            assertEquals(InstallState.Absent, api(server).installState("t", "yantra"))
        }
    }

    @Test
    fun `a dead token is told apart from a missing installation`() {
        // These need opposite things said — one is a browser trip, the other is signing in again —
        // so collapsing them into one "not installed" would send people to fix the wrong thing.
        listOf(401, 403).forEach { code ->
            FakeGitHub().use { server ->
                server.on("/user/installations", code, """{"message":"Bad credentials"}""")
                assertEquals(InstallState.Unauthorized, api(server).installState("t", "yantra"))
            }
        }
    }

    @Test
    fun `an unreachable github is neither absent nor unauthorized`() {
        val offline = GitHubApi(base = "http://127.0.0.1:1")
        assertTrue(offline.installState("t", "yantra") is InstallState.Failed)
    }

    @Test
    fun `a malformed installation list is absent rather than a crash`() {
        FakeGitHub().use { server ->
            server.on("/user/installations", 200, "not json at all")
            assertEquals(InstallState.Absent, api(server).installState("t", "yantra"))
        }
    }
}
