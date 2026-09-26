package com.example.vm.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    INITIALIZED("Initialized (Pending Tap Driver)", false),
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
}

/**
 * VirtioNetBackend provides the real networking backend abstraction.
 *
 * Current Phase Status: "Not implemented".
 * Does NOT generate fake packet counters or artificial traffic.
 * Prepared for future User-Mode TCP/IP stack (SLIRP) or Android VpnService/TUN TAP backend.
 */
class VirtioNetBackend(
    override var macAddress: String = "52:54:00:12:34:56",
    override var networkMode: NetworkMode = NetworkMode.NAT
) : NetworkBackend {

    private val _status = MutableStateFlow(NetworkImplementationStatus.NOT_IMPLEMENTED)
    override val status: StateFlow<NetworkImplementationStatus> = _status.asStateFlow()

    override val isImplemented: Boolean = false

    override var gatewayIp: String = "192.168.122.1"
        private set

    override var guestIp: String = "192.168.122.15"
        private set

    override var subnetMask: String = "255.255.255.0"
        private set

    override var dnsServer: String = "8.8.8.8"
        private set

    private val _portForwardRules = mutableListOf<PortForwardRule>()
    override val portForwardRules: List<PortForwardRule> get() = _portForwardRules.toList()

    private var linkUp = false

    override fun configure(mode: NetworkMode, mac: String, portForwards: List<PortForwardRule>) {
        this.networkMode = mode
        this.macAddress = mac
        this._portForwardRules.clear()
        this._portForwardRules.addAll(portForwards)
        if (mode == NetworkMode.DISABLED) {
            _status.value = NetworkImplementationStatus.DISCONNECTED
        } else {
            // Clearly marked as Not implemented in this phase
            _status.value = NetworkImplementationStatus.NOT_IMPLEMENTED
        }
    }

    override fun setLinkUp(up: Boolean) {
        linkUp = up
        if (!up) {
            _status.value = NetworkImplementationStatus.DISCONNECTED
        } else if (!isImplemented) {
            _status.value = NetworkImplementationStatus.NOT_IMPLEMENTED
        }
    }

    override fun isLinkUp(): Boolean = linkUp && isImplemented

    override fun transmitPacket(packet: ByteArray): Boolean {
        // Real packet transmission is not implemented yet
        return false
    }

    override fun receivePacket(): ByteArray? {
        // Real packet reception is not implemented yet
        return null
    }

    override fun reset() {
        linkUp = false
        _status.value = NetworkImplementationStatus.NOT_IMPLEMENTED
    }
}
