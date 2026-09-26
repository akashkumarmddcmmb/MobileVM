package com.example.vm.usb

/**
 * Universal taxonomy of physical USB devices supported by MobileVM.
 * Represents the modular hierarchy:
 * USB Manager
 * ├── Keyboard
 * ├── Mouse
 * ├── HID
 * ├── Storage
 * ├── Serial
 * └── Future supported devices
 */
enum class UsbDeviceCategory(
    val title: String,
    val description: String,
    val defaultDriverName: String
) {
    KEYBOARD(
        title = "USB Keyboard",
        description = "Standard HID keyboard peripheral (Boot & Report Protocol)",
        defaultDriverName = "USB HID Keyboard Driver"
    ),
    MOUSE(
        title = "USB Mouse",
        description = "Relative pointer & scroll wheel mouse device",
        defaultDriverName = "USB HID Mouse Driver"
    ),
    GENERIC_HID(
        title = "Generic HID",
        description = "Human Interface Device (Gamepad, Joystick, Digitizer, Barcode Scanner)",
        defaultDriverName = "Generic USB HID Driver"
    ),
    STORAGE(
        title = "USB Mass Storage",
        description = "Bulk-Only Transport SCSI storage (Flash Drive, SSD, HDD, Card Reader)",
        defaultDriverName = "USB Mass Storage BOT/SCSI Driver"
    ),
    SERIAL(
        title = "USB Serial / UART",
        description = "USB to UART communication adapter (CDC-ACM, FTDI, CP210x, CH340, PL2303)",
        defaultDriverName = "USB CDC-ACM / Serial Driver"
    ),
    FUTURE_SUPPORTED(
        title = "Future Supported Device",
        description = "Extensible peripheral category (Audio, Network/Ethernet, Vendor Custom)",
        defaultDriverName = "Generic Host-Mediated Passthrough Driver"
    )
}
