package com.example.vm.network

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * DnsEngine intercepts UDP port 53 DNS Queries targeting 8.8.8.8 or 10.0.2.3,
 * performs asynchronous host DNS name resolution using InetAddress,
 * and synthesizes DNS A-record response packets back to the guest VM.
 */
class DnsEngine(
    val gatewayMacStr: String = "52:54:00:12:34:02",
    val guestMacStr: String = "52:54:00:12:34:56",
    val gatewayIpStr: String = "10.0.2.2",
    val guestIpStr: String = "10.0.2.15"
) {
    private val gatewayMacBytes = NetworkPacketUtils.parseMac(gatewayMacStr)
    private val guestMacBytes = NetworkPacketUtils.parseMac(guestMacStr)
    private val gatewayIpInt = NetworkPacketUtils.parseIp(gatewayIpStr)
    private val guestIpInt = NetworkPacketUtils.parseIp(guestIpStr)

    private val resolverExecutor = Executors.newFixedThreadPool(2)

    fun processDnsQuery(
        dnsQueryFrame: ByteArray,
        onDnsResponseReady: (ByteArray) -> Unit
    ): Boolean {
        if (dnsQueryFrame.size < 42) return false

        val buf = ByteBuffer.wrap(dnsQueryFrame).order(ByteOrder.BIG_ENDIAN)

        // Verify Ethernet IPv4 and UDP
        val etherType = buf.getShort(12).toInt() and 0xFFFF
        if (etherType != NetworkPacketUtils.ETHERTYPE_IPv4) return false

        val ipProto = buf.get(23).toInt() and 0xFF
        if (ipProto != NetworkPacketUtils.PROTOCOL_UDP) return false

        val srcIp = buf.getInt(26)
        val dstIp = buf.getInt(30)
        val srcPort = buf.getShort(34).toInt() and 0xFFFF
        val dstPort = buf.getShort(36).toInt() and 0xFFFF

        if (dstPort != 53) return false // Not DNS query

        val dnsHeaderPos = 42
        if (dnsQueryFrame.size < dnsHeaderPos + 12) return false

        val txId = buf.getShort(dnsHeaderPos).toInt() and 0xFFFF
        val flags = buf.getShort(dnsHeaderPos + 2).toInt() and 0xFFFF
        val qdCount = buf.getShort(dnsHeaderPos + 4).toInt() and 0xFFFF

        val isQuery = (flags and 0x8000) == 0
        if (!isQuery || qdCount < 1) return false

        // Parse Question QNAME starting at offset 54
        var pos = dnsHeaderPos + 12
        val domainParts = mutableListOf<String>()

        try {
            while (pos < dnsQueryFrame.size) {
                val len = dnsQueryFrame[pos].toInt() and 0xFF
                if (len == 0) { pos++; break }
                if (pos + 1 + len > dnsQueryFrame.size) return false
                val part = String(dnsQueryFrame, pos + 1, len, Charsets.US_ASCII)
                domainParts.add(part)
                pos += 1 + len
            }
        } catch (e: Exception) {
            return false
        }

        if (domainParts.isEmpty() || pos + 4 > dnsQueryFrame.size) return false

        val qType = buf.getShort(pos).toInt() and 0xFFFF
        val domainName = domainParts.joinToString(".")

        // Asynchronously resolve domain name on background I/O thread
        resolverExecutor.execute {
            try {
                val addresses = InetAddress.getAllByName(domainName)
                val ipv4Addresses = addresses.filterIsInstance<java.net.Inet4Address>()

                val responseFrame = synthesizeDnsResponse(
                    queryFrame = dnsQueryFrame,
                    txId = txId,
                    srcIp = dstIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    domainNameParts = domainParts,
                    resolvedIps = ipv4Addresses.map { NetworkPacketUtils.parseIp(it.hostAddress ?: "0.0.0.0") },
                    isSuccess = ipv4Addresses.isNotEmpty()
                )

                if (responseFrame != null) {
                    onDnsResponseReady(responseFrame)
                }
            } catch (e: Exception) {
                // Resolution failure -> send NXDOMAIN response
                val failFrame = synthesizeDnsResponse(
                    queryFrame = dnsQueryFrame,
                    txId = txId,
                    srcIp = dstIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    domainNameParts = domainParts,
                    resolvedIps = emptyList(),
                    isSuccess = false
                )
                if (failFrame != null) {
                    onDnsResponseReady(failFrame)
                }
            }
        }

        return true
    }

    private fun synthesizeDnsResponse(
        queryFrame: ByteArray,
        txId: Int,
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        domainNameParts: List<String>,
        resolvedIps: List<Int>,
        isSuccess: Boolean
    ): ByteArray? {
        val qNameLength = domainNameParts.sumOf { it.length + 1 } + 1
        val answersCount = if (isSuccess) resolvedIps.size else 0
        val answerRecordLength = 16 // Name Pointer (2) + Type (2) + Class (2) + TTL (4) + RDLEN (2) + IP (4)
        val dnsBodyLength = 12 + qNameLength + 4 + (answersCount * answerRecordLength)
        val udpLength = 8 + dnsBodyLength
        val totalLength = 14 + 20 + udpLength

        val reply = ByteArray(totalLength)
        val buf = ByteBuffer.wrap(reply).order(ByteOrder.BIG_ENDIAN)

        // 1. Ethernet Header
        buf.put(guestMacBytes)
        buf.put(gatewayMacBytes)
        buf.putShort(NetworkPacketUtils.ETHERTYPE_IPv4.toShort())

        // 2. IP Header
        val ipStart = buf.position()
        buf.put(0x45.toByte())
        buf.put(0.toByte())
        buf.putShort((20 + udpLength).toShort())
        buf.putShort(0x5678)
        buf.putShort(0x4000)
        buf.put(64.toByte())
        buf.put(NetworkPacketUtils.PROTOCOL_UDP.toByte())
        buf.putShort(0)
        buf.putInt(srcIp)
        buf.putInt(dstIp)

        val ipCksum = NetworkPacketUtils.calculateChecksum(reply, ipStart, 20)
        buf.putShort(ipStart + 10, ipCksum.toShort())

        // 3. UDP Header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLength.toShort())
        buf.putShort(0)

        // 4. DNS Header
        val dnsStart = buf.position()
        buf.putShort(txId.toShort())
        val rcode = if (isSuccess) 0 else 3 // 0 = NoError, 3 = NXDomain
        val responseFlags = 0x8180 or rcode // QR=1, Opcode=0, AA=0, TC=0, RD=1, RA=1
        buf.putShort(responseFlags.toShort())
        buf.putShort(1) // QDCOUNT = 1
        buf.putShort(answersCount.toShort()) // ANCOUNT
        buf.putShort(0) // NSCOUNT
        buf.putShort(0) // ARCOUNT

        // Write Question Section
        val qNameStartOffset = buf.position() - dnsStart
        for (part in domainNameParts) {
            buf.put(part.length.toByte())
            buf.put(part.toByteArray(Charsets.US_ASCII))
        }
        buf.put(0.toByte())
        buf.putShort(1) // QTYPE A
        buf.putShort(1) // QCLASS IN

        // Write Answer Records
        if (isSuccess) {
            for (ip in resolvedIps) {
                buf.putShort((0xC000 or qNameStartOffset).toShort()) // Compression Name Pointer
                buf.putShort(1) // TYPE A
                buf.putShort(1) // CLASS IN
                buf.putInt(300) // TTL 300s
                buf.putShort(4) // RDLENGTH = 4 bytes
                buf.putInt(ip)
            }
        }

        return reply
    }

    fun shutdown() {
        resolverExecutor.shutdownNow()
    }
}
