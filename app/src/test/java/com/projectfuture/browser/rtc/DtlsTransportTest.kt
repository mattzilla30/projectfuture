package com.projectfuture.browser.rtc

import java.net.DatagramSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A real DTLS 1.2 handshake (via the platform `SSLEngine`) between two [DtlsTransport]s over two ICE-connected loopback sockets, including the fingerprint check that is WebRTC's actual authentication step. */
class DtlsTransportTest {
    private fun connectedIcePair(): Pair<IceAgent, IceAgent> {
        val socketA = DatagramSocket(0)
        val socketB = DatagramSocket(0)
        val a = IceAgent("af", "aaaaaaaaaaaaaaaaaaaaaa", controlling = true)
        val b = IceAgent("bf", "bbbbbbbbbbbbbbbbbbbbbb", controlling = false)
        a.gatherCandidates(socketA, "127.0.0.1")
        b.gatherCandidates(socketB, "127.0.0.1")
        a.setRemoteCredentials(b.localUfrag, b.localPwd)
        b.setRemoteCredentials(a.localUfrag, a.localPwd)
        for (c in b.localCandidates) a.addRemoteCandidate(c)
        for (c in a.localCandidates) b.addRemoteCandidate(c)
        val t1 = Thread { a.startConnectivityChecks() }.apply { start() }
        val t2 = Thread { b.startConnectivityChecks() }.apply { start() }
        t1.join(10000)
        t2.join(10000)
        return a to b
    }

    @Test fun matchingFingerprintsHandshakeSuccessfullyAndCarryApplicationData() {
        val (a, b) = connectedIcePair()
        val certA = SelfSignedCert.generate()
        val certB = SelfSignedCert.generate()
        try {
            val dtlsA = DtlsTransport.create(a, isClient = true, localCert = certA, expectedRemoteFingerprint = certB.fingerprint)!!
            val dtlsB = DtlsTransport.create(b, isClient = false, localCert = certB, expectedRemoteFingerprint = certA.fingerprint)!!

            val resultA = AtomicBoolean(false)
            val resultB = AtomicBoolean(false)
            val tA = Thread { resultA.set(dtlsA.handshake()) }.apply { start() }
            val tB = Thread { resultB.set(dtlsB.handshake()) }.apply { start() }
            tA.join(15000)
            tB.join(15000)

            assertTrue("client-side DTLS handshake should succeed", resultA.get())
            assertTrue("server-side DTLS handshake should succeed", resultB.get())
            assertTrue(dtlsA.remoteFingerprintVerified)
            assertTrue(dtlsB.remoteFingerprintVerified)

            dtlsA.startReceiveLoop()
            dtlsB.startReceiveLoop()
            val received = AtomicReference<String>()
            val latch = CountDownLatch(1)
            dtlsB.onApplicationData = { bytes -> received.set(String(bytes)); latch.countDown() }
            dtlsA.send("encrypted over real dtls".toByteArray())
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals("encrypted over real dtls", received.get())
        } finally {
            a.close(); b.close()
        }
    }

    @Test fun mismatchedFingerprintIsRejected() {
        val (a, b) = connectedIcePair()
        val certA = SelfSignedCert.generate()
        val certB = SelfSignedCert.generate()
        val attackerCert = SelfSignedCert.generate()
        try {
            val dtlsA = DtlsTransport.create(a, isClient = true, localCert = certA, expectedRemoteFingerprint = attackerCert.fingerprint)!!
            val dtlsB = DtlsTransport.create(b, isClient = false, localCert = certB, expectedRemoteFingerprint = certA.fingerprint)!!

            val clientThrew = AtomicBoolean(false)
            val tA = Thread {
                try {
                    dtlsA.handshake()
                } catch (_: DtlsFingerprintMismatchException) {
                    clientThrew.set(true)
                }
            }.apply { start() }
            val tB = Thread {
                try { dtlsB.handshake() } catch (_: Exception) {}
            }.apply { start() }
            tA.join(15000)
            tB.join(15000)

            assertTrue("a certificate that doesn't match the SDP fingerprint must be rejected", clientThrew.get())
            assertFalse(dtlsA.remoteFingerprintVerified)
        } finally {
            a.close(); b.close()
        }
    }
}
