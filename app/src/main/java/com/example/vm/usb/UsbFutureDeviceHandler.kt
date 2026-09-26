package com.example.vm.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * UsbFutureDeviceHandler implements the standard UsbDeviceHandler contract for
 * future supported or specialized USB devices (e.g. Audio interfaces, USB Ethernet,
 * Video/UVC cameras, Vendor-specific hardware).
 * Provides an extensible registry where new device drivers can be registered dynamically.
 * Lifecycle: attach, detach, permission, identification, release, error handling.
 */
class UsbFutureDeviceHandler(
    private val permissionManager: UsbPermissionManager
) : UsbDeviceHandler {

    override val category: UsbDeviceCategory = UsbDeviceCategory.FUTURE_SUPPORTED
    override val handlerName: String = "UsbFutureDeviceHandler"

    private val attachedDevices = ConcurrentHashMap<String, UsbDeviceIdentification>()
    private val activeConnections = ConcurrentHashMap<String, UsbDeviceConnection>()
    private val activeInterfaces = ConcurrentHashMap<String, MutableList<UsbInterface>>()
    private val targetVmMap = ConcurrentHashMap<String, Long>()
    private val lastErrors = ConcurrentHashMap<String, UsbDeviceError>()

    // Extensible driver hooks for custom plugins/extensions
    private val customDriverExtensions = ConcurrentHashMap<String, (UsbDevice, UsbDeviceConnection) -> Result<Unit>>()

    override fun canHandle(identification: UsbDeviceIdentification): Boolean {
        // Fallback for any device category not handled by specific hardware drivers
        return identification.category == UsbDeviceCategory.FUTURE_SUPPORTED
    }

    override fun onAttach(device: UsbDevice, identification: UsbDeviceIdentification) {
        attachedDevices[device.deviceName] = identification
        Log.i(handlerName, "Attached generic/future USB device: ${identification.displayName} (${device.deviceName})")
    }

    override fun onRequestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        permissionManager.requestPermission(device) { granted ->
            if (!granted) {
                lastErrors[device.deviceName] = UsbDeviceError(
                    category = UsbErrorCategory.PERMISSION_DENIED,
                    deviceName = device.deviceName,
                    message = "Host permission denied for USB peripheral (${device.deviceName}).",
                    suggestedRemedy = "Allow USB permission in the prompt to route device to the VM."
                )
            } else {
                lastErrors.remove(device.deviceName)
            }
            onResult(granted)
        }
    }

    override fun onClaim(device: UsbDevice, connection: UsbDeviceConnection, targetVmId: Long): Result<Unit> {
        val devKey = device.deviceName
        val claimedList = mutableListOf<UsbInterface>()

        try {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val claimed = connection.claimInterface(iface, true)
                if (claimed) {
                    claimedList.add(iface)
                    Log.d(handlerName, "Claimed interface ${iface.id} on $devKey")
                }
            }

            if (claimedList.isEmpty() && device.interfaceCount > 0) {
                val err = UsbDeviceError(
                    category = UsbErrorCategory.CLAIM_FAILED,
                    deviceName = devKey,
                    message = "Could not claim any interface on device $devKey (held by host OS driver).",
                    suggestedRemedy = "Check if host kernel driver has exclusive lock on this peripheral."
                )
                lastErrors[devKey] = err
                return Result.failure(IllegalStateException(err.message))
            }

            activeConnections[devKey] = connection
            activeInterfaces[devKey] = claimedList
            targetVmMap[devKey] = targetVmId
            lastErrors.remove(devKey)

            // Trigger any custom extension hook if registered
            val extension = customDriverExtensions[devKey]
            extension?.invoke(device, connection)

            Log.i(handlerName, "Claimed ${claimedList.size} interfaces on future/specialized device $devKey for VM $targetVmId")
            return Result.success(Unit)
        } catch (e: Exception) {
            val err = UsbDeviceError(
                category = UsbErrorCategory.CLAIM_FAILED,
                deviceName = devKey,
                message = "Exception claiming device: ${e.message}",
                suggestedRemedy = "Verify USB hardware connection and host support."
            )
            lastErrors[devKey] = err
            return Result.failure(e)
        }
    }

    override fun onRelease(device: UsbDevice): Result<Unit> {
        val devKey = device.deviceName
        val ifaces = activeInterfaces.remove(devKey)
        val conn = activeConnections.remove(devKey)

        if (conn != null && ifaces != null) {
            for (iface in ifaces) {
                try {
                    conn.releaseInterface(iface)
                } catch (e: Exception) {
                    Log.w(handlerName, "Error releasing interface ${iface.id} on $devKey: ${e.message}")
                }
            }
            try {
                conn.close()
            } catch (e: Exception) {
                Log.w(handlerName, "Error closing connection on $devKey: ${e.message}")
            }
        }
        targetVmMap.remove(devKey)
        Log.i(handlerName, "Released future/specialized device $devKey")
        return Result.success(Unit)
    }

    override fun onDetach(device: UsbDevice) {
        val devKey = device.deviceName
        if (targetVmMap.containsKey(devKey)) {
            lastErrors[devKey] = UsbDeviceError(
                category = UsbErrorCategory.DETACHED_UNEXPECTEDLY,
                deviceName = devKey,
                message = "USB peripheral was disconnected while routed to VM.",
                suggestedRemedy = "Reconnect peripheral if needed."
            )
        }
        onRelease(device)
        attachedDevices.remove(devKey)
        Log.i(handlerName, "Detached device $devKey")
    }

    override fun getStatus(deviceName: String): UsbHandlerStatus {
        val claimed = activeInterfaces[deviceName]
        val vmId = targetVmMap[deviceName]

        return when {
            claimed != null && claimed.isNotEmpty() -> UsbHandlerStatus(
                state = UsbHandlerState.CLAIMED,
                summary = "Active (${claimed.size} interfaces claimed)",
                targetVmId = vmId
            )
            attachedDevices.containsKey(deviceName) -> UsbHandlerStatus(
                state = UsbHandlerState.ATTACHED,
                summary = "Detected (${attachedDevices[deviceName]?.className})",
                targetVmId = null
            )
            else -> UsbHandlerStatus(
                state = UsbHandlerState.IDLE,
                summary = "Not active"
            )
        }
    }

    override fun getLastError(deviceName: String): UsbDeviceError? = lastErrors[deviceName]

    override fun clearError(deviceName: String) {
        lastErrors.remove(deviceName)
    }

    /**
     * Registers a custom extension hook for future hardware integrations.
     */
    fun registerDriverExtension(deviceName: String, extension: (UsbDevice, UsbDeviceConnection) -> Result<Unit>) {
        customDriverExtensions[deviceName] = extension
    }
}
