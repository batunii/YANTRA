package ie.napkin.supertasks

import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections

/**
 * A real HTTP server, standing in for GitHub.
 *
 * Deliberately not a mock of the client. Everything that goes wrong in [ie.napkin.supertasks.data.sync.GitHubApi]
 * and [ie.napkin.supertasks.data.sync.GitHubDeviceAuth] goes wrong at the HTTP layer — a status code
 * read the wrong way round, an error body on a stream nobody reads, a form encoding GitHub rejects —
 * and a mocked client would simply agree with whatever the code already does.
 *
 * A raw socket rather than `com.sun.net.httpserver`, which is not fully visible on the Android
 * unit-test compile classpath. It is forty lines and it costs no dependency.
 */
class FakeGitHub : AutoCloseable {

    /** What to answer, keyed by path. A handler sees the request body and returns status to body. */
    private val routes = mutableMapOf<String, (String) -> Pair<Int, String>>()

    /** Every request that arrived, so a test can assert on what was actually sent. */
    val seen: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    data class Request(val method: String, val path: String, val body: String, val auth: String?)

    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())

    val base: String get() = "http://127.0.0.1:${server.localPort}"

    private val loop = Thread {
        while (!server.isClosed) {
            try {
                server.accept().use { serve(it) }
            } catch (_: SocketException) {
                return@Thread          // closed while blocked in accept, which is how we stop
            } catch (_: Exception) {
                // A client that hangs up mid-request must not take the server down: the token
                // endpoint is polled repeatedly and one aborted read would end the test run.
            }
        }
    }.apply { isDaemon = true; start() }

    fun on(path: String, handler: (String) -> Pair<Int, String>): FakeGitHub {
        routes[path] = handler
        return this
    }

    fun on(path: String, status: Int, body: String): FakeGitHub = on(path) { status to body }

    private fun serve(socket: java.net.Socket) {
        val input = socket.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, target) = lines[0].split(' ').let { it[0] to it.getOrElse(1) { "/" } }
        val headers = lines.drop(1)
            .mapNotNull { line -> line.split(": ", limit = 2).takeIf { it.size == 2 } }
            .associate { it[0].lowercase() to it[1] }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length).also { buf ->
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
        }.decodeToString()

        val path = target.substringBefore('?')
        seen += Request(method, path, body, headers["authorization"])

        val (status, response) = routes[path]?.invoke(body) ?: (404 to "{}")
        val bytes = response.toByteArray()
        BufferedOutputStream(socket.getOutputStream()).apply {
            // `Connection: close` on purpose. HttpURLConnection would otherwise keep the socket
            // alive and the next request would arrive on a connection this loop has stopped reading.
            write(
                ("HTTP/1.1 $status X\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            write(bytes)
            flush()
        }
    }

    /** Everything up to the blank line, read a byte at a time so the body is left in the stream. */
    private fun readHead(input: InputStream): String? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
        }
        return head.toString().removeSuffix("\r\n\r\n")
    }

    override fun close() {
        server.close()
        loop.join(1_000)
    }
}
