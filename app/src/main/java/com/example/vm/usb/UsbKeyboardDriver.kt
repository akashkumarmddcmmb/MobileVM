package com.example.vm.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import com.example.vm.input.KeyboardInputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class UsbKeyboardState(
    val deviceName: String,
    val isPolling: Boolean = false,
    val totalReportsProcessed: Long = 0L,
    val activeKeysCount: Int = 0,
    val lastKeySummary: String = "Idle",
    val isCtrlActive: Boolean = false,
    val isShiftActive: Boolean = false,
    val isAltActive: Boolean = false,
    val isMetaActive: Boolean = false
)

/**
 * UsbKeyboardDriver manages the asynchronous USB Host polling loop over
 * a physical USB HID keyboard's interrupt IN endpoint.
 */
class UsbKeyboardDriver(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val keyboardInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint
) {
    companion object {
        private const val TAG = "UsbKeyboardDriver"
        private const val HID_REPORT_BUFFER_SIZE = 8
        private const val POLL_TIMEOUT_MS = 200

        /**
         * Checks if a UsbInterface is a standard USB HID Keyboard.
         */
        fun isKeyboardInterface(iface: UsbInterface): Boolean {
            return iface.interfaceClass == UsbConstants.USB_CLASS_HID &&
                    (iface.interfaceSubclass == 1 || iface.interfaceProtocol == 1 || iface.interfaceSubclass == 0)
        }

        /**
         * Locates the interrupt IN endpoint on a USB keyboard interface.
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

    private val parser = UsbKeyboardParser()
    private val driverScope = CoroutineScope(Dispatchers.IO + Job())
    private var pollingJob: Job? = null

    private val _keyboardState = MutableStateFlow(UsbKeyboardState(device.deviceName))
    val keyboardState: StateFlow<UsbKeyboardState> = _keyboardState.asStateFlow()

    var onKeyEvent: ((KeyboardInputEvent) -> Unit)? = null
    var onTerminalBytes: ((ByteArray) -> Unit)? = null

    fun startPolling() {
        if (pollingJob?.isActive == true) return

        _keyboardState.value = _keyboardState.value.copy(isPolling = true)
        Log.i(TAG, "Starting physical USB keyboard polling loop for ${device.deviceName} on endpoint ${inEndpoint.endpointNumber}")

        pollingJob = driverScope.launch {
            val buffer = ByteArray(HID_REPORT_BUFFER_SIZE)
            var reportCount = 0L

            while (isActive) {
                try {
                    val bytesRead = connection.bulkTransfer(inEndpoint, buffer, buffer.size, POLL_TIMEOUT_MS)
                    if (bytesRead >= 8) {
                        reportCount++
                        val report = parser.parseReport(buffer, bytesRead)

                        // 1. Dispatch discrete evdev key events
                        for (ev in report.events) {
                            onKeyEvent?.invoke(ev)
                        }

                        // 2. Dispatch terminal character bytes (for serial console)
                        if (report.terminalOutputBytes.isNotEmpty()) {
                            onTerminalBytes?.invoke(report.terminalOutputBytes)
                        }

                        // 3. Update driver state & telemetry
                        val isCtrl = (report.modifierMask and (UsbKeyboardParser.MOD_LCTRL or UsbKeyboardParser.MOD_RCTRL)) != 0
                        val isShift = (report.modifierMask and (UsbKeyboardParser.MOD_LSHIFT or UsbKeyboardParser.MOD_RSHIFT)) != 0
                        val isAlt = (report.modifierMask and (UsbKeyboardParser.MOD_LALT or UsbKeyboardParser.MOD_RALT)) != 0
                        val isMeta = (report.modifierMask and (UsbKeyboardParser.MOD_LMETA or UsbKeyboardParser.MOD_RMETA)) != 0

                        val summary = if (report.events.isNotEmpty()) {
                            val last = report.events.last()
                            "${if (last.isDown) "DOWN" else "UP"} code=${last.scanCode} char='${last.unicodeChar}'"
                        } else {
                            _keyboardState.value.lastKeySummary
                        }

                        _keyboardState.value = UsbKeyboardState(
                            deviceName = device.deviceName,
                            isPolling = true,
                            totalReportsProcessed = reportCount,
                            activeKeysCount = report.activePressedUsageIds.size,
                            lastKeySummary = summary,
                            isCtrlActive = isCtrl,
                            isShiftActive = isShift,
                            isAltActive = isAlt,
                            isMetaActive = isMeta
                        )
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "USB Keyboard read exception on ${device.deviceName}: ${e.message}")
                    }
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        parser.reset()
        _keyboardState.value = _keyboardState.value.copy(
            isPolling = false,
            activeKeysCount = 0,
            isCtrlActive = false,
            isShiftActive = false,
            isAltActive = false,
            isMetaActive = false
        )
        Log.i(TAG, "Stopped physical USB keyboard polling for ${device.deviceName}")
    }
}
