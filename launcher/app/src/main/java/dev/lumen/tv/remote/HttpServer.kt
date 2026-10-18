package dev.lumen.tv.remote

import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class Request(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Header names lower-cased. */
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun param(name: String): String? = query[name]
    val text: String get() = body.toString(Charsets.UTF_8)
}

class Response(
    val status: Int = 200,
    val contentType: String = "text/plain; charset=utf-8",
    val body: ByteArray = ByteArray(0),
    val cacheSeconds: Int = 0,
) {
    companion object {
        fun text(text: String, status: Int = 200) = Response(status, body = text.toByteArray())
        fun json(json: String) = Response(contentType = "application/json; charset=utf-8", body = json.toByteArray())
        val notFound get() = text("Not found", 404)
    }
}

/**
 * Minimal HTTP/1.1 server for the phone remote, local network only: one request per connection,
 * plus a WebSocket at /ws that stays open so each tap is a few bytes on a live connection rather
 * than a new TCP handshake. Small on purpose, so there's no web framework inside the launcher.
 */
class HttpServer(
    private val port: Int,
    private val handler: (Request) -> Response,
    private val onSocketMessage: (String) -> Unit,
) {

    private var server: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    fun start() {
        val socket = ServerSocket(port).also { server = it }
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                pool.execute { serve(client) }
            }
        }, "lumen-http").start()
        Log.i("Lumen", "Remote server listening on :$port")
    }

    fun stop() {
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    private fun serve(client: Socket) = client.use {
        runCatching {
            client.soTimeout = 10_000
            client.tcpNoDelay = true // tiny packets: don't let Nagle hold them back
            if (!client.inetAddress.isLocal()) {
                write(client.getOutputStream(), Response.text("Forbidden", 403))
                return@runCatching
            }
            val input = BufferedInputStream(client.getInputStream())
            val request = parse(input) ?: return@runCatching
            if (request.headers["upgrade"].equals("websocket", ignoreCase = true)) {
                webSocket(client, input, request)
                return@runCatching
            }
            val response = runCatching { handler(request) }
                .getOrElse { Log.w("Lumen", "Remote handler failed: ${request.path}", it); Response.text("Error", 500) }
            write(client.getOutputStream(), response)
        }.onFailure { Log.w("Lumen", "Remote connection failed", it) }
    }

    private fun parse(input: InputStream): Request? {
        val requestLine = input.readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length.coerceIn(0, 64 * 1024))
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Request(parts[0], path, query, headers, body)
    }

    /** RFC 6455, text frames only: enough for "k:up"-style messages from the phone. */
    private fun webSocket(client: Socket, input: InputStream, request: Request) {
        val key = request.headers["sec-websocket-key"] ?: return
        val accept = android.util.Base64.encodeToString(
            java.security.MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            android.util.Base64.NO_WRAP,
        )
        val output = client.getOutputStream()
        output.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
        )
        output.flush()
        client.soTimeout = 60_000 // the phone sends a heartbeat every 20 s

        while (true) {
            val b0 = input.read()
            val b1 = input.read()
            if (b0 < 0 || b1 < 0) return
            val opcode = b0 and 0x0F
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
            else if (length == 127L) length = (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or input.read().toLong() }
            if (length > 64 * 1024) return
            val mask = if (b1 and 0x80 != 0) ByteArray(4) { input.read().toByte() } else null
            val payload = ByteArray(length.toInt())
            var read = 0
            while (read < payload.size) {
                val n = input.read(payload, read, payload.size - read)
                if (n < 0) return
                read += n
            }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                0x1 -> onSocketMessage(payload.toString(Charsets.UTF_8))
                0x8 -> { output.write(byteArrayOf(0x88.toByte(), 0)); output.flush(); return }
                0x9 -> { output.write(byteArrayOf(0x8A.toByte(), payload.size.toByte()) + payload); output.flush() }
            }
        }
    }

    private fun write(output: OutputStream, response: Response) {
        val reason = when (response.status) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        val cache = if (response.cacheSeconds > 0) "public, max-age=${response.cacheSeconds}" else "no-store"
        val head = "HTTP/1.1 ${response.status} $reason\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            "Content-Length: ${response.body.size}\r\n" +
            "Cache-Control: $cache\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray())
        output.write(response.body)
        output.flush()
    }

    private fun InputStream.readLine(): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return bytes.toString(Charsets.UTF_8.name()).trimEnd('\r')
            bytes.write(b)
        }
    }

    /** Only answer devices on the home network (private IPv4 ranges or loopback). */
    private fun InetAddress.isLocal(): Boolean =
        isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress ||
            (this !is Inet4Address && hostAddress?.startsWith("fe80") == true)
}
