package com.projectfuture.browser.rtc

import java.net.DatagramSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFC 8445 priority formulas, and a real end-to-end connectivity-check/nomination run between two [IceAgent]s over loopback UDP - no mocking, genuine STUN transactions both ways. */
class IceAgentTest {
    @Test fun hostCandidatePriorityMatchesRfc8445Formula() {
        // RFC 8445 section 5.1.2.1: (2^24)*type_pref + (2^8)*local_pref + (256 - component).
        // type_pref(host)=126, local_pref=65535 (the max, appropriate for a single interface),
        // component=1: (126 << 24) + (65535 << 8) + 255 = 2130706431.
        assertEquals(2130706431L, icePriority("host"))
    }

    @Test fun serverReflexiveHasLowerPriorityThanHost() {
        assertTrue(icePriority("host") > icePriority("srflx"))
        assertTrue(icePriority("srflx") > icePriority("relay"))
    }

    @Test fun pairPriorityFormulaMatchesRfc8445() {
        val g = 2113937151L // controlling agent's candidate priority
        val d = 1845501695L // controlled agent's candidate priority
        val expected = (minOf(g, d) shl 32) + 2 * maxOf(g, d) + 1
        assertEquals(expected, pairPriority(g, d))
        // Swapping which side is higher changes the tie-break bit.
        assertEquals((minOf(d, g) shl 32) + 2 * maxOf(d, g) + 0, pairPriority(d, g))
    }

    @Test fun twoAgentsConnectAndNominateAPairOverRealStunChecks() {
        val controllingSocket = DatagramSocket(0)
        val controlledSocket = DatagramSocket(0)
        val controlling = IceAgent(localUfrag = "cfrag", localPwd = "controlling-password-1234", controlling = true)
        val controlled = IceAgent(localUfrag = "dfrag", localPwd = "controlled-password-5678", controlling = false)
        try {
            controlling.gatherCandidates(controllingSocket, "127.0.0.1")
            controlled.gatherCandidates(controlledSocket, "127.0.0.1")

            controlling.setRemoteCredentials(controlled.localUfrag, controlled.localPwd)
            controlled.setRemoteCredentials(controlling.localUfrag, controlling.localPwd)
            for (c in controlled.localCandidates) controlling.addRemoteCandidate(c)
            for (c in controlling.localCandidates) controlled.addRemoteCandidate(c)

            val controllingResult = java.util.concurrent.atomic.AtomicBoolean(false)
            val controlledResult = java.util.concurrent.atomic.AtomicBoolean(false)
            val t1 = Thread { controllingResult.set(controlling.startConnectivityChecks()) }.apply { start() }
            val t2 = Thread { controlledResult.set(controlled.startConnectivityChecks()) }.apply { start() }
            t1.join(10000)
            t2.join(10000)

            assertTrue("controlling agent should nominate a working pair", controllingResult.get())
            assertTrue("controlled agent should see the nomination", controlledResult.get())
            assertNotNull(controlling.selectedRemoteAddress)
            assertNotNull(controlled.selectedRemoteAddress)
            assertEquals(controlledSocket.localPort, controlling.selectedRemoteAddress!!.port)
            assertEquals(controllingSocket.localPort, controlled.selectedRemoteAddress!!.port)
        } finally {
            controlling.close()
            controlled.close()
            controllingSocket.close()
            controlledSocket.close()
        }
    }
}
