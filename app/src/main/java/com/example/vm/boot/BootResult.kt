package com.example.vm.boot

enum class BootStage(val displayName: String) {
    VALIDATION("Pre-Flight Validation"),
    FIRMWARE("ARM64 UEFI Firmware Loading"),
    NVRAM("UEFI NVRAM Variable Resolution"),
    DISK("Virtual Disk & GPT Partition Table Detection"),
    ESP("EFI System Partition (ESP) Inspection"),
    EFI_LOADER("EFI Boot Application Discovery"),
    BOOT_MANAGER("Boot Manager Execution"),
    OS_LOADER("OS Loader Execution"),
    GUEST("Guest OS Kernel Initialization")
}

data class BootDiagnostics(
    val firmwareValid: Boolean,
    val firmwarePath: String,
    val nvramStoreValid: Boolean,
    val diskExists: Boolean,
    val gptValid: Boolean,
    val espExists: Boolean,
    val fat32Valid: Boolean,
    val efiLoaderFound: Boolean,
    val efiLoaderPath: String,
    val bcdValid: Boolean,
    val kernelFound: Boolean,
    val initramfsFound: Boolean,
    val rootfsFound: Boolean,
    val archCompatible: Boolean,
    val details: String
)

data class BootResult(
    val success: Boolean,
    val stage: BootStage,
    val errorCode: String? = null,
    val message: String,
    val recoveryAvailable: Boolean = true,
    val diagnostics: BootDiagnostics
)
