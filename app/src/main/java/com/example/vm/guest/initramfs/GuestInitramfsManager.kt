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
    fun inspectInitramfs(initramfsPath: String): InitramfsInfo {
        if (initramfsPath.isEmpty()) {
            return InitramfsInfo(
                path = "Embedded Minimal RAM Shell",
                exists = true,
                sizeBytes = 16384L,
                isCompressed = false,
                format = "Built-in /init binary"
            )
        }

        val file = File(initramfsPath)
        val exists = file.exists()
        val size = if (exists) file.length() else 0L

        var isGzip = false
        if (exists && size >= 2) {
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
            exists = exists,
            sizeBytes = size,
            isCompressed = isGzip,
            format = if (isGzip) "CPIO archive (gzip compressed)" else "CPIO / Raw Ramfs"
        )
    }
}
