package com.projectfuture.browser.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocketFactory

private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

/**
 * A from-scratch RFC 6455 WebSocket client on a raw socket - no
 * OkHttp/java.net WebSocket support used, same boundary as the rest of
 * this project's networking. Handles the HTTP Upgrade handshake
 * (including verifying `Sec-WebSocket-Accept`), text/binary data frames,
 * ping/pong, and a close handshake.
 *
 * Not implemented: message *fragmentation* - every message this client
 * sends or expects to receive must fit in a single WebSocket frame (real
 * but bounded: most real-world messages are small enough that a
 * compliant server sends them unfragmented anyway), and
 * `permessage-deflate` compression. Binary frames are surfaced through
 * the same text callback as UTF-8 (real binary `ArrayBuffer`/`Blob`
 * delivery isn't modeled - this engine's JS bridge has no typed-array
 * support to hand one to a script anyway).
 */
class WebSocketClient(private val url: Url) {
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false

    var onOpen: (() -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null
    var onClose: ((Int, String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Runs the handshake and the whole read loop on its own thread - callers get results via the on*/
    fun connect() {
        Thread {
            try {
                val sock: Socket = if (url.scheme == "wss") {
                    (SSLSocketFactory.getDefault().createSocket() as Socket).also { it.connect(InetSocketAddress(url.host, url.port), 15000) }
                } else {
                    Socket().also { it.connect(InetSocketAddress(url.host, url.port), 15000) }
                }
                socket = sock

                val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val key = Base64.getEncoder().encodeToString(keyBytes)
                val request = buildString {
                    append("GET ${url.path} HTTP/1.1\r\n")
                    append("Host: ${url.host}\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("\r\n")
                }
                sock.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                sock.getOutputStream().flush()

                val input = sock.getInputStream()
                val statusLine = readLine(input) ?: throw IOException("Empty handshake response")
                if (!statusLine.contains(" 101 ")) throw IOException("Unexpected handshake response: $statusLine")
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx == -1) continue
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                val expectedAccept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.US_ASCII))
                )
                if (headers["sec-websocket-accept"] != expectedAccept) throw IOException("Sec-WebSocket-Accept mismatch")

                onOpen?.invoke()
                readFrames(sock, input)
            } catch (e: Exception) {
                if (!closed) onError?.invoke(e.message ?: e.toString())
            }
        }.apply { isDaemon = true }.start()
    }

    fun send(message: String) {
        val sock = socket ?: return
        try {
            writeFrame(sock, 0x1, message.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            onError?.invoke(e.message ?: e.toString())
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try { socket?.let { writeFrame(it, 0x8, ByteArray(0)) } } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun readFrames(sock: Socket, input: InputStream) {
        while (!closed) {
            val first = input.read()
            if (first == -1) {
                handleClose(1006, "Connection closed abnormally")
                return
            }
            val opcode = first and 0x0F
            val second = input.read()
            if (second == -1) {
                handleClose(1006, "Connection closed abnormally")
                return
            }
            val masked = (second and 0x80) != 0
            var payloadLen = (second and 0x7F).toLong()
            if (payloadLen == 126L) {
                payloadLen = ((input.read() shl 8) or input.read()).toLong()
            } else if (payloadLen == 127L) {
                var len = 0L
                repeat(8) { len = (len shl 8) or input.read().toLong() }
                payloadLen = len
            }
            val maskKey = if (masked) ByteArray(4).also { readFully(input, it) } else null
            val payload = ByteArray(payloadLen.toInt())
            readFully(input, payload)
            if (maskKey != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
            when (opcode) {
                0x1, 0x2 -> onMessage?.invoke(String(payload, Charsets.UTF_8)) // text, and binary-as-text - see class doc
                0x8 -> {
                    // RFC 6455 5.5.1: a close frame's payload, when present, is a 2-byte big-endian
                    // status code followed by an optional UTF-8 reason - not always 1000 (Normal
                    // Closure). A server closing for e.g. "going away" (1001) or a policy violation
                    // (1008) needs that real code delivered, not a hardcoded "1000" that hides it.
                    val code = if (payload.size >= 2) {
                        ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                    } else {
                        1005 // "No Status Rcvd" - the close frame carried no status code at all.
                    }
                    val reason = if (payload.size > 2) String(payload, 2, payload.size - 2, Charsets.UTF_8) else ""
                    handleClose(code, reason)
                    return
                }
                0x9 -> writeFrame(sock, 0xA, payload) // ping -> pong
                0xA -> {} // pong: nothing to do
                0x0 -> {} // continuation frame - fragmentation isn't implemented (see class doc)
                else -> {
                    // RFC 6455 7.1.7 / 5.2: opcodes 0x3-0x7 and 0xB-0xF are reserved for future
                    // non-control and control frames. A frame using one of them is something this
                    // (or any RFC 6455) client cannot interpret, so per spec the connection must be
                    // failed rather than silently dropping the frame and trying to parse whatever
                    // bytes follow as the next frame header.
                    try { writeFrame(sock, 0x8, byteArrayOf(0x03, 0xEA.toByte())) } catch (_: Exception) {}
                    handleClose(1002, "Unsupported opcode: $opcode")
                    return
                }
            }
        }
    }

    private fun writeFrame(sock: Socket, opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode) // FIN + opcode, no fragmentation ever sent
        val maskBit = 0x80 // every client-to-server frame must be masked per spec
        when {
            payload.size < 126 -> header.write(maskBit or payload.size)
            payload.size <= 0xFFFF -> {
                header.write(maskBit or 126)
                header.write((payload.size shr 8) and 0xFF)
                header.write(payload.size and 0xFF)
            }
            else -> {
                header.write(maskBit or 127)
                repeat(8) { i -> header.write((payload.size.toLong() shr ((7 - i) * 8)).toInt() and 0xFF) }
            }
        }
        val maskKey = ByteArray(4).also { SecureRandom().nextBytes(it) }
        header.write(maskKey)
        val maskedPayload = ByteArray(payload.size)
        for (i in payload.indices) maskedPayload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        val out = sock.getOutputStream()
        synchronized(sock) {
            out.write(header.toByteArray())
            out.write(maskedPayload)
            out.flush()
        }
    }

    private fun handleClose(code: Int, reason: String) {
        if (closed) return
        closed = true
        try { socket?.close() } catch (_: Exception) {}
        onClose?.invoke(code, reason)
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n == -1) throw IOException("Unexpected end of stream")
            read += n
        }
    }

    private fun readLine(input: InputStream): String? = readCrlfLine(input)
}
