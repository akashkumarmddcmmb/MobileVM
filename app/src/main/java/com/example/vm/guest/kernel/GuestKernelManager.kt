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
    val isIsoImage: Boolean,
    val formatDescription: String
)

object GuestKernelManager {
    const val MAX_REASONABLE_KERNEL_BYTES: Long = 256L * 1024L * 1024L // 256 MB max

    fun inspectKernel(kernelPath: String): KernelImageInfo {
        if (kernelPath.isEmpty()) {
            return KernelImageInfo(
                path = "",
                exists = false,
                sizeBytes = 0L,
                architecture = "None",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "No kernel image configured"
            )
        }

        val file = File(kernelPath)
        val exists = file.exists()
        val size = if (exists) file.length() else 0L

        if (!exists) {
            return KernelImageInfo(
                path = kernelPath,
                exists = false,
                sizeBytes = 0L,
                architecture = "Unknown",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "Kernel File Not Found"
            )
        }

        // Check for reasonable size bounds
        if (size > MAX_REASONABLE_KERNEL_BYTES) {
            return KernelImageInfo(
                path = kernelPath,
                exists = true,
                sizeBytes = size,
                architecture = "Unknown",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "File exceeds reasonable ARM64 kernel size limit (> 256 MB): ${size / (1024 * 1024)} MB"
            )
        }

        var isIso = file.name.endsWith(".iso", ignoreCase = true)
        var isArm64 = false
        var isElf64 = false

        if (size >= 64) {
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

                // Check ISO 9660 volume descriptor magic ("CD001" at 0x8001 or 0x8801 or 0x9001)
                if (size >= 0x9006) {
                    file.inputStream().use { stream ->
                        val skipBytes = 0x8000L
                        if (stream.skip(skipBytes) == skipBytes) {
                            val isoBuf = ByteArray(6)
                            if (stream.read(isoBuf) == 6) {
                                if (isoBuf[1] == 'C'.code.toByte() && isoBuf[2] == 'D'.code.toByte() &&
                                    isoBuf[3] == '0'.code.toByte() && isoBuf[4] == '0'.code.toByte() &&
                                    isoBuf[5] == '1'.code.toByte()) {
                                    isIso = true
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                isArm64 = false
                isElf64 = false
            }
        }

        if (isIso) {
            return KernelImageInfo(
                path = kernelPath,
                exists = true,
                sizeBytes = size,
                architecture = "ISO 9660 Installer Disc",
                isArm64Valid = false,
                isIsoImage = true,
                formatDescription = "Rejected: ISO optical disc image detected. An ISO installer disc cannot be used directly as an ARM64 Linux kernel. Please extract or provide the authentic ARM64 'vmlinuz-generic' kernel binary and 'initrd-generic' from the Ubuntu release."
            )
        }

        val valid = isArm64 || isElf64
        val desc = when {
            isArm64 -> "ARM64 Linux Kernel Image (Magic: 0x644D5241 verified)"
            isElf64 -> "ELF64 Linux Kernel Binary (vmlinux)"
            else -> "Unrecognized Kernel Binary (Not ARM64)"
        }

        return KernelImageInfo(
            path = kernelPath,
            exists = true,
            sizeBytes = size,
            architecture = if (valid) "ARM64 (AArch64)" else "Unknown / Invalid",
            isArm64Valid = valid,
            isIsoImage = false,
            formatDescription = desc
        )
    }
}
