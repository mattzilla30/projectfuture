package com.projectfuture.browser.rtc

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Direct tests of the ARQ layer underneath RTCDataChannel - see its class doc for what "reliable" means here. */
class ReliableUdpChannelTest {

    @Test fun messagesArriveInOrderAndExactlyOnce() {
        val a = ReliableUdpChannel()
        val b = ReliableUdpChannel()
        try {
            a.connect("127.0.0.1", b.localPort)
            b.connect("127.0.0.1", a.localPort)

            val received = CopyOnWriteArrayList<String>()
            val latch = CountDownLatch(5)
            b.onMessage = { received.add(it); latch.countDown() }
            a.start()
            b.start()

            for (i in 1..5) assertTrue("send #$i should be acked", a.send("message-$i"))

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("message-1", "message-2", "message-3", "message-4", "message-5"), received.toList())
        } finally {
            a.close()
            b.close()
        }
    }

    @Test fun bidirectionalExchangeWorks() {
        val a = ReliableUdpChannel()
        val b = ReliableUdpChannel()
        try {
            a.connect("127.0.0.1", b.localPort)
            b.connect("127.0.0.1", a.localPort)
            val aReceived = CountDownLatch(1)
            val bReceived = CountDownLatch(1)
            var aGot: String? = null
            var bGot: String? = null
            a.onMessage = { aGot = it; aReceived.countDown() }
            b.onMessage = { bGot = it; bReceived.countDown() }
            a.start()
            b.start()

            a.send("ping")
            assertTrue(bReceived.await(5, TimeUnit.SECONDS))
            assertEquals("ping", bGot)

            b.send("pong")
            assertTrue(aReceived.await(5, TimeUnit.SECONDS))
            assertEquals("pong", aGot)
        } finally {
            a.close()
            b.close()
        }
    }

    @Test fun sendToNothingListeningEventuallyFailsInsteadOfHangingForever() {
        val a = ReliableUdpChannel()
        try {
            // An unused local port almost certainly has nothing listening - the retry loop should
            // exhaust its attempts and return false rather than blocking indefinitely.
            val deadPort = ReliableUdpChannel().let { val p = it.localPort; it.close(); p }
            a.connect("127.0.0.1", deadPort)
            a.start()
            assertEquals(false, a.send("nobody home"))
        } finally {
            a.close()
        }
    }
}
