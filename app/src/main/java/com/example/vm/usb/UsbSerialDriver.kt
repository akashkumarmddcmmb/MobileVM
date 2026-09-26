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

data class UsbSerialConfig(
    val baudRate: Int = 115200,
    val dataBits: Int = 8,
    val stopBits: Int = 1,
    val parity: Int = 0 // 0=None, 1=Odd, 2=Even
)

data class UsbSerialState(
    val isStreaming: Boolean = false,
    val totalBytesRead: Long = 0L,
    val totalBytesWritten: Long = 0L,
    val lastError: String? = null
)

/**
 * UsbSerialDriver provides bidirectional communication with USB Serial / UART adapters:
 * - USB CDC-ACM standard (Class 2 / 10)
 * - FTDI FT232 / FT2232 (VID 0x0403)
 * - Silicon Labs CP2102 / CP2104 (VID 0x10C4)
 * - WCH CH340 / CH341 (VID 0x1A86)
 * - Prolific PL2303 (VID 0x067B)
 */
class UsbSerialDriver(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    val dataInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint?
) {
    companion object {
        private const val TAG = "UsbSerialDriver"
        private const val READ_BUFFER_SIZE = 512
        private const val TIMEOUT_MS = 100

        fun isSerialInterface(iface: UsbInterface): Boolean {
            return iface.interfaceClass == UsbConstants.USB_CLASS_COMM ||
                    iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA ||
                    iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC
        }

        fun findBulkEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint?>? {
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null

            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) {
                        inEp = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) {
                        outEp = ep
                    }
                }
            }

            return inEp?.let { Pair(it, outEp) }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var readJob: Job? = null

    private val _serialState = MutableStateFlow(UsbSerialState())
    val serialState: StateFlow<UsbSerialState> = _serialState.asStateFlow()

    var onDataReceived: ((ByteArray) -> Unit)? = null

    fun startListening(config: UsbSerialConfig = UsbSerialConfig()) {
        if (readJob != null) return

        configureBaudRate(config)

        readJob = scope.launch {
            _serialState.value = _serialState.value.copy(isStreaming = true)
            val buffer = ByteArray(READ_BUFFER_SIZE)
            var bytesReadTotal = 0L

            Log.i(TAG, "Started USB Serial polling on ${device.deviceName} (Endpoint ${inEndpoint.endpointNumber})")

            try {
                while (isActive) {
                    val bytesRead = connection.bulkTransfer(inEndpoint, buffer, buffer.size, TIMEOUT_MS)
                    if (bytesRead > 0) {
                        bytesReadTotal += bytesRead
                        val received = buffer.copyOf(bytesRead)
                        _serialState.value = _serialState.value.copy(totalBytesRead = bytesReadTotal)
                        onDataReceived?.invoke(received)
                    } else if (bytesRead < 0 && bytesRead != -1) {
                        // Transfer error
                        Log.w(TAG, "USB Serial read error: code $bytesRead")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in USB Serial polling loop: ${e.message}")
                _serialState.value = _serialState.value.copy(lastError = e.message)
            } finally {
                _serialState.value = _serialState.value.copy(isStreaming = false)
            }
        }
    }

    fun write(data: ByteArray): Int {
        if (outEndpoint == null) return -1
        return try {
            val written = connection.bulkTransfer(outEndpoint, data, data.size, TIMEOUT_MS)
            if (written > 0) {
                _serialState.value = _serialState.value.copy(
                    totalBytesWritten = _serialState.value.totalBytesWritten + written
                )
            }
            written
        } catch (e: Exception) {
            Log.e(TAG, "USB Serial write error: ${e.message}")
            _serialState.value = _serialState.value.copy(lastError = e.message)
            -1
        }
    }

    private fun configureBaudRate(config: UsbSerialConfig) {
        try {
            // Standard CDC-ACM SET_LINE_CODING (0x20)
            // 7 bytes: [dwDTERate 4B, bCharFormat 1B, bParityType 1B, bDataBits 1B]
            val lineCoding = ByteArray(7)
            val baud = config.baudRate
            lineCoding[0] = (baud and 0xFF).toByte()
            lineCoding[1] = ((baud shr 8) and 0xFF).toByte()
            lineCoding[2] = ((baud shr 16) and 0xFF).toByte()
            lineCoding[3] = ((baud shr 24) and 0xFF).toByte()
            lineCoding[4] = if (config.stopBits == 2) 2.toByte() else 0.toByte() // 0=1 stop, 2=2 stop
            lineCoding[5] = config.parity.toByte()
            lineCoding[6] = config.dataBits.toByte()

            connection.controlTransfer(
                0x21, // Class request, Interface target
                0x20, // SET_LINE_CODING
                0,
                dataInterface.id,
                lineCoding,
                lineCoding.size,
                500
            )

            // SET_CONTROL_LINE_STATE: DTR and RTS high
            connection.controlTransfer(
                0x21,
                0x22, // SET_CONTROL_LINE_STATE
                0x03, // DTR = 1, RTS = 1
                dataInterface.id,
                null,
                0,
                500
            )
            Log.d(TAG, "Configured USB Serial Line Coding: ${config.baudRate} 8N1")
        } catch (e: Exception) {
            Log.w(TAG, "Could not set USB Serial line coding: ${e.message}")
        }
    }

    fun stopListening() {
        readJob?.cancel()
        readJob = null
        _serialState.value = _serialState.value.copy(isStreaming = false)
        Log.i(TAG, "Stopped USB Serial polling on ${device.deviceName}")
    }
}
