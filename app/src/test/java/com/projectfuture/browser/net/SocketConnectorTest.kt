package com.projectfuture.browser.net

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Test

class SocketConnectorTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun alternatesFamiliesStartingWithIpv6() {
        val input = listOf(ip("2606:4700::1"), ip("2606:4700::2"), ip("104.20.1.1"), ip("104.20.1.2"))
        assertEquals(
            listOf("2606:4700:0:0:0:0:0:1", "104.20.1.1", "2606:4700:0:0:0:0:0:2", "104.20.1.2"),
            SocketConnector.orderAddresses(input, preferIpv4 = false).map { it.hostAddress }
        )
        assertEquals("104.20.1.1", SocketConnector.orderAddresses(input, preferIpv4 = true).first().hostAddress)
    }

    @Test fun singleFamilyKeepsDnsOrder() {
        val input = listOf(ip("1.1.1.1"), ip("1.0.0.1"))
        assertEquals(input, SocketConnector.orderAddresses(input, preferIpv4 = false))
    }

    @Test fun connectsToALocalServer() {
        ServerSocket(0).use { server ->
            SocketConnector.connect("127.0.0.1", server.localPort) { java.net.Socket() }.use { assert(it.isConnected) }
        }
    }
}
