package com.example.vm.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.example.vm.input.KeyboardInputEvent
import com.example.vm.input.MouseInputEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class RoutedUsbDeviceState(
    val deviceName: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val targetVmId: Long,
    val claimedInterfacesCount: Int,
    val isKeyboard: Boolean = false,
    val isMouse: Boolean = false,
    val keyboardStateSummary: String = "",
    val mouseStateSummary: String = "",
    val totalBytesTransferred: Long = 0L,
    val isAttached: Boolean = true
)

class UsbDeviceRouter(
    private val usbManager: UsbManager
) {
    companion object {
        private const val TAG = "UsbDeviceRouter"
    }

    private data class ActiveDeviceRoute(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val claimedInterfaces: MutableList<UsbInterface>,
        val targetVmId: Long,
        var keyboardDriver: UsbKeyboardDriver? = null,
        var mouseDriver: UsbMouseDriver? = null,
        var bytesTransferred: Long = 0L
    )

    private val activeRoutes = ConcurrentHashMap<String, ActiveDeviceRoute>()

    private val _routedDevices = MutableStateFlow<Map<String, RoutedUsbDeviceState>>(emptyMap())
    val routedDevices: StateFlow<Map<String, RoutedUsbDeviceState>> = _routedDevices.asStateFlow()

    var onKeyboardInputEvent: ((targetVmId: Long, event: KeyboardInputEvent) -> Unit)? = null
    var onKeyboardTerminalBytes: ((targetVmId: Long, bytes: ByteArray) -> Unit)? = null
    var onMouseInputEvent: ((targetVmId: Long, event: MouseInputEvent) -> Unit)? = null

    fun isDeviceRouted(deviceName: String): Boolean {
        return activeRoutes.containsKey(deviceName)
    }

    fun isDeviceRouted(device: UsbDevice): Boolean {
        return activeRoutes.containsKey(device.deviceName)
    }

    fun getRoutedTargetVm(deviceName: String): Long? {
        return activeRoutes[deviceName]?.targetVmId
    }

    /**
     * Explicitly claims and routes a physical USB device to a target virtual machine.
     * Automatically initializes keyboard and mouse hardware drivers when HID interfaces
     * are discovered on claimed endpoints.
     */
    fun routeDeviceToVM(device: UsbDevice, targetVmId: Long): Result<Boolean> {
        val devKey = device.deviceName
        if (activeRoutes.containsKey(devKey)) {
            Log.w(TAG, "Device $devKey is already routed to VM ${activeRoutes[devKey]?.targetVmId}")
            return Result.success(true)
        }

        if (!usbManager.hasPermission(device)) {
            val msg = "Cannot route USB device $devKey without host permission."
            Log.e(TAG, msg)
            return Result.failure(SecurityException(msg))
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            val msg = "Failed to open USB device connection for $devKey (Device may be held exclusively by host driver)."
            Log.e(TAG, msg)
            return Result.failure(IllegalStateException(msg))
        }

        val claimedList = mutableListOf<UsbInterface>()
        var keyboardDriver: UsbKeyboardDriver? = null
        var mouseDriver: UsbMouseDriver? = null

        try {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val claimed = connection.claimInterface(iface, true)
                if (claimed) {
                    claimedList.add(iface)
                    Log.d(TAG, "Claimed USB interface ${iface.id} on device $devKey for VM $targetVmId")

                    // 1. Check if interface is a USB HID keyboard
                    if (keyboardDriver == null && UsbKeyboardDriver.isKeyboardInterface(iface)) {
                        val inEp = UsbKeyboardDriver.findInterruptInEndpoint(iface)
                        if (inEp != null) {
                            Log.i(TAG, "Configuring USB Keyboard driver on $devKey interface ${iface.id}, endpoint ${inEp.endpointNumber}")
                            val driver = UsbKeyboardDriver(device, connection, iface, inEp)
                            driver.onKeyEvent = { keyEv ->
                                onKeyboardInputEvent?.invoke(targetVmId, keyEv)
                            }
                            driver.onTerminalBytes = { bytes ->
                                onKeyboardTerminalBytes?.invoke(targetVmId, bytes)
                            }
                            driver.startPolling()
                            keyboardDriver = driver
                        }
                    }

                    // 2. Check if interface is a USB HID mouse
                    if (mouseDriver == null && UsbMouseDriver.isMouseInterface(iface)) {
                        val inEp = UsbMouseDriver.findInterruptInEndpoint(iface)
                        if (inEp != null) {
                            Log.i(TAG, "Configuring USB Mouse driver on $devKey interface ${iface.id}, endpoint ${inEp.endpointNumber}")
                            val driver = UsbMouseDriver(device, connection, iface, inEp)
                            driver.onMouseEvent = { mouseEv ->
                                onMouseInputEvent?.invoke(targetVmId, mouseEv)
                            }
                            driver.startPolling()
                            mouseDriver = driver
                        }
                    }
                } else {
                    Log.w(TAG, "Could not claim interface ${iface.id} on device $devKey")
                }
            }

            val route = ActiveDeviceRoute(
                device = device,
                connection = connection,
                claimedInterfaces = claimedList,
                targetVmId = targetVmId,
                keyboardDriver = keyboardDriver,
                mouseDriver = mouseDriver
            )
            activeRoutes[devKey] = route
            updateState()

            Log.i(TAG, "Successfully routed USB device $devKey (Interfaces: ${claimedList.size}, Keyboard: ${keyboardDriver != null}, Mouse: ${mouseDriver != null}) to VM $targetVmId")
            return Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error claiming interfaces for $devKey: ${e.message}", e)
            keyboardDriver?.stopPolling()
            mouseDriver?.stopPolling()
            safeReleaseRoute(connection, claimedList)
            return Result.failure(e)
        }
    }

    /**
     * Safely releases all claimed interfaces and closes the hardware connection.
     * Note: Does not interfere with host touch input controllers.
     */
    fun releaseDevice(deviceName: String): Boolean {
        val route = activeRoutes.remove(deviceName) ?: return false
        Log.i(TAG, "Safely releasing USB device $deviceName from VM ${route.targetVmId}")
        route.keyboardDriver?.stopPolling()
        route.mouseDriver?.stopPolling()
        safeReleaseRoute(route.connection, route.claimedInterfaces)
        updateState()
        return true
    }

    /**
     * Handles hardware detachment event by tearing down active route safely.
     * Host touch input remains completely active.
     */
    fun onHardwareDeviceDetached(device: UsbDevice) {
        val devKey = device.deviceName
        if (activeRoutes.containsKey(devKey)) {
            Log.i(TAG, "USB Device $devKey physically disconnected; releasing route cleanly.")
            releaseDevice(devKey)
        }
    }

    /**
     * Releases all active USB device routes upon VM shutdown or app suspension.
     */
    fun releaseAll() {
        Log.i(TAG, "Releasing all active USB routes (${activeRoutes.size} total)")
        for ((_, route) in activeRoutes) {
            route.keyboardDriver?.stopPolling()
            route.mouseDriver?.stopPolling()
            safeReleaseRoute(route.connection, route.claimedInterfaces)
        }
        activeRoutes.clear()
        updateState()
    }

    private fun safeReleaseRoute(connection: UsbDeviceConnection, interfaces: List<UsbInterface>) {
        for (iface in interfaces) {
            try {
                connection.releaseInterface(iface)
                Log.d(TAG, "Released USB interface ${iface.id}")
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing interface ${iface.id}: ${e.message}")
            }
        }
        try {
            connection.close()
            Log.d(TAG, "Closed USB device connection.")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing USB connection: ${e.message}")
        }
    }

    private fun updateState() {
        val snapshot = activeRoutes.mapValues { (k, v) ->
            val isKb = v.keyboardDriver != null
            val isMs = v.mouseDriver != null
            val kbState = v.keyboardDriver?.keyboardState?.value?.lastKeySummary ?: ""
            val msState = v.mouseDriver?.mouseState?.value?.lastEventSummary ?: ""
            RoutedUsbDeviceState(
                deviceName = k,
                deviceId = v.device.deviceId,
                vendorId = v.device.vendorId,
                productId = v.device.productId,
                targetVmId = v.targetVmId,
                claimedInterfacesCount = v.claimedInterfaces.size,
                isKeyboard = isKb,
                isMouse = isMs,
                keyboardStateSummary = kbState,
                mouseStateSummary = msState,
                totalBytesTransferred = v.bytesTransferred,
                isAttached = true
            )
        }
        _routedDevices.value = snapshot
    }
}
