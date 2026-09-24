package com.projectfuture.browser.net

import com.projectfuture.browser.debug.BrowserLog
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Opens a TCP connection to a host by trying each of its addresses in turn, the way real browsers
 * do (a sequential form of RFC 8305 "Happy Eyeballs"). Connecting to `InetSocketAddress(host, port)`
 * only ever tries the first address DNS returns. On a network with a broken IPv6 route (a VPN or
 * carrier handing out an address like 2001:db8::, which can't reach the internet) every dual-stack
 * site failed with ENETUNREACH while IPv4-only sites such as duckduckgo.com kept working.
 */
object SocketConnector {
    /** How long an attempt may take while other addresses remain to try. */
    private const val ATTEMPT_TIMEOUT_MS = 4000

    // Set once an IPv6 attempt fails and an IPv4 one then succeeds: later connections try IPv4 first
    // instead of paying for a failed IPv6 attempt every time.
    @Volatile private var preferIpv4 = false

    fun connect(host: String, port: Int, timeoutMs: Int = 15000, newSocket: () -> Socket): Socket {
        val ordered = orderAddresses(InetAddress.getAllByName(host).toList(), preferIpv4)
        var lastError: IOException? = null
        var ipv6Failed = false
        for ((i, address) in ordered.withIndex()) {
            val socket = newSocket()
            try {
                // Keep the hostname on the address so TLS still sends it as SNI.
                val target = InetSocketAddress(InetAddress.getByAddress(host, address.address), port)
                socket.connect(target, if (i == ordered.lastIndex) timeoutMs else ATTEMPT_TIMEOUT_MS)
                if (ipv6Failed && address !is Inet6Address && !preferIpv4) {
                    preferIpv4 = true
                    BrowserLog.w("net", "IPv6 is unreachable on this network; trying IPv4 first from now on")
                }
                return socket
            } catch (e: IOException) {
                try { socket.close() } catch (_: Exception) {}
                if (address is Inet6Address) ipv6Failed = true
                BrowserLog.i("net", "connect to ${address.hostAddress} port $port failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IOException("No addresses for $host")
    }

    /** Alternates address families, starting with IPv6 unless [preferIpv4], keeping DNS order within each family. */
    fun orderAddresses(addresses: List<InetAddress>, preferIpv4: Boolean): List<InetAddress> {
        val v6 = addresses.filterIsInstance<Inet6Address>()
        val v4 = addresses.filter { it !is Inet6Address }
        val (first, second) = if (preferIpv4) v4 to v6 else v6 to v4
        val result = ArrayList<InetAddress>(addresses.size)
        for (i in 0 until maxOf(first.size, second.size)) {
            first.getOrNull(i)?.let { result.add(it) }
            second.getOrNull(i)?.let { result.add(it) }
        }
        return result
    }
}
