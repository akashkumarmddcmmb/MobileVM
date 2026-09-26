package com.example.vm.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import com.example.vm.input.MouseInputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class UsbMouseState(
    val deviceName: String,
    val isPolling: Boolean = false,
    val totalReportsProcessed: Long = 0L,
    val cursorX: Int = 512,
    val cursorY: Int = 384,
    val lastDeltaX: Int = 0,
    val lastDeltaY: Int = 0,
    val lastWheelDelta: Int = 0,
    val isLeftPressed: Boolean = false,
    val isRightPressed: Boolean = false,
    val isMiddlePressed: Boolean = false,
    val lastEventSummary: String = "Idle"
)

/**
 * UsbMouseDriver manages the asynchronous USB Host polling loop over
 * a physical USB HID mouse's interrupt IN endpoint.
 */
class UsbMouseDriver(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val mouseInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    val screenWidth: Int = 1024,
    val screenHeight: Int = 768
) {
    companion object {
        private const val TAG = "UsbMouseDriver"
        private const val HID_REPORT_BUFFER_SIZE = 8
        private const val POLL_TIMEOUT_MS = 200

        /**
         * Checks if a UsbInterface is a standard USB HID Mouse.
         * Subclass 1 = Boot Interface, Protocol 2 = Mouse.
         */
        fun isMouseInterface(iface: UsbInterface): Boolean {
            return iface.interfaceClass == UsbConstants.USB_CLASS_HID &&
                    (iface.interfaceProtocol == 2 ||
                     (iface.interfaceSubclass == 1 && iface.interfaceProtocol != 1))
        }

        /**
         * Locates the interrupt IN endpoint on a USB mouse interface.
         */
        fun findInterruptInEndpoint(iface: UsbInterface): UsbEndpoint? {
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.direction == UsbConstants.USB_DIR_IN &&
                    (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT || ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK)) {
                    return ep
                }
            }
            return null
        }
    }

    private val parser = UsbMouseParser(screenWidth, screenHeight)
    private val driverScope = CoroutineScope(Dispatchers.IO + Job())
    private var pollingJob: Job? = null

    private val _mouseState = MutableStateFlow(
        UsbMouseState(
            deviceName = device.deviceName,
            cursorX = screenWidth / 2,
            cursorY = screenHeight / 2
        )
    )
    val mouseState: StateFlow<UsbMouseState> = _mouseState.asStateFlow()

    var onMouseEvent: ((MouseInputEvent) -> Unit)? = null

    fun startPolling() {
        if (pollingJob?.isActive == true) return

        _mouseState.value = _mouseState.value.copy(isPolling = true)
        Log.i(TAG, "Starting physical USB mouse polling loop for ${device.deviceName} on endpoint ${inEndpoint.endpointNumber}")

        pollingJob = driverScope.launch {
            val buffer = ByteArray(HID_REPORT_BUFFER_SIZE)
            var reportCount = 0L

            while (isActive) {
                try {
                    val bytesRead = connection.bulkTransfer(inEndpoint, buffer, buffer.size, POLL_TIMEOUT_MS)
                    if (bytesRead >= 3) {
                        reportCount++
                        val report = parser.parseReport(buffer, bytesRead)
                        if (report != null) {
                            // 1. Dispatch discrete mouse event
                            onMouseEvent?.invoke(report.event)

                            // 2. Update telemetry
                            val summary = "Move=(${report.deltaX}, ${report.deltaY}) " +
                                    "Btns=[L:${if (report.isLeftPressed) "1" else "0"}, " +
                                    "R:${if (report.isRightPressed) "1" else "0"}, " +
                                    "M:${if (report.isMiddlePressed) "1" else "0"}] " +
                                    "Wheel=${report.wheelDelta} @ (${report.absX}, ${report.absY})"

                            _mouseState.value = UsbMouseState(
                                deviceName = device.deviceName,
                                isPolling = true,
                                totalReportsProcessed = reportCount,
                                cursorX = report.absX,
                                cursorY = report.absY,
                                lastDeltaX = report.deltaX,
                                lastDeltaY = report.deltaY,
                                lastWheelDelta = report.wheelDelta,
                                isLeftPressed = report.isLeftPressed,
                                isRightPressed = report.isRightPressed,
                                isMiddlePressed = report.isMiddlePressed,
                                lastEventSummary = summary
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "USB Mouse read exception on ${device.deviceName}: ${e.message}")
                    }
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        parser.reset()
        _mouseState.value = _mouseState.value.copy(
            isPolling = false,
            lastDeltaX = 0,
            lastDeltaY = 0,
            lastWheelDelta = 0,
            isLeftPressed = false,
            isRightPressed = false,
            isMiddlePressed = false
        )
        Log.i(TAG, "Stopped physical USB mouse polling for ${device.deviceName}")
    }
}
