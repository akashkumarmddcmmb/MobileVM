package com.example.vm.network

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VMNetworkTest {

    private lateinit var backend: VirtioNetBackend

    @Before
    fun setUp() {
        backend = VirtioNetBackend()
        backend.configure(NetworkMode.NAT, "52:54:00:12:34:56")
    }

    @Test
    fun testNicConfigurationAndReset() {
        assertTrue(backend.isLinkUp())
        assertEquals("52:54:00:12:34:56", backend.macAddress)
        assertEquals("10.0.2.2", backend.gatewayIp)
        assertEquals("10.0.2.15", backend.guestIp)

        // Disable interface
        backend.setLinkUp(false)
        assertFalse(backend.isLinkUp())

        // Re-enable interface
        backend.setLinkUp(true)
        assertTrue(backend.isLinkUp())

        backend.reset()
        assertEquals(0L, backend.rxPackets)
    }

    @Test
    fun testArpRequestAndReplySynthesis() {
        val arpReq = ByteArray(42)
        val buf = ByteBuffer.wrap(arpReq).order(ByteOrder.BIG_ENDIAN)

        // Ethernet Header
        for (i in 0..5) buf.put(0xFF.toByte()) // Destination: Broadcast
        buf.put(NetworkPacketUtils.parseMac("52:54:00:12:34:56")) // Source: Guest MAC
        buf.putShort(NetworkPacketUtils.ETHERTYPE_ARP.toShort())

        // ARP Payload
        buf.putShort(1) // HW Type: Ethernet
        buf.putShort(0x0800) // Proto: IPv4
        buf.put(6) // HW Len
        buf.put(4) // Proto Len
        buf.putShort(1) // Opcode: Request
        buf.put(NetworkPacketUtils.parseMac("52:54:00:12:34:56")) // Sender MAC
        buf.putInt(NetworkPacketUtils.parseIp("10.0.2.15")) // Sender IP
        buf.put(ByteArray(6)) // Target MAC (zero)
        buf.putInt(NetworkPacketUtils.parseIp("10.0.2.2")) // Target IP: Gateway

        val transmitted = backend.transmitPacket(arpReq)
        assertTrue(transmitted)

        val reply = backend.receivePacket()
        assertNotNull(reply)
        assertTrue(reply!!.size >= 42)

        val replyBuf = ByteBuffer.wrap(reply).order(ByteOrder.BIG_ENDIAN)
        val etherType = replyBuf.getShort(12).toInt() and 0xFFFF
        val opCode = replyBuf.getShort(20).toInt() and 0xFFFF
        val senderIp = replyBuf.getInt(28)

        assertEquals(NetworkPacketUtils.ETHERTYPE_ARP, etherType)
        assertEquals(2, opCode) // ARP Reply
        assertEquals(NetworkPacketUtils.parseIp("10.0.2.2"), senderIp) // Gateway IP
    }

    @Test
    fun testDhcpOfferSynthesis() {
        val dhcpDiscover = ByteArray(300)
        val buf = ByteBuffer.wrap(dhcpDiscover).order(ByteOrder.BIG_ENDIAN)

        // Ethernet Header
        for (i in 0..5) buf.put(0xFF.toByte())
        buf.put(NetworkPacketUtils.parseMac("52:54:00:12:34:56"))
        buf.putShort(NetworkPacketUtils.ETHERTYPE_IPv4.toShort())

        // IP Header
        buf.put(0x45.toByte())
        buf.put(0.toByte())
        buf.putShort(286)
        buf.putShort(0)
        buf.putShort(0)
        buf.put(64.toByte())
        buf.put(NetworkPacketUtils.PROTOCOL_UDP.toByte())
        buf.putShort(0)
        buf.putInt(0) // 0.0.0.0
        buf.putInt(0xFFFFFFFF.toInt()) // 255.255.255.255

        // UDP Header
        buf.putShort(68) // Src: 68
        buf.putShort(67) // Dst: 67
        buf.putShort(266)
        buf.putShort(0)

        // BOOTP Header
        buf.put(1.toByte()) // BOOTREQUEST
        buf.put(1.toByte())
        buf.put(6.toByte())
        buf.put(0.toByte())
        buf.putInt(0x12345678) // XID

        // Option 53 = 1 (DHCP DISCOVER) at optionPos 282
        buf.position(282)
        buf.put(53.toByte())
        buf.put(1.toByte())
        buf.put(1.toByte()) // DISCOVER

        val transmitted = backend.transmitPacket(dhcpDiscover)
        assertTrue(transmitted)

        val offer = backend.receivePacket()
        assertNotNull(offer)
        assertTrue(offer!!.size >= 300)

        val offerBuf = ByteBuffer.wrap(offer).order(ByteOrder.BIG_ENDIAN)
        val assignedIp = offerBuf.getInt(58) // yiaddr
        assertEquals(NetworkPacketUtils.parseIp("10.0.2.15"), assignedIp)
    }

    @Test
    fun testPacketTelemetryAndBoundsChecking() {
        assertEquals(0L, backend.txPackets)
        assertEquals(0L, backend.rxPackets)

        // Transmit undersized frame (< 14 bytes) -> should be dropped
        val undersized = ByteArray(10)
        val result = backend.transmitPacket(undersized)
        assertFalse(result)
        assertEquals(1L, backend.droppedPackets)
    }
}
