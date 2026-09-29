package com.example.vm.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

data class ArpEntry(
    val ipInt: Int,
    val macBytes: ByteArray,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * ArpEngine handles IPv4 Address Resolution Protocol (ARP) frame processing,
 * maintains a thread-safe ARP cache with TTL expiration, and synthesizes ARP responses
 * for Gateway (10.0.2.2) and DNS (10.0.2.3).
 */
class ArpEngine(
    val gatewayMac: String = "52:54:00:12:34:02",
    val gatewayIpStr: String = "10.0.2.2",
    val dnsIpStr: String = "10.0.2.3",
    val cacheTtlMs: Long = 300_000L // 5 minute TTL
) {
    private val gatewayMacBytes = NetworkPacketUtils.parseMac(gatewayMac)
    private val gatewayIpInt = NetworkPacketUtils.parseIp(gatewayIpStr)
    private val dnsIpInt = NetworkPacketUtils.parseIp(dnsIpStr)

    private val arpCache = ConcurrentHashMap<Int, ArpEntry>()

    init {
        // Pre-populate Gateway and DNS entries in ARP table
        arpCache[gatewayIpInt] = ArpEntry(gatewayIpInt, gatewayMacBytes)
        arpCache[dnsIpInt] = ArpEntry(dnsIpInt, gatewayMacBytes)
    }

    /**
     * Inspects an incoming Ethernet ARP frame (length >= 42 bytes).
     * If it is an ARP Request asking for the Gateway or DNS IP, synthesizes and returns an ARP Reply frame.
     */
    fun processArpFrame(frame: ByteArray): ByteArray? {
        if (frame.size < 42) return null

        val buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val etherType = buf.getShort(12).toInt() and 0xFFFF
        if (etherType != NetworkPacketUtils.ETHERTYPE_ARP) return null

        val hwType = buf.getShort(14).toInt() and 0xFFFF
        val protoType = buf.getShort(16).toInt() and 0xFFFF
        val hwLen = buf.get(18).toInt() and 0xFF
        val protoLen = buf.get(19).toInt() and 0xFF
        val opCode = buf.getShort(20).toInt() and 0xFFFF

        if (hwType != 1 || protoType != 0x0800 || hwLen != 6 || protoLen != 4) return null

        val senderMac = ByteArray(6)
        buf.position(22)
        buf.get(senderMac)
        val senderIp = buf.getInt(28)
        val targetIp = buf.getInt(38)

        // Store guest ARP mapping
        arpCache[senderIp] = ArpEntry(senderIp, senderMac)

        if (opCode == 1) { // ARP Request
            if (targetIp == gatewayIpInt || targetIp == dnsIpInt) {
                return synthesizeArpReply(
                    targetMac = senderMac,
                    targetIp = senderIp,
                    senderMac = gatewayMacBytes,
                    senderIp = targetIp
                )
            }
        }

        return null
    }

    fun lookupMac(ipInt: Int): ByteArray? {
        cleanExpiredEntries()
        return arpCache[ipInt]?.macBytes
    }

    private fun synthesizeArpReply(
        targetMac: ByteArray,
        targetIp: Int,
        senderMac: ByteArray,
        senderIp: Int
    ): ByteArray {
        val reply = ByteArray(42)
        val buf = ByteBuffer.wrap(reply).order(ByteOrder.BIG_ENDIAN)

        // Ethernet Header (14 bytes)
        buf.put(targetMac)      // Destination MAC
        buf.put(senderMac)      // Source MAC
        buf.putShort(NetworkPacketUtils.ETHERTYPE_ARP.toShort())

        // ARP Payload (28 bytes)
        buf.putShort(1)         // Hardware Type: Ethernet (1)
        buf.putShort(0x0800)    // Protocol Type: IPv4 (0x0800)
        buf.put(6)              // Hardware Address Length: 6
        buf.put(4)              // Protocol Address Length: 4
        buf.putShort(2)         // Opcode: ARP Reply (2)
        buf.put(senderMac)      // Sender Hardware Address
        buf.putInt(senderIp)    // Sender Protocol Address
        buf.put(targetMac)      // Target Hardware Address
        buf.putInt(targetIp)    // Target Protocol Address

        return reply
    }

    private fun cleanExpiredEntries() {
        val now = System.currentTimeMillis()
        val iterator = arpCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key != gatewayIpInt && entry.key != dnsIpInt) {
                if (now - entry.value.timestampMs > cacheTtlMs) {
                    iterator.remove()
                }
            }
        }
    }

    fun clear() {
        arpCache.clear()
        arpCache[gatewayIpInt] = ArpEntry(gatewayIpInt, gatewayMacBytes)
        arpCache[dnsIpInt] = ArpEntry(dnsIpInt, gatewayMacBytes)
    }
}
