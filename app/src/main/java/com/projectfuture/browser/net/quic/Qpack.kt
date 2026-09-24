package com.projectfuture.browser.net.quic

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * The QPACK static table, RFC 9204 Appendix A - 99 fixed (name, value) entries every QPACK
 * implementation agrees on without any dynamic-table negotiation. Index 0 is `:authority: ""`,
 * matching the RFC's table exactly (it descends from HPACK's static table but is renumbered and
 * extended).
 */
object QpackStaticTable {
    val entries: List<Pair<String, String>> = listOf(
        ":authority" to "",
        ":path" to "/",
        "age" to "0",
        "content-disposition" to "",
        "content-length" to "0",
        "cookie" to "",
        "date" to "",
        "etag" to "",
        "if-modified-since" to "",
        "if-none-match" to "",
        "last-modified" to "",
        "link" to "",
        "location" to "",
        "referer" to "",
        "set-cookie" to "",
        ":method" to "CONNECT",
        ":method" to "DELETE",
        ":method" to "GET",
        ":method" to "HEAD",
        ":method" to "OPTIONS",
        ":method" to "POST",
        ":method" to "PUT",
        ":scheme" to "http",
        ":scheme" to "https",
        ":status" to "103",
        ":status" to "200",
        ":status" to "304",
        ":status" to "404",
        ":status" to "503",
        "accept" to "*/*",
        "accept" to "application/dns-message",
        "accept-encoding" to "gzip, deflate, br",
        "accept-ranges" to "bytes",
        "access-control-allow-headers" to "cache-control",
        "access-control-allow-headers" to "content-type",
        "access-control-allow-origin" to "*",
        "cache-control" to "max-age=0",
        "cache-control" to "max-age=2592000",
        "cache-control" to "max-age=604800",
        "cache-control" to "no-cache",
        "cache-control" to "no-store",
        "cache-control" to "public, max-age=31536000",
        "content-encoding" to "br",
        "content-encoding" to "gzip",
        "content-type" to "application/dns-message",
        "content-type" to "application/javascript",
        "content-type" to "application/json",
        "content-type" to "application/x-www-form-urlencoded",
        "content-type" to "image/gif",
        "content-type" to "image/jpeg",
        "content-type" to "image/png",
        "content-type" to "text/css",
        "content-type" to "text/html; charset=utf-8",
        "content-type" to "text/plain",
        "content-type" to "text/plain;charset=utf-8",
        "range" to "bytes=0-",
        "strict-transport-security" to "max-age=31536000",
        "strict-transport-security" to "max-age=31536000; includesubdomains",
        "strict-transport-security" to "max-age=31536000; includesubdomains; preload",
        "vary" to "accept-encoding",
        "vary" to "origin",
        "x-content-type-options" to "nosniff",
        "x-xss-protection" to "1; mode=block",
        ":status" to "100",
        ":status" to "204",
        ":status" to "206",
        ":status" to "302",
        ":status" to "400",
        ":status" to "403",
        ":status" to "421",
        ":status" to "425",
        ":status" to "500",
        "accept-language" to "",
        "access-control-allow-credentials" to "FALSE",
        "access-control-allow-credentials" to "TRUE",
        "access-control-allow-headers" to "*",
        "access-control-allow-methods" to "get",
        "access-control-allow-methods" to "get, post, options",
        "access-control-allow-methods" to "options",
        "access-control-expose-headers" to "content-length",
        "access-control-request-headers" to "content-type",
        "access-control-request-method" to "get",
        "access-control-request-method" to "post",
        "alt-svc" to "clear",
        "authorization" to "",
        "content-security-policy" to "script-src 'none'; object-src 'none'; base-uri 'none'",
        "early-data" to "1",
        "expect-ct" to "",
        "forwarded" to "",
        "if-range" to "",
        "origin" to "",
        "purpose" to "prefetch",
        "server" to "",
        "timing-allow-origin" to "*",
        "upgrade-insecure-requests" to "1",
        "user-agent" to "",
        "x-forwarded-for" to "",
        "x-frame-options" to "deny",
        "x-frame-options" to "sameorigin"
    )

    /** The index of an exact (name, value) match, or null. */
    fun exactIndex(name: String, value: String): Int? {
        val i = entries.indexOfFirst { it.first == name && it.second == value }
        return if (i >= 0) i else null
    }

    /** The index of the first entry with this name (any value), or null - for name-reference literals. */
    fun nameIndex(name: String): Int? {
        val i = entries.indexOfFirst { it.first == name }
        return if (i >= 0) i else null
    }
}

/**
 * A from-scratch, deliberately minimal QPACK (RFC 9204) codec: static table plus literal field
 * lines only. QPACK's whole raison d'etre over HPACK is a *dynamic* table that survives
 * out-of-order HTTP/3 stream delivery (via a separate encoder/decoder stream, "Required Insert
 * Count", and stream blocking until referenced entries arrive) - RFC 9204 section 2.1.2 and
 * section 4.5.1 explicitly document that an encoder MAY choose never to use the dynamic table at
 * all, always encoding with `Required Insert Count = 0`. That's the choice made here: every
 * field section this codec emits sets Required Insert Count and Delta Base to zero and never
 * references or defines a dynamic table entry - so there is no decoder blocking to implement, no
 * encoder stream, and no dynamic table state to keep in sync. This is spec-legal QPACK, just
 * without QPACK's main compression win when reusing headers across requests: every header not in
 * the 99-entry static table is sent as a literal on every request. Huffman string coding (RFC
 * 9204 4.1.2, reusing HPACK's Huffman code, RFC 7541 Appendix B) is optional per the spec and is
 * not implemented here either - literals are sent as raw (non-Huffman) strings, which any
 * compliant QPACK decoder must still accept.
 */
object Qpack {
    /** Encodes a header list into a QPACK field section (with its 2-byte all-zero prefix). */
    fun encode(headers: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        // Field section prefix: Required Insert Count = 0, Delta Base sign=0 magnitude=0 - see class doc.
        out.write(0x00)
        out.write(0x00)
        for ((name, value) in headers) {
            val exact = QpackStaticTable.exactIndex(name, value)
            if (exact != null) {
                writeIndexedStatic(out, exact)
                continue
            }
            val nameIdx = QpackStaticTable.nameIndex(name)
            if (nameIdx != null) {
                writeLiteralWithStaticNameRef(out, nameIdx, value)
            } else {
                writeLiteralWithLiteralName(out, name, value)
            }
        }
        return out.toByteArray()
    }

    // RFC 9204 4.5.2: Indexed Field Line, static table: '1 T=1' + 6-bit prefix index.
    private fun writeIndexedStatic(out: ByteArrayOutputStream, index: Int) {
        writePrefixedInt(out, index, 6, 0xC0)
    }

    // RFC 9204 4.5.4: Literal Field Line With Name Reference, static: '01 N=0 T=1' + 4-bit prefix index, then value.
    private fun writeLiteralWithStaticNameRef(out: ByteArrayOutputStream, nameIndex: Int, value: String) {
        writePrefixedInt(out, nameIndex, 4, 0x50)
        writeStringLiteral(out, value)
    }

    // RFC 9204 4.5.6: Literal Field Line With Literal Name: '001 N=0 H=0' + 3-bit prefix name length, name, value.
    private fun writeLiteralWithLiteralName(out: ByteArrayOutputStream, name: String, value: String) {
        val nameBytes = name.toByteArray(StandardCharsets.US_ASCII)
        writePrefixedInt(out, nameBytes.size, 3, 0x20)
        out.write(nameBytes)
        writeStringLiteral(out, value)
    }

    // A QPACK/HPACK string literal: H=0 (no Huffman) + 7-bit prefix length, then raw bytes.
    private fun writeStringLiteral(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writePrefixedInt(out, bytes.size, 7, 0x00)
        out.write(bytes)
    }

    /** RFC 7541 5.1 "prefixed integer" encoding, reused by QPACK: [flagsAndPrefixBits] carries the fixed leading bits. */
    private fun writePrefixedInt(out: ByteArrayOutputStream, value: Int, prefixBits: Int, leadingBits: Int) {
        val max = (1 shl prefixBits) - 1
        if (value < max) {
            out.write(leadingBits or value)
            return
        }
        out.write(leadingBits or max)
        var n = value - max
        while (n >= 128) {
            out.write((n % 128) or 0x80)
            n /= 128
        }
        out.write(n)
    }

    /** Decodes a QPACK field section produced by [encode] (Required Insert Count/Delta Base must be zero). */
    fun decode(data: ByteArray): List<Pair<String, String>> {
        val reader = QuicByteReader(data)
        val requiredInsertCount = readPrefixedInt(reader, 8)
        require(requiredInsertCount == 0) { "dynamic table not supported (Required Insert Count != 0)" }
        val deltaBaseByte = reader.readByte()
        require(deltaBaseByte == 0x00) { "dynamic table not supported (non-zero Delta Base)" }
        val headers = ArrayList<Pair<String, String>>()
        while (reader.hasRemaining()) {
            val first = reader.data[reader.position].toInt() and 0xFF
            when {
                first and 0x80 != 0 -> { // Indexed Field Line
                    val isStatic = first and 0x40 != 0
                    require(isStatic) { "dynamic table reference not supported" }
                    val index = readPrefixedIntWithFirstByteKnown(reader, 6)
                    headers.add(QpackStaticTable.entries[index])
                }
                first and 0x40 != 0 -> { // Literal Field Line With Name Reference
                    val isStatic = first and 0x10 != 0
                    require(isStatic) { "dynamic table reference not supported" }
                    val nameIndex = readPrefixedIntWithFirstByteKnown(reader, 4)
                    val value = readStringLiteral(reader)
                    headers.add(QpackStaticTable.entries[nameIndex].first to value)
                }
                first and 0x20 != 0 -> { // Literal Field Line With Literal Name
                    val huffman = first and 0x08 != 0
                    require(!huffman) { "Huffman-coded names not supported by this minimal codec" }
                    val nameLen = readPrefixedIntWithFirstByteKnown(reader, 3)
                    val name = String(reader.readBytes(nameLen), StandardCharsets.US_ASCII)
                    val value = readStringLiteral(reader)
                    headers.add(name to value)
                }
                else -> throw IllegalArgumentException("unsupported QPACK field line type (dynamic-table indexing): 0x${first.toString(16)}")
            }
        }
        return headers
    }

    private fun readStringLiteral(reader: QuicByteReader): String {
        val first = reader.data[reader.position].toInt() and 0xFF
        val huffman = first and 0x80 != 0
        require(!huffman) { "Huffman-coded values not supported by this minimal codec" }
        val len = readPrefixedIntWithFirstByteKnown(reader, 7)
        return String(reader.readBytes(len), StandardCharsets.UTF_8)
    }

    private fun readPrefixedInt(reader: QuicByteReader, prefixBits: Int): Int =
        readPrefixedIntWithFirstByteKnown(reader, prefixBits)

    private fun readPrefixedIntWithFirstByteKnown(reader: QuicByteReader, prefixBits: Int): Int {
        val max = (1 shl prefixBits) - 1
        val first = reader.readByte() and max
        if (first < max) return first
        var value = max
        var shift = 0
        while (true) {
            val b = reader.readByte()
            value += (b and 0x7F) shl shift
            if (b and 0x80 == 0) break
            shift += 7
        }
        return value
    }
}
