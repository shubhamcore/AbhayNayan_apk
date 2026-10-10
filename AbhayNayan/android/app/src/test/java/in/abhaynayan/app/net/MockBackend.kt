package `in`.abhaynayan.app.net

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Minimal mocked FastAPI backend for JVM tests (plain ServerSocket + HTTP/1.1, one request per
 * connection). Each route returns a scripted (status, body, delay).
 */
class MockBackend : AutoCloseable {
    data class Reply(val code: Int, val body: String, val delayMs: Long = 0)
    data class Received(val method: String, val path: String, val body: String)

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    @Volatile private var routes: Map<String, (String) -> Reply> = emptyMap()
    val requests = CopyOnWriteArrayList<Received>()
    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true, name = "mock-backend") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: SocketException) { break }
                thread(isDaemon = true) { handle(client) }
            }
        }
    }

    private fun handle(client: Socket) { client.use { s ->
        try {
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return@use
            val (method, target) = requestLine.split(" ").let { it[0] to it[1] }
            var length = 0
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                if (header.lowercase().startsWith("content-length:")) length = header.substringAfter(':').trim().toInt()
            }
            val body = ByteArray(length).also { buf -> var read = 0; while (read < length) { val n = input.read(buf, read, length - read); if (n < 0) break; read += n } }
                .toString(Charsets.UTF_8)
            val path = target.substringBefore('?')
            requests += Received(method, path, body)
            val reply = routes[path]?.invoke(body) ?: Reply(404, """{"detail":"Not found."}""")
            if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 ${reply.code} X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            s.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
        } catch (_: Exception) {
            // Client disconnected (timeout tests) — nothing to do.
        }
    } }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("US-ASCII")
            if (b == '\n'.code) return out.toString("US-ASCII").trimEnd('\r')
            out.write(b)
        }
    }

    fun on(path: String, handler: (String) -> Reply) { routes = routes + (path to handler) }
    fun on(path: String, code: Int, body: String, delayMs: Long = 0) = on(path) { Reply(code, body, delayMs) }

    override fun close() = socket.close()
}

/** android.jar's org.json lacks keySet() at compile time; keys() works with both implementations. */
fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()
