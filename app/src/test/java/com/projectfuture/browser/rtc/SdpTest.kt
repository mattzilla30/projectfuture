package com.projectfuture.browser.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpTest {
    @Test fun generatedOfferHasTheExpectedRfc4566Shape() {
        val sdp = SdpSession(
            sessionId = "12345",
            iceUfrag = "abcd",
            icePwd = "0123456789012345678901",
            fingerprint = "AA:BB:CC",
            setup = "actpass",
            sctpPort = 5000,
            candidates = listOf(IceCandidate("0", 1, "udp", 2113937151L, "127.0.0.1", 54321))
        )
        val text = sdp.toSdpString()
        assertTrue(text.startsWith("v=0\r\n"))
        assertTrue(text.contains("m=application 54321 UDP/DTLS/SCTP webrtc-datachannel\r\n"))
        assertTrue(text.contains("a=ice-ufrag:abcd\r\n"))
        assertTrue(text.contains("a=ice-pwd:0123456789012345678901\r\n"))
        assertTrue(text.contains("a=fingerprint:sha-256 AA:BB:CC\r\n"))
        assertTrue(text.contains("a=setup:actpass\r\n"))
        assertTrue(text.contains("a=sctp-port:5000\r\n"))
        assertTrue(text.contains("a=candidate:0 1 udp 2113937151 127.0.0.1 54321 typ host\r\n"))
    }

    @Test fun sdpRoundTripsThroughParse() {
        val original = SdpSession(
            sessionId = "999",
            iceUfrag = "xyz1",
            icePwd = "abcdefghijklmnopqrstuv",
            fingerprint = "11:22:33:44",
            setup = "active",
            sctpPort = 5000,
            candidates = listOf(IceCandidate("0", 1, "udp", 2113937151L, "192.168.1.5", 40000))
        )
        val parsed = SdpSession.parse(original.toSdpString())
        assertEquals(original.iceUfrag, parsed.iceUfrag)
        assertEquals(original.icePwd, parsed.icePwd)
        assertEquals(original.fingerprint, parsed.fingerprint)
        assertEquals(original.setup, parsed.setup)
        assertEquals(original.sctpPort, parsed.sctpPort)
        assertEquals(1, parsed.candidates.size)
        assertEquals("192.168.1.5", parsed.candidates[0].ip)
        assertEquals(40000, parsed.candidates[0].port)
    }

    @Test fun candidateLineRoundTrips() {
        val candidate = IceCandidate("1", 1, "udp", 12345L, "10.0.0.2", 9999, "host")
        val parsed = IceCandidate.parse(candidate.toSdpLine())
        assertEquals(candidate, parsed)
    }

    @Test fun malformedCandidateLineParsesToNull() {
        assertNull(IceCandidate.parse("a=candidate:garbage"))
    }

    @Test fun parsingIgnoresUnrelatedSdpLinesWithoutCrashing() {
        val text = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=-\r\nm=audio 0 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\n"
        val parsed = SdpSession.parse(text)
        assertEquals("1", parsed.sessionId)
        assertTrue(parsed.candidates.isEmpty())
    }
}
