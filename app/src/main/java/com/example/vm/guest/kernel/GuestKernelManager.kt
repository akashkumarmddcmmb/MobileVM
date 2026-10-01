package com.example.vm.guest.kernel

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Guest Linux Kernel Management Module.
 * Validates, locates, and inspects ARM64 Linux Kernel Images (Image, vmlinuz, vmlinux)
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
    const val MAX_REASONABLE_KERNEL_BYTES: Long = 512L * 1024L * 1024L // 512 MB max

    fun inspectKernel(kernelPath: String): KernelImageInfo {
        if (kernelPath.isBlank()) {
            return KernelImageInfo(
                path = "",
                exists = false,
                sizeBytes = 0L,
                architecture = "None",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "Kernel path is empty"
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

        if (size < 64) {
            return KernelImageInfo(
                path = kernelPath,
                exists = true,
                sizeBytes = size,
                architecture = "Invalid",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "Kernel file is empty or truncated ($size bytes)"
            )
        }

        if (size > MAX_REASONABLE_KERNEL_BYTES) {
            return KernelImageInfo(
                path = kernelPath,
                exists = true,
                sizeBytes = size,
                architecture = "Invalid",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "File exceeds reasonable kernel size limit (> 512 MB): ${size / (1024 * 1024)} MB"
            )
        }

        val header = ByteArray(512)
        var bytesRead = 0
        try {
            file.inputStream().use { stream ->
                bytesRead = stream.read(header)
            }
        } catch (e: Exception) {
            return KernelImageInfo(
                path = kernelPath,
                exists = true,
                sizeBytes = size,
                architecture = "Unknown",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "Unreadable kernel file: ${e.localizedMessage}"
            )
        }

        // 1. Check for HTML or text error download
        val textSnippet = String(header, 0, minOf(bytesRead, 256), Charsets.US_ASCII).lowercase()
        if (textSnippet.contains("<!doctype html") || textSnippet.contains("<html") ||
            textSnippet.contains("404 not found") || textSnippet.contains("accessdenied") ||
            textSnippet.contains("{\"error\"")) {
            return KernelImageInfo(
                path = kernelPath,
                exists = true,
                sizeBytes = size,
                architecture = "HTML/Text",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "File is an HTML/text error response from download server"
            )
        }

        // 2. Check for ISO 9660 Disc Image
        var isIso = file.name.endsWith(".iso", ignoreCase = true)
        if (size >= 0x9006) {
            try {
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
            } catch (_: Exception) {}
        }
        if (isIso) {
            return KernelImageInfo(
                path = kernelPath,
                exists = true,
                sizeBytes = size,
                architecture = "ISO Disc",
                isArm64Valid = false,
                isIsoImage = true,
                formatDescription = "Disk image supplied where ARM64 kernel is required (ISO optical disc image detected)"
            )
        }

        // 3. Check for Disk Image formats (QCOW2, GPT, MBR, ext4, SquashFS, ZIP/TAR)
        // QCOW2 magic: QFI\xfb
        if (header[0] == 'Q'.code.toByte() && header[1] == 'F'.code.toByte() &&
            header[2] == 'I'.code.toByte() && (header[3].toInt() and 0xFF) == 0xFB) {
            return KernelImageInfo(
                path = kernelPath,
                exists = true,
                sizeBytes = size,
                architecture = "QCOW2 Disk",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "Disk image supplied where ARM64 kernel is required (QCOW2 disk image detected)"
            )
        }

        // SquashFS magic: hsqs or sQsh
        if ((header[0] == 'h'.code.toByte() && header[1] == 's'.code.toByte() && header[2] == 'q'.code.toByte() && header[3] == 's'.code.toByte()) ||
            (header[0] == 's'.code.toByte() && header[1] == 'Q'.code.toByte() && header[2] == 's'.code.toByte() && header[3] == 'h'.code.toByte())) {
            return KernelImageInfo(
                path = kernelPath,
                exists = true,
                sizeBytes = size,
                architecture = "SquashFS Disk",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "Disk image supplied where ARM64 kernel is required (SquashFS image detected)"
            )
        }

        // ZIP Archive magic: PK\x03\x04
        if (header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() && header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) {
            return KernelImageInfo(
                path = kernelPath,
                exists = true,
                sizeBytes = size,
                architecture = "ZIP Archive",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "Disk image supplied where ARM64 kernel is required (ZIP archive detected)"
            )
        }

        // GPT magic at 0x200: "EFI PART"
        if (size >= 0x208) {
            try {
                file.inputStream().use { stream ->
                    val skipBytes = 0x200L
                    if (stream.skip(skipBytes) == skipBytes) {
                        val gptBuf = ByteArray(8)
                        if (stream.read(gptBuf) == 8) {
                            val gptMagic = String(gptBuf, Charsets.US_ASCII)
                            if (gptMagic == "EFI PART") {
                                return KernelImageInfo(
                                    path = kernelPath,
                                    exists = true,
                                    sizeBytes = size,
                                    architecture = "GPT Disk",
                                    isArm64Valid = false,
                                    isIsoImage = false,
                                    formatDescription = "Disk image supplied where ARM64 kernel is required (GPT disk image detected)"
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // ext4 magic at 0x438: 0x53 0xEF
        if (size >= 0x43A) {
            try {
                file.inputStream().use { stream ->
                    val skipBytes = 0x438L
                    if (stream.skip(skipBytes) == skipBytes) {
                        val extBuf = ByteArray(2)
                        if (stream.read(extBuf) == 2) {
                            val magicExt = ((extBuf[1].toInt() and 0xFF) shl 8) or (extBuf[0].toInt() and 0xFF)
                            if (magicExt == 0xEF53) {
                                return KernelImageInfo(
                                    path = kernelPath,
                                    exists = true,
                                    sizeBytes = size,
                                    architecture = "ext4 Disk",
                                    isArm64Valid = false,
                                    isIsoImage = false,
                                    formatDescription = "Disk image supplied where ARM64 kernel is required (ext4 disk image detected)"
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 4. Check for Unsupported Compressed formats (XZ, ZSTD, BZIP2)
        if (header[0] == 0xFD.toByte() && header[1] == '7'.code.toByte() && header[2] == 'z'.code.toByte() &&
            header[3] == 'X'.code.toByte() && header[4] == 'Z'.code.toByte()) {
            return KernelImageInfo(
                path = kernelPath,
                exists = true,
                sizeBytes = size,
                architecture = "XZ Compressed",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "Unsupported kernel compression format (XZ); provide uncompressed Image or gzip vmlinuz"
            )
        }

        if (header[0] == 0x28.toByte() && header[1] == 0xB5.toByte() && header[2] == 0x2F.toByte() && header[3] == 0xFD.toByte()) {
            return KernelImageInfo(
                path = kernelPath,
                exists = true,
                sizeBytes = size,
                architecture = "ZSTD Compressed",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "Unsupported kernel compression format (ZSTD); provide uncompressed Image or gzip vmlinuz"
            )
        }

        if (header[0] == 'B'.code.toByte() && header[1] == 'Z'.code.toByte() && header[2] == 'h'.code.toByte()) {
            return KernelImageInfo(
                path = kernelPath,
                exists = true,
                sizeBytes = size,
                architecture = "BZIP2 Compressed",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "Unsupported kernel compression format (BZIP2); provide uncompressed Image or gzip vmlinuz"
            )
        }

        // 5. Check Gzip-Compressed vmlinuz (0x1F, 0x8B)
        if (header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()) {
            try {
                file.inputStream().use { fis ->
                    GZIPInputStream(fis).use { gzis ->
                        val decompBuf = ByteArray(256)
                        var decompRead = 0
                        var chunk = 0
                        while (decompRead < decompBuf.size && gzis.read(decompBuf, decompRead, decompBuf.size - decompRead).also { chunk = it } > 0) {
                            decompRead += chunk
                        }
                        if (decompRead >= 64) {
                            val decompMagic = ((decompBuf[0x3B].toInt() and 0xFF) shl 24) or
                                              ((decompBuf[0x3A].toInt() and 0xFF) shl 16) or
                                              ((decompBuf[0x39].toInt() and 0xFF) shl 8) or
                                              (decompBuf[0x38].toInt() and 0xFF)

                            if (decompMagic == 0x644D5241) { // "ARMd"
                                return KernelImageInfo(
                                    path = kernelPath,
                                    exists = true,
                                    sizeBytes = size,
                                    architecture = "ARM64 (AArch64)",
                                    isArm64Valid = true,
                                    isIsoImage = false,
                                    formatDescription = "Valid ARM64 Linux vmlinuz (gzip-compressed, Magic: 0x644D5241 verified)"
                                )
                            }

                            if (decompBuf[0] == 0x7F.toByte() && decompBuf[1] == 'E'.code.toByte() &&
                                decompBuf[2] == 'L'.code.toByte() && decompBuf[3] == 'F'.code.toByte() &&
                                decompBuf[4] == 2.toByte()) {
                                val eMachine = ((decompBuf[0x13].toInt() and 0xFF) shl 8) or (decompBuf[0x12].toInt() and 0xFF)
                                if (eMachine == 183 /* EM_AARCH64 */) {
                                    return KernelImageInfo(
                                        path = kernelPath,
                                        exists = true,
                                        sizeBytes = size,
                                        architecture = "ARM64 (AArch64)",
                                        isArm64Valid = true,
                                        isIsoImage = false,
                                        formatDescription = "Valid ARM64 Linux vmlinux (gzip-compressed ELF64)"
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                return KernelImageInfo(
                    path = kernelPath,
                    exists = true,
                    sizeBytes = size,
                    architecture = "Corrupted Gzip",
                    isArm64Valid = false,
                    isIsoImage = false,
                    formatDescription = "Corrupted gzip vmlinuz archive: ${e.localizedMessage}"
                )
            }

            return KernelImageInfo(
                path = kernelPath,
                exists = true,
                sizeBytes = size,
                architecture = "Unknown Gzip",
                isArm64Valid = false,
                isIsoImage = false,
                formatDescription = "Gzip archive does not contain a valid ARM64 Linux kernel Image header"
            )
        }

        // 6. Check Uncompressed ARM64 Image (Magic 0x644D5241 at offset 0x38)
        if (bytesRead >= 0x3C) {
            val magicArm64 = ((header[0x3B].toInt() and 0xFF) shl 24) or
                             ((header[0x3A].toInt() and 0xFF) shl 16) or
                             ((header[0x39].toInt() and 0xFF) shl 8) or
                             (header[0x38].toInt() and 0xFF)

            if (magicArm64 == 0x644D5241) { // "ARMd"
                return KernelImageInfo(
                    path = kernelPath,
                    exists = true,
                    sizeBytes = size,
                    architecture = "ARM64 (AArch64)",
                    isArm64Valid = true,
                    isIsoImage = false,
                    formatDescription = "ARM64 Linux Kernel Image (Magic: 0x644D5241 verified)"
                )
            }
        }

        // 7. Check ELF Binaries
        if (header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) {
            val is64Bit = (header[4] == 2.toByte())
            val eMachine = ((header[0x13].toInt() and 0xFF) shl 8) or (header[0x12].toInt() and 0xFF)

            if (!is64Bit) {
                val archName = if (eMachine == 40) "ARM32" else if (eMachine == 3) "x86" else "32-bit"
                return KernelImageInfo(
                    path = kernelPath,
                    exists = true,
                    sizeBytes = size,
                    architecture = archName,
                    isArm64Valid = false,
                    isIsoImage = false,
                    formatDescription = "$archName kernel binary rejected: only ARM64 (AArch64) is supported"
                )
            }

            if (eMachine == 183 /* EM_AARCH64 */) {
                return KernelImageInfo(
                    path = kernelPath,
                    exists = true,
                    sizeBytes = size,
                    architecture = "ARM64 (AArch64)",
                    isArm64Valid = true,
                    isIsoImage = false,
                    formatDescription = "Valid ARM64 vmlinux (ELF64)"
                )
            } else if (eMachine == 62 /* EM_X86_64 */) {
                return KernelImageInfo(
                    path = kernelPath,
                    exists = true,
                    sizeBytes = size,
                    architecture = "x86_64",
                    isArm64Valid = false,
                    isIsoImage = false,
                    formatDescription = "x86_64 kernel binary rejected: only ARM64 (AArch64) is supported"
                )
            } else {
                return KernelImageInfo(
                    path = kernelPath,
                    exists = true,
                    sizeBytes = size,
                    architecture = "ELF64 Machine $eMachine",
                    isArm64Valid = false,
                    isIsoImage = false,
                    formatDescription = "Non-ARM64 ELF kernel binary rejected (e_machine=$eMachine)"
                )
            }
        }

        return KernelImageInfo(
            path = kernelPath,
            exists = true,
            sizeBytes = size,
            architecture = "Unknown / Invalid",
            isArm64Valid = false,
            isIsoImage = false,
            formatDescription = "Unrecognized Kernel Binary (Not ARM64)"
        )
    }
}
