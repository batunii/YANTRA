package ie.shoonya.yantra

import ie.shoonya.yantra.data.sync.DeviceStart
import ie.shoonya.yantra.data.sync.DevicePoll
import ie.shoonya.yantra.data.sync.GitHubAuth
import ie.shoonya.yantra.data.sync.GitHubDeviceAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device flow, against a real HTTP server.
 *
 * The interesting cases are all the ones that are not success. A sign-in that hangs forever because
 * `authorization_pending` was read as a failure, or that spins the token endpoint because
 * `slow_down` was ignored, looks like a network problem rather than a bug — and the second one gets
 * the OAuth app rate-limited for every install, not just this one.
 */
class GitHubDeviceAuthTest {

    private val codeJson = """
        {"device_code":"dev-123","user_code":"WDJB-MJHT",
         "verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}
    """.trimIndent()

    private fun auth(server: FakeGitHub, clientId: String = "Iv1.testclient") =
        GitHubDeviceAuth(base = server.base) { clientId }

    /** The code, for the tests whose subject is the poll rather than the start. */
    private fun GitHubDeviceAuth.started(): ie.shoonya.yantra.data.sync.DeviceCode =
        (start() as DeviceStart.Ok).code

    @Test
    fun `a code comes back ready to show`() {
        FakeGitHub().use { server ->
            server.on("/login/device/code", 200, codeJson)
            val code = (auth(server).start() as DeviceStart.Ok).code

            assertEquals("dev-123", code.deviceCode)
            assertEquals("WDJB-MJHT", code.userCode)
            assertEquals("https://github.com/login/device", code.verificationUri)
            assertEquals(5, code.intervalSecs)

            val sent = server.seen.single()
            assertEquals("POST", sent.method)
            assertTrue(sent.body.contains("client_id=Iv1.testclient"))
            // The scope goes out with the *code* request, not the token exchange: GitHub builds
            // the consent screen from it, so asking later would be asking after the user had already
            // agreed to something narrower. Sent with no scope at all, the device flow yields a token
            // that can read public data and nothing else — which authenticates perfectly and then
            // fails at the first private repository.
            assertTrue("no scope was sent: ${sent.body}", sent.body.contains("scope=repo"))
        }
    }

    @Test
    fun `an unconfigured build never reaches the network`() {
        FakeGitHub().use { server ->
            server.on("/login/device/code", 200, codeJson)
            assertTrue(auth(server, clientId = "").start() is DeviceStart.Failed)
            // The point: no request at all, rather than one that fails. A build with no client id
            // should say so on the screen, not produce a network error.
            assertTrue(server.seen.isEmpty())
        }
    }

    @Test
    fun `waiting for the user is not a failure`() {
        FakeGitHub().use { server ->
            server.on("/login/device/code", 200, codeJson)
            // GitHub has answered this with both 200 and 400 over the years, so the error field
            // decides and the status line does not.
            server.on("/login/oauth/access_token", 400, """{"error":"authorization_pending"}""")

            val a = auth(server)
            assertEquals(DevicePoll.Pending, a.poll(a.started()))
        }
    }

    @Test
    fun `the token is picked up when it arrives`() {
        FakeGitHub().use { server ->
            server.on("/login/device/code", 200, codeJson)
            server.on(
                "/login/oauth/access_token", 200,
                """{"access_token":"gho_abc123","token_type":"bearer","scope":"repo"}""",
            )

            val a = auth(server)
            assertEquals(DevicePoll.Token("gho_abc123"), a.poll(a.started()))

            // The device code identifies the request; sending the wrong grant type is the mistake
            // that makes GitHub answer unsupported_grant_type forever.
            val poll = server.seen.last()
            assertTrue(poll.body.contains("device_code=dev-123"))
            assertTrue(poll.body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
        }
    }

    @Test
    fun `slow down raises the interval rather than stopping`() {
        FakeGitHub().use { server ->
            server.on("/login/device/code", 200, codeJson)
            server.on("/login/oauth/access_token", 200, """{"error":"slow_down","interval":10}""")

            val a = auth(server)
            assertEquals(DevicePoll.SlowDown(10), a.poll(a.started()))
        }
    }

    @Test
    fun `slow down with no interval still backs off`() {
        FakeGitHub().use { server ->
            server.on("/login/device/code", 200, codeJson)
            server.on("/login/oauth/access_token", 200, """{"error":"slow_down"}""")

            val a = auth(server)
            val poll = a.poll(a.started()) as DevicePoll.SlowDown
            // Backing off to the same interval would be no backoff at all, and GitHub is entitled to
            // omit the field.
            assertTrue("did not back off: ${poll.intervalSecs}", poll.intervalSecs > 5)
        }
    }

    @Test
    fun `each way it can end has its own sentence`() {
        val cases = mapOf(
            "expired_token" to "expired",
            "access_denied" to "cancelled",
            "device_flow_disabled" to "device flow",
        )
        cases.forEach { (error, expected) ->
            FakeGitHub().use { server ->
                server.on("/login/device/code", 200, codeJson)
                server.on("/login/oauth/access_token", 200, """{"error":"$error"}""")

                val a = auth(server)
                val failed = a.poll(a.started()) as DevicePoll.Failed
                assertTrue(
                    "$error read as: ${failed.reason}",
                    failed.reason.contains(expected, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `a failure says what went wrong rather than just that it did`() {
        FakeGitHub().use { server ->
            server.on("/login/device/code", 500, "")
            val failed = auth(server).start() as DeviceStart.Failed
            // "Could not reach GitHub" with nothing after it is unactionable for the user and
            // undiagnosable for us — which is exactly what happened the first time this ran for real.
            assertTrue("said nothing useful: ${failed.reason}", failed.reason.contains("500"))
        }
    }

    @Test
    fun `an unreachable server is offline, which is not the same as refused`() {
        // Port 1 is nothing. Polling has to survive a dropped connection: it happens on every
        // sign-in that starts on wifi and finishes in a lift.
        val a = GitHubDeviceAuth(base = "http://127.0.0.1:1") { "x" }
        val code = ie.shoonya.yantra.data.sync.DeviceCode("d", "U-1", "https://x", 5, 900)

        // Offline, specifically — the caller keeps polling. Reading a dropped request as a refusal
        // is what killed a sign-in that was going perfectly the first time this ran for real.
        assertTrue(a.poll(code) is DevicePoll.Offline)
        assertTrue(a.poll(code) !is DevicePoll.Failed)

        // Starting is different: there is no code to keep waiting on, so there is nothing to retry.
        assertTrue(a.start() is DeviceStart.Failed)
    }

    /**
     * The restricted method sends no scope at all, and that is not a detail.
     *
     * A GitHub App's permissions are fixed at registration and chosen again at each installation, so
     * there is nothing to ask for — and `scope=` sent empty is a different request from one that
     * omits the key. It is also the test that would have caught the two halves being wired to one
     * another's client id, which polls back `incorrect_client_credentials` and reads like a
     * misconfigured build.
     */
    @Test
    fun `the restricted method asks for no scope`() {
        FakeGitHub().use { server ->
            server.on("/login/device/code", 200, codeJson)
            val auth = GitHubDeviceAuth(base = server.base) { it.clientId }

            val code = (auth.start(GitHubAuth.Method.Restricted) as DeviceStart.Ok).code
            val sent = server.seen.single()

            assertTrue("a scope was sent: ${sent.body}", !sent.body.contains("scope"))
            assertTrue(sent.body.contains("client_id=${GitHubAuth.Method.Restricted.clientId}"))
            // Carried on the code, so the poll cannot reach for the other registration's id.
            assertEquals(GitHubAuth.Method.Restricted, code.method)
        }
    }

    @Test
    fun `polling uses the id the code was started with`() {
        FakeGitHub().use { server ->
            server.on("/login/device/code", 200, codeJson)
            server.on("/login/oauth/access_token", 200, """{"error":"authorization_pending"}""")
            val auth = GitHubDeviceAuth(base = server.base) { it.clientId }

            val code = (auth.start(GitHubAuth.Method.Restricted) as DeviceStart.Ok).code
            auth.poll(code)

            val polled = server.seen.last()
            assertTrue(
                "polled with the wrong registration: ${polled.body}",
                polled.body.contains("client_id=${GitHubAuth.Method.Restricted.clientId}"),
            )
        }
    }

    @Test
    fun `a refresh goes back to the registration that issued it`() {
        FakeGitHub().use { server ->
            server.on("/login/oauth/access_token", 200, """{"access_token":"gho_new"}""")
            val auth = GitHubDeviceAuth(base = server.base) { it.clientId }

            auth.refresh("r1", GitHubAuth.Method.Restricted)

            val sent = server.seen.single()
            assertTrue(sent.body.contains("client_id=${GitHubAuth.Method.Restricted.clientId}"))
            assertTrue(sent.body.contains("grant_type=refresh_token"))
        }
    }
}
