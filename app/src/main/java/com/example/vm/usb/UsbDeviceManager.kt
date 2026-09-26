package com.example.vm.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * UsbDeviceManager: Root USB Manager implementing the generic modular architecture:
 *
 * USB Manager
 * ├── Keyboard (UsbKeyboardHandler)
 * ├── Mouse (UsbMouseHandler)
 * ├── HID (UsbGenericHidHandler)
 * ├── Storage (UsbStorageHandler)
 * ├── Serial (UsbSerialHandler)
 * └── Future supported devices (UsbFutureDeviceHandler)
 *
 * Every device is managed through standard lifecycle operations:
 * - attach
 * - detach
 * - permission
 * - identification
 * - release
 * - error handling
 */
class UsbDeviceManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "UsbDeviceManager"
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    val permissionManager = UsbPermissionManager(context, usbManager)
    val deviceRouter = UsbDeviceRouter(usbManager)
    val storageManager = UsbStorageManager(context, usbManager, deviceRouter, permissionManager)

    // Modular Device Subsystem Handlers
    val keyboardHandler = UsbKeyboardHandler(permissionManager)
    val mouseHandler = UsbMouseHandler(permissionManager)
    val genericHidHandler = UsbGenericHidHandler(permissionManager)
    val storageHandler = UsbStorageHandler(storageManager, permissionManager)
    val serialHandler = UsbSerialHandler(permissionManager)
    val futureDeviceHandler = UsbFutureDeviceHandler(permissionManager)

    val handlers: List<UsbDeviceHandler> = listOf(
        keyboardHandler,
        mouseHandler,
        genericHidHandler,
        storageHandler,
        serialHandler,
        futureDeviceHandler
    )

    // State flows
    private val _devices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val devices: StateFlow<List<UsbDeviceInfo>> = _devices.asStateFlow()

    private val _identifications = MutableStateFlow<Map<String, UsbDeviceIdentification>>(emptyMap())
    val identifications: StateFlow<Map<String, UsbDeviceIdentification>> = _identifications.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, UsbDeviceError>>(emptyMap())
    val errors: StateFlow<Map<String, UsbDeviceError>> = _errors.asStateFlow()

    var onDeviceAttached: ((UsbDeviceInfo) -> Unit)? = null
    var onDeviceDetached: ((UsbDeviceInfo) -> Unit)? = null

    private var isReceiverRegistered = false

    private val usbHardwareReceiver = object : BroadcastReceiver() {
        override fun onReceive(recvContext: Context, intent: Intent) {
            val action = intent.action
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device != null) {
                        Log.i(TAG, "Hardware Attached: ${device.deviceName} (0x${device.vendorId.toString(16)}:0x${device.productId.toString(16)})")
                        onHardwareAttached(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null) {
                        Log.i(TAG, "Hardware Detached: ${device.deviceName}")
                        onHardwareDetached(device)
                    }
                }
            }
        }
    }

    init {
        registerHardwareReceiver()
        enumerateDevices()
    }

    private fun registerHardwareReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbHardwareReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbHardwareReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    // =========================================================================
    // STANDARD LIFECYCLE 1: IDENTIFICATION
    // =========================================================================

    /**
     * Identifies a physical USB device, analyzes its descriptors, and categorizes it.
     */
    fun identifyDevice(device: UsbDevice): UsbDeviceIdentification {
        val identification = UsbDeviceIdentification.identify(device, usbManager)
        _identifications.value = _identifications.value + (device.deviceName to identification)
        return identification
    }

    fun getIdentification(deviceName: String): UsbDeviceIdentification? {
        return _identifications.value[deviceName]
    }

    // =========================================================================
    // STANDARD LIFECYCLE 2: ATTACH
    // =========================================================================

    private fun onHardwareAttached(device: UsbDevice) {
        val id = identifyDevice(device)
        val handler = resolveHandler(id)
        handler.onAttach(device, id)

        val updatedList = enumerateDevices()
        val info = updatedList.firstOrNull { it.deviceName == device.deviceName }
            ?: UsbDeviceInfo.fromUsbDevice(device, usbManager, isRouted = false)
        onDeviceAttached?.invoke(info)
    }

    // =========================================================================
    // STANDARD LIFECYCLE 3: PERMISSION
    // =========================================================================

    /**
     * Requests host permission for the specified USB device.
     */
    fun requestPermission(deviceInfo: UsbDeviceInfo, onResult: (Boolean) -> Unit) {
        val dev = deviceInfo.rawDevice
        val id = identifyDevice(dev)
        val handler = resolveHandler(id)

        handler.onRequestPermission(dev) { granted ->
            if (!granted) {
                recordError(
                    UsbDeviceError(
                        category = UsbErrorCategory.PERMISSION_DENIED,
                        deviceName = dev.deviceName,
                        message = "Host permission was denied for ${id.displayName}.",
                        suggestedRemedy = "Allow USB permission in the Android dialog to permit VM hardware passthrough."
                    )
                )
            } else {
                clearError(dev.deviceName)
            }
            enumerateDevices()
            onResult(granted)
        }
    }

    // =========================================================================
    // STANDARD LIFECYCLE 4: CLAIM / ROUTE
    // =========================================================================

    /**
     * Explicitly claims and routes a USB device to a target VM using its specialized handler.
     */
    fun routeDeviceToVM(deviceInfo: UsbDeviceInfo, targetVmId: Long): Result<Boolean> {
        val dev = deviceInfo.rawDevice
        val id = identifyDevice(dev)
        val handler = resolveHandler(id)

        if (!usbManager.hasPermission(dev)) {
            val err = UsbDeviceError(
                category = UsbErrorCategory.PERMISSION_DENIED,
                deviceName = dev.deviceName,
                message = "Cannot route device without host permission.",
                suggestedRemedy = "Request host permission first."
            )
            recordError(err)
            return Result.failure(SecurityException(err.message))
        }

        val connection = usbManager.openDevice(dev)
        if (connection == null) {
            val err = UsbDeviceError(
                category = UsbErrorCategory.EXCLUSIVE_LOCK_FAILURE,
                deviceName = dev.deviceName,
                message = "Unable to open USB connection (device may be locked by another driver).",
                suggestedRemedy = "Reconnect device and try again."
            )
            recordError(err)
            return Result.failure(IllegalStateException(err.message))
        }

        val result = handler.onClaim(dev, connection, targetVmId)
        return if (result.isSuccess) {
            clearError(dev.deviceName)
            // Synchronize with legacy router state
            deviceRouter.routeDeviceToVM(dev, targetVmId)
            enumerateDevices()
            Result.success(true)
        } else {
            val err = handler.getLastError(dev.deviceName) ?: UsbDeviceError(
                category = UsbErrorCategory.CLAIM_FAILED,
                deviceName = dev.deviceName,
                message = result.exceptionOrNull()?.message ?: "Claim failed",
                suggestedRemedy = "Verify endpoint configuration and retry."
            )
            recordError(err)
            enumerateDevices()
            Result.failure(result.exceptionOrNull() ?: IllegalStateException(err.message))
        }
    }

    // =========================================================================
    // STANDARD LIFECYCLE 5: RELEASE
    // =========================================================================

    /**
     * Safely releases a claimed USB device from its VM route and active handler.
     */
    fun releaseDeviceFromVM(deviceInfo: UsbDeviceInfo): Boolean {
        val dev = deviceInfo.rawDevice
        val id = identifyDevice(dev)
        val handler = resolveHandler(id)

        handler.onRelease(dev)
        val releasedFromRouter = deviceRouter.releaseDevice(dev.deviceName)
        enumerateDevices()
        return releasedFromRouter
    }

    /**
     * Releases all claimed USB devices and connections across all handlers.
     */
    fun releaseAllDevices() {
        for (handler in handlers) {
            for (dev in usbManager.deviceList.values) {
                handler.onRelease(dev)
            }
        }
        deviceRouter.releaseAll()
        enumerateDevices()
    }

    // =========================================================================
    // STANDARD LIFECYCLE 6: DETACH
    // =========================================================================

    private fun onHardwareDetached(device: UsbDevice) {
        val id = _identifications.value[device.deviceName] ?: identifyDevice(device)
        val handler = resolveHandler(id)
        handler.onDetach(device)

        deviceRouter.onHardwareDeviceDetached(device)
        val info = UsbDeviceInfo.fromUsbDevice(device, usbManager, isRouted = false)
        onDeviceDetached?.invoke(info)
        enumerateDevices()
    }

    // =========================================================================
    // ERROR HANDLING & DIAGNOSTICS
    // =========================================================================

    fun recordError(error: UsbDeviceError) {
        _errors.value = _errors.value + (error.deviceName to error)
    }

    fun clearError(deviceName: String) {
        _errors.value = _errors.value - deviceName
        for (handler in handlers) {
            handler.clearError(deviceName)
        }
    }

    fun getLastError(deviceName: String): UsbDeviceError? {
        return _errors.value[deviceName]
    }

    /**
     * Enumerates all physical USB devices currently present on the host controller,
     * updating identifications and storage managers.
     */
    fun enumerateDevices(): List<UsbDeviceInfo> {
        val rawList = usbManager.deviceList
        val mapped = rawList.values.map { dev ->
            val isRouted = deviceRouter.isDeviceRouted(dev.deviceName)
            identifyDevice(dev)
            UsbDeviceInfo.fromUsbDevice(dev, usbManager, isRouted)
        }
        _devices.value = mapped
        storageManager.updateDevices(mapped)
        return mapped
    }

    /**
     * Resolves the appropriate specialized handler for a given device identification.
     */
    fun resolveHandler(identification: UsbDeviceIdentification): UsbDeviceHandler {
        return when (identification.category) {
            UsbDeviceCategory.KEYBOARD -> keyboardHandler
            UsbDeviceCategory.MOUSE -> mouseHandler
            UsbDeviceCategory.GENERIC_HID -> genericHidHandler
            UsbDeviceCategory.STORAGE -> storageHandler
            UsbDeviceCategory.SERIAL -> serialHandler
            UsbDeviceCategory.FUTURE_SUPPORTED -> futureDeviceHandler
        }
    }

    fun cleanup() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbHardwareReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering USB hardware receiver: ${e.message}")
            }
            isReceiverRegistered = false
        }
        permissionManager.cleanup()
        releaseAllDevices()
    }
}
