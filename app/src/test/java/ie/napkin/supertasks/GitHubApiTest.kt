package ie.napkin.supertasks

import ie.napkin.supertasks.data.sync.GitHubApi
import ie.napkin.supertasks.data.sync.RepoCheck
import ie.napkin.supertasks.data.sync.RepoCreate
import ie.napkin.supertasks.data.sync.RepoRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The API slice, against a real HTTP server.
 *
 * Two of these guard decisions that are silent when wrong. Read access reported as push access means
 * a workspace that commits happily for a week and can never push any of it. And a repository name
 * already in use being treated as a failure would stop someone re-running the setup they just half
 * finished, which is exactly when they would re-run it.
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
    fun `a created repository is private and not initialised`() {
        FakeGitHub().use { server ->
            server.on("/user", 200, """{"login":"batunii"}""")
            server.on("/user/repos", 201, """{"full_name":"batunii/tasks"}""")

            val made = api(server).createRepo("tasks", "ghp_x") as RepoCreate.Ok
            assertEquals(RepoRef("batunii", "tasks"), made.ref)

            val post = server.seen.last { it.path == "/user/repos" }
            assertEquals("POST", post.method)
            // A task list is a diary of what someone has not done yet. Public by accident is not a
            // mistake this app gets to make for them.
            assertTrue(post.body.contains("\"private\":true"))
            // An initial commit on main would be a second root for the orphan branch to step around.
            assertTrue(post.body.contains("\"auto_init\":false"))
        }
    }

    @Test
    fun `a name already in use is reported as taken rather than failed`() {
        FakeGitHub().use { server ->
            server.on("/user", 200, """{"login":"batunii"}""")
            server.on(
                "/user/repos", 422,
                """{"errors":[{"message":"name already exists on this account"}]}""",
            )
            val made = api(server).createRepo("tasks", "ghp_x")
            assertEquals(RepoCreate.Taken(RepoRef("batunii", "tasks")), made)
        }
    }

    @Test
    fun `a name GitHub will not accept is a failure`() {
        FakeGitHub().use { server ->
            server.on("/user", 200, """{"login":"batunii"}""")
            server.on("/user/repos", 422, """{"errors":[{"message":"is too long"}]}""")
            assertTrue(api(server).createRepo("x".repeat(300), "ghp_x") is RepoCreate.Failed)
        }
    }

    @Test
    fun `creating without a usable token stops before asking`() {
        FakeGitHub().use { server ->
            server.on("/user", 401, "{}")
            assertTrue(api(server).createRepo("tasks", "bad") is RepoCreate.Failed)
            // Never reached the create endpoint: there would be no owner to name the result with.
            assertTrue(server.seen.none { it.path == "/user/repos" })
        }
    }

    @Test
    fun `an invitation is asked for whether or not they already have access`() {
        // 201 is a fresh invitation, 204 means they were already in. Both are what the caller wanted.
        listOf(201, 204).forEach { code ->
            FakeGitHub().use { server ->
                server.on("/repos/batunii/YANTRA/collaborators/alice", code, "")
                assertTrue("$code read as failure", api(server).addCollaborator(ref, "alice", "t"))
                val put = server.seen.single()
                assertEquals("PUT", put.method)
                assertTrue(put.body.contains("push"))
            }
        }
    }

    @Test
    fun `an unknown username is not invited`() {
        FakeGitHub().use { server ->
            server.on("/repos/batunii/YANTRA/collaborators/nobody", 404, "{}")
            assertFalse(api(server).addCollaborator(ref, "nobody", "t"))
        }
    }
}
