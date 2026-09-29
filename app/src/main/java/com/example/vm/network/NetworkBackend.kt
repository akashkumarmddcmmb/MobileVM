package com.example.vm.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Networking modes supported by the VM networking abstraction.
 */
enum class NetworkMode(val label: String, val description: String) {
    NAT(
        label = "NAT (Network Address Translation)",
        description = "Guest can access external internet through host socket bridge; isolated from LAN."
    ),
    HOST_ONLY(
        label = "Host-to-Guest Private",
        description = "Direct private network link between Android host and guest VM only."
    ),
    GUEST_TO_INTERNET(
        label = "Guest-to-Internet Direct",
        description = "Outbound internet access for guest package management and downloads."
    ),
    DISABLED(
        label = "Disabled / Isolated",
        description = "No network adapter attached (air-gapped VM)."
    )
}

/**
 * Real implementation status of the networking subsystem.
 */
enum class NetworkImplementationStatus(val label: String, val isFunctional: Boolean) {
    NOT_IMPLEMENTED("Not implemented", false),
    INITIALIZED("Initialized (User-mode SLIRP)", true),
    CONNECTED("Connected", true),
    DISCONNECTED("Link Down", false),
    ERROR("Driver Error", false)
}

/**
 * Port forwarding rule definition for host-to-guest communication (e.g., SSH, HTTP).
 */
data class PortForwardRule(
    val protocol: String = "TCP",
    val hostPort: Int,
    val guestPort: Int,
    val description: String = ""
)

/**
 * NetworkBackend defines the contract for virtual network packet delivery,
 * NAT routing, and port forwarding between the guest VM and Android host/internet.
 */
interface NetworkBackend {
    val macAddress: String
    val networkMode: NetworkMode
    val status: StateFlow<NetworkImplementationStatus>
    val isImplemented: Boolean
    val gatewayIp: String
    val guestIp: String
    val subnetMask: String
    val dnsServer: String
    val portForwardRules: List<PortForwardRule>

    val txBytes: Long
    val rxBytes: Long
    val txPackets: Long
    val rxPackets: Long
    val droppedPackets: Long

    fun configure(
        mode: NetworkMode,
        mac: String = "52:54:00:12:34:56",
        portForwards: List<PortForwardRule> = emptyList()
    )
    fun setLinkUp(up: Boolean)
    fun isLinkUp(): Boolean
    fun transmitPacket(packet: ByteArray): Boolean
    fun receivePacket(): ByteArray?
    fun reset()
    fun shutdown()
}

/**
 * VirtioNetBackend provides a real user-mode SLIRP / ARP / DHCP / DNS / NAT packet engine.
 *
 * Implements:
 * - Real ARP reply synthesis for guest queries targeting Gateway 10.0.2.2
 * - Real DHCP Offer/ACK responses for guest automatic IPv4 address configuration (10.0.2.15)
 * - Real DNS resolver intercepting UDP port 53 and resolving internet names via Android host
 * - Real User-Mode NAT SLIRP engine for outbound UDP and ICMP echo packets
 * - Thread-safe bounded packet queueing with zero fake traffic.
 */
class VirtioNetBackend(
    override var macAddress: String = "52:54:00:12:34:56",
    override var networkMode: NetworkMode = NetworkMode.NAT
) : NetworkBackend {

    companion object {
        const val GATEWAY_MAC = "52:54:00:12:34:02"
        const val ETHERTYPE_ARP = 0x0806
        const val ETHERTYPE_IP  = 0x0800
        const val MAX_QUEUE_CAPACITY = 1024
    }

    private val _status = MutableStateFlow(NetworkImplementationStatus.INITIALIZED)
    override val status: StateFlow<NetworkImplementationStatus> = _status.asStateFlow()

    override val isImplemented: Boolean = true

    override var gatewayIp: String = "10.0.2.2"
        private set

    override var guestIp: String = "10.0.2.15"
        private set

    override var subnetMask: String = "255.255.255.0"
        private set

    override var dnsServer: String = "8.8.8.8"
        private set

    private val _portForwardRules = mutableListOf<PortForwardRule>()
    override val portForwardRules: List<PortForwardRule> get() = _portForwardRules.toList()

    private val rxQueue = ConcurrentLinkedQueue<ByteArray>()
    private var linkUp = true

    // Real telemetry counters
    private val txBytesCounter = AtomicLong(0L)
    private val rxBytesCounter = AtomicLong(0L)
    private val txPacketsCounter = AtomicLong(0L)
    private val rxPacketsCounter = AtomicLong(0L)
    private val droppedPacketsCounter = AtomicLong(0L)

    override val txBytes: Long get() = txBytesCounter.get()
    override val rxBytes: Long get() = rxBytesCounter.get()
    override val txPackets: Long get() = txPacketsCounter.get()
    override val rxPackets: Long get() = rxPacketsCounter.get()
    override val droppedPackets: Long get() = droppedPacketsCounter.get()

    // Subsystem engines
    private val arpEngine = ArpEngine(gatewayMac = GATEWAY_MAC, gatewayIpStr = gatewayIp, dnsIpStr = "10.0.2.3")
    private val dhcpServer = DhcpServer(gatewayMac = GATEWAY_MAC, gatewayIpStr = gatewayIp, guestIpStr = guestIp, dnsIpStr = dnsServer)
    private val dnsEngine = DnsEngine(gatewayMacStr = GATEWAY_MAC, guestMacStr = macAddress, gatewayIpStr = gatewayIp, guestIpStr = guestIp)
    private val natEngine = NatEngine(gatewayMacStr = GATEWAY_MAC, guestMacStr = macAddress, gatewayIpStr = gatewayIp, guestIpStr = guestIp)

    override fun configure(mode: NetworkMode, mac: String, portForwards: List<PortForwardRule>) {
        this.networkMode = mode
        this.macAddress = mac
        this._portForwardRules.clear()
        this._portForwardRules.addAll(portForwards)

        if (mode == NetworkMode.DISABLED) {
            linkUp = false
            _status.value = NetworkImplementationStatus.DISCONNECTED
            rxQueue.clear()
        } else {
            linkUp = true
            _status.value = NetworkImplementationStatus.CONNECTED
        }
    }

    override fun setLinkUp(up: Boolean) {
        linkUp = up
        _status.value = if (up) NetworkImplementationStatus.CONNECTED else NetworkImplementationStatus.DISCONNECTED
        if (!up) rxQueue.clear()
    }

    override fun isLinkUp(): Boolean = linkUp && networkMode != NetworkMode.DISABLED

    override fun transmitPacket(packet: ByteArray): Boolean {
        if (!isLinkUp() || packet.size < NetworkPacketUtils.MIN_ETHERNET_FRAME_SIZE || packet.size > NetworkPacketUtils.MAX_ETHERNET_FRAME_SIZE) {
            droppedPacketsCounter.incrementAndGet()
            return false
        }

        txBytesCounter.addAndGet(packet.size.toLong())
        txPacketsCounter.incrementAndGet()

        val buf = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val etherType = buf.getShort(12).toInt() and 0xFFFF

        // 1. Check for ARP Frame
        if (etherType == ETHERTYPE_ARP && packet.size >= 42) {
            val arpReply = arpEngine.processArpFrame(packet)
            if (arpReply != null) {
                enqueueRxPacket(arpReply)
            }
            return true
        }

        // 2. Check for IPv4 Frame
        if (etherType == ETHERTYPE_IP && packet.size >= 34) {
            val protocol = buf.get(23).toInt() and 0xFF

            // Check for DHCP (UDP Port 67)
            if (protocol == NetworkPacketUtils.PROTOCOL_UDP && packet.size >= 42) {
                val destPort = buf.getShort(36).toInt() and 0xFFFF
                if (destPort == 67) {
                    val dhcpReply = dhcpServer.processDhcpFrame(packet, macAddress)
                    if (dhcpReply != null) {
                        enqueueRxPacket(dhcpReply)
                    }
                    return true
                }

                // Check for DNS (UDP Port 53)
                if (destPort == 53) {
                    val handled = dnsEngine.processDnsQuery(packet) { dnsReply ->
                        enqueueRxPacket(dnsReply)
                    }
                    if (handled) return true
                }
            }

            // Check for Outbound NAT SLIRP Routing (UDP / ICMP)
            val natHandled = natEngine.processOutboundIpPacket(packet) { natReply ->
                enqueueRxPacket(natReply)
            }
            if (natHandled) return true
        }

        return true
    }

    private fun enqueueRxPacket(packet: ByteArray) {
        if (rxQueue.size >= MAX_QUEUE_CAPACITY) {
            droppedPacketsCounter.incrementAndGet()
            return
        }
        rxQueue.add(packet)
    }

    override fun receivePacket(): ByteArray? {
        if (!isLinkUp()) return null
        val packet = rxQueue.poll() ?: return null
        rxBytesCounter.addAndGet(packet.size.toLong())
        rxPacketsCounter.incrementAndGet()
        return packet
    }

    override fun reset() {
        rxQueue.clear()
        arpEngine.clear()
        _status.value = NetworkImplementationStatus.INITIALIZED
    }

    override fun shutdown() {
        reset()
        dnsEngine.shutdown()
        natEngine.shutdown()
        _status.value = NetworkImplementationStatus.DISCONNECTED
    }
}
