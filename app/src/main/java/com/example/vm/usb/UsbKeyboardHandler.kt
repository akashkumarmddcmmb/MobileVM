package com.example.vm.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.util.Log
import com.example.vm.input.KeyboardInputEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * UsbKeyboardHandler implements the standard UsbDeviceHandler contract for USB Keyboards.
 * Lifecycle: attach, detach, permission, identification, release, error handling.
 */
class UsbKeyboardHandler(
    private val permissionManager: UsbPermissionManager
) : UsbDeviceHandler {

    override val category: UsbDeviceCategory = UsbDeviceCategory.KEYBOARD
    override val handlerName: String = "UsbKeyboardHandler"

    private val activeDrivers = ConcurrentHashMap<String, UsbKeyboardDriver>()
    private val activeInterfaces = ConcurrentHashMap<String, UsbInterface>()
    private val activeConnections = ConcurrentHashMap<String, UsbDeviceConnection>()
    private val targetVmMap = ConcurrentHashMap<String, Long>()
    private val lastErrors = ConcurrentHashMap<String, UsbDeviceError>()
    private val attachedDevices = ConcurrentHashMap<String, UsbDeviceIdentification>()

    var onKeyboardInputEvent: ((targetVmId: Long, event: KeyboardInputEvent) -> Unit)? = null
    var onKeyboardTerminalBytes: ((targetVmId: Long, bytes: ByteArray) -> Unit)? = null

    override fun canHandle(identification: UsbDeviceIdentification): Boolean {
        return identification.category == UsbDeviceCategory.KEYBOARD
    }

    override fun onAttach(device: UsbDevice, identification: UsbDeviceIdentification) {
        attachedDevices[device.deviceName] = identification
        Log.i(handlerName, "Attached USB Keyboard: ${identification.displayName} (${device.deviceName})")
    }

    override fun onRequestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        permissionManager.requestPermission(device) { granted ->
            if (!granted) {
                lastErrors[device.deviceName] = UsbDeviceError(
                    category = UsbErrorCategory.PERMISSION_DENIED,
                    deviceName = device.deviceName,
                    message = "User or system denied host permission to access USB Keyboard.",
                    suggestedRemedy = "Grant USB permission in the prompt to allow keyboard input into the VM."
                )
            } else {
                lastErrors.remove(device.deviceName)
            }
            onResult(granted)
        }
    }

    override fun onClaim(device: UsbDevice, connection: UsbDeviceConnection, targetVmId: Long): Result<Unit> {
        val devKey = device.deviceName

        // Find keyboard interface & interrupt endpoint
        var kbIface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (UsbKeyboardDriver.isKeyboardInterface(iface)) {
                kbIface = iface
                break
            }
        }

        if (kbIface == null && device.interfaceCount > 0) {
            kbIface = device.getInterface(0)
        }

        if (kbIface == null) {
            val err = UsbDeviceError(
                category = UsbErrorCategory.UNSUPPORTED_DEVICE,
                deviceName = devKey,
                message = "No valid USB HID Keyboard interface found on device.",
                suggestedRemedy = "Verify the connected peripheral supports USB HID boot or report protocol."
            )
            lastErrors[devKey] = err
            return Result.failure(IllegalStateException(err.message))
        }

        val inEp = UsbKeyboardDriver.findInterruptInEndpoint(kbIface)
        if (inEp == null) {
            val err = UsbDeviceError(
                category = UsbErrorCategory.UNSUPPORTED_DEVICE,
                deviceName = devKey,
                message = "No Interrupt IN endpoint located for keyboard polling.",
                suggestedRemedy = "Check device interface descriptors for endpoint type INTERRUPT IN."
            )
            lastErrors[devKey] = err
            return Result.failure(IllegalStateException(err.message))
        }

        val claimed = connection.claimInterface(kbIface, true)
        if (!claimed) {
            val err = UsbDeviceError(
                category = UsbErrorCategory.CLAIM_FAILED,
                deviceName = devKey,
                message = "Failed to claim USB Keyboard interface ${kbIface.id} (may be held by host OS).",
                suggestedRemedy = "Reconnect keyboard or check if another host application has exclusive lock."
            )
            lastErrors[devKey] = err
            return Result.failure(IllegalStateException(err.message))
        }

        val driver = UsbKeyboardDriver(device, connection, kbIface, inEp)
        driver.onKeyEvent = { ev ->
            val vmId = targetVmMap[devKey] ?: targetVmId
            onKeyboardInputEvent?.invoke(vmId, ev)
        }
        driver.onTerminalBytes = { bytes ->
            val vmId = targetVmMap[devKey] ?: targetVmId
            onKeyboardTerminalBytes?.invoke(vmId, bytes)
        }
        driver.startPolling()

        activeDrivers[devKey] = driver
        activeInterfaces[devKey] = kbIface
        activeConnections[devKey] = connection
        targetVmMap[devKey] = targetVmId
        lastErrors.remove(devKey)

        Log.i(handlerName, "Successfully claimed and started polling USB Keyboard on $devKey for VM $targetVmId")
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
        Log.i(handlerName, "Released USB Keyboard on $devKey")
        return Result.success(Unit)
    }

    override fun onDetach(device: UsbDevice) {
        val devKey = device.deviceName
        if (activeDrivers.containsKey(devKey)) {
            lastErrors[devKey] = UsbDeviceError(
                category = UsbErrorCategory.DETACHED_UNEXPECTEDLY,
                deviceName = devKey,
                message = "USB Keyboard was physically detached while claimed by VM.",
                suggestedRemedy = "Reconnect the USB keyboard to resume typing."
            )
        }
        onRelease(device)
        attachedDevices.remove(devKey)
        Log.i(handlerName, "Detached USB Keyboard $devKey")
    }

    override fun getStatus(deviceName: String): UsbHandlerStatus {
        val driver = activeDrivers[deviceName]
        val iface = activeInterfaces[deviceName]
        val vmId = targetVmMap[deviceName]

        return when {
            driver != null -> UsbHandlerStatus(
                state = UsbHandlerState.STREAMING,
                summary = "Active (${driver.keyboardState.value.lastKeySummary.ifEmpty { "Listening" }})",
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
