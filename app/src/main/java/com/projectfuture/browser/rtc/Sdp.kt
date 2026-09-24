package com.projectfuture.browser.rtc

import java.security.SecureRandom

/**
 * A single ICE host candidate, in this engine's data-channel-only subset:
 * always `typ host` (no STUN server reflexive candidates, no TURN relay
 * candidates - see RTCPeerConnection.kt's class doc for why), always UDP.
 */
data class IceCandidate(
    val foundation: String,
    val component: Int,
    val protocol: String,
    val priority: Long,
    val ip: String,
    val port: Int,
    val type: String = "host"
) {
    /** The `a=candidate:...` line as it appears in an SDP body (RFC 8839 section 5.1's textual form). */
    fun toSdpLine(): String = "a=candidate:$foundation $component $protocol $priority $ip $port typ $type"

    companion object {
        fun parse(line: String): IceCandidate? {
            // "a=candidate:<foundation> <component> <protocol> <priority> <ip> <port> typ <type> ..."
            val body = line.substringAfter("a=candidate:", "").ifEmpty { return null }
            val parts = body.trim().split(Regex("\\s+"))
            if (parts.size < 8 || parts[6] != "typ") return null
            return try {
                IceCandidate(
                    foundation = parts[0],
                    component = parts[1].toInt(),
                    protocol = parts[2],
                    priority = parts[3].toLong(),
                    ip = parts[4],
                    port = parts[5].toInt(),
                    type = parts[7]
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}

/**
 * The subset of an SDP offer/answer this engine actually generates and
 * understands: exactly one `m=application ... UDP/DTLS/SCTP webrtc-
 * datachannel` media section (data channels only - see
 * RTCPeerConnection.kt's class doc on why audio/video `m=` sections,
 * codecs and RTP/SRTP are out of scope), its ICE credentials, a DTLS
 * fingerprint, the SCTP port, and its host candidate(s). This is real
 * RFC 4566/8866 syntax - a genuine SDP parser could read this engine's
 * offers and vice versa for the data-channel section specifically - it
 * just never describes anything beyond that one section.
 */
data class SdpSession(
    val sessionId: String,
    val iceUfrag: String,
    val icePwd: String,
    /** `sha-256` fingerprint hex string. Cosmetic only in this engine - see RTCPeerConnection.kt's class doc: nothing here actually performs a DTLS handshake or checks this value against a peer certificate, since no DTLS is implemented. It's included so the SDP is shaped like a real offer/answer and a real WebRTC stack reading it wouldn't immediately reject it as malformed. */
    val fingerprint: String,
    /** "actpass" (offer) or "active"/"passive" (answer) - RFC 8842 DTLS role negotiation. Not acted on beyond being echoed, for the same reason as [fingerprint]. */
    val setup: String,
    val sctpPort: Int,
    val candidates: List<IceCandidate>,
    val mid: String = "0"
) {
    fun toSdpString(): String {
        val cand = candidates.firstOrNull()
        val connIp = cand?.ip ?: "0.0.0.0"
        val sb = StringBuilder()
        sb.append("v=0\r\n")
        sb.append("o=- $sessionId 2 IN IP4 $connIp\r\n")
        sb.append("s=-\r\n")
        sb.append("t=0 0\r\n")
        sb.append("a=group:BUNDLE $mid\r\n")
        sb.append("m=application ${cand?.port ?: 9} UDP/DTLS/SCTP webrtc-datachannel\r\n")
        sb.append("c=IN IP4 $connIp\r\n")
        sb.append("a=ice-ufrag:$iceUfrag\r\n")
        sb.append("a=ice-pwd:$icePwd\r\n")
        sb.append("a=fingerprint:sha-256 $fingerprint\r\n")
        sb.append("a=setup:$setup\r\n")
        sb.append("a=mid:$mid\r\n")
        sb.append("a=sctp-port:$sctpPort\r\n")
        for (c in candidates) sb.append(c.toSdpLine()).append("\r\n")
        return sb.toString()
    }

    companion object {
        fun parse(text: String): SdpSession {
            var sessionId = "0"
            var iceUfrag = ""
            var icePwd = ""
            var fingerprint = ""
            var setup = "actpass"
            var sctpPort = 5000
            var mid = "0"
            val candidates = ArrayList<IceCandidate>()
            for (rawLine in text.split("\r\n", "\n")) {
                val line = rawLine.trim()
                when {
                    line.startsWith("o=") -> line.split(" ").getOrNull(1)?.let { sessionId = it }
                    line.startsWith("a=ice-ufrag:") -> iceUfrag = line.substringAfter(":")
                    line.startsWith("a=ice-pwd:") -> icePwd = line.substringAfter(":")
                    line.startsWith("a=fingerprint:") -> fingerprint = line.substringAfter(" ")
                    line.startsWith("a=setup:") -> setup = line.substringAfter(":")
                    line.startsWith("a=mid:") -> mid = line.substringAfter(":")
                    line.startsWith("a=sctp-port:") -> sctpPort = line.substringAfter(":").toIntOrNull() ?: sctpPort
                    line.startsWith("a=candidate:") -> IceCandidate.parse(line)?.let { candidates.add(it) }
                }
            }
            return SdpSession(sessionId, iceUfrag, icePwd, fingerprint, setup, sctpPort, candidates, mid)
        }
    }
}

private val random = SecureRandom()

fun randomIceToken(length: Int): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
}

fun randomFingerprint(): String =
    (1..32).map { "%02X".format(random.nextInt(256)) }.joinToString(":")
