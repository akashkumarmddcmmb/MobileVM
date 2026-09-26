package com.example.vm.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * UsbSerialHandler implements the standard UsbDeviceHandler contract for USB Serial adapters
 * (CDC-ACM, FTDI, Silicon Labs CP210x, WCH CH340, Prolific PL2303).
 * Lifecycle: attach, detach, permission, identification, release, error handling.
 */
class UsbSerialHandler(
    private val permissionManager: UsbPermissionManager
) : UsbDeviceHandler {

    override val category: UsbDeviceCategory = UsbDeviceCategory.SERIAL
    override val handlerName: String = "UsbSerialHandler"

    private val activeDrivers = ConcurrentHashMap<String, UsbSerialDriver>()
    private val activeInterfaces = ConcurrentHashMap<String, UsbInterface>()
    private val activeConnections = ConcurrentHashMap<String, UsbDeviceConnection>()
    private val targetVmMap = ConcurrentHashMap<String, Long>()
    private val lastErrors = ConcurrentHashMap<String, UsbDeviceError>()
    private val attachedDevices = ConcurrentHashMap<String, UsbDeviceIdentification>()

    var onSerialDataReceived: ((targetVmId: Long, data: ByteArray) -> Unit)? = null

    override fun canHandle(identification: UsbDeviceIdentification): Boolean {
        return identification.category == UsbDeviceCategory.SERIAL
    }

    override fun onAttach(device: UsbDevice, identification: UsbDeviceIdentification) {
        attachedDevices[device.deviceName] = identification
        Log.i(handlerName, "Attached USB Serial adapter: ${identification.displayName} (${device.deviceName})")
    }

    override fun onRequestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        permissionManager.requestPermission(device) { granted ->
            if (!granted) {
                lastErrors[device.deviceName] = UsbDeviceError(
                    category = UsbErrorCategory.PERMISSION_DENIED,
                    deviceName = device.deviceName,
                    message = "Host permission denied for USB Serial adapter.",
                    suggestedRemedy = "Allow USB permission to route serial I/O stream to the VM console."
                )
            } else {
                lastErrors.remove(device.deviceName)
            }
            onResult(granted)
        }
    }

    override fun onClaim(device: UsbDevice, connection: UsbDeviceConnection, targetVmId: Long): Result<Unit> {
        val devKey = device.deviceName

        // Find data interface and bulk endpoints
        var dataIface: UsbInterface? = null
        var bulkEndpoints: Pair<android.hardware.usb.UsbEndpoint, android.hardware.usb.UsbEndpoint?>? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val endpoints = UsbSerialDriver.findBulkEndpoints(iface)
            if (endpoints != null) {
                dataIface = iface
                bulkEndpoints = endpoints
                break
            }
        }

        if (dataIface == null || bulkEndpoints == null) {
            val err = UsbDeviceError(
                category = UsbErrorCategory.UNSUPPORTED_DEVICE,
                deviceName = devKey,
                message = "No valid USB Serial bulk data interface and endpoints found.",
                suggestedRemedy = "Verify the connected peripheral supports USB CDC-ACM or standard UART bridge protocols."
            )
            lastErrors[devKey] = err
            return Result.failure(IllegalStateException(err.message))
        }

        val claimed = connection.claimInterface(dataIface, true)
        if (!claimed) {
            val err = UsbDeviceError(
                category = UsbErrorCategory.CLAIM_FAILED,
                deviceName = devKey,
                message = "Failed to claim USB Serial data interface ${dataIface.id}.",
                suggestedRemedy = "Ensure the serial device is not in use by another application."
            )
            lastErrors[devKey] = err
            return Result.failure(IllegalStateException(err.message))
        }

        val driver = UsbSerialDriver(device, connection, dataIface, bulkEndpoints.first, bulkEndpoints.second)
        driver.onDataReceived = { bytes ->
            val vmId = targetVmMap[devKey] ?: targetVmId
            onSerialDataReceived?.invoke(vmId, bytes)
        }
        driver.startListening()

        activeDrivers[devKey] = driver
        activeInterfaces[devKey] = dataIface
        activeConnections[devKey] = connection
        targetVmMap[devKey] = targetVmId
        lastErrors.remove(devKey)

        Log.i(handlerName, "Successfully claimed and started USB Serial driver on $devKey for VM $targetVmId")
        return Result.success(Unit)
    }

    fun writeSerialData(deviceName: String, data: ByteArray): Int {
        val driver = activeDrivers[deviceName] ?: return -1
        return driver.write(data)
    }

    override fun onRelease(device: UsbDevice): Result<Unit> {
        val devKey = device.deviceName
        val driver = activeDrivers.remove(devKey)
        driver?.stopListening()

        val iface = activeInterfaces.remove(devKey)
        val conn = activeConnections.remove(devKey)

        if (iface != null && conn != null) {
            try {
                conn.releaseInterface(iface)
                conn.close()
            } catch (e: Exception) {
                Log.w(handlerName, "Error releasing serial interface on $devKey: ${e.message}")
            }
        }
        targetVmMap.remove(devKey)
        Log.i(handlerName, "Released USB Serial on $devKey")
        return Result.success(Unit)
    }

    override fun onDetach(device: UsbDevice) {
        val devKey = device.deviceName
        if (activeDrivers.containsKey(devKey)) {
            lastErrors[devKey] = UsbDeviceError(
                category = UsbErrorCategory.DETACHED_UNEXPECTEDLY,
                deviceName = devKey,
                message = "USB Serial adapter was disconnected during an active communication session.",
                suggestedRemedy = "Reconnect adapter to restore serial link."
            )
        }
        onRelease(device)
        attachedDevices.remove(devKey)
        Log.i(handlerName, "Detached USB Serial $devKey")
    }

    override fun getStatus(deviceName: String): UsbHandlerStatus {
        val driver = activeDrivers[deviceName]
        val iface = activeInterfaces[deviceName]
        val vmId = targetVmMap[deviceName]

        return when {
            driver != null -> {
                val state = driver.serialState.value
                UsbHandlerStatus(
                    state = if (state.isStreaming) UsbHandlerState.STREAMING else UsbHandlerState.CLAIMED,
                    summary = "Streaming (RX: ${state.totalBytesRead}B, TX: ${state.totalBytesWritten}B)",
                    bytesTransferred = state.totalBytesRead + state.totalBytesWritten,
                    targetVmId = vmId,
                    activeInterfaceId = iface?.id
                )
            }
            attachedDevices.containsKey(deviceName) -> UsbHandlerStatus(
                state = UsbHandlerState.ATTACHED,
                summary = "Attached and ready (115200 8N1 default)",
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
