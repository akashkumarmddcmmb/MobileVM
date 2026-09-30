package com.example.vm.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Categories for diagnostic error reporting in the VM subsystem.
 */
enum class VMErrorCategory(val displayTitle: String) {
    BACKEND_UNAVAILABLE("VM Backend Unavailable"),
    VIRTUALIZATION_UNAVAILABLE("Virtualization Unavailable"),
    EMULATOR_INIT_FAILED("Emulator Initialization Failed"),
    KERNEL_MISSING("Kernel Image Missing"),
    INITRAMFS_MISSING("Initramfs Image Missing"),
    DISK_INVALID("Virtual Disk Image Invalid"),
    INSUFFICIENT_RAM("Insufficient Host RAM"),
    USB_PERMISSION_DENIED("USB Permission Denied"),
    USB_UNSUPPORTED("USB Device Unsupported"),
    GUEST_BOOT_FAILED("Guest Boot Failed"),
    UNEXPECTED_SHUTDOWN("VM Stopped Unexpectedly"),
    UNSUPPORTED_INSTRUCTION("Unsupported ARM64 Instruction"),
    INVALID_GUEST_MEMORY("Invalid Guest Memory Access"),
    UNSUPPORTED_DEVICE("Unsupported Device"),
    UNSUPPORTED_CPU_FEATURE("Unsupported CPU Feature"),
    KERNEL_PANIC("Guest Kernel Panic"),
    DOWNLOAD_FAILED("OS Download Failed"),
    CHECKSUM_FAILED("Checksum Verification Failed"),
    INSUFFICIENT_STORAGE("Insufficient Device Storage"),
    INVALID_KERNEL("Invalid Linux Kernel"),
    INVALID_INITRAMFS("Invalid Initramfs"),
    INVALID_DISK("Invalid Virtual Disk"),
    UNSUPPORTED_ARCHITECTURE("Unsupported Architecture"),
    INVALID_DTB("Invalid Device Tree Blob"),
    GUEST_MEMORY_ERROR("Guest Memory Error"),
    VIRTUAL_DEVICE_NOT_IMPLEMENTED("Virtual Device Not Implemented"),
    PTY_NOT_IMPLEMENTED("Linux PTY Not Implemented"),
    NETWORK_NOT_IMPLEMENTED("Virtual Network Not Implemented"),
    KVM_NOT_AVAILABLE("KVM Not Available"),
    WINDOWS_IMAGE_NOT_CONFIGURED("Windows Image Not Configured"),
    WINDOWS_IMAGE_NOT_FOUND("Windows Image Not Found"),
    WINDOWS_IMAGE_UNREADABLE("Windows Image Unreadable"),
    WINDOWS_IMAGE_INVALID("Windows Image Invalid"),
    WINDOWS_IMAGE_UNSUPPORTED("Windows Image Unsupported"),
    FIRMWARE_UNAVAILABLE("Firmware Unavailable"),
    CONFIGURATION_ERROR("Configuration Error"),
    IMAGE_ERROR("Image Error"),
    KVM_ERROR("KVM Error"),
    MEMORY_ERROR("Memory Allocation Error"),
    VCPU_ERROR("Virtual CPU Error"),
    DISK_ERROR("Virtual Disk Error"),
    FIRMWARE_ERROR("Firmware Error"),
    KERNEL_ERROR("Kernel Error"),
    INITRAMFS_ERROR("Initramfs Error"),
    BOOT_ERROR("Boot Error"),
    LIFECYCLE_ERROR("Lifecycle Operation Error"),
    TIMEOUT_ERROR("Operation Timeout"),
    NATIVE_ERROR("Native Engine Error")
}

/**
 * Diagnostic error report with technical logs, cause analysis, and remedy suggestions.
 */
data class VMError(
    val category: VMErrorCategory,
    val summary: String,
    val technicalDetails: String,
    val suggestedRemedy: String,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val formattedTimestamp: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            return sdf.format(Date(timestampMs))
        }

    companion object {
        fun backendUnavailable(details: String): VMError = VMError(
            category = VMErrorCategory.BACKEND_UNAVAILABLE,
            summary = "Native virtualization engine library is not loaded or initialization failed.",
            technicalDetails = "Native library libnative_vm.so load error: $details",
            suggestedRemedy = "Verify application binary architecture matches device ABI (arm64-v8a or x86_64) and restart."
        )

        fun virtualizationUnavailable(reason: String): VMError = VMError(
            category = VMErrorCategory.VIRTUALIZATION_UNAVAILABLE,
            summary = "Hardware-assisted virtualization (/dev/kvm) is unavailable on this device kernel.",
            technicalDetails = "KVM probe diagnostics: $reason. Android host kernel does not expose KVM ioctls to unprivileged apps.",
            suggestedRemedy = "Switch the VM configuration acceleration to Software CPU Emulation mode in VM settings."
        )

        fun emulatorInitFailed(cause: String): VMError = VMError(
            category = VMErrorCategory.EMULATOR_INIT_FAILED,
            summary = "Failed to construct virtual CPU registers or memory map during emulator startup.",
            technicalDetails = "Emulator setup error: $cause",
            suggestedRemedy = "Review VM vCPU count, RAM allocation, and device bus MMIO configurations."
        )

        fun kernelMissing(path: String, details: String = ""): VMError = VMError(
            category = VMErrorCategory.KERNEL_MISSING,
            summary = "ARM64 Linux kernel image is missing or invalid.",
            technicalDetails = "Target path: '$path'. $details",
            suggestedRemedy = "Import or download a valid ARM64 uncompressed Image or vmlinux file in VM Settings."
        )

        fun initramfsMissing(path: String): VMError = VMError(
            category = VMErrorCategory.INITRAMFS_MISSING,
            summary = "Configured initramfs / rootfs archive could not be opened.",
            technicalDetails = "Failed to read initrd archive at '$path'. File not found or unreadable.",
            suggestedRemedy = "Verify the cpio.gz / initramfs archive exists and Android read permissions are granted."
        )

        fun diskInvalid(path: String, details: String): VMError = VMError(
            category = VMErrorCategory.DISK_INVALID,
            summary = "Virtual disk image file is missing, corrupted, or has invalid partition geometry.",
            technicalDetails = "Disk target: '$path'. Error: $details",
            suggestedRemedy = "Click 'Allocate' on the VM card to format a raw sparse virtual disk before booting."
        )

        fun insufficientRam(requestedMb: Int, availableMb: Long): VMError = VMError(
            category = VMErrorCategory.INSUFFICIENT_RAM,
            summary = "Requested guest RAM ($requestedMb MB) exceeds available device physical memory ($availableMb MB).",
            technicalDetails = "Android Low Memory Killer (LMK) protection prevented allocation to avoid system OOM crash.",
            suggestedRemedy = "Reduce guest RAM in VM configuration to a safe value (e.g., 512 MB or 1024 MB)."
        )

        fun usbPermissionDenied(deviceName: String, vid: String, pid: String): VMError = VMError(
            category = VMErrorCategory.USB_PERMISSION_DENIED,
            summary = "Android USB Host permission was rejected for peripheral $deviceName ($vid:$pid).",
            technicalDetails = "UsbManager.requestPermission() user cancelled or security policy denied host claiming.",
            suggestedRemedy = "Reconnect the USB device and tap 'Allow' when the system USB permission dialog appears."
        )

        fun usbUnsupported(deviceName: String, deviceClass: Int): VMError = VMError(
            category = VMErrorCategory.USB_UNSUPPORTED,
            summary = "USB peripheral ($deviceName) uses an unsupported USB interface class (0x${deviceClass.toString(16)}).",
            technicalDetails = "Device descriptor class 0x${deviceClass.toString(16)} has no active guest virtual device binding.",
            suggestedRemedy = "Connect supported USB peripherals: Keyboards (HID 0x03), Mice (HID 0x03), or Mass Storage (MSC 0x08)."
        )

        fun guestBootFailed(reason: String): VMError = VMError(
            category = VMErrorCategory.GUEST_BOOT_FAILED,
            summary = "Guest OS boot sequence aborted before reaching userspace.",
            technicalDetails = "Boot fault: $reason",
            suggestedRemedy = "Check serial console (ttyAMA0) logs for kernel panic or missing rootfs bootargs."
        )

        fun unexpectedShutdown(exitCode: Int, lastPc: Long): VMError = VMError(
            category = VMErrorCategory.UNEXPECTED_SHUTDOWN,
            summary = "VM instance stopped unexpectedly or triggered an unhandled hardware trap.",
            technicalDetails = "Exit code: $exitCode, Program Counter (PC): 0x${lastPc.toString(16).uppercase()}",
            suggestedRemedy = "Inspect registers and recent serial output to identify the faulting instruction."
        )

        fun unsupportedInstruction(opcode: Long, pc: Long): VMError = VMError(
            category = VMErrorCategory.UNSUPPORTED_INSTRUCTION,
            summary = "Unsupported ARM64 CPU instruction encountered during guest execution.",
            technicalDetails = "Opcode 0x${opcode.toString(16).uppercase()} at PC 0x${pc.toString(16).uppercase()}.",
            suggestedRemedy = "The selected kernel boot path executed an ARM64 instruction not yet supported by the software emulator. Report the opcode and PC."
        )

        fun invalidGuestMemoryAccess(address: Long, size: Long): VMError = VMError(
            category = VMErrorCategory.INVALID_GUEST_MEMORY,
            summary = "Guest attempted to access memory outside allocated guest RAM or MMIO range.",
            technicalDetails = "Invalid access at 0x${address.toString(16).uppercase()} (size: $size bytes).",
            suggestedRemedy = "Verify guest memory limits in VM configuration and device tree memory node."
        )

        fun initramfsMissingInit(path: String): VMError = VMError(
            category = VMErrorCategory.INITRAMFS_MISSING,
            summary = "REAL BOOT FAILED: initramfs does not contain a usable /init",
            technicalDetails = "Initramfs at '$path' was unpacked and scanned but does not contain a root '/init' or executable binary.",
            suggestedRemedy = "Provide a valid Linux ARM64 initramfs containing an executable '/init' script or binary."
        )

        fun unsupportedDevice(deviceName: String): VMError = VMError(
            category = VMErrorCategory.UNSUPPORTED_DEVICE,
            summary = "Guest requested access to unsupported virtual device: $deviceName",
            technicalDetails = "Device '$deviceName' is not mapped in the emulator MMIO bus.",
            suggestedRemedy = "Check kernel command line or device tree blob configurations."
        )

        fun unsupportedCpuFeature(feature: String): VMError = VMError(
            category = VMErrorCategory.UNSUPPORTED_CPU_FEATURE,
            summary = "Guest kernel requires unsupported ARM64 CPU feature: $feature",
            technicalDetails = "CPU feature '$feature' is not implemented in the software emulation core.",
            suggestedRemedy = "Use a kernel compiled with baseline ARMv8-A options."
        )

        fun kernelPanic(panicMessage: String): VMError = VMError(
            category = VMErrorCategory.KERNEL_PANIC,
            summary = "Guest Linux kernel reported a fatal kernel panic.",
            technicalDetails = "Kernel panic: $panicMessage",
            suggestedRemedy = "Inspect console output (ttyAMA0) to diagnose the root cause of the panic."
        )

        fun downloadFailed(url: String, reason: String): VMError = VMError(
            category = VMErrorCategory.DOWNLOAD_FAILED,
            summary = "OS download failed from official source.",
            technicalDetails = "URL: $url. Error: $reason",
            suggestedRemedy = "Verify internet connectivity and HTTPS certificate status, then retry."
        )

        fun checksumFailed(expected: String, computed: String): VMError = VMError(
            category = VMErrorCategory.CHECKSUM_FAILED,
            summary = "Verification failed: SHA-256 mismatch",
            technicalDetails = "Expected: $expected\nComputed: $computed",
            suggestedRemedy = "The downloaded file was corrupted or incomplete. The file has been deleted for safety. Please re-download."
        )

        fun insufficientStorage(requiredMb: Long, availableMb: Long): VMError = VMError(
            category = VMErrorCategory.INSUFFICIENT_STORAGE,
            summary = "Insufficient device storage space for OS download and VM allocation.",
            technicalDetails = "Required: $requiredMb MB (with 500 MB headroom), Available: $availableMb MB",
            suggestedRemedy = "Free up device internal storage space before proceeding with download."
        )

        fun ptyNotImplemented(): VMError = VMError(
            category = VMErrorCategory.PTY_NOT_IMPLEMENTED,
            summary = "Linux PTY terminal: NOT IMPLEMENTED",
            technicalDetails = "Unix pseudo-terminal (PTY) driver is not active. Using direct PL011 UART ttyAMA0 console.",
            suggestedRemedy = "Keystrokes are delivered directly to the guest serial console RX register."
        )

        fun networkNotImplemented(): VMError = VMError(
            category = VMErrorCategory.NETWORK_NOT_IMPLEMENTED,
            summary = "Virtual Network: NOT IMPLEMENTED",
            technicalDetails = "VirtIO-Net SLIRP/NAT virtual network adapter is not yet implemented.",
            suggestedRemedy = "Operate the VM in standalone/offline mode."
        )

        fun kvmNotAvailable(reason: String): VMError = VMError(
            category = VMErrorCategory.KVM_NOT_AVAILABLE,
            summary = "KVM: NOT AVAILABLE",
            technicalDetails = "Kernel virtual machine node /dev/kvm is not exposed by host Android kernel. Reason: $reason",
            suggestedRemedy = "Operating with real ARM64 software-emulation backend."
        )

        fun windowsImageNotConfigured(details: String = ""): VMError = VMError(
            category = VMErrorCategory.WINDOWS_IMAGE_NOT_CONFIGURED,
            summary = "WINDOWS_IMAGE_NOT_CONFIGURED: Windows ARM64 boot media or virtual disk image is not configured.",
            technicalDetails = if (details.isNotBlank()) details else "No user-supplied Windows ARM64 installation ISO or disk image specified.",
            suggestedRemedy = "Import a legally obtained Windows 11 on ARM ISO or virtual disk into application storage via the Import Manager."
        )

        fun windowsImageNotFound(path: String): VMError = VMError(
            category = VMErrorCategory.WINDOWS_IMAGE_NOT_FOUND,
            summary = "WINDOWS_IMAGE_NOT_FOUND: Windows image file does not exist on disk.",
            technicalDetails = "Windows boot image file not found at path: '$path'.",
            suggestedRemedy = "Re-import the Windows ARM64 installation media into private application storage."
        )

        fun windowsImageUnreadable(path: String): VMError = VMError(
            category = VMErrorCategory.WINDOWS_IMAGE_UNREADABLE,
            summary = "WINDOWS_IMAGE_UNREADABLE: Windows image is not readable.",
            technicalDetails = "Permission denied or I/O error reading Windows image at: '$path'.",
            suggestedRemedy = "Verify file permissions or re-select media using Storage Access Framework."
        )

        fun windowsImageInvalid(path: String, reason: String): VMError = VMError(
            category = VMErrorCategory.WINDOWS_IMAGE_INVALID,
            summary = "WINDOWS_IMAGE_INVALID: Windows ARM64 installation media or disk image is invalid or corrupted.",
            technicalDetails = "Path: '$path'. Reason: $reason",
            suggestedRemedy = "Ensure the Windows ISO/disk was created for the ARM64 (AArch64) architecture, is non-corrupt, and contains valid GPT/ESP or ISO 9660 volume structures."
        )

        fun windowsImageUnsupported(path: String, reason: String): VMError = VMError(
            category = VMErrorCategory.WINDOWS_IMAGE_UNSUPPORTED,
            summary = "WINDOWS_IMAGE_UNSUPPORTED: Unsupported Windows image format or architecture.",
            technicalDetails = "Path: '$path'. Reason: $reason",
            suggestedRemedy = "MobileVM requires authentic ARM64 (AArch64) Windows 11/10 ISO or GPT disk images. x86/x64 images are not supported."
        )

        fun windowsImageMissing(details: String = ""): VMError = windowsImageNotConfigured(details)

        fun firmwareUnavailable(arch: String, reason: String): VMError = VMError(
            category = VMErrorCategory.FIRMWARE_UNAVAILABLE,
            summary = "FIRMWARE_UNAVAILABLE: UEFI/EDK2 Firmware configuration is unavailable for $arch.",
            technicalDetails = "Firmware setup failure: $reason",
            suggestedRemedy = "Verify the UEFI EDK2 (QEMU_EFI.fd) firmware image and NVRAM variable store configuration."
        )

        fun lifecycleError(summary: String, details: String = "", remedy: String = ""): VMError = VMError(
            category = VMErrorCategory.LIFECYCLE_ERROR,
            summary = summary,
            technicalDetails = details,
            suggestedRemedy = if (remedy.isNotBlank()) remedy else "Wait for the current VM operation to finish before retrying."
        )

        fun timeoutError(operation: String, details: String = ""): VMError = VMError(
            category = VMErrorCategory.TIMEOUT_ERROR,
            summary = "Operation '$operation' timed out.",
            technicalDetails = "The VM operation '$operation' exceeded the safety timeout threshold. $details",
            suggestedRemedy = "The VM state has been cleanly reset to STOPPED. Please verify host resources and retry."
        )

        fun nativeError(operation: String, details: String): VMError = VMError(
            category = VMErrorCategory.NATIVE_ERROR,
            summary = "Native hypervisor engine failed during $operation.",
            technicalDetails = "Native error details: $details",
            suggestedRemedy = "Check system memory, KVM capability, and restart the VM instance."
        )

        fun memoryError(reason: String, details: String = ""): VMError = VMError(
            category = VMErrorCategory.MEMORY_ERROR,
            summary = "Failed to allocate or map guest physical memory: $reason",
            technicalDetails = details,
            suggestedRemedy = "Reduce configured VM RAM size in VM Settings."
        )

        fun vcpuError(reason: String, details: String = ""): VMError = VMError(
            category = VMErrorCategory.VCPU_ERROR,
            summary = "Virtual CPU initialization failed: $reason",
            technicalDetails = details,
            suggestedRemedy = "Verify host CPU virtualization support or reduce CPU core count."
        )

        fun diskError(path: String, reason: String): VMError = VMError(
            category = VMErrorCategory.DISK_ERROR,
            summary = "Virtual disk operation failed for '$path': $reason",
            technicalDetails = "Path: $path. $reason",
            suggestedRemedy = "Check disk image format, read permissions, and available storage."
        )
    }
}
