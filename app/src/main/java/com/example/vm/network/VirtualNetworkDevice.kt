package com.example.vm.network

import com.example.vm.devices.VirtualDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VirtualNetworkState(
    val macAddress: String = "52:54:00:12:34:56",
    val networkMode: NetworkMode = NetworkMode.NAT,
    val status: NetworkImplementationStatus = NetworkImplementationStatus.NOT_IMPLEMENTED,
    val isImplemented: Boolean = false,
    val baseMmioAddress: String = "0x0D000000",
    val irqNumber: Int = 5,
    val gatewayIp: String = "192.168.122.1",
    val guestIp: String = "192.168.122.15",
    val subnetMask: String = "255.255.255.0",
    val dnsServer: String = "8.8.8.8",
    val portForwardRules: List<PortForwardRule> = emptyList(),
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val txPackets: Long = 0L,
    val rxPackets: Long = 0L
) {
    val statusDisplay: String
        get() = if (!isImplemented) "Not implemented" else status.label
}

/**
 * VirtualNetworkDevice represents the virtual hardware network adapter
 * mapped into the VM bus memory map (VirtIO-Net MMIO @ 0x0D000000, IRQ 5).
 *
 * Prepared for future:
 *  - NAT (Network Address Translation)
 *  - Host-to-Guest port forwarding (e.g. SSH 2222 -> 22)
 *  - Guest-to-Internet outbound bridging
 */
class VirtualNetworkDevice(
    val backend: NetworkBackend = VirtioNetBackend()
) : VirtualDevice {

    private val _networkState = MutableStateFlow(
        VirtualNetworkState(
            macAddress = backend.macAddress,
            networkMode = backend.networkMode,
            status = backend.status.value,
            isImplemented = backend.isImplemented,
            gatewayIp = backend.gatewayIp,
            guestIp = backend.guestIp,
            subnetMask = backend.subnetMask,
            dnsServer = backend.dnsServer,
            portForwardRules = backend.portForwardRules
        )
    )
    val networkState: StateFlow<VirtualNetworkState> = _networkState.asStateFlow()

    fun configure(
        mode: NetworkMode,
        mac: String = "52:54:00:12:34:56",
        portForwards: List<PortForwardRule> = emptyList()
    ) {
        backend.configure(mode, mac, portForwards)
        updateState()
    }

    fun setLinkUp(up: Boolean) {
        backend.setLinkUp(up)
        updateState()
    }

    private fun updateState() {
        _networkState.value = VirtualNetworkState(
            macAddress = backend.macAddress,
            networkMode = backend.networkMode,
            status = backend.status.value,
            isImplemented = backend.isImplemented,
            gatewayIp = backend.gatewayIp,
            guestIp = backend.guestIp,
            subnetMask = backend.subnetMask,
            dnsServer = backend.dnsServer,
            portForwardRules = backend.portForwardRules,
            txBytes = 0L,
            rxBytes = 0L,
            txPackets = 0L,
            rxPackets = 0L
        )
    }

    override fun getDeviceName(): String = "VirtIO Network Adapter (virtio-net MMIO @ 0x0D000000)"

    override fun getDeviceStatus(): String {
        return "MAC: ${backend.macAddress}, Mode: ${backend.networkMode.label} - Status: ${if (!backend.isImplemented) "Not implemented" else backend.status.value.label}"
    }

    override fun reset() {
        backend.reset()
        updateState()
    }
}
