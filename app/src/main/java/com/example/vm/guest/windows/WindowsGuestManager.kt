package com.example.vm.guest.windows

/**
 * Windows Guest Module [Future Target].
 * Defines bootloader specifications for Windows on ARM (WoA) and x86_64 Windows.
 *
 * Requirements:
 * - UEFI EDK2 Firmware (QEMU_EFI.fd)
 * - ACPI 6.0 Tables (MADT, FADT, GTDT, DSDT)
 * - Red Hat VirtIO Drivers for Windows (viostor.sys, netkvm.sys)
 * - Strict Licensing Boundary: ISO/VHDX must be user-provided from Microsoft.
 *   No proprietary Windows components bundled into MobileVM.
 */
data class WindowsGuestRoadmap(
    val targetOs: String = "Windows 11 ARM64 (Future)",
    val isEnabled: Boolean = false,
    val firmwareRequired: String = "TianoCore EDK2 (UEFI)",
    val tableStandard: String = "ACPI 6.x Mandatory",
    val driverRequirements: String = "Red Hat VirtIO Windows WHQL Drivers (viostor, netkvm)",
    val licensingNotice: String = "Windows ISO and activation keys are strictly user-provided. Proprietary components are never bundled."
)

object WindowsGuestManager {
    fun getRoadmap(): WindowsGuestRoadmap = WindowsGuestRoadmap()
}
