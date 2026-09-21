package ie.shoonya.yantra

import ie.shoonya.yantra.data.sync.GitHubApi
import ie.shoonya.yantra.data.sync.RepoCheck
import ie.shoonya.yantra.data.sync.RepoCreate
import ie.shoonya.yantra.data.sync.SignInState
import ie.shoonya.yantra.data.sync.RepoRef
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
    fun `a live token reads as signed in`() {
        FakeGitHub().use { server ->
            server.on("/user", 200, """{"login":"batunii","id":7}""")
            assertEquals(SignInState.Ok, api(server).signInState("t"))
        }
    }

    @Test
    fun `a revoked sign-in is told apart from a dead network`() {
        // These need opposite things said — one is signing in again, the other is waiting — so
        // collapsing them would have people re-authorising to fix a tunnel.
        listOf(401, 403).forEach { code ->
            FakeGitHub().use { server ->
                server.on("/user", code, """{"message":"Bad credentials"}""")
                assertEquals(SignInState.Unauthorized, api(server).signInState("t"))
            }
        }
        assertTrue(GitHubApi(base = "http://127.0.0.1:1").signInState("t") is SignInState.Failed)
    }

    @Test
    fun `rate limiting is not mistaken for a revoked sign-in`() {
        // The two need opposite things done: one is waiting, the other is signing in again — and
        // signing in again mints a token, of which GitHub keeps only ten before revoking the oldest.
        // Telling someone to spend one to fix a rate limit is how a working device stops working.
        FakeGitHub().use { server ->
            server.on("/user") { _ ->
                403 to """{"message":"You have exceeded a secondary rate limit"}"""
            }
            server.header("x-ratelimit-remaining", "0")
            assertTrue(api(server).signInState("t") is SignInState.Failed)
        }
    }

    @Test
    fun `a genuinely revoked token still reads as revoked`() {
        FakeGitHub().use { server ->
            // The same status, without the header that says why. This is the real thing.
            server.on("/user", 403, """{"message":"Bad credentials"}""")
            assertEquals(SignInState.Unauthorized, api(server).signInState("t"))
        }
    }

    @Test
    fun `a new repository is asked for private, by name`() {
        FakeGitHub().use { server ->
            server.on("/user/repos", 201, """{"full_name":"batunii/team-tasks","default_branch":"main"}""")
            val made = api(server).createRepo("team-tasks", "t")

            assertEquals(RepoCreate.Ok(RepoRef("batunii", "team-tasks"), "main"), made)
            val sent = server.seen.single { it.path == "/user/repos" }
            assertEquals("POST", sent.method)
            // Private is not a preference here. A task list made public by default cannot be made
            // private again by anyone who is not an admin of it.
            assertTrue(sent.body.contains(""""private":true"""))
            assertTrue(sent.body.contains(""""name":"team-tasks""""))
        }
    }

    @Test
    fun `where it landed is read from the answer, not from what we asked for`() {
        FakeGitHub().use { server ->
            // GitHub normalises names it does not like. Assembling the ref from the typed name would
            // point the workspace at a repository that does not exist, and only fail at the push.
            server.on("/user/repos", 201, """{"full_name":"batunii/my-tasks","default_branch":"main"}""")
            val made = api(server).createRepo("my tasks", "t")
            assertEquals(RepoCreate.Ok(RepoRef("batunii", "my-tasks"), "main"), made)
        }
    }

    @Test
    fun `a name already taken is its own answer`() {
        FakeGitHub().use { server ->
            // The likeliest failure by far, and the only one the user can fix without leaving the
            // screen — so it must not arrive dressed as "GitHub returned 422".
            server.on(
                "/user/repos", 422,
                """{"message":"Repository creation failed.","errors":[
                     {"message":"name already exists on this account"}]}""",
            )
            assertEquals(RepoCreate.Exists, api(server).createRepo("team-tasks", "t"))
        }
    }

    @Test
    fun `other refusals are not mistaken for a name clash`() {
        FakeGitHub().use { server ->
            server.on("/user/repos", 422, """{"message":"name is too long"}""")
            assertTrue(api(server).createRepo("x".repeat(200), "t") is RepoCreate.Failed)
        }
    }

    @Test
    fun `a sign-in that cannot create says so rather than failing vaguely`() {
        // What a token issued without the repo scope looks like: it authenticates, and this one
        // endpoint refuses it.
        listOf(401, 403).forEach { code ->
            FakeGitHub().use { server ->
                server.on("/user/repos", code, """{"message":"Requires authentication"}""")
                assertEquals(RepoCreate.Unauthorized, api(server).createRepo("team-tasks", "t"))
            }
        }
    }

    @Test
    fun `a 201 github will not explain is a failure, not a half-made workspace`() {
        FakeGitHub().use { server ->
            server.on("/user/repos", 201, """{"default_branch":"main"}""")
            assertTrue(api(server).createRepo("team-tasks", "t") is RepoCreate.Failed)
        }
    }
}
