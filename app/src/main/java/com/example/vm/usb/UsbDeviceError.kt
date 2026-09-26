package com.example.vm.usb

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class UsbErrorCategory(val displayTitle: String) {
    PERMISSION_DENIED("USB Host Permission Denied"),
    CLAIM_FAILED("Interface Claim Conflict"),
    EXCLUSIVE_LOCK_FAILURE("Host Driver Exclusivity"),
    IO_ERROR("Endpoint I/O Transfer Failure"),
    DETACHED_UNEXPECTEDLY("Device Detached During Active Session"),
    DEVICE_NOT_FOUND("Device Not Enumerated"),
    PROTOCOL_ERROR("USB Protocol Parsing Error"),
    UNSUPPORTED_DEVICE("Unsupported Device Configuration")
}

data class UsbDeviceError(
    val category: UsbErrorCategory,
    val deviceName: String,
    val message: String,
    val suggestedRemedy: String,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val formattedTimestamp: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
}
