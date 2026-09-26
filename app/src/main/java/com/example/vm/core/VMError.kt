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
    UNEXPECTED_SHUTDOWN("VM Stopped Unexpectedly")
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
            summary = "Configured Linux kernel image was not found at specified path.",
            technicalDetails = "Target path: '$path'. $details",
            suggestedRemedy = "Provide a valid ARM64 uncompressed Image or vmlinux file, or leave blank to use built-in microkernel."
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
    }
}
