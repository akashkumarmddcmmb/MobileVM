package com.example.vm.network

import com.example.vm.devices.VirtualDevice

/**
 * Legacy NetworkDevice interface, wrapping the real VirtualNetworkDevice.
 */
interface NetworkDevice : VirtualDevice {
    val isImplemented: Boolean
    fun configureMac(macAddress: String)
    fun setInterfaceUp(up: Boolean)
    fun isConnected(): Boolean
    fun getTxBytes(): Long
    fun getRxBytes(): Long
}

class VirtualEthernetDevice(
    val virtualNetworkDevice: VirtualNetworkDevice = VirtualNetworkDevice()
) : NetworkDevice {

    override val isImplemented: Boolean
        get() = virtualNetworkDevice.backend.isImplemented

    override fun configureMac(macAddress: String) {
        virtualNetworkDevice.configure(virtualNetworkDevice.backend.networkMode, macAddress)
    }

    override fun setInterfaceUp(up: Boolean) {
        virtualNetworkDevice.setLinkUp(up)
    }

    override fun isConnected(): Boolean = virtualNetworkDevice.backend.isLinkUp()

    override fun getTxBytes(): Long = virtualNetworkDevice.backend.txBytes

    override fun getRxBytes(): Long = virtualNetworkDevice.backend.rxBytes

    override fun getDeviceName(): String = virtualNetworkDevice.getDeviceName()

    override fun getDeviceStatus(): String = virtualNetworkDevice.getDeviceStatus()

    override fun reset() {
        virtualNetworkDevice.reset()
    }
}
