package com.example.vm.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DhcpServer handles guest VM automatic IPv4 configuration (BOOTP / DHCP RFC 2131).
 * Responds to DHCP DISCOVER with DHCP OFFER, and DHCP REQUEST with DHCP ACK.
 */
class DhcpServer(
    val gatewayMac: String = "52:54:00:12:34:02",
    val gatewayIpStr: String = "10.0.2.2",
    val guestIpStr: String = "10.0.2.15",
    val dnsIpStr: String = "8.8.8.8"
) {
    private val gatewayMacBytes = NetworkPacketUtils.parseMac(gatewayMac)
    private val gatewayIpInt = NetworkPacketUtils.parseIp(gatewayIpStr)
    private val guestIpInt = NetworkPacketUtils.parseIp(guestIpStr)
    private val dnsIpInt = NetworkPacketUtils.parseIp(dnsIpStr)
    private val broadcastMac = ByteArray(6) { 0xFF.toByte() }

    fun processDhcpFrame(frame: ByteArray, guestMacStr: String): ByteArray? {
        if (frame.size < 282) return null // Minimum Ethernet (14) + IP (20) + UDP (8) + DHCP (240)

        val buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)

        // Verify Ethernet EtherType = IPv4
        val etherType = buf.getShort(12).toInt() and 0xFFFF
        if (etherType != NetworkPacketUtils.ETHERTYPE_IPv4) return null

        // Verify IP Protocol = UDP (17)
        val ipProto = buf.get(23).toInt() and 0xFF
        if (ipProto != NetworkPacketUtils.PROTOCOL_UDP) return null

        // Verify UDP Destination Port = 67 (DHCP Server)
        val destPort = buf.getShort(36).toInt() and 0xFFFF
        if (destPort != 67) return null

        // Inspect DHCP Body at offset 42
        val op = buf.get(42).toInt() and 0xFF
        if (op != 1) return null // Must be BOOTREQUEST (1)

        val xid = buf.getInt(46) // Transaction ID

        // Locate DHCP Message Type option (Option 53)
        var msgType = 0
        var optionPos = 282 // Options start after 240-byte BOOTP body + magic cookie (0x63825363)
        while (optionPos < frame.size - 2) {
            val optCode = frame[optionPos].toInt() and 0xFF
            if (optCode == 255) break // Option END
            if (optCode == 0) { optionPos++; continue } // Option PAD
            val optLen = frame[optionPos + 1].toInt() and 0xFF
            if (optCode == 53 && optLen >= 1 && optionPos + 2 < frame.size) {
                msgType = frame[optionPos + 2].toInt() and 0xFF
            }
            optionPos += 2 + optLen
        }

        val responseType = when (msgType) {
            1 -> 2 // DHCP DISCOVER -> DHCP OFFER (Type 2)
            3 -> 5 // DHCP REQUEST -> DHCP ACK (Type 5)
            else -> 2
        }

        return synthesizeDhcpResponse(xid, guestMacStr, responseType)
    }

    private fun synthesizeDhcpResponse(
        xid: Int,
        guestMacStr: String,
        msgType: Int // 2 = OFFER, 5 = ACK
    ): ByteArray {
        val reply = ByteArray(350)
        val buf = ByteBuffer.wrap(reply).order(ByteOrder.BIG_ENDIAN)
        val guestMacBytes = NetworkPacketUtils.parseMac(guestMacStr)

        // 1. Ethernet Header (14 bytes)
        buf.put(guestMacBytes)        // Destination MAC
        buf.put(gatewayMacBytes)      // Source MAC
        buf.putShort(NetworkPacketUtils.ETHERTYPE_IPv4.toShort())

        // 2. IP Header (20 bytes)
        val ipHeaderStart = buf.position()
        buf.put(0x45.toByte())        // Version 4, Header Length 5 (20 bytes)
        buf.put(0.toByte())           // DSCP/ECN
        buf.putShort(336)             // Total Length (IP 20 + UDP 8 + BOOTP 308)
        buf.putShort(0x1234)          // Identification
        buf.putShort(0x4000)          // Flags: Don't Fragment
        buf.put(64.toByte())          // TTL = 64
        buf.put(NetworkPacketUtils.PROTOCOL_UDP.toByte()) // Protocol = UDP
        buf.putShort(0)               // Header Checksum placeholder
        buf.putInt(gatewayIpInt)      // Source IP: 10.0.2.2
        buf.putInt(guestIpInt)        // Destination IP: 10.0.2.15

        // Compute and insert IP checksum
        val ipChecksum = NetworkPacketUtils.calculateChecksum(reply, ipHeaderStart, 20)
        buf.putShort(ipHeaderStart + 10, ipChecksum.toShort())

        // 3. UDP Header (8 bytes)
        buf.putShort(67)              // Source Port: 67 (DHCP Server)
        buf.putShort(68)              // Destination Port: 68 (DHCP Client)
        buf.putShort(316)             // UDP Length
        buf.putShort(0)               // UDP Checksum (0 = omitted in IPv4)

        // 4. BOOTP Body (236 bytes)
        buf.put(2.toByte())           // Message Type: BOOTREPLY (2)
        buf.put(1.toByte())           // Hardware Type: Ethernet (1)
        buf.put(6.toByte())           // Hardware Address Length: 6
        buf.put(0.toByte())           // Hops: 0
        buf.putInt(xid)               // Transaction ID
        buf.putShort(0)               // Seconds elapsed
        buf.putShort(0x8000.toShort())// Flags: Broadcast
        buf.putInt(0)                 // Client IP (ciaddr) = 0.0.0.0
        buf.putInt(guestIpInt)        // Your (Client) IP (yiaddr) = 10.0.2.15
        buf.putInt(gatewayIpInt)      // Next Server IP (siaddr) = 10.0.2.2
        buf.putInt(0)                 // Relay Agent IP (giaddr) = 0.0.0.0
        buf.put(guestMacBytes)        // Client Hardware Address (chaddr)
        buf.position(buf.position() + 10) // Pad chaddr to 16 bytes
        buf.position(buf.position() + 64) // sname (64 bytes zeroes)
        buf.position(buf.position() + 128)// file (128 bytes zeroes)

        // 5. DHCP Options Magic Cookie (4 bytes: 0x63825363)
        buf.putInt(0x63825363)

        // DHCP Options:
        // Option 53: Message Type (1 byte)
        buf.put(53.toByte()); buf.put(1.toByte()); buf.put(msgType.toByte())

        // Option 54: Server Identifier (10.0.2.2)
        buf.put(54.toByte()); buf.put(4.toByte()); buf.putInt(gatewayIpInt)

        // Option 51: IP Address Lease Time (86400s = 24h)
        buf.put(51.toByte()); buf.put(4.toByte()); buf.putInt(86400)

        // Option 1: Subnet Mask (255.255.255.0)
        buf.put(1.toByte()); buf.put(4.toByte()); buf.putInt(0xFFFFFF00.toInt())

        // Option 3: Router / Default Gateway (10.0.2.2)
        buf.put(3.toByte()); buf.put(4.toByte()); buf.putInt(gatewayIpInt)

        // Option 6: Domain Name Server (8.8.8.8)
        buf.put(6.toByte()); buf.put(4.toByte()); buf.putInt(dnsIpInt)

        // Option 255: End
        buf.put(255.toByte())

        return reply
    }
}
