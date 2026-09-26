package com.example.vm.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.example.vm.storage.AndroidStorageDiskBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * UsbStorageManager coordinates USB Mass Storage peripherals,
 * SCSI capacity querying, and the dual-mode abstraction:
 * [Direct Guest Passthrough] vs [Safe Host-Mediated Storage Access].
 */
class UsbStorageManager(
    private val context: Context,
    private val usbManager: UsbManager,
    private val deviceRouter: UsbDeviceRouter,
    private val permissionManager: UsbPermissionManager
) {
    companion object {
        private const val TAG = "UsbStorageManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val storageMap = ConcurrentHashMap<String, UsbStorageDeviceInfo>()

    private val _storageDevices = MutableStateFlow<List<UsbStorageDeviceInfo>>(emptyList())
    val storageDevices: StateFlow<List<UsbStorageDeviceInfo>> = _storageDevices.asStateFlow()

    private val diskBackend = AndroidStorageDiskBackend(context)

    /**
     * Updates and detects USB mass storage peripherals from the enumerated host device list.
     */
    fun updateDevices(allDevices: List<UsbDeviceInfo>) {
        val currentKeys = mutableSetOf<String>()

        for (dev in allDevices) {
            // Check if device or any interface belongs to USB Mass Storage Class (0x08)
            val isStorage = dev.deviceClass == 0x08 || dev.interfaces.any { it.interfaceClass == 0x08 }
            if (isStorage) {
                currentKeys.add(dev.deviceName)
                val existing = storageMap[dev.deviceName]

                if (existing != null) {
                    val isRouted = deviceRouter.isDeviceRouted(dev.deviceName)
                    val updatedState = when {
                        !dev.hasPermission -> UsbStorageConnectionState.PERMISSION_PENDING
                        isRouted && existing.accessMode == UsbStorageAccessMode.GUEST_PASSTHROUGH -> UsbStorageConnectionState.CLAIMED_GUEST
                        isRouted && existing.accessMode == UsbStorageAccessMode.HOST_ACCESS -> UsbStorageConnectionState.CLAIMED_HOST
                        else -> UsbStorageConnectionState.DETECTED
                    }
                    storageMap[dev.deviceName] = existing.copy(
                        connectionState = updatedState,
                        rawDevice = dev.rawDevice
                    )
                } else {
                    val detectedType = UsbStorageDeviceInfo.detectStorageType(dev.rawDevice)
                    val initial = UsbStorageDeviceInfo(
                        deviceName = dev.deviceName,
                        deviceId = dev.deviceId,
                        vendorId = dev.vendorId,
                        productId = dev.productId,
                        storageType = detectedType,
                        accessMode = UsbStorageAccessMode.UNATTACHED,
                        connectionState = if (dev.hasPermission) UsbStorageConnectionState.DETECTED else UsbStorageConnectionState.PERMISSION_PENDING,
                        rawDevice = dev.rawDevice
                    )
                    storageMap[dev.deviceName] = initial

                    // If permission is already granted, query SCSI capacity asynchronously
                    if (dev.hasPermission) {
                        queryScsiMetadata(initial)
                    }
                }
            }
        }

        // Clean up removed devices
        val toRemove = storageMap.keys.filter { !currentKeys.contains(it) }
        for (k in toRemove) {
            storageMap.remove(k)
        }

        _storageDevices.value = storageMap.values.toList()
    }

    /**
     * Executes SCSI INQUIRY and READ CAPACITY (10) commands over Bulk-Only Transport.
     */
    fun queryScsiMetadata(storageInfo: UsbStorageDeviceInfo) {
        val device = storageInfo.rawDevice
        if (!usbManager.hasPermission(device)) return

        scope.launch {
            try {
                val connection = usbManager.openDevice(device) ?: return@launch
                var ifaceToUse: android.hardware.usb.UsbInterface? = null

                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    if (UsbStorageDeviceInfo.isStorageInterface(iface)) {
                        ifaceToUse = iface
                        break
                    }
                }

                if (ifaceToUse == null && device.interfaceCount > 0) {
                    ifaceToUse = device.getInterface(0)
                }

                if (ifaceToUse != null) {
                    val claimed = connection.claimInterface(ifaceToUse, true)
                    if (claimed) {
                        val endpoints = UsbScsiBotDriver.findBulkEndpoints(ifaceToUse)
                        if (endpoints != null) {
                            val botDriver = UsbScsiBotDriver(connection, ifaceToUse, endpoints.first, endpoints.second)
                            val inquiry = botDriver.queryInquiry()
                            val capacity = botDriver.queryCapacity()

                            val current = storageMap[device.deviceName] ?: storageInfo
                            val updated = current.copy(
                                scsiVendor = inquiry?.vendor ?: current.scsiVendor,
                                scsiProduct = inquiry?.product ?: current.scsiProduct,
                                scsiRevision = inquiry?.revision ?: current.scsiRevision,
                                capacityBytes = capacity?.totalCapacityBytes ?: current.capacityBytes,
                                sectorSize = capacity?.blockSize ?: current.sectorSize,
                                totalSectors = capacity?.lastLba?.let { it + 1 } ?: current.totalSectors
                            )
                            storageMap[device.deviceName] = updated
                            _storageDevices.value = storageMap.values.toList()
                            Log.i(TAG, "SCSI query success for ${device.deviceName}: Capacity=${updated.formattedCapacity}, Vendor=${updated.scsiVendor}, Product=${updated.scsiProduct}")
                        }
                        connection.releaseInterface(ifaceToUse)
                    }
                }
                connection.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error querying SCSI metadata on ${device.deviceName}: ${e.message}")
            }
        }
    }

    /**
     * Sets storage access mode between Guest Passthrough and Host-Mediated Storage.
     * Note: Direct passthrough claims the raw USB SCSI interface directly.
     * Host-Mediated mode provides safe access via a dedicated container disk image
     * without exposing Android private app storage to the guest.
     */
    fun configureAccessMode(
        storageInfo: UsbStorageDeviceInfo,
        mode: UsbStorageAccessMode,
        targetVmId: Long
    ): Result<Boolean> {
        val devKey = storageInfo.deviceName

        when (mode) {
            UsbStorageAccessMode.GUEST_PASSTHROUGH -> {
                // Direct Raw Passthrough
                val routeResult = deviceRouter.routeDeviceToVM(storageInfo.rawDevice, targetVmId)
                return if (routeResult.isSuccess) {
                    val updated = storageInfo.copy(
                        accessMode = UsbStorageAccessMode.GUEST_PASSTHROUGH,
                        connectionState = UsbStorageConnectionState.CLAIMED_GUEST,
                        targetVmId = targetVmId
                    )
                    storageMap[devKey] = updated
                    _storageDevices.value = storageMap.values.toList()
                    Log.i(TAG, "Configured Direct Guest Passthrough for $devKey on VM $targetVmId")
                    Result.success(true)
                } else {
                    Result.failure(routeResult.exceptionOrNull() ?: Exception("Failed to claim USB storage device"))
                }
            }

            UsbStorageAccessMode.HOST_ACCESS -> {
                // Host-Mediated Storage Mode:
                // Device remains under host control. A dedicated virtual disk file is provisioned in the
                // VM's sandboxed storage directory to act as a bridge, strictly isolating Android private files.
                val safeDisksDir = diskBackend.getAuthorizedDisksDirectory()
                val safeDiskName = "usb_storage_${storageInfo.vendorHex}_${storageInfo.productHex}.img"
                val safeDiskFile = File(safeDisksDir, safeDiskName)

                if (!safeDiskFile.exists()) {
                    diskBackend.createDiskImage(safeDiskFile.absolutePath, sizeGb = 2, sparse = true)
                }

                val updated = storageInfo.copy(
                    accessMode = UsbStorageAccessMode.HOST_ACCESS,
                    connectionState = UsbStorageConnectionState.CLAIMED_HOST,
                    hostMediatedVirtualDiskPath = safeDiskFile.absolutePath,
                    targetVmId = targetVmId
                )
                storageMap[devKey] = updated
                _storageDevices.value = storageMap.values.toList()
                Log.i(TAG, "Configured Host-Mediated Storage for $devKey -> ${safeDiskFile.absolutePath}")
                return Result.success(true)
            }

            UsbStorageAccessMode.UNATTACHED -> {
                return unmountOrRelease(storageInfo)
            }
        }
    }

    /**
     * Safely unmounts and releases the storage device from any VM route or host bridge.
     */
    fun unmountOrRelease(storageInfo: UsbStorageDeviceInfo): Result<Boolean> {
        val devKey = storageInfo.deviceName
        if (storageInfo.accessMode == UsbStorageAccessMode.GUEST_PASSTHROUGH) {
            deviceRouter.releaseDevice(devKey)
        }

        val updated = storageInfo.copy(
            accessMode = UsbStorageAccessMode.UNATTACHED,
            connectionState = UsbStorageConnectionState.DETECTED,
            targetVmId = null
        )
        storageMap[devKey] = updated
        _storageDevices.value = storageMap.values.toList()
        Log.i(TAG, "Unmounted USB storage device $devKey")
        return Result.success(true)
    }

    fun requestStoragePermission(storageInfo: UsbStorageDeviceInfo, onResult: (Boolean) -> Unit) {
        permissionManager.requestPermission(storageInfo.rawDevice) { granted ->
            if (granted) {
                queryScsiMetadata(storageInfo)
            }
            onResult(granted)
        }
    }
}
