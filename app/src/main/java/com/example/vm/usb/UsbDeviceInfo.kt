package com.example.vm.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

data class UsbEndpointInfo(
    val endpointNumber: Int,
    val direction: Int, // UsbConstants.USB_DIR_IN or UsbConstants.USB_DIR_OUT
    val type: Int,      // UsbConstants.USB_ENDPOINT_XFER_BULK, INT, CONTROL, ISOC
    val maxPacketSize: Int,
    val interval: Int
) {
    val directionName: String
        get() = if (direction == UsbConstants.USB_DIR_IN) "IN (Device -> Host)" else "OUT (Host -> Device)"

    val typeName: String
        get() = when (type) {
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
            else -> "TYPE_$type"
        }

    companion object {
        fun fromUsbEndpoint(ep: UsbEndpoint): UsbEndpointInfo {
            return UsbEndpointInfo(
                endpointNumber = ep.endpointNumber,
                direction = ep.direction,
                type = ep.type,
                maxPacketSize = ep.maxPacketSize,
                interval = ep.interval
            )
        }
    }
}

data class UsbInterfaceInfo(
    val id: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val name: String?,
    val endpoints: List<UsbEndpointInfo>
) {
    val className: String
        get() = UsbDeviceInfo.resolveUsbClassName(interfaceClass)

    companion object {
        fun fromUsbInterface(iface: UsbInterface): UsbInterfaceInfo {
            val eps = (0 until iface.endpointCount).map { i ->
                UsbEndpointInfo.fromUsbEndpoint(iface.getEndpoint(i))
            }
            return UsbInterfaceInfo(
                id = iface.id,
                interfaceClass = iface.interfaceClass,
                interfaceSubclass = iface.interfaceSubclass,
                interfaceProtocol = iface.interfaceProtocol,
                name = iface.name,
                endpoints = eps
            )
        }
    }
}

data class UsbDeviceInfo(
    val deviceName: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
    val interfaces: List<UsbInterfaceInfo>,
    val hasPermission: Boolean,
    val isRoutedToVM: Boolean,
    val rawDevice: UsbDevice
) {
    val vendorHex: String
        get() = "0x" + vendorId.toString(16).padStart(4, '0').uppercase()

    val productHex: String
        get() = "0x" + productId.toString(16).padStart(4, '0').uppercase()

    val displayName: String
        get() {
            val prod = productName?.takeIf { it.isNotBlank() }
            val mfg = manufacturerName?.takeIf { it.isNotBlank() }
            return when {
                prod != null && mfg != null -> "$mfg $prod"
                prod != null -> prod
                mfg != null -> "$mfg Device ($vendorHex:$productHex)"
                else -> "USB Device ($vendorHex:$productHex)"
            }
        }

    val deviceClassName: String
        get() = resolveUsbClassName(deviceClass)

    companion object {
        fun resolveUsbClassName(cls: Int): String {
            return when (cls) {
                UsbConstants.USB_CLASS_PER_INTERFACE -> "Per-Interface Defined (Composite/Specialized)"
                UsbConstants.USB_CLASS_AUDIO -> "Audio Endpoint"
                UsbConstants.USB_CLASS_COMM -> "Communications / CDC Control"
                UsbConstants.USB_CLASS_HID -> "Human Interface Device (HID - Keyboard/Mouse/Digitizer)"
                5 -> "Physical Feedback Device"
                6 -> "Image / Still Camera (PTP/MTP)"
                UsbConstants.USB_CLASS_PRINTER -> "Printer Device"
                UsbConstants.USB_CLASS_MASS_STORAGE -> "Mass Storage (U盘 / SSD / Flash Drive)"
                UsbConstants.USB_CLASS_HUB -> "USB Root / Expansion Hub"
                UsbConstants.USB_CLASS_CDC_DATA -> "CDC Data Stream"
                0x0B -> "Smart Card Reader (CCID)"
                UsbConstants.USB_CLASS_CONTENT_SEC -> "Content Security"
                UsbConstants.USB_CLASS_VIDEO -> "Video Camera / UVC Stream"
                0x0F -> "Personal Healthcare"
                0x10 -> "Audio / Video Device"
                0x11 -> "USB Type-C Billboard"
                0x12 -> "USB Type-C Bridge"
                UsbConstants.USB_CLASS_MISC -> "Miscellaneous Device"
                UsbConstants.USB_CLASS_APP_SPEC -> "Application Specific Device"
                UsbConstants.USB_CLASS_VENDOR_SPEC -> "Vendor Specific Custom Interface"
                UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "Wireless Controller (Bluetooth/Wi-Fi)"
                else -> "Standard USB Class (Code $cls)"
            }
        }

        fun fromUsbDevice(
            device: UsbDevice,
            usbManager: UsbManager,
            isRouted: Boolean = false
        ): UsbDeviceInfo {
            val hasPerm = usbManager.hasPermission(device)
            val ifaces = (0 until device.interfaceCount).map { i ->
                UsbInterfaceInfo.fromUsbInterface(device.getInterface(i))
            }

            var mfg: String? = null
            var prod: String? = null
            var serial: String? = null

            if (hasPerm) {
                try {
                    mfg = device.manufacturerName
                    prod = device.productName
                    serial = device.serialNumber
                } catch (_: SecurityException) {
                    // Ignored if permissions dropped
                }
            }

            return UsbDeviceInfo(
                deviceName = device.deviceName,
                deviceId = device.deviceId,
                vendorId = device.vendorId,
                productId = device.productId,
                deviceClass = device.deviceClass,
                deviceSubclass = device.deviceSubclass,
                deviceProtocol = device.deviceProtocol,
                manufacturerName = mfg,
                productName = prod,
                serialNumber = serial,
                interfaces = ifaces,
                hasPermission = hasPerm,
                isRoutedToVM = isRouted,
                rawDevice = device
            )
        }
    }
}
