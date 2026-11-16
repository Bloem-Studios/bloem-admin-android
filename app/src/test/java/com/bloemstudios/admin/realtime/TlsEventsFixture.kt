package com.bloemstudios.admin.realtime

import io.ktor.client.engine.cio.CIO
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** Independent raw RFC6455 fixture: real TLS, HTTP upgrade, masking and close frames. */
internal class TlsEventsFixture : Closeable {
    private val listener = context.serverSocketFactory.createServerSocket(0, 20, InetAddress.getLoopbackAddress()) as SSLServerSocket
    val origin = "https://localhost:${listener.localPort}"
    private val sockets = CopyOnWriteArrayList<Socket>()
    val peerFailures = CopyOnWriteArrayList<String>()
    val requests = LinkedBlockingQueue<Request>()
    val connections = LinkedBlockingQueue<Connection>()
    @Volatile var ticketStatus = 200
    @Volatile var upgradeStatus = 101
    @Volatile var selectedProtocol: String? = "silo.events.v2"
    @Volatile var redirectLocation: String? = null
    @Volatile var ticketProtocol = "silo.events.v2"
    @Volatile var invalidTicket = false
    @Volatile var pauseTicket: CountDownLatch? = null
    @Volatile var pauseUpgrade: CountDownLatch? = null
    @Volatile var onTicket: (() -> Unit)? = null
    @Volatile var answerPings = true
    /** Replaces the ticket answer's body (given the minted ticket) for another protocol's ticket route. */
    @Volatile var ticketJson: ((String) -> String)? = null
    private var sequence = 0
    val minted = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true, name = "events-tls-accept") {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                sockets += socket
                thread(isDaemon = true, name = "events-tls-peer") {
                    try { handle(socket) } catch (failure: Exception) {
                        peerFailures += generateSequence(failure as Throwable?) { it.cause }.joinToString(" -> ") { it.javaClass.name }
                        socket.close()
                    }
                }
            }
        }
    }

    fun engine() = CIO.create { https { trustManager = fixtureTrust } }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val header = readHeaders(input)
        val lines = header.split("\r\n")
        val request = Request(lines.first(), lines.drop(1).filter { ':' in it }.associate {
            it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
        })
        requests.put(request)
        if (request.line.startsWith("POST ")) {
            onTicket?.invoke()
            pauseTicket?.await(5, TimeUnit.SECONDS)
            val ticket = synchronized(this) { (++sequence).toString().padStart(43, 'x') }
            minted += ticket
            val body = if (ticketStatus == 200 && ticketJson != null) ticketJson!!.invoke(ticket) else if (ticketStatus == 200) """{"ticket":"${if (invalidTicket) "invalid" else ticket}","protocol":"$ticketProtocol","expires_in":30,"max_connection_seconds":300}""" else """{"message":"private diagnostic"}"""
            response(socket, ticketStatus, body)
            socket.close()
            return
        }
        pauseUpgrade?.await(5, TimeUnit.SECONDS)
        if (upgradeStatus != 101) {
            response(socket, upgradeStatus, "refused")
            socket.close()
            return
        }
        val key = request.headers.getValue("sec-websocket-key")
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        val protocolHeader = selectedProtocol?.let { "Sec-WebSocket-Protocol: $it\r\n" }.orEmpty()
        socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n" + protocolHeader + "\r\n").toByteArray())
        socket.getOutputStream().flush()
        val connection = Connection(socket)
        connections.put(connection)
        try {
            while (true) {
                val first = input.read()
                if (first < 0) break
                val second = input.read()
                var size = (second and 127).toLong()
                if (size == 126L) size = ((input.read() shl 8) or input.read()).toLong()
                if (size == 127L) { size = 0; repeat(8) { size = (size shl 8) or input.read().toLong() } }
                require(size <= 2_000_000)
                val mask = if (second and 128 != 0) readBytes(input, 4) else ByteArray(0)
                val data = readBytes(input, size.toInt())
                if (mask.isNotEmpty()) data.indices.forEach { data[it] = (data[it].toInt() xor mask[it % 4].toInt()).toByte() }
                when (first and 15) {
                    1 -> connection.messages.put(data.toString(Charsets.UTF_8))
                    8 -> { connection.frame(8, data); break }
                    9 -> if (answerPings) connection.frame(10, data)
                    10 -> connection.pong.countDown()
                }
            }
        } finally { connection.closed.countDown(); socket.close() }
    }

    private fun response(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray()
        val location = redirectLocation?.let { "Location: $it\r\n" }.orEmpty()
        socket.getOutputStream().write("HTTP/1.1 $status Fixture\r\n${location}Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
        socket.getOutputStream().flush()
    }

    override fun close() {
        pauseTicket?.countDown(); pauseUpgrade?.countDown()
        listener.close()
        sockets.forEach { runCatching { it.close() } }
    }

    data class Request(val line: String, val headers: Map<String, String>)
    class Connection(private val socket: Socket) {
        val messages = LinkedBlockingQueue<String>()
        val closed = CountDownLatch(1)
        val pong = CountDownLatch(1)
        fun text(text: String) = frame(1, text.toByteArray())
        fun close(code: Int) = frame(8, byteArrayOf((code shr 8).toByte(), code.toByte()))
        fun ping() = frame(9, byteArrayOf(1))
        @Synchronized fun frame(opcode: Int, bytes: ByteArray, final: Boolean = true) {
            val output = socket.getOutputStream()
            output.write((if (final) 128 else 0) or opcode)
            when {
                bytes.size < 126 -> output.write(bytes.size)
                bytes.size <= 65535 -> { output.write(126); output.write(bytes.size shr 8); output.write(bytes.size and 255) }
                else -> { output.write(127); repeat(8) { output.write(((bytes.size.toLong() shr ((7 - it) * 8)) and 255).toInt()) } }
            }
            output.write(bytes); output.flush()
        }
    }

    companion object {
        private val keyStore: KeyStore by lazy {
            val dir = Files.createTempDirectory("events-tls-")
            val file = dir.resolve("fixture.p12")
            val process = ProcessBuilder("${System.getProperty("java.home")}/bin/keytool", "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", "fixture-only", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-noprompt").redirectErrorStream(true).start()
            process.inputStream.readBytes()
            check(process.waitFor() == 0) { "Fixture certificate generation failed" }
            val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, "fixture-only".toCharArray()) } }
            Files.delete(file); Files.delete(dir)
            store
        }
        private val fixtureTrust: X509TrustManager by lazy {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }.trustManagers.filterIsInstance<X509TrustManager>().single()
        }
        private val context: SSLContext by lazy {
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, "fixture-only".toCharArray()) }
            // Robolectric installs Conscrypt globally. Its server sockets reflect into JDK internals
            // on Java 21; keep this independent JVM fixture on the JDK server implementation.
            SSLContext.getInstance("TLS", "SunJSSE").apply { init(managers.keyManagers, null, null) }
        }
        private fun readHeaders(input: InputStream): String {
            val bytes = ArrayList<Byte>()
            while (bytes.size < 16384) {
                val value = input.read(); check(value >= 0); bytes += value.toByte()
                if (bytes.size >= 4 && bytes.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) return bytes.toByteArray().toString(Charsets.ISO_8859_1)
            }
            error("Fixture header too large")
        }
        private fun readBytes(input: InputStream, size: Int): ByteArray {
            val bytes = ByteArray(size); var offset = 0
            while (offset < size) { val count = input.read(bytes, offset, size - offset); check(count > 0); offset += count }
            return bytes
        }
    }
}
