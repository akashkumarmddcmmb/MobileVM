package com.example.vm.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.util.Locale

/**
 * Detailed hardware identification for a connected USB physical device.
 * Extracts descriptors, vendor/product signatures, interfaces, endpoints,
 * and categorizes the device into the modular subsystem hierarchy.
 */
data class UsbDeviceIdentification(
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
    val category: UsbDeviceCategory,
    val hasPermission: Boolean,
    val interfaces: List<UsbInterfaceInfo>,
    val assignedHandlerName: String,
    val classifierNotes: String
) {
    val vendorHex: String
        get() = "0x" + vendorId.toString(16).padStart(4, '0').uppercase(Locale.US)

    val productHex: String
        get() = "0x" + productId.toString(16).padStart(4, '0').uppercase(Locale.US)

    val displayName: String
        get() {
            val prod = productName?.takeIf { it.isNotBlank() }
            val mfg = manufacturerName?.takeIf { it.isNotBlank() }
            return when {
                prod != null && mfg != null -> "$mfg $prod"
                prod != null -> prod
                mfg != null -> "$mfg Device ($vendorHex:$productHex)"
                else -> "${category.title} ($vendorHex:$productHex)"
            }
        }

    val className: String
        get() = UsbDeviceInfo.resolveUsbClassName(deviceClass)

    companion object {
        // Known USB Serial Vendor IDs
        const val VID_FTDI = 0x0403
        const val VID_SILABS_CP210X = 0x10C4
        const val VID_WCH_CH340 = 0x1A86
        const val VID_PROLIFIC_PL2303 = 0x067B
        const val VID_ARDUINO = 0x2341
        const val VID_ST_MICRO = 0x0483

        /**
         * Identifies and classifies an Android UsbDevice into its canonical subsystem category.
         */
        fun identify(device: UsbDevice, usbManager: UsbManager): UsbDeviceIdentification {
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
                    // Ignored if permission was revoked in race condition
                }
            }

            val (category, handlerName, notes) = classifyDevice(device, ifaces, mfg, prod)

            return UsbDeviceIdentification(
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
                category = category,
                hasPermission = hasPerm,
                interfaces = ifaces,
                assignedHandlerName = handlerName,
                classifierNotes = notes
            )
        }

        private fun classifyDevice(
            device: UsbDevice,
            ifaces: List<UsbInterfaceInfo>,
            mfg: String?,
            prod: String?
        ): Triple<UsbDeviceCategory, String, String> {
            val nameText = "${mfg ?: ""} ${prod ?: ""}".lowercase(Locale.US)

            // 1. Check for USB Mass Storage (Class 0x08)
            val hasStorageIface = ifaces.any { it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
            if (device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE || hasStorageIface) {
                return Triple(
                    UsbDeviceCategory.STORAGE,
                    "UsbStorageHandler",
                    "Class 0x08 (Mass Storage) bulk endpoints detected. Managed via Dual-Mode SCSI BOT engine."
                )
            }

            // 2. Check for USB Serial / UART Adapter
            // CDC-ACM Class 0x02 (Communications) + 0x0A (CDC Data) or known USB-Serial Chipsets
            val hasCdcComm = ifaces.any { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
            val hasCdcData = ifaces.any { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
            val isKnownSerialVid = device.vendorId in listOf(
                VID_FTDI, VID_SILABS_CP210X, VID_WCH_CH340, VID_PROLIFIC_PL2303, VID_ARDUINO
            )
            val nameContainsSerial = nameText.contains("serial") || nameText.contains("uart") ||
                    nameText.contains("ch340") || nameText.contains("cp210") || nameText.contains("ftdi") ||
                    nameText.contains("pl2303")

            if ((hasCdcComm && hasCdcData) || isKnownSerialVid || nameContainsSerial) {
                return Triple(
                    UsbDeviceCategory.SERIAL,
                    "UsbSerialHandler",
                    "Serial/UART device identified (CDC-ACM / FTDI / CP210x / CH340 / PL2303)."
                )
            }

            // 3. Check for Keyboard (HID Class 0x03, Subclass 1 [Boot], Protocol 1 [Keyboard])
            val isKeyboardIface = ifaces.any { iface ->
                iface.interfaceClass == UsbConstants.USB_CLASS_HID &&
                        ((iface.interfaceSubclass == 1 && iface.interfaceProtocol == 1) ||
                         (iface.interfaceProtocol == 1))
            }
            val nameContainsKeyboard = nameText.contains("keyboard") || nameText.contains("keypad")
            if (isKeyboardIface || (ifaces.any { it.interfaceClass == UsbConstants.USB_CLASS_HID } && nameContainsKeyboard)) {
                return Triple(
                    UsbDeviceCategory.KEYBOARD,
                    "UsbKeyboardHandler",
                    "USB HID Keyboard (Boot/Report protocol) mapped to Linux evdev scancodes."
                )
            }

            // 4. Check for Mouse (HID Class 0x03, Subclass 1 [Boot], Protocol 2 [Mouse])
            val isMouseIface = ifaces.any { iface ->
                iface.interfaceClass == UsbConstants.USB_CLASS_HID &&
                        ((iface.interfaceSubclass == 1 && iface.interfaceProtocol == 2) ||
                         (iface.interfaceProtocol == 2))
            }
            val nameContainsMouse = nameText.contains("mouse") || nameText.contains("trackball") || nameText.contains("touchpad")
            if (isMouseIface || (ifaces.any { it.interfaceClass == UsbConstants.USB_CLASS_HID } && nameContainsMouse)) {
                return Triple(
                    UsbDeviceCategory.MOUSE,
                    "UsbMouseHandler",
                    "USB HID Mouse (Relative movement, buttons, wheel) mapped to VirtIO pointer."
                )
            }

            // 5. Check for Generic HID (Class 0x03)
            val hasHidIface = ifaces.any { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            if (device.deviceClass == UsbConstants.USB_CLASS_HID || hasHidIface) {
                return Triple(
                    UsbDeviceCategory.GENERIC_HID,
                    "UsbGenericHidHandler",
                    "Generic Human Interface Device (Gamepad, Joystick, Digitizer, Barcode Scanner)."
                )
            }

            // 6. Future Supported Devices (Audio, Video, Network, Vendor Specific)
            return Triple(
                UsbDeviceCategory.FUTURE_SUPPORTED,
                "UsbFutureDeviceHandler",
                "Device class ${device.deviceClass} (${UsbDeviceInfo.resolveUsbClassName(device.deviceClass)}). Extensible handler registry active."
            )
        }
    }
}
