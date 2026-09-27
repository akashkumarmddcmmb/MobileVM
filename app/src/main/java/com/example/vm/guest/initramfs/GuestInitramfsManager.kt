package com.example.vm.guest.initramfs

import java.io.File

/**
 * Guest Initramfs (Initial RAM Filesystem) Module.
 * Manages loading of CPIO/Gzip initramfs containing userland scripts (/init, busybox)
 * before rootfs is mounted.
 */
data class InitramfsInfo(
    val path: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val isCompressed: Boolean,
    val format: String
)

object GuestInitramfsManager {
    const val MAX_REASONABLE_INITRAMFS_BYTES: Long = 512L * 1024L * 1024L // 512 MB max

    fun inspectInitramfs(initramfsPath: String): InitramfsInfo {
        if (initramfsPath.isEmpty()) {
            return InitramfsInfo(
                path = "",
                exists = false,
                sizeBytes = 0L,
                isCompressed = false,
                format = "No initramfs configured"
            )
        }

        val file = File(initramfsPath)
        val exists = file.exists()
        val size = if (exists) file.length() else 0L

        if (!exists) {
            return InitramfsInfo(
                path = initramfsPath,
                exists = false,
                sizeBytes = 0L,
                isCompressed = false,
                format = "File Not Found"
            )
        }

        if (size > MAX_REASONABLE_INITRAMFS_BYTES) {
            return InitramfsInfo(
                path = initramfsPath,
                exists = true,
                sizeBytes = size,
                isCompressed = false,
                format = "Initramfs exceeds reasonable size limit (> 512 MB): ${size / (1024 * 1024)} MB"
            )
        }

        var isGzip = false
        if (size >= 2) {
            try {
                file.inputStream().use { stream ->
                    val magic = ByteArray(2)
                    stream.read(magic)
                    // Gzip magic: 0x1F, 0x8B
                    isGzip = (magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte())
                }
            } catch (e: Exception) {
                isGzip = false
            }
        }

        return InitramfsInfo(
            path = initramfsPath,
            exists = true,
            sizeBytes = size,
            isCompressed = isGzip,
            format = if (isGzip) "CPIO archive (gzip compressed)" else "CPIO / Raw Ramfs"
        )
    }
}
