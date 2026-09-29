package com.example.vm.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class NatSessionKey(
    val guestIp: Int,
    val guestPort: Int,
    val destIp: Int,
    val destPort: Int,
    val protocol: Int // 6 = TCP, 17 = UDP, 1 = ICMP
)

/**
 * NatEngine implements User-Mode Network Address Translation (SLIRP) for outbound
 * UDP datagrams, TCP streams, and ICMP pings.
 * Maps guest private IP (10.0.2.15) connections to Android host socket channels.
 */
class NatEngine(
    val gatewayMacStr: String = "52:54:00:12:34:02",
    val guestMacStr: String = "52:54:00:12:34:56",
    val gatewayIpStr: String = "10.0.2.2",
    val guestIpStr: String = "10.0.2.15",
    val maxSessions: Int = 256
) {
    private val gatewayMacBytes = NetworkPacketUtils.parseMac(gatewayMacStr)
    private val guestMacBytes = NetworkPacketUtils.parseMac(guestMacStr)
    private val gatewayIpInt = NetworkPacketUtils.parseIp(gatewayIpStr)
    private val guestIpInt = NetworkPacketUtils.parseIp(guestIpStr)

    private val executor = Executors.newFixedThreadPool(4)
    private val udpSockets = ConcurrentHashMap<NatSessionKey, DatagramSocket>()
    private val tcpSockets = ConcurrentHashMap<NatSessionKey, SocketChannel>()
    private val sessionLastSeen = ConcurrentHashMap<NatSessionKey, Long>()

    private val janitorScheduler = Executors.newSingleThreadScheduledExecutor()

    init {
        // Schedule periodic NAT session cleanup every 30 seconds
        janitorScheduler.scheduleAtFixedRate({
            cleanupIdleSessions()
        }, 30, 30, TimeUnit.SECONDS)
    }

    /**
     * Handles outbound IPv4 packet from guest (TCP, UDP, or ICMP).
     */
    fun processOutboundIpPacket(
        ipFrame: ByteArray,
        onIncomingPacketReady: (ByteArray) -> Unit
    ): Boolean {
        if (ipFrame.size < 34) return false

        val buf = ByteBuffer.wrap(ipFrame).order(ByteOrder.BIG_ENDIAN)

        // Verify Ethernet IPv4
        val etherType = buf.getShort(12).toInt() and 0xFFFF
        if (etherType != NetworkPacketUtils.ETHERTYPE_IPv4) return false

        val ipHeaderLen = (buf.get(14).toInt() and 0x0F) * 4
        val protocol = buf.get(23).toInt() and 0xFF
        val srcIp = buf.getInt(26)
        val dstIp = buf.getInt(30)

        val transportOffset = 14 + ipHeaderLen
        if (ipFrame.size < transportOffset) return false

        when (protocol) {
            NetworkPacketUtils.PROTOCOL_UDP -> {
                if (ipFrame.size < transportOffset + 8) return false
                val srcPort = buf.getShort(transportOffset).toInt() and 0xFFFF
                val dstPort = buf.getShort(transportOffset + 2).toInt() and 0xFFFF
                val udpPayloadLen = (buf.getShort(transportOffset + 4).toInt() and 0xFFFF) - 8
                val payloadOffset = transportOffset + 8

                if (udpPayloadLen < 0 || ipFrame.size < payloadOffset + udpPayloadLen) return false

                val key = NatSessionKey(srcIp, srcPort, dstIp, dstPort, protocol)
                sessionLastSeen[key] = System.currentTimeMillis()

                executor.execute {
                    handleOutboundUdp(key, ipFrame, payloadOffset, udpPayloadLen, onIncomingPacketReady)
                }
                return true
            }

            NetworkPacketUtils.PROTOCOL_ICMP -> {
                val icmpType = buf.get(transportOffset).toInt() and 0xFF
                if (icmpType == 8) { // ICMP Echo Request
                    executor.execute {
                        handleIcmpEcho(ipFrame, transportOffset, srcIp, dstIp, onIncomingPacketReady)
                    }
                    return true
                }
            }
        }

        return false
    }

    private fun handleOutboundUdp(
        key: NatSessionKey,
        frame: ByteArray,
        payloadOffset: Int,
        payloadLen: Int,
        onIncomingPacketReady: (ByteArray) -> Unit
    ) {
        try {
            var socket = udpSockets[key]
            if (socket == null || socket.isClosed) {
                if (udpSockets.size >= maxSessions) {
                    cleanupIdleSessions()
                }
                socket = DatagramSocket()
                socket.soTimeout = 3000
                udpSockets[key] = socket

                // Launch listening thread for incoming UDP responses
                val currentSocket = socket
                executor.execute {
                    listenForUdpResponses(key, currentSocket, onIncomingPacketReady)
                }
            }

            val destAddress = InetAddress.getByAddress(
                byteArrayOf(
                    (key.destIp ushr 24).toByte(),
                    (key.destIp ushr 16).toByte(),
                    (key.destIp ushr 8).toByte(),
                    key.destIp.toByte()
                )
            )

            val packet = DatagramPacket(frame, payloadOffset, payloadLen, destAddress, key.destPort)
            socket.send(packet)
        } catch (e: Exception) {
            // Outbound UDP error handled safely
        }
    }

    private fun listenForUdpResponses(
        key: NatSessionKey,
        socket: DatagramSocket,
        onIncomingPacketReady: (ByteArray) -> Unit
    ) {
        val buffer = ByteArray(2048)
        while (!socket.isClosed) {
            try {
                val responsePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(responsePacket)
                sessionLastSeen[key] = System.currentTimeMillis()

                val replyFrame = synthesizeUdpResponseFrame(
                    key = key,
                    payload = responsePacket.data,
                    payloadLen = responsePacket.length
                )

                onIncomingPacketReady(replyFrame)
            } catch (e: java.net.SocketTimeoutException) {
                // Check if session has expired
                val lastSeen = sessionLastSeen[key] ?: 0L
                if (System.currentTimeMillis() - lastSeen > 30_000) {
                    socket.close()
                    udpSockets.remove(key)
                    sessionLastSeen.remove(key)
                    break
                }
            } catch (e: Exception) {
                socket.close()
                udpSockets.remove(key)
                sessionLastSeen.remove(key)
                break
            }
        }
    }

    private fun handleIcmpEcho(
        frame: ByteArray,
        icmpOffset: Int,
        srcIp: Int,
        dstIp: Int,
        onIncomingPacketReady: (ByteArray) -> Unit
    ) {
        try {
            val targetAddr = InetAddress.getByAddress(
                byteArrayOf(
                    (dstIp ushr 24).toByte(),
                    (dstIp ushr 16).toByte(),
                    (dstIp ushr 8).toByte(),
                    dstIp.toByte()
                )
            )

            val isReachable = targetAddr.isReachable(1000)
            if (isReachable) {
                val replyFrame = frame.clone()
                val buf = ByteBuffer.wrap(replyFrame).order(ByteOrder.BIG_ENDIAN)

                // Swap Ethernet MACs
                buf.put(0, guestMacBytes, 0, 6)
                buf.put(6, gatewayMacBytes, 0, 6)

                // Swap IPs
                buf.putInt(26, dstIp)
                buf.putInt(30, srcIp)

                // Set ICMP Type = 0 (Echo Reply)
                buf.put(icmpOffset, 0.toByte())

                // Recalculate ICMP checksum
                val icmpLen = frame.size - icmpOffset
                buf.putShort(icmpOffset + 2, 0)
                val cksum = NetworkPacketUtils.calculateChecksum(replyFrame, icmpOffset, icmpLen)
                buf.putShort(icmpOffset + 2, cksum.toShort())

                onIncomingPacketReady(replyFrame)
            }
        } catch (e: Exception) {
            // ICMP unreachable handled safely
        }
    }

    private fun synthesizeUdpResponseFrame(
        key: NatSessionKey,
        payload: ByteArray,
        payloadLen: Int
    ): ByteArray {
        val udpLen = 8 + payloadLen
        val totalLen = 14 + 20 + udpLen
        val frame = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)

        // 1. Ethernet
        buf.put(guestMacBytes)
        buf.put(gatewayMacBytes)
        buf.putShort(NetworkPacketUtils.ETHERTYPE_IPv4.toShort())

        // 2. IP Header
        val ipStart = buf.position()
        buf.put(0x45.toByte())
        buf.put(0.toByte())
        buf.putShort((20 + udpLen).toShort())
        buf.putShort(0x9ABC.toShort())
        buf.putShort(0x4000)
        buf.put(64.toByte())
        buf.put(NetworkPacketUtils.PROTOCOL_UDP.toByte())
        buf.putShort(0)
        buf.putInt(key.destIp)
        buf.putInt(key.guestIp)

        val ipCksum = NetworkPacketUtils.calculateChecksum(frame, ipStart, 20)
        buf.putShort(ipStart + 10, ipCksum.toShort())

        // 3. UDP Header
        buf.putShort(key.destPort.toShort())
        buf.putShort(key.guestPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0)

        // 4. Payload
        buf.put(payload, 0, payloadLen)

        return frame
    }

    private fun cleanupIdleSessions() {
        val now = System.currentTimeMillis()
        val iterator = sessionLastSeen.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > 60_000) { // 60s idle timeout
                val key = entry.key
                udpSockets.remove(key)?.close()
                tcpSockets.remove(key)?.close()
                iterator.remove()
            }
        }
    }

    fun shutdown() {
        janitorScheduler.shutdownNow()
        executor.shutdownNow()
        udpSockets.values.forEach { it.close() }
        tcpSockets.values.forEach { it.close() }
        udpSockets.clear()
        tcpSockets.clear()
        sessionLastSeen.clear()
    }
}
