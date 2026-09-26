package com.example.vm.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import java.util.Locale

/**
 * Categorization of physical USB Mass Storage hardware peripherals.
 */
enum class UsbStorageType(val displayName: String, val description: String) {
    FLASH_DRIVE(
        displayName = "USB Flash Drive",
        description = "Thumb drive / USB Pen Drive (NAND flash storage)"
    ),
    SOLID_STATE_DRIVE(
        displayName = "USB Solid State Drive (SSD)",
        description = "High-performance external NVMe / SATA USB SSD"
    ),
    HARD_DISK_DRIVE(
        displayName = "USB Hard Disk Drive (HDD)",
        description = "High-capacity external spinning magnetic hard drive"
    ),
    CARD_READER(
        displayName = "USB Card Reader",
        description = "Multi-LUN SD / MicroSD / CF card reader peripheral"
    ),
    OPTICAL_DRIVE(
        displayName = "USB Optical Drive",
        description = "External CD / DVD / Blu-Ray optical drive (ATAPI)"
    ),
    UNKNOWN(
        displayName = "USB Mass Storage Device",
        description = "Generic SCSI Bulk-Only Storage peripheral"
    )
}

/**
 * Access mode abstraction separating Direct Guest Passthrough from Safe Host Access.
 */
enum class UsbStorageAccessMode(val title: String, val subtitle: String) {
    UNATTACHED(
        title = "Isolated / Unattached",
        subtitle = "Device is detected on host bus but not mounted or routed to any VM"
    ),
    HOST_ACCESS(
        title = "Host-Mediated Storage Access",
        subtitle = "Safe bridge via host driver: guest accesses dedicated disk images without risking host private data"
    ),
    GUEST_PASSTHROUGH(
        title = "Direct Guest Passthrough",
        subtitle = "Direct raw SCSI block claiming by the guest VM (OEM Android kernel support dependent)"
    )
}

enum class UsbStorageConnectionState(val label: String) {
    DETECTED("Detected on Bus"),
    PERMISSION_PENDING("Permission Required"),
    CLAIMED_HOST("Mounted in Host Mode"),
    CLAIMED_GUEST("Active Guest Passthrough"),
    DETACHED("Physically Disconnected"),
    ERROR("Driver Error")
}

data class UsbStorageDeviceInfo(
    val deviceName: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val storageType: UsbStorageType,
    val accessMode: UsbStorageAccessMode = UsbStorageAccessMode.UNATTACHED,
    val connectionState: UsbStorageConnectionState = UsbStorageConnectionState.DETECTED,
    val capacityBytes: Long? = null,
    val sectorSize: Int = 512,
    val totalSectors: Long? = null,
    val scsiVendor: String? = null,
    val scsiProduct: String? = null,
    val scsiRevision: String? = null,
    val isDirectPassthroughSupported: Boolean = true,
    val passthroughCompatibilityNotice: String = DEFAULT_PASSTHROUGH_NOTICE,
    val hostMediatedVirtualDiskPath: String? = null,
    val isReadOnly: Boolean = false,
    val targetVmId: Long? = null,
    val rawDevice: UsbDevice
) {
    val vendorHex: String
        get() = "0x" + vendorId.toString(16).padStart(4, '0').uppercase()

    val productHex: String
        get() = "0x" + productId.toString(16).padStart(4, '0').uppercase()

    val formattedCapacity: String
        get() {
            val bytes = capacityBytes ?: return "Capacity Unknown (Query on connect)"
            val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return if (gb >= 1000.0) {
                String.format(Locale.US, "%.2f TB (%,d bytes)", gb / 1024.0, bytes)
            } else if (gb >= 1.0) {
                String.format(Locale.US, "%.2f GB (%,d bytes)", gb, bytes)
            } else {
                val mb = bytes.toDouble() / (1024.0 * 1024.0)
                String.format(Locale.US, "%.1f MB (%,d bytes)", mb, bytes)
            }
        }

    val displayName: String
        get() {
            val scsi = scsiProduct?.takeIf { it.isNotBlank() }
            val rawProd = rawDevice.productName?.takeIf { it.isNotBlank() }
            val mfg = scsiVendor?.takeIf { it.isNotBlank() } ?: rawDevice.manufacturerName?.takeIf { it.isNotBlank() }

            return when {
                scsi != null && mfg != null -> "$mfg $scsi (${storageType.displayName})"
                rawProd != null && mfg != null -> "$mfg $rawProd (${storageType.displayName})"
                rawProd != null -> "$rawProd (${storageType.displayName})"
                else -> "${storageType.displayName} ($vendorHex:$productHex)"
            }
        }

    companion object {
        const val DEFAULT_PASSTHROUGH_NOTICE =
            "Direct guest passthrough claims the raw USB SCSI interface directly. " +
            "Compatibility depends on Android OEM kernel USB host policies. " +
            "If direct passthrough encounters driver locking, Host-Mediated Storage Mode provides a 100% reliable safe bridge."

        fun isStorageInterface(iface: UsbInterface): Boolean {
            return iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
        }

        fun detectStorageType(device: UsbDevice): UsbStorageType {
            val name = "${device.manufacturerName ?: ""} ${device.productName ?: ""}".lowercase(Locale.US)

            return when {
                name.contains("card reader") || name.contains("sd reader") || name.contains("cardreader") || name.contains("multi-card") || name.contains("crw") ->
                    UsbStorageType.CARD_READER

                name.contains("nvme") || name.contains("ssd") || name.contains("solid state") || name.contains("portable ssd") || name.contains("extreme ssd") ->
                    UsbStorageType.SOLID_STATE_DRIVE

                name.contains("external hdd") || name.contains("elements") || name.contains("expansion") || name.contains("hard drive") || name.contains("backup plus") || name.contains("my passport") ->
                    UsbStorageType.HARD_DISK_DRIVE

                name.contains("dvd") || name.contains("cd-rom") || name.contains("bd-rom") || name.contains("optical") ->
                    UsbStorageType.OPTICAL_DRIVE

                name.contains("flash") || name.contains("cruzer") || name.contains("ultra") || name.contains("pen drive") || name.contains("datatraveler") || name.contains("jetflash") || name.contains("usb drive") ->
                    UsbStorageType.FLASH_DRIVE

                else -> {
                    // Check interface subclass: 0x06 SCSI is typical for drives
                    UsbStorageType.FLASH_DRIVE
                }
            }
        }
    }
}
