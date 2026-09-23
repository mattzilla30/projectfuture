package com.projectfuture.browser.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebSocketClient does real socket I/O with no Android dependency, so
 * (like Url.kt's ConnectionPool tests) it's exercised against a genuine
 * hand-rolled local WebSocket server rather than only ever being checked
 * by "it compiles" - this is exactly the kind of framing/masking logic
 * where a real round-trip test is worth it.
 */
class WebSocketClientTest {

    private fun acceptKeyFor(clientKey: String): String {
        val guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((clientKey + guid).toByteArray(Charsets.US_ASCII)))
    }

    private fun writeServerFrame(out: java.io.OutputStream, opcode: Int, payload: ByteArray) {
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(payload.size)
            else -> {
                out.write(126)
                out.write((payload.size shr 8) and 0xFF)
                out.write(payload.size and 0xFF)
            }
        }
        out.write(payload) // server frames are unmasked per spec
        out.flush()
    }

    @Test fun handshakeSendReceiveAndClose() {
        val server = ServerSocket(0)
        val receivedFromClient = java.util.concurrent.atomic.AtomicReference<String>()
        val serverDone = CountDownLatch(1)

        Thread {
            try {
                val socket = server.accept()
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                var clientKey: String? = null
                var line = input.readLine()
                while (line != null && line.isNotEmpty()) {
                    if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                        clientKey = line.substringAfter(":").trim()
                    }
                    line = input.readLine()
                }
                val accept = acceptKeyFor(clientKey!!)
                val response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
                socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()

                // Read one masked client text frame.
                val rawInput = socket.getInputStream()
                val first = rawInput.read()
                val opcode = first and 0x0F
                val second = rawInput.read()
                val masked = (second and 0x80) != 0
                var len = (second and 0x7F).toLong()
                val maskKey = ByteArray(4)
                if (masked) {
                    var read = 0
                    while (read < 4) read += rawInput.read(maskKey, read, 4 - read)
                }
                val payload = ByteArray(len.toInt())
                var read = 0
                while (read < payload.size) read += rawInput.read(payload, read, payload.size - read)
                if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                if (opcode == 0x1) receivedFromClient.set(String(payload, Charsets.UTF_8))

                // Reply with a text frame, then a close frame.
                writeServerFrame(socket.getOutputStream(), 0x1, "world".toByteArray(Charsets.UTF_8))
                writeServerFrame(socket.getOutputStream(), 0x8, ByteArray(0))
                socket.close()
            } catch (_: Exception) {
            } finally {
                serverDone.countDown()
            }
        }.apply { isDaemon = true }.start()

        val port = server.localPort
        val client = WebSocketClient(Url.parse("ws://127.0.0.1:$port/socket"))
        val openLatch = CountDownLatch(1)
        val messageLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)
        val receivedByClient = java.util.concurrent.atomic.AtomicReference<String>()
        var closeCode = -1

        client.onOpen = { openLatch.countDown() }
        client.onMessage = { msg -> receivedByClient.set(msg); messageLatch.countDown() }
        client.onClose = { code, _ -> closeCode = code; closeLatch.countDown() }
        client.connect()

        assertTrue("handshake should complete", openLatch.await(5, TimeUnit.SECONDS))
        client.send("hello")
        assertTrue("client should receive the server's message", messageLatch.await(5, TimeUnit.SECONDS))
        assertEquals("world", receivedByClient.get())
        assertTrue("close should be delivered", closeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(1000, closeCode)

        assertTrue(serverDone.await(5, TimeUnit.SECONDS))
        assertEquals("hello", receivedFromClient.get())
        server.close()
    }
}
