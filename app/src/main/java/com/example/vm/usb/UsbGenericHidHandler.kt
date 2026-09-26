package com.example.vm.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * UsbGenericHidHandler implements the standard UsbDeviceHandler contract for Generic HID peripherals
 * (e.g. Gamepads, Joysticks, Barcode Scanners, Digitizers, Custom Controllers).
 * Lifecycle: attach, detach, permission, identification, release, error handling.
 */
class UsbGenericHidHandler(
    private val permissionManager: UsbPermissionManager
) : UsbDeviceHandler {

    override val category: UsbDeviceCategory = UsbDeviceCategory.GENERIC_HID
    override val handlerName: String = "UsbGenericHidHandler"

    private val scope = CoroutineScope(Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeInterfaces = ConcurrentHashMap<String, UsbInterface>()
    private val activeConnections = ConcurrentHashMap<String, UsbDeviceConnection>()
    private val targetVmMap = ConcurrentHashMap<String, Long>()
    private val lastErrors = ConcurrentHashMap<String, UsbDeviceError>()
    private val attachedDevices = ConcurrentHashMap<String, UsbDeviceIdentification>()
    private val bytesTransferred = ConcurrentHashMap<String, Long>()

    private val _genericHidReports = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val genericHidReports: StateFlow<Map<String, ByteArray>> = _genericHidReports.asStateFlow()

    var onGenericHidReport: ((targetVmId: Long, report: ByteArray) -> Unit)? = null

    override fun canHandle(identification: UsbDeviceIdentification): Boolean {
        return identification.category == UsbDeviceCategory.GENERIC_HID
    }

    override fun onAttach(device: UsbDevice, identification: UsbDeviceIdentification) {
        attachedDevices[device.deviceName] = identification
        Log.i(handlerName, "Attached Generic HID device: ${identification.displayName} (${device.deviceName})")
    }

    override fun onRequestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        permissionManager.requestPermission(device) { granted ->
            if (!granted) {
                lastErrors[device.deviceName] = UsbDeviceError(
                    category = UsbErrorCategory.PERMISSION_DENIED,
                    deviceName = device.deviceName,
                    message = "Host permission denied for Generic HID device.",
                    suggestedRemedy = "Allow USB permission to route HID input reports to the VM."
                )
            } else {
                lastErrors.remove(device.deviceName)
            }
            onResult(granted)
        }
    }

    override fun onClaim(device: UsbDevice, connection: UsbDeviceConnection, targetVmId: Long): Result<Unit> {
        val devKey = device.deviceName

        // Find HID interface
        var hidIface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_HID) {
                hidIface = iface
                break
            }
        }

        if (hidIface == null && device.interfaceCount > 0) {
            hidIface = device.getInterface(0)
        }

        if (hidIface == null) {
            val err = UsbDeviceError(
                category = UsbErrorCategory.UNSUPPORTED_DEVICE,
                deviceName = devKey,
                message = "No HID interface found on device.",
                suggestedRemedy = "Verify the peripheral declares USB Class 3 (HID)."
            )
            lastErrors[devKey] = err
            return Result.failure(IllegalStateException(err.message))
        }

        // Find Interrupt IN endpoint
        var inEp: UsbEndpoint? = null
        for (i in 0 until hidIface.endpointCount) {
            val ep = hidIface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN) {
                inEp = ep
                break
            }
        }

        if (inEp == null) {
            val err = UsbDeviceError(
                category = UsbErrorCategory.UNSUPPORTED_DEVICE,
                deviceName = devKey,
                message = "No Interrupt IN endpoint located for HID polling.",
                suggestedRemedy = "Verify device endpoint descriptors."
            )
            lastErrors[devKey] = err
            return Result.failure(IllegalStateException(err.message))
        }

        val claimed = connection.claimInterface(hidIface, true)
        if (!claimed) {
            val err = UsbDeviceError(
                category = UsbErrorCategory.CLAIM_FAILED,
                deviceName = devKey,
                message = "Failed to claim Generic HID interface ${hidIface.id}.",
                suggestedRemedy = "Ensure the device is not held by another process."
            )
            lastErrors[devKey] = err
            return Result.failure(IllegalStateException(err.message))
        }

        activeInterfaces[devKey] = hidIface
        activeConnections[devKey] = connection
        targetVmMap[devKey] = targetVmId
        lastErrors.remove(devKey)
        bytesTransferred[devKey] = 0L

        val bufferSize = inEp.maxPacketSize.coerceAtLeast(8)
        val job = scope.launch {
            val buf = ByteArray(bufferSize)
            Log.i(handlerName, "Starting Generic HID polling on $devKey, endpoint ${inEp.endpointNumber}")
            try {
                while (isActive) {
                    val bytesRead = connection.bulkTransfer(inEp, buf, buf.size, 100)
                    if (bytesRead > 0) {
                        val currentTotal = bytesTransferred[devKey] ?: 0L
                        bytesTransferred[devKey] = currentTotal + bytesRead
                        val report = buf.copyOf(bytesRead)
                        _genericHidReports.value = _genericHidReports.value + (devKey to report)
                        onGenericHidReport?.invoke(targetVmId, report)
                    }
                }
            } catch (e: Exception) {
                Log.e(handlerName, "Error in Generic HID polling for $devKey: ${e.message}")
                lastErrors[devKey] = UsbDeviceError(
                    category = UsbErrorCategory.IO_ERROR,
                    deviceName = devKey,
                    message = "I/O error during HID transfer: ${e.message}",
                    suggestedRemedy = "Check physical USB cable connection."
                )
            }
        }

        activeJobs[devKey] = job
        Log.i(handlerName, "Successfully claimed Generic HID on $devKey for VM $targetVmId")
        return Result.success(Unit)
    }

    override fun onRelease(device: UsbDevice): Result<Unit> {
        val devKey = device.deviceName
        activeJobs.remove(devKey)?.cancel()

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
        Log.i(handlerName, "Released Generic HID on $devKey")
        return Result.success(Unit)
    }

    override fun onDetach(device: UsbDevice) {
        val devKey = device.deviceName
        if (activeJobs.containsKey(devKey)) {
            lastErrors[devKey] = UsbDeviceError(
                category = UsbErrorCategory.DETACHED_UNEXPECTEDLY,
                deviceName = devKey,
                message = "Generic HID device was disconnected during active session.",
                suggestedRemedy = "Reconnect device to resume input."
            )
        }
        onRelease(device)
        attachedDevices.remove(devKey)
        _genericHidReports.value = _genericHidReports.value - devKey
        Log.i(handlerName, "Detached Generic HID $devKey")
    }

    override fun getStatus(deviceName: String): UsbHandlerStatus {
        val job = activeJobs[deviceName]
        val iface = activeInterfaces[deviceName]
        val vmId = targetVmMap[deviceName]
        val bytes = bytesTransferred[deviceName] ?: 0L

        return when {
            job != null && job.isActive -> UsbHandlerStatus(
                state = UsbHandlerState.STREAMING,
                summary = "Streaming HID reports ($bytes bytes transferred)",
                bytesTransferred = bytes,
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
