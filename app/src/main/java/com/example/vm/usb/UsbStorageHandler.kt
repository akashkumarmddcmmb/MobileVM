package com.example.vm.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * UsbStorageHandler implements the standard UsbDeviceHandler contract for USB Mass Storage devices.
 * Coordinates dual-mode storage (Host-Mediated safe bridge vs Direct Guest Passthrough).
 * Lifecycle: attach, detach, permission, identification, release, error handling.
 */
class UsbStorageHandler(
    private val storageManager: UsbStorageManager,
    private val permissionManager: UsbPermissionManager
) : UsbDeviceHandler {

    override val category: UsbDeviceCategory = UsbDeviceCategory.STORAGE
    override val handlerName: String = "UsbStorageHandler"

    private val attachedDevices = ConcurrentHashMap<String, UsbDeviceIdentification>()
    private val targetVmMap = ConcurrentHashMap<String, Long>()
    private val lastErrors = ConcurrentHashMap<String, UsbDeviceError>()

    override fun canHandle(identification: UsbDeviceIdentification): Boolean {
        return identification.category == UsbDeviceCategory.STORAGE
    }

    override fun onAttach(device: UsbDevice, identification: UsbDeviceIdentification) {
        attachedDevices[device.deviceName] = identification
        Log.i(handlerName, "Attached USB Mass Storage: ${identification.displayName} (${device.deviceName})")
    }

    override fun onRequestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        permissionManager.requestPermission(device) { granted ->
            if (!granted) {
                lastErrors[device.deviceName] = UsbDeviceError(
                    category = UsbErrorCategory.PERMISSION_DENIED,
                    deviceName = device.deviceName,
                    message = "Host permission denied for USB Storage device.",
                    suggestedRemedy = "Allow USB permission to mount this drive in the VM."
                )
            } else {
                lastErrors.remove(device.deviceName)
                val info = storageManager.storageDevices.value.firstOrNull { it.deviceName == device.deviceName }
                if (info != null) {
                    storageManager.queryScsiMetadata(info)
                }
            }
            onResult(granted)
        }
    }

    override fun onClaim(device: UsbDevice, connection: UsbDeviceConnection, targetVmId: Long): Result<Unit> {
        val devKey = device.deviceName
        val storageInfo = storageManager.storageDevices.value.firstOrNull { it.deviceName == devKey }
            ?: run {
                val err = UsbDeviceError(
                    category = UsbErrorCategory.DEVICE_NOT_FOUND,
                    deviceName = devKey,
                    message = "Storage device metadata not found in storage manager.",
                    suggestedRemedy = "Rescan the USB bus."
                )
                lastErrors[devKey] = err
                return Result.failure(IllegalStateException(err.message))
            }

        // Default to safe Host-Mediated mode if unconfigured
        val mode = if (storageInfo.accessMode == UsbStorageAccessMode.UNATTACHED) {
            UsbStorageAccessMode.HOST_ACCESS
        } else {
            storageInfo.accessMode
        }

        val result = storageManager.configureAccessMode(storageInfo, mode, targetVmId)
        return if (result.isSuccess) {
            targetVmMap[devKey] = targetVmId
            lastErrors.remove(devKey)
            Log.i(handlerName, "Claimed USB Storage $devKey in mode ${mode.name} for VM $targetVmId")
            Result.success(Unit)
        } else {
            val ex = result.exceptionOrNull()
            val err = UsbDeviceError(
                category = UsbErrorCategory.CLAIM_FAILED,
                deviceName = devKey,
                message = "Failed to configure storage access: ${ex?.message}",
                suggestedRemedy = "Try switching to Host-Mediated Storage Mode."
            )
            lastErrors[devKey] = err
            Result.failure(ex ?: IllegalStateException(err.message))
        }
    }

    override fun onRelease(device: UsbDevice): Result<Unit> {
        val devKey = device.deviceName
        val storageInfo = storageManager.storageDevices.value.firstOrNull { it.deviceName == devKey }
        if (storageInfo != null) {
            storageManager.unmountOrRelease(storageInfo)
        }
        targetVmMap.remove(devKey)
        Log.i(handlerName, "Released USB Storage $devKey")
        return Result.success(Unit)
    }

    override fun onDetach(device: UsbDevice) {
        val devKey = device.deviceName
        if (targetVmMap.containsKey(devKey)) {
            lastErrors[devKey] = UsbDeviceError(
                category = UsbErrorCategory.DETACHED_UNEXPECTEDLY,
                deviceName = devKey,
                message = "USB Storage drive was removed while mounted.",
                suggestedRemedy = "Ensure data is synced before removing external drives."
            )
        }
        onRelease(device)
        attachedDevices.remove(devKey)
        Log.i(handlerName, "Detached USB Storage $devKey")
    }

    override fun getStatus(deviceName: String): UsbHandlerStatus {
        val storageInfo = storageManager.storageDevices.value.firstOrNull { it.deviceName == deviceName }
        val vmId = targetVmMap[deviceName]

        return when {
            storageInfo != null && storageInfo.accessMode != UsbStorageAccessMode.UNATTACHED -> UsbHandlerStatus(
                state = UsbHandlerState.CLAIMED,
                summary = "${storageInfo.connectionState.label} (${storageInfo.accessMode.title})",
                targetVmId = vmId
            )
            attachedDevices.containsKey(deviceName) -> UsbHandlerStatus(
                state = UsbHandlerState.ATTACHED,
                summary = "Detected (${storageInfo?.formattedCapacity ?: "Querying capacity..."})",
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
