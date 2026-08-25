package ie.napkin.supertasks

import ie.napkin.supertasks.data.sync.DevicePoll
import ie.napkin.supertasks.data.sync.GitHubDeviceAuth
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
        GitHubDeviceAuth(clientId = clientId, base = server.base)

    @Test
    fun `a code comes back ready to show`() {
        FakeGitHub().use { server ->
            server.on("/login/device/code", 200, codeJson)
            val code = auth(server).start()!!

            assertEquals("dev-123", code.deviceCode)
            assertEquals("WDJB-MJHT", code.userCode)
            assertEquals("https://github.com/login/device", code.verificationUri)
            assertEquals(5, code.intervalSecs)

            // Form-encoded, with the scope we actually intend to ask for — if this said something
            // else the consent screen would too, and the user would be approving the wrong thing.
            val sent = server.seen.single()
            assertEquals("POST", sent.method)
            assertTrue(sent.body.contains("client_id=Iv1.testclient"))
            assertTrue(sent.body.contains("scope=repo"))
        }
    }

    @Test
    fun `an unconfigured build never reaches the network`() {
        FakeGitHub().use { server ->
            server.on("/login/device/code", 200, codeJson)
            assertNull(auth(server, clientId = "").start())
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
            assertEquals(DevicePoll.Pending, a.poll(a.start()!!))
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
            val code = a.start()!!
            assertEquals(DevicePoll.Token("gho_abc123"), a.poll(code))

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
            assertEquals(DevicePoll.SlowDown(10), a.poll(a.start()!!))
        }
    }

    @Test
    fun `slow down with no interval still backs off`() {
        FakeGitHub().use { server ->
            server.on("/login/device/code", 200, codeJson)
            server.on("/login/oauth/access_token", 200, """{"error":"slow_down"}""")

            val a = auth(server)
            val poll = a.poll(a.start()!!) as DevicePoll.SlowDown
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
                val failed = a.poll(a.start()!!) as DevicePoll.Failed
                assertTrue(
                    "$error read as: ${failed.reason}",
                    failed.reason.contains(expected, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `an unreachable server is a failure and not a crash`() {
        // Port 1 is nothing. Polling has to survive a dropped connection: it happens on every
        // sign-in that starts on wifi and finishes in a lift.
        val a = GitHubDeviceAuth(clientId = "x", base = "http://127.0.0.1:1")
        val code = ie.napkin.supertasks.data.sync.DeviceCode("d", "U-1", "https://x", 5, 900)
        assertTrue(a.poll(code) is DevicePoll.Failed)
        assertNull(a.start())
    }
}
