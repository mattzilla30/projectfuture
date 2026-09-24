package com.projectfuture.browser.net.http2

import java.io.ByteArrayOutputStream

/** A single header field, lower-cased name per HTTP/2's requirement (RFC 7540 8.1.2). */
data class HpackHeader(val name: String, val value: String) {
    val size: Int get() = name.length + value.length + 32 // RFC 7541 4.1's fixed per-entry overhead.
}

/**
 * HPACK (RFC 7541): the header-compression scheme HTTP/2 uses instead of plain-text headers.
 * This is a real implementation of both halves the RFC actually specifies - integer/string
 * primitive coding, the 61-entry static table, a bounded FIFO dynamic table shared across all
 * streams on one connection, and Huffman-coded string literals (see [Huffman]) - not a wrapper
 * that skips compression and just writes header text.
 */
object HpackStaticTable {
    val entries: List<HpackHeader> = listOf(
        HpackHeader(":authority", ""),
        HpackHeader(":method", "GET"),
        HpackHeader(":method", "POST"),
        HpackHeader(":path", "/"),
        HpackHeader(":path", "/index.html"),
        HpackHeader(":scheme", "http"),
        HpackHeader(":scheme", "https"),
        HpackHeader(":status", "200"),
        HpackHeader(":status", "204"),
        HpackHeader(":status", "206"),
        HpackHeader(":status", "304"),
        HpackHeader(":status", "400"),
        HpackHeader(":status", "404"),
        HpackHeader(":status", "500"),
        HpackHeader("accept-charset", ""),
        HpackHeader("accept-encoding", "gzip, deflate"),
        HpackHeader("accept-language", ""),
        HpackHeader("accept-ranges", ""),
        HpackHeader("accept", ""),
        HpackHeader("access-control-allow-origin", ""),
        HpackHeader("age", ""),
        HpackHeader("allow", ""),
        HpackHeader("authorization", ""),
        HpackHeader("cache-control", ""),
        HpackHeader("content-disposition", ""),
        HpackHeader("content-encoding", ""),
        HpackHeader("content-language", ""),
        HpackHeader("content-length", ""),
        HpackHeader("content-location", ""),
        HpackHeader("content-range", ""),
        HpackHeader("content-type", ""),
        HpackHeader("cookie", ""),
        HpackHeader("date", ""),
        HpackHeader("etag", ""),
        HpackHeader("expect", ""),
        HpackHeader("expires", ""),
        HpackHeader("from", ""),
        HpackHeader("host", ""),
        HpackHeader("if-match", ""),
        HpackHeader("if-modified-since", ""),
        HpackHeader("if-none-match", ""),
        HpackHeader("if-range", ""),
        HpackHeader("if-unmodified-since", ""),
        HpackHeader("last-modified", ""),
        HpackHeader("link", ""),
        HpackHeader("location", ""),
        HpackHeader("max-forwards", ""),
        HpackHeader("proxy-authenticate", ""),
        HpackHeader("proxy-authorization", ""),
        HpackHeader("range", ""),
        HpackHeader("referer", ""),
        HpackHeader("refresh", ""),
        HpackHeader("retry-after", ""),
        HpackHeader("server", ""),
        HpackHeader("set-cookie", ""),
        HpackHeader("strict-transport-security", ""),
        HpackHeader("transfer-encoding", ""),
        HpackHeader("user-agent", ""),
        HpackHeader("vary", ""),
        HpackHeader("via", ""),
        HpackHeader("www-authenticate", "")
    )

    /** Index by exact (name, value); used by the encoder to emit a fully-indexed reference. */
    fun indexOfExact(header: HpackHeader): Int = entries.indexOf(header).let { if (it == -1) -1 else it + 1 }

    /** Index of the first static entry with this name (any value); used for a name-only reference. */
    fun indexOfName(name: String): Int = entries.indexOfFirst { it.name == name }.let { if (it == -1) -1 else it + 1 }
}

/**
 * The dynamic table (RFC 7541 2.3.2/4): a bounded, per-connection FIFO of recently-sent/received
 * headers, most-recently-added first, evicted from the tail once [maxSize] is exceeded. One
 * instance is used for encoding (client's view of what it has told the server) and a separate one
 * for decoding (server's HEADERS responses) - encoder and decoder tables always evolve in lockstep
 * per RFC 7541 as long as both sides only remove entries via legitimate eviction, which is all
 * this implementation ever does.
 */
class HpackDynamicTable(private var maxSize: Int = 4096) {
    private val entries = ArrayDeque<HpackHeader>()
    private var size = 0

    fun setMaxSize(newMax: Int) {
        maxSize = newMax
        evictToFit()
    }

    fun add(header: HpackHeader) {
        entries.addFirst(header)
        size += header.size
        evictToFit()
    }

    private fun evictToFit() {
        while (size > maxSize && entries.isNotEmpty()) {
            size -= entries.removeLast().size
        }
    }

    /** 0-based index into just this table (caller adds [HpackStaticTable.entries.size] for the combined HPACK index space). */
    operator fun get(index: Int): HpackHeader? = entries.getOrNull(index)

    fun indexOfExact(header: HpackHeader): Int = entries.indexOf(header)
    fun indexOfName(name: String): Int = entries.indexOfFirst { it.name == name }

    val entryCount: Int get() = entries.size
}

/** Encodes a header list into an HPACK block (used for outgoing request HEADERS frames). */
class HpackEncoder(private val dynamicTable: HpackDynamicTable = HpackDynamicTable()) {
    private val out = ByteArrayOutputStream()

    fun encode(headers: List<HpackHeader>): ByteArray {
        out.reset()
        for (header in headers) encodeHeader(header)
        return out.toByteArray()
    }

    private fun encodeHeader(header: HpackHeader) {
        val staticExact = HpackStaticTable.indexOfExact(header)
        if (staticExact != -1) {
            writeInt(staticExact, 7, 0x80) // Indexed Header Field (6.1)
            return
        }
        val dynamicExact = dynamicTable.indexOfExact(header)
        if (dynamicExact != -1) {
            writeInt(HpackStaticTable.entries.size + dynamicExact + 1, 7, 0x80)
            return
        }
        // Not fully indexed: literal with incremental indexing (6.2.1), referencing a name-only
        // index when one exists so at least the header name is compressed.
        val staticName = HpackStaticTable.indexOfName(header.name)
        val dynamicNameIndex = if (staticName == -1) dynamicTable.indexOfName(header.name) else -1
        when {
            staticName != -1 -> writeInt(staticName, 6, 0x40)
            dynamicNameIndex != -1 -> writeInt(HpackStaticTable.entries.size + dynamicNameIndex + 1, 6, 0x40)
            else -> {
                out.write(0x40)
                writeString(header.name)
            }
        }
        writeString(header.value)
        dynamicTable.add(header)
    }

    private fun writeString(value: String) {
        val raw = value.toByteArray(Charsets.ISO_8859_1)
        val huffman = Huffman.encode(raw)
        // Only use the Huffman form when it's actually smaller - RFC 7541 doesn't require it, and
        // a server is required to accept either, so this is a pure size optimization.
        if (huffman.size < raw.size) {
            writeInt(huffman.size, 7, 0x80)
            out.write(huffman)
        } else {
            writeInt(raw.size, 7, 0x00)
            out.write(raw)
        }
    }

    /** RFC 7541 5.1: an N-bit prefix integer, [prefixBits] wide, with [flagBits] already set in the top bits of the first byte. */
    private fun writeInt(value: Int, prefixBits: Int, flagBits: Int) {
        val max = (1 shl prefixBits) - 1
        if (value < max) {
            out.write(flagBits or value)
            return
        }
        out.write(flagBits or max)
        var remaining = value - max
        while (remaining >= 128) {
            out.write((remaining and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
        out.write(remaining)
    }
}

/** Decodes an HPACK block (used for incoming HEADERS/CONTINUATION frames). */
class HpackDecoder(private val dynamicTable: HpackDynamicTable = HpackDynamicTable()) {
    fun decode(block: ByteArray): List<HpackHeader> {
        val headers = ArrayList<HpackHeader>()
        var pos = 0
        fun readInt(prefixBits: Int, firstByte: Int): Int {
            val max = (1 shl prefixBits) - 1
            var value = firstByte and max
            if (value < max) return value
            var shift = 0
            while (true) {
                val b = block[pos++].toInt() and 0xff
                value += (b and 0x7f) shl shift
                if (b and 0x80 == 0) break
                shift += 7
            }
            return value
        }
        fun readString(): String {
            val first = block[pos++].toInt() and 0xff
            val huffman = (first and 0x80) != 0
            val length = readInt(7, first)
            val raw = block.copyOfRange(pos, pos + length)
            pos += length
            val bytes = if (huffman) Huffman.decode(raw) else raw
            return String(bytes, Charsets.ISO_8859_1)
        }
        fun resolveIndexed(index: Int): HpackHeader {
            val staticCount = HpackStaticTable.entries.size
            if (index in 1..staticCount) return HpackStaticTable.entries[index - 1]
            return dynamicTable[index - staticCount - 1]
                ?: throw IllegalArgumentException("Invalid HPACK index $index")
        }

        while (pos < block.size) {
            val first = block[pos++].toInt() and 0xff
            when {
                first and 0x80 != 0 -> {
                    // Indexed Header Field (6.1): entirely a table reference, no literal data follows.
                    val index = readInt(7, first)
                    headers.add(resolveIndexed(index))
                }
                first and 0x40 != 0 -> {
                    // Literal Header Field with Incremental Indexing (6.2.1): added to the dynamic table.
                    val index = readInt(6, first)
                    val name = if (index == 0) readString() else resolveIndexed(index).name
                    val value = readString()
                    val header = HpackHeader(name, value)
                    headers.add(header)
                    dynamicTable.add(header)
                }
                first and 0x20 != 0 -> {
                    // Dynamic Table Size Update (6.3).
                    val newSize = readInt(5, first)
                    dynamicTable.setMaxSize(newSize)
                }
                else -> {
                    // Literal Header Field without/never Indexing (6.2.2/6.2.3): 0x10 (never-indexed,
                    // a sensitivity marker only - proxies must not re-compress it) and 0x00 share this
                    // 4-bit-prefix shape and only differ in that marker, which this client has no
                    // reason to act on differently since it never re-encodes/forwards headers.
                    val index = readInt(4, first)
                    val name = if (index == 0) readString() else resolveIndexed(index).name
                    val value = readString()
                    headers.add(HpackHeader(name, value))
                }
            }
        }
        return headers
    }
}
