package com.example.vm.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.util.Log
import com.example.vm.input.MouseInputEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * UsbMouseHandler implements the standard UsbDeviceHandler contract for USB Mice.
 * Lifecycle: attach, detach, permission, identification, release, error handling.
 */
class UsbMouseHandler(
    private val permissionManager: UsbPermissionManager
) : UsbDeviceHandler {

    override val category: UsbDeviceCategory = UsbDeviceCategory.MOUSE
    override val handlerName: String = "UsbMouseHandler"

    private val activeDrivers = ConcurrentHashMap<String, UsbMouseDriver>()
    private val activeInterfaces = ConcurrentHashMap<String, UsbInterface>()
    private val activeConnections = ConcurrentHashMap<String, UsbDeviceConnection>()
    private val targetVmMap = ConcurrentHashMap<String, Long>()
    private val lastErrors = ConcurrentHashMap<String, UsbDeviceError>()
    private val attachedDevices = ConcurrentHashMap<String, UsbDeviceIdentification>()

    var onMouseInputEvent: ((targetVmId: Long, event: MouseInputEvent) -> Unit)? = null

    override fun canHandle(identification: UsbDeviceIdentification): Boolean {
        return identification.category == UsbDeviceCategory.MOUSE
    }

    override fun onAttach(device: UsbDevice, identification: UsbDeviceIdentification) {
        attachedDevices[device.deviceName] = identification
        Log.i(handlerName, "Attached USB Mouse: ${identification.displayName} (${device.deviceName})")
    }

    override fun onRequestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        permissionManager.requestPermission(device) { granted ->
            if (!granted) {
                lastErrors[device.deviceName] = UsbDeviceError(
                    category = UsbErrorCategory.PERMISSION_DENIED,
                    deviceName = device.deviceName,
                    message = "Host permission denied for USB Mouse.",
                    suggestedRemedy = "Allow USB permission to route mouse movements and clicks to the VM."
                )
            } else {
                lastErrors.remove(device.deviceName)
            }
            onResult(granted)
        }
    }

    override fun onClaim(device: UsbDevice, connection: UsbDeviceConnection, targetVmId: Long): Result<Unit> {
        val devKey = device.deviceName

        var mouseIface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (UsbMouseDriver.isMouseInterface(iface)) {
                mouseIface = iface
                break
            }
        }

        if (mouseIface == null && device.interfaceCount > 0) {
            mouseIface = device.getInterface(0)
        }

        if (mouseIface == null) {
            val err = UsbDeviceError(
                category = UsbErrorCategory.UNSUPPORTED_DEVICE,
                deviceName = devKey,
                message = "No valid USB HID Mouse interface found on device.",
                suggestedRemedy = "Verify the peripheral supports USB HID mouse protocol."
            )
            lastErrors[devKey] = err
            return Result.failure(IllegalStateException(err.message))
        }

        val inEp = UsbMouseDriver.findInterruptInEndpoint(mouseIface)
        if (inEp == null) {
            val err = UsbDeviceError(
                category = UsbErrorCategory.UNSUPPORTED_DEVICE,
                deviceName = devKey,
                message = "No Interrupt IN endpoint found for mouse polling.",
                suggestedRemedy = "Check USB endpoint configuration."
            )
            lastErrors[devKey] = err
            return Result.failure(IllegalStateException(err.message))
        }

        val claimed = connection.claimInterface(mouseIface, true)
        if (!claimed) {
            val err = UsbDeviceError(
                category = UsbErrorCategory.CLAIM_FAILED,
                deviceName = devKey,
                message = "Failed to claim USB Mouse interface ${mouseIface.id}.",
                suggestedRemedy = "Ensure no other app is holding an exclusive handle to the mouse."
            )
            lastErrors[devKey] = err
            return Result.failure(IllegalStateException(err.message))
        }

        val driver = UsbMouseDriver(device, connection, mouseIface, inEp)
        driver.onMouseEvent = { ev ->
            val vmId = targetVmMap[devKey] ?: targetVmId
            onMouseInputEvent?.invoke(vmId, ev)
        }
        driver.startPolling()

        activeDrivers[devKey] = driver
        activeInterfaces[devKey] = mouseIface
        activeConnections[devKey] = connection
        targetVmMap[devKey] = targetVmId
        lastErrors.remove(devKey)

        Log.i(handlerName, "Successfully claimed and started polling USB Mouse on $devKey for VM $targetVmId")
        return Result.success(Unit)
    }

    override fun onRelease(device: UsbDevice): Result<Unit> {
        val devKey = device.deviceName
        val driver = activeDrivers.remove(devKey)
        driver?.stopPolling()

        val iface = activeInterfaces.remove(devKey)
        val conn = activeConnections.remove(devKey)

        if (iface != null && conn != null) {
            try {
                conn.releaseInterface(iface)
                conn.close()
            } catch (e: Exception) {
                Log.w(handlerName, "Error releasing interface on $devKey: ${e.message}")
            }
        }
        targetVmMap.remove(devKey)
        Log.i(handlerName, "Released USB Mouse on $devKey")
        return Result.success(Unit)
    }

    override fun onDetach(device: UsbDevice) {
        val devKey = device.deviceName
        if (activeDrivers.containsKey(devKey)) {
            lastErrors[devKey] = UsbDeviceError(
                category = UsbErrorCategory.DETACHED_UNEXPECTEDLY,
                deviceName = devKey,
                message = "USB Mouse physically detached.",
                suggestedRemedy = "Reconnect the mouse to resume pointer control."
            )
        }
        onRelease(device)
        attachedDevices.remove(devKey)
        Log.i(handlerName, "Detached USB Mouse $devKey")
    }

    override fun getStatus(deviceName: String): UsbHandlerStatus {
        val driver = activeDrivers[deviceName]
        val iface = activeInterfaces[deviceName]
        val vmId = targetVmMap[deviceName]

        return when {
            driver != null -> UsbHandlerStatus(
                state = UsbHandlerState.STREAMING,
                summary = "Active (${driver.mouseState.value.lastEventSummary.ifEmpty { "Listening" }})",
                targetVmId = vmId,
                activeInterfaceId = iface?.id
            )
            attachedDevices.containsKey(deviceName) -> UsbHandlerStatus(
                state = UsbHandlerState.ATTACHED,
                summary = "Attached and ready to claim",
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
}
