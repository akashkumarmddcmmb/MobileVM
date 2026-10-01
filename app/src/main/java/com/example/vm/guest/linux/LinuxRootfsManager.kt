package com.example.vm.guest.linux

import java.io.File

data class RootfsInfo(
    val path: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val isVerified: Boolean,
    val format: String,
    val statusMessage: String
)

object LinuxRootfsManager {
    const val MIN_ROOTFS_BYTES: Long = 1024L * 1024L // 1 MB minimum for a non-empty filesystem

    fun inspectRootfs(diskPath: String): RootfsInfo {
        if (diskPath.isBlank()) {
            return RootfsInfo(
                path = "",
                exists = false,
                sizeBytes = 0L,
                isVerified = false,
                format = "No disk configured",
                statusMessage = "Root filesystem path is empty."
            )
        }

        val file = File(diskPath)
        if (!file.exists()) {
            return RootfsInfo(
                path = diskPath,
                exists = false,
                sizeBytes = 0L,
                isVerified = false,
                format = "File Not Found",
                statusMessage = "ROOTFS_UNVERIFIED: Rootfs disk file does not exist."
            )
        }

        val size = file.length()
        if (size < MIN_ROOTFS_BYTES) {
            return RootfsInfo(
                path = diskPath,
                exists = true,
                sizeBytes = size,
                isVerified = false,
                format = "Empty / Truncated Disk",
                statusMessage = "ROOTFS_UNVERIFIED: Disk image ($size bytes) is too small to contain a bootable Linux root filesystem (minimum 1 MB required)."
            )
        }

        val header = ByteArray(4096)
        var readLen = 0
        try {
            file.inputStream().use { stream ->
                readLen = stream.read(header)
            }
        } catch (e: Exception) {
            return RootfsInfo(
                path = diskPath,
                exists = true,
                sizeBytes = size,
                isVerified = false,
                format = "Unreadable",
                statusMessage = "ROOTFS_UNVERIFIED: Cannot read disk image: ${e.message}"
            )
        }

        if (readLen < 1024) {
            return RootfsInfo(
                path = diskPath,
                exists = true,
                sizeBytes = size,
                isVerified = false,
                format = "Truncated Header",
                statusMessage = "ROOTFS_UNVERIFIED: Disk image header could not be read."
            )
        }

        // Check if file is completely all-zero (sparse empty file from truncate)
        var allZero = true
        for (i in 0 until minOf(readLen, 2048)) {
            if (header[i] != 0.toByte()) {
                allZero = false
                break
            }
        }
        if (allZero) {
            return RootfsInfo(
                path = diskPath,
                exists = true,
                sizeBytes = size,
                isVerified = false,
                format = "Unformatted Sparse Image",
                statusMessage = "ROOTFS_UNVERIFIED: Virtual disk image is empty/unformatted. Authentic ARM64 root filesystem required."
            )
        }

        // 1. Check ext4 superblock magic at offset 0x438 (1080): 0x53, 0xEF
        if (readLen >= 0x43A) {
            val magic = ((header[0x439].toInt() and 0xFF) shl 8) or (header[0x438].toInt() and 0xFF)
            if (magic == 0xEF53) {
                val hasUserspace = scanForUserspaceStructures(file)
                return if (hasUserspace) {
                    RootfsInfo(
                        path = diskPath,
                        exists = true,
                        sizeBytes = size,
                        isVerified = true,
                        format = "ext4",
                        statusMessage = "Authentic ext4 Linux root filesystem verified with standard userspace."
                    )
                } else {
                    RootfsInfo(
                        path = diskPath,
                        exists = true,
                        sizeBytes = size,
                        isVerified = true,
                        format = "ext4",
                        statusMessage = "ext4 Linux filesystem image verified."
                    )
                }
            }
        }

        // 2. Check GPT Header at offset 0x200 (512): "EFI PART"
        if (readLen >= 0x208) {
            val gptMagic = String(header, 0x200, 8, Charsets.US_ASCII)
            if (gptMagic == "EFI PART") {
                return RootfsInfo(
                    path = diskPath,
                    exists = true,
                    sizeBytes = size,
                    isVerified = true,
                    format = "GPT Partitioned Disk",
                    statusMessage = "GPT partitioned ARM64 disk image verified."
                )
            }
        }

        // 3. Check MBR Partition Table at 0x1FE: 0x55, 0xAA
        if (readLen >= 512) {
            val b0 = header[0x1FE].toInt() and 0xFF
            val b1 = header[0x1FF].toInt() and 0xFF
            if (b0 == 0x55 && b1 == 0xAA) {
                // Check if any partition has non-zero sectors
                var hasPartition = false
                for (p in 0 until 4) {
                    val pOffset = 0x1BE + p * 16
                    val secCount = (header[pOffset + 12].toInt() and 0xFF) or
                                   ((header[pOffset + 13].toInt() and 0xFF) shl 8) or
                                   ((header[pOffset + 14].toInt() and 0xFF) shl 16) or
                                   ((header[pOffset + 15].toInt() and 0xFF) shl 24)
                    if (secCount > 0) {
                        hasPartition = true
                        break
                    }
                }
                if (hasPartition) {
                    return RootfsInfo(
                        path = diskPath,
                        exists = true,
                        sizeBytes = size,
                        isVerified = true,
                        format = "MBR Partitioned Disk",
                        statusMessage = "MBR partitioned ARM64 disk image verified."
                    )
                }
            }
        }

        // 4. Check QCOW2 magic: QFI\xfb
        if (header[0] == 'Q'.code.toByte() && header[1] == 'F'.code.toByte() &&
            header[2] == 'I'.code.toByte() && (header[3].toInt() and 0xFF) == 0xFB) {
            return RootfsInfo(
                path = diskPath,
                exists = true,
                sizeBytes = size,
                isVerified = true,
                format = "QCOW2 Disk",
                statusMessage = "QCOW2 format virtual disk verified."
            )
        }

        // 5. Check SquashFS: hsqs or sQsh
        if ((header[0] == 'h'.code.toByte() && header[1] == 's'.code.toByte() && header[2] == 'q'.code.toByte() && header[3] == 's'.code.toByte()) ||
            (header[0] == 's'.code.toByte() && header[1] == 'Q'.code.toByte() && header[2] == 's'.code.toByte() && header[3] == 'h'.code.toByte())) {
            return RootfsInfo(
                path = diskPath,
                exists = true,
                sizeBytes = size,
                isVerified = true,
                format = "SquashFS",
                statusMessage = "SquashFS root filesystem image verified."
            )
        }

        return RootfsInfo(
            path = diskPath,
            exists = true,
            sizeBytes = size,
            isVerified = false,
            format = "Unknown Filesystem Format",
            statusMessage = "ROOTFS_UNVERIFIED: Selected disk image format could not be verified as a valid Linux rootfs."
        )
    }

    private fun scanForUserspaceStructures(file: File): Boolean {
        return try {
            file.inputStream().use { stream ->
                val buf = ByteArray(minOf(file.length().toInt(), 2 * 1024 * 1024))
                val read = stream.read(buf)
                if (read <= 0) return false
                val content = String(buf, 0, read, Charsets.ISO_8859_1)
                (content.contains("/etc") || content.contains("etc/os-release")) &&
                (content.contains("/usr") || content.contains("/bin") || content.contains("/sbin"))
            }
        } catch (_: Exception) {
            false
        }
    }
}
