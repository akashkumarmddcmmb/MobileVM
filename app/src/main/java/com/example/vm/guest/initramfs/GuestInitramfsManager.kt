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
    val format: String,
    val hasUsableInit: Boolean
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
                format = "No initramfs configured",
                hasUsableInit = false
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
                format = "File Not Found",
                hasUsableInit = false
            )
        }

        if (size > MAX_REASONABLE_INITRAMFS_BYTES) {
            return InitramfsInfo(
                path = initramfsPath,
                exists = true,
                sizeBytes = size,
                isCompressed = false,
                format = "Initramfs exceeds reasonable size limit (> 512 MB): ${size / (1024 * 1024)} MB",
                hasUsableInit = false
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

        val hasInit = scanForUsableInit(file, isGzip)

        return InitramfsInfo(
            path = initramfsPath,
            exists = true,
            sizeBytes = size,
            isCompressed = isGzip,
            format = if (isGzip) "CPIO archive (gzip compressed)" else "CPIO / Raw Ramfs",
            hasUsableInit = hasInit
        )
    }

    /**
     * Inspects the CPIO/gzip archive to check whether it contains an executable /init entry.
     */
    fun scanForUsableInit(file: File, isGzip: Boolean): Boolean {
        if (!file.exists() || file.length() < 16) return false
        return try {
            val rawStream = file.inputStream().buffered()
            val stream: java.io.InputStream = if (isGzip) {
                try {
                    java.util.zip.GZIPInputStream(rawStream)
                } catch (e: Exception) {
                    rawStream
                }
            } else {
                rawStream
            }

            stream.use { input ->
                val buffer = ByteArray(65536)
                var bytesRead = input.read(buffer)
                var totalScanned = 0
                // Scan decompressed payload for standard CPIO entry names for init
                while (bytesRead > 0 && totalScanned < 16 * 1024 * 1024) { // scan up to first 16 MB of archive
                    totalScanned += bytesRead
                    val content = String(buffer, 0, bytesRead, Charsets.ISO_8859_1)
                    if (content.contains("init\u0000") || content.contains("./init\u0000") || 
                        content.contains("/init\u0000") || content.contains("bin/init\u0000") ||
                        content.contains("sbin/init\u0000") || content.contains("rdinit")) {
                        return true
                    }
                    bytesRead = input.read(buffer)
                }
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
