package com.projectfuture.browser.rtc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A genuine end-to-end test of two [PeerConnectionCore] instances talking
 * over real loopback UDP sockets - offer/answer SDP exchange (as strings,
 * exactly as two pages would hand them to each other over a signaling
 * channel), then real bidirectional data channel messaging. See
 * PeerConnectionCore's class doc for exactly what this proves versus what
 * it deliberately doesn't (no ICE/STUN/TURN, no DTLS, not real SCTP).
 */
class PeerConnectionCoreTest {

    @Test fun offerAnswerExchangeEstablishesAWorkingDataChannel() {
        val offerer = PeerConnectionCore()
        val answerer = PeerConnectionCore()
        try {
            val remoteChannelLatch = CountDownLatch(1)
            var remoteChannel: RTCDataChannelCore? = null
            answerer.onDataChannel = { ch -> remoteChannel = ch; remoteChannelLatch.countDown() }

            val localChannel = offerer.createDataChannel("chat")

            // Exchange SDP as plain strings, exactly as it would cross a real signaling channel (a
            // WebSocket to a server, copy/paste, etc.) - this is not sharing Kotlin objects.
            val offerSdpText = offerer.createOffer().toSdpString()
            offerer.setLocalDescription(SdpSession.parse(offerSdpText))
            answerer.setRemoteDescription(SdpSession.parse(offerSdpText))

            val answerSdpText = answerer.createAnswer().toSdpString()
            answerer.setLocalDescription(SdpSession.parse(answerSdpText))
            offerer.setRemoteDescription(SdpSession.parse(answerSdpText))

            assertTrue("ondatachannel should fire on the answerer", remoteChannelLatch.await(5, TimeUnit.SECONDS))
            assertEquals("chat", remoteChannel!!.label)

            val localOpenLatch = CountDownLatch(1)
            localChannel.onOpen = { localOpenLatch.countDown() }
            assertTrue("local channel should reach readyState open (possibly already open before onOpen was attached)", localOpenLatch.await(2, TimeUnit.SECONDS))
            assertEquals("open", localChannel.readyState)

            val forwardLatch = CountDownLatch(1)
            var forwardReceived: String? = null
            remoteChannel!!.onMessage = { text -> forwardReceived = text; forwardLatch.countDown() }
            localChannel.send("hello from the offerer")
            assertTrue(forwardLatch.await(5, TimeUnit.SECONDS))
            assertEquals("hello from the offerer", forwardReceived)

            val backLatch = CountDownLatch(1)
            var backReceived: String? = null
            localChannel.onMessage = { text -> backReceived = text; backLatch.countDown() }
            remoteChannel!!.send("hello back, containing: a colon")
            assertTrue(backLatch.await(5, TimeUnit.SECONDS))
            assertEquals("hello back, containing: a colon", backReceived)
        } finally {
            offerer.close()
            answerer.close()
        }
    }

    @Test fun multipleLabeledChannelsAreIndependentlyMultiplexed() {
        val offerer = PeerConnectionCore()
        val answerer = PeerConnectionCore()
        try {
            val receivedChannels = java.util.Collections.synchronizedList(ArrayList<RTCDataChannelCore>())
            val bothChannelsLatch = CountDownLatch(2)
            answerer.onDataChannel = { ch -> receivedChannels.add(ch); bothChannelsLatch.countDown() }

            val chatChannel = offerer.createDataChannel("chat")
            val fileChannel = offerer.createDataChannel("file-transfer")

            val offerSdp = offerer.createOffer()
            offerer.setLocalDescription(offerSdp)
            answerer.setRemoteDescription(offerSdp)
            val answerSdp = answerer.createAnswer()
            answerer.setLocalDescription(answerSdp)
            offerer.setRemoteDescription(answerSdp)

            assertTrue(bothChannelsLatch.await(5, TimeUnit.SECONDS))
            val byLabel = receivedChannels.associateBy { it.label }
            assertTrue(byLabel.containsKey("chat"))
            assertTrue(byLabel.containsKey("file-transfer"))

            val chatLatch = CountDownLatch(1)
            var chatMsg: String? = null
            byLabel["chat"]!!.onMessage = { chatMsg = it; chatLatch.countDown() }
            val fileLatch = CountDownLatch(1)
            var fileMsg: String? = null
            byLabel["file-transfer"]!!.onMessage = { fileMsg = it; fileLatch.countDown() }

            chatChannel.send("chat message")
            fileChannel.send("file bytes as text")

            assertTrue(chatLatch.await(5, TimeUnit.SECONDS))
            assertTrue(fileLatch.await(5, TimeUnit.SECONDS))
            assertEquals("chat message", chatMsg)
            assertEquals("file bytes as text", fileMsg)
        } finally {
            offerer.close()
            answerer.close()
        }
    }
}
