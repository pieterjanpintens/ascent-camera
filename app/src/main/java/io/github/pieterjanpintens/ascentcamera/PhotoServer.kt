package io.github.pieterjanpintens.ascentcamera

import android.os.Build
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A very small HTTP server for Ascent (see README.md, "Protocol"). One request per connection:
 *   GET /hello  -> {"app":"ascent-camera","protocol":1}         (no code needed; "is this the camera app?")
 *   GET /status -> device and app version                       (needs the pairing code in header X-Key)
 *   GET /photo  -> takes a photo, image/jpeg, header X-Rotation  (needs the pairing code)
 */
class PhotoServer(
    private val port: Int,
    private val pairingCode: () -> String,
    private val takePhoto: () -> Photo,
    private val onEvent: (String) -> Unit,
) {
    companion object {
        const val PROTOCOL = 1
        private const val MAX_LINE = 8 * 1024
        private val REASONS = mapOf(
            200 to "OK", 401 to "Unauthorized", 404 to "Not Found", 405 to "Method Not Allowed",
            500 to "Internal Server Error", 503 to "Service Unavailable",
        )
    }

    @Volatile private var socket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(2)
    private val capturing = AtomicBoolean(false)

    /** Throws when the port is taken. */
    fun start() {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        socket = server
        thread(name = "ascent-camera-server", isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                workers.execute { handle(client) }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        workers.shutdownNow()
    }

    private fun handle(client: Socket) {
        client.use { connection ->
            try {
                connection.soTimeout = 10_000
                val input = BufferedInputStream(connection.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (headers.size < 50) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val parts = requestLine.split(" ")
                if (parts.size < 2 || parts[0] != "GET") {
                    respond(connection, 405, "text/plain", "Only GET is supported".toByteArray())
                    return
                }
                when (val path = parts[1].substringBefore('?')) {
                    "/hello" -> respond(connection, 200, "application/json", """{"app":"ascent-camera","protocol":$PROTOCOL}""".toByteArray())
                    "/status", "/photo" -> {
                        if (!codeMatches(headers["x-key"])) {
                            Thread.sleep(500) // makes guessing the six digits slow
                            respond(connection, 401, "text/plain", "Wrong pairing code".toByteArray())
                            onEvent("Refused a request with a wrong code from ${connection.inetAddress.hostAddress}")
                            return
                        }
                        if (path == "/status") respond(connection, 200, "application/json", statusJson().toByteArray())
                        else sendPhoto(connection)
                    }
                    else -> respond(connection, 404, "text/plain", "Unknown path".toByteArray())
                }
            } catch (e: Exception) {
                onEvent("Request failed: ${e.message}")
            }
        }
    }

    private fun sendPhoto(connection: Socket) {
        if (!capturing.compareAndSet(false, true)) {
            respond(connection, 503, "text/plain", "Already taking a photo".toByteArray())
            return
        }
        try {
            val started = System.currentTimeMillis()
            val photo = takePhoto()
            respond(connection, 200, "image/jpeg", photo.jpeg, mapOf("X-Rotation" to photo.rotation.toString()))
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            onEvent("Photo sent to ${connection.inetAddress.hostAddress} (${"%.1f".format(seconds)} s, ${photo.jpeg.size / 1024} KB)")
        } catch (e: Exception) {
            respond(connection, 500, "text/plain", "The camera failed: ${e.message}".toByteArray())
            onEvent("The camera failed: ${e.message}")
        } finally {
            capturing.set(false)
        }
    }

    private fun statusJson(): String {
        val device = (Build.MANUFACTURER + " " + Build.MODEL).replace(Regex("[^A-Za-z0-9 ._-]"), "")
        return """{"app":"ascent-camera","protocol":$PROTOCOL,"version":"${BuildConfigInfo.VERSION}","device":"$device"}"""
    }

    private fun codeMatches(sent: String?): Boolean {
        if (sent == null) return false
        return MessageDigest.isEqual(sent.toByteArray(), pairingCode().toByteArray())
    }

    private fun respond(connection: Socket, status: Int, type: String, body: ByteArray, extra: Map<String, String> = emptyMap()) {
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(status).append(' ').append(REASONS[status] ?: "Error").append("\r\n")
            .append("Content-Type: ").append(type).append("\r\n")
            .append("Content-Length: ").append(body.size).append("\r\n")
            .append("Connection: close\r\n")
        extra.forEach { (name, value) -> head.append(name).append(": ").append(value).append("\r\n") }
        head.append("\r\n")
        val out = connection.getOutputStream()
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    /** One line without its line ending; null at the end of the stream or when the line is absurdly long. */
    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
            if (bytes.size() > MAX_LINE) return null
        }
        return bytes.toString(Charsets.US_ASCII.name())
    }
}

/** The app version for /status (kept here so the server needs no generated BuildConfig class). */
object BuildConfigInfo {
    const val VERSION = "0.1.0"
}
