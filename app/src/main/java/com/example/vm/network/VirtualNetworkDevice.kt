package com.example.vm.network

import com.example.vm.devices.VirtualDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VirtualNetworkState(
    val macAddress: String = "52:54:00:12:34:56",
    val networkMode: NetworkMode = NetworkMode.NAT,
    val status: NetworkImplementationStatus = NetworkImplementationStatus.INITIALIZED,
    val isImplemented: Boolean = true,
    val baseMmioAddress: String = "0x0D000000",
    val irqNumber: Int = 5,
    val gatewayIp: String = "10.0.2.2",
    val guestIp: String = "10.0.2.15",
    val subnetMask: String = "255.255.255.0",
    val dnsServer: String = "8.8.8.8",
    val portForwardRules: List<PortForwardRule> = emptyList(),
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val txPackets: Long = 0L,
    val rxPackets: Long = 0L,
    val droppedPackets: Long = 0L
) {
    val statusDisplay: String
        get() = if (!isImplemented) "Not implemented" else status.label
}

/**
 * VirtualNetworkDevice represents the virtual hardware network adapter
 * mapped into the VM bus memory map (VirtIO-Net MMIO @ 0x0D000000, IRQ 5).
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
            portForwardRules = backend.portForwardRules,
            txBytes = backend.txBytes,
            rxBytes = backend.rxBytes,
            txPackets = backend.txPackets,
            rxPackets = backend.rxPackets,
            droppedPackets = backend.droppedPackets
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

    fun updateTelemetry() {
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
            txBytes = backend.txBytes,
            rxBytes = backend.rxBytes,
            txPackets = backend.txPackets,
            rxPackets = backend.rxPackets,
            droppedPackets = backend.droppedPackets
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

    fun shutdown() {
        backend.shutdown()
        updateState()
    }
}
