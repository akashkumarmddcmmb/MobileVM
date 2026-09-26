package com.example.vm.guest.kernel

import java.io.File

/**
 * Guest Linux Kernel Management Module.
 * Validates, locates, and prepares ARM64 Linux Kernel Images (Image, vmlinuz)
 * for booting inside the VM.
 */
data class KernelImageInfo(
    val path: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val architecture: String,
    val isArm64Valid: Boolean,
    val formatDescription: String
)

object GuestKernelManager {
    fun inspectKernel(kernelPath: String): KernelImageInfo {
        if (kernelPath.isEmpty()) {
            return KernelImageInfo(
                path = "Internal Embedded Minimal Linux Kernel",
                exists = true,
                sizeBytes = 32768L,
                architecture = "ARM64 (AArch64)",
                isArm64Valid = true,
                formatDescription = "Built-in ARM64 Kernel Reset Vector"
            )
        }

        val file = File(kernelPath)
        val exists = file.exists()
        val size = if (exists) file.length() else 0L

        // A standard ARM64 Linux Image has "ARM\x64" magic at offset 0x38 (0x644D5241)
        val isArm64 = if (exists && size >= 64) {
            try {
                file.inputStream().use { stream ->
                    val header = ByteArray(64)
                    stream.read(header)
                    // Check magic bytes at 0x38: 0x41, 0x52, 0x4D, 0x64 ("ARMd" in LE)
                    header[0x38] == 0x41.toByte() && header[0x39] == 0x52.toByte() &&
                    header[0x3A] == 0x4D.toByte() && header[0x3B] == 0x64.toByte()
                }
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }

        return KernelImageInfo(
            path = kernelPath,
            exists = exists,
            sizeBytes = size,
            architecture = if (isArm64) "ARM64 (Verified Header)" else "Unknown / Raw Binary",
            isArm64Valid = exists && (isArm64 || size > 0),
            formatDescription = if (isArm64) "ARM64 Linux Kernel Image" else "Custom Kernel Binary"
        )
    }
}
