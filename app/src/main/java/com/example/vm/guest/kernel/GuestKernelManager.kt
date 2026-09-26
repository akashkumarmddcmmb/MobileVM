package com.example.vm.guest.kernel

import java.io.File

/**
 * Guest Linux Kernel Management Module.
 * Validates, locates, and inspects ARM64 Linux Kernel Images (Image, vmlinuz)
 * for booting inside the VM according to Documentation/arch/arm64/booting.rst.
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
                path = "",
                exists = false,
                sizeBytes = 0L,
                architecture = "None",
                isArm64Valid = false,
                formatDescription = "No kernel image configured"
            )
        }

        val file = File(kernelPath)
        val exists = file.exists()
        val size = if (exists) file.length() else 0L

        // A standard ARM64 Linux Image has "ARM\x64" magic at offset 0x38 (0x644D5241)
        // or ELF64 header (\x7fELF with class 2)
        var isArm64 = false
        var isElf64 = false
        if (exists && size >= 64) {
            try {
                file.inputStream().use { stream ->
                    val header = ByteArray(64)
                    stream.read(header)
                    // Check magic bytes at 0x38: 0x41, 0x52, 0x4D, 0x64 ("ARMd" in LE)
                    isArm64 = (header[0x38] == 0x41.toByte() && header[0x39] == 0x52.toByte() &&
                               header[0x3A] == 0x4D.toByte() && header[0x3B] == 0x64.toByte())

                    // Check ELF64
                    isElf64 = (header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
                               header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
                               header[4] == 2.toByte())
                }
            } catch (e: Exception) {
                isArm64 = false
                isElf64 = false
            }
        }

        val valid = exists && (isArm64 || isElf64)
        val desc = when {
            isArm64 -> "ARM64 Linux Kernel Image (Magic: 0x644D5241 verified)"
            isElf64 -> "ELF64 Linux Kernel Binary (vmlinux)"
            exists -> "Unrecognized Kernel Binary (Not ARM64)"
            else -> "Kernel File Not Found"
        }

        return KernelImageInfo(
            path = kernelPath,
            exists = exists,
            sizeBytes = size,
            architecture = if (valid) "ARM64 (AArch64)" else "Unknown / Invalid",
            isArm64Valid = valid,
            formatDescription = desc
        )
    }
}
