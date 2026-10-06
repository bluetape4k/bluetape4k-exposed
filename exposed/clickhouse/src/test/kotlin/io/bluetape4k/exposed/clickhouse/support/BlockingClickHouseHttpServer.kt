package io.bluetape4k.exposed.clickhouse.support

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** V2 JDBC reader가 첫 행 이후의 socket read에서 멈추는 상황을 재현하는 로컬 HTTP fixture입니다. */
internal class BlockingClickHouseHttpServer: AutoCloseable {

    private val server = ServerSocket(0)
    private val connections = CopyOnWriteArrayList<Socket>()
    private val executor = Executors.newCachedThreadPool()
    private val requests = CopyOnWriteArrayList<String>()
    private val queryRequests = AtomicInteger()
    private val acceptor = thread(start = true, name = "clickhouse-blocking-fixture") { acceptRequests() }

    val port: Int get() = server.localPort

    val queryRequestCount: Int get() = queryRequests.get()

    val requestLines: List<String>
        get() = requests.map { it.lineSequence().firstOrNull().orEmpty() }

    private fun acceptRequests() {
        try {
            while (!server.isClosed) {
                val socket = server.accept()
                connections += socket
                executor.execute { handle(socket) }
            }
        } catch (_: IOException) {
            if (!server.isClosed) throw IllegalStateException("blocking ClickHouse fixture stopped unexpectedly")
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { client ->
                val request = readRequest(client.getInputStream())
                requests += request
                val isQueryRequest = request.lineSequence()
                    .firstOrNull()
                    .orEmpty()
                    .contains("query_id=", ignoreCase = true)
                val queryRequest = if (isQueryRequest) queryRequests.incrementAndGet() else 0
                writeResponse(client, complete = queryRequest != 1)
                if (queryRequest == 1) awaitClientClose(client)
            }
        } finally {
            connections.remove(socket)
        }
    }

    private fun awaitClientClose(client: Socket) {
        client.soTimeout = 3_000
        try {
            while (client.getInputStream().read() >= 0) {
                // The client closes the response stream after the bounded read returns.
            }
        } catch (_: SocketTimeoutException) {
            // The test will fail if the driver does not close the blocked response in time.
        }
    }

    private fun readRequest(input: InputStream): String {
        val header = readHeader(input)
        val contentLength = header.contentLength()
        val body = when {
            header.isChunked() -> readChunkedBody(input)
            contentLength > 0 -> readFixedBody(input, contentLength)
            else -> ""
        }
        return header + body
    }

    private fun readHeader(input: InputStream): String {
        val header = ByteArrayOutputStream()
        var previous = -1
        var complete = false
        while (!complete) {
            val current = input.read()
            if (current < 0) {
                complete = true
            } else {
                header.write(current)
                complete = previous == '\r'.code && current == '\n'.code && header.endsWithHeaderTerminator()
                previous = current
            }
        }
        return header.toString(StandardCharsets.ISO_8859_1)
    }

    private fun String.contentLength(): Int = lineSequence()
        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.toIntOrNull()
        ?: 0

    private fun String.isChunked(): Boolean = lineSequence()
        .firstOrNull { it.startsWith("Transfer-Encoding:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.contains("chunked", ignoreCase = true) == true

    private fun readFixedBody(input: InputStream, contentLength: Int): String {
        val body = ByteArray(contentLength)
        input.readFully(body)
        return String(body, StandardCharsets.UTF_8)
    }

    private fun readChunkedBody(input: InputStream): String {
        val body = ByteArrayOutputStream()
        var complete = false
        while (!complete) {
            val sizeLine = input.readHttpLine()
            val chunkSize = sizeLine.substringBefore(';').trim().toInt(16)
            if (chunkSize == 0) {
                input.readHttpLine()
                complete = true
            } else {
                val chunk = ByteArray(chunkSize)
                input.readFully(chunk)
                input.readFully(ByteArray(2))
                body.write(chunk)
            }
        }
        return body.toString(StandardCharsets.UTF_8)
    }

    private fun writeResponse(socket: Socket, complete: Boolean) {
        val body = responseBody()
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("X-ClickHouse-Format: RowBinaryWithNamesAndTypes\r\n")
            if (complete) append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        socket.getOutputStream().apply {
            write(headers)
            write(body)
            flush()
        }
    }

    private fun responseBody(): ByteArray {
        val schema = byteArrayOf(1, 6) + "number".toByteArray() + byteArrayOf(6) + "UInt64".toByteArray()
        val firstRow = ByteArray(Long.SIZE_BYTES)
        return schema + firstRow
    }

    override fun close() {
        runCatching { server.close() }
        connections.forEach { runCatching { it.close() } }
        executor.shutdownNow()
        acceptor.join(1_000)
    }

    private fun ByteArrayOutputStream.endsWithHeaderTerminator(): Boolean {
        val bytes = toByteArray()
        return bytes.size >= 4 &&
                bytes[bytes.size - 4] == '\r'.code.toByte() &&
                bytes[bytes.size - 3] == '\n'.code.toByte() &&
                bytes[bytes.size - 2] == '\r'.code.toByte() &&
                bytes[bytes.size - 1] == '\n'.code.toByte()
    }

    private fun InputStream.readHttpLine(): String {
        val line = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val current = read()
            check(current >= 0) { "HTTP fixture received an incomplete line" }
            if (previous == '\r'.code && current == '\n'.code) {
                val bytes = line.toByteArray()
                return String(bytes, 0, bytes.size - 1, StandardCharsets.ISO_8859_1)
            }
            line.write(current)
            previous = current
        }
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            check(read >= 0) { "HTTP fixture received an incomplete body" }
            offset += read
        }
    }
}
