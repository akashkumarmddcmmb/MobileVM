package com.example.vm.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

enum class UsbHandlerState {
    IDLE,
    ATTACHED,
    PERMISSION_PENDING,
    CLAIMED,
    STREAMING,
    ERROR,
    DETACHED
}

data class UsbHandlerStatus(
    val state: UsbHandlerState,
    val summary: String,
    val bytesTransferred: Long = 0L,
    val targetVmId: Long? = null,
    val activeInterfaceId: Int? = null
)

/**
 * Universal contract for device-class handlers under the MobileVM USB Manager.
 * Every device implements standard lifecycle operations:
 * - attach
 * - detach
 * - permission
 * - identification
 * - release
 * - error handling
 */
interface UsbDeviceHandler {
    val category: UsbDeviceCategory
    val handlerName: String

    /**
     * Determines whether this handler can manage the identified physical device.
     */
    fun canHandle(identification: UsbDeviceIdentification): Boolean

    /**
     * Lifecycle: ATTACH
     * Called when a physical USB device matching this category is plugged in.
     */
    fun onAttach(device: UsbDevice, identification: UsbDeviceIdentification)

    /**
     * Lifecycle: PERMISSION
     * Requests host permission from the user for this device.
     */
    fun onRequestPermission(device: UsbDevice, onResult: (Boolean) -> Unit)

    /**
     * Lifecycle: CLAIM / ROUTE
     * Opens endpoints, claims interfaces, and starts device operation for a target VM.
     */
    fun onClaim(device: UsbDevice, connection: UsbDeviceConnection, targetVmId: Long): Result<Unit>

    /**
     * Lifecycle: RELEASE
     * Closes driver threads, releases claimed interfaces, and detaches VM routing cleanly.
     */
    fun onRelease(device: UsbDevice): Result<Unit>

    /**
     * Lifecycle: DETACH
     * Called when the physical USB device is unplugged from the host port.
     */
    fun onDetach(device: UsbDevice)

    /**
     * Lifecycle: IDENTIFICATION & STATUS
     */
    fun getStatus(deviceName: String): UsbHandlerStatus

    /**
     * Lifecycle: ERROR HANDLING
     */
    fun getLastError(deviceName: String): UsbDeviceError?

    /**
     * Clears tracked errors for the device.
     */
    fun clearError(deviceName: String)
}
