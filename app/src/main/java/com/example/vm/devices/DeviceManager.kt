package com.example.vm.devices

import com.example.vm.console.ConsoleBackend
import com.example.vm.display.DisplayBackend
import com.example.vm.input.VirtualInputDevice
import com.example.vm.network.NetworkDevice

class DeviceManager(
    val displayDevice: DisplayBackend,
    val serialConsole: ConsoleBackend,
    val networkDevice: NetworkDevice,
    val inputDevice: VirtualInputDevice = VirtualInputDevice(1024, 768)
) {
    private val devices = mutableMapOf<String, VirtualDevice>()
    var onPowerAction: ((Int) -> Unit)? = null

    init {
        registerDevice("display_0", displayDevice)
        registerDevice("serial_0", serialConsole)
        registerDevice("net_0", networkDevice)
        registerDevice("input_0", inputDevice)
    }

    fun registerDevice(id: String, device: VirtualDevice) {
        devices[id] = device
    }

    fun getDevice(id: String): VirtualDevice? = devices[id]

    fun getAllDevices(): Map<String, VirtualDevice> = devices

    fun powerControllerWrite(powerCode: Int) {
        onPowerAction?.invoke(powerCode)
    }

    fun resetAll() {
        devices.values.forEach { it.reset() }
    }
}

interface VirtualDevice {
    fun getDeviceName(): String
    fun getDeviceStatus(): String
    fun reset()
}
