package com.example.vm.guest.initramfs

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Guest Initramfs (Initial RAM Filesystem) Module.
 * Implements real CPIO parsing (newc/crc, gzip-compressed or raw),
 * inspecting entry structures, permissions, /init presence and execution prerequisites.
 */
data class InitramfsInfo(
    val path: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val isCompressed: Boolean,
    val format: String,
    val hasUsableInit: Boolean,
    val statusMessage: String = ""
)

data class CpioArchiveEntry(
    val path: String,
    val mode: Int,
    val size: Long,
    val isExecutable: Boolean,
    val isRegularFile: Boolean,
    val isSymlink: Boolean,
    val content: ByteArray? = null
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
                hasUsableInit = false,
                statusMessage = "No initramfs configured"
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
                hasUsableInit = false,
                statusMessage = "Initramfs file not found: $initramfsPath"
            )
        }

        if (size == 0L) {
            return InitramfsInfo(
                path = initramfsPath,
                exists = true,
                sizeBytes = 0L,
                isCompressed = false,
                format = "Empty file (0 bytes)",
                hasUsableInit = false,
                statusMessage = "Initramfs file is 0 bytes."
            )
        }

        if (size > MAX_REASONABLE_INITRAMFS_BYTES) {
            return InitramfsInfo(
                path = initramfsPath,
                exists = true,
                sizeBytes = size,
                isCompressed = false,
                format = "Initramfs exceeds size limit (> 512 MB)",
                hasUsableInit = false,
                statusMessage = "Initramfs exceeds reasonable size limit (> 512 MB): ${size / (1024 * 1024)} MB"
            )
        }

        var isGzip = false
        if (size >= 2) {
            try {
                file.inputStream().use { stream ->
                    val magic = ByteArray(2)
                    stream.read(magic)
                    isGzip = (magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte())
                }
            } catch (_: Exception) {
                isGzip = false
            }
        }

        val parseResult = parseAndValidateInitramfs(file, isGzip)

        return InitramfsInfo(
            path = initramfsPath,
            exists = true,
            sizeBytes = size,
            isCompressed = isGzip,
            format = if (isGzip) "CPIO archive (gzip compressed)" else "CPIO archive (uncompressed)",
            hasUsableInit = parseResult.hasUsableInit,
            statusMessage = parseResult.message
        )
    }

    data class InitramfsValidationResult(
        val hasUsableInit: Boolean,
        val message: String
    )

    fun parseAndValidateInitramfs(file: File, isGzip: Boolean): InitramfsValidationResult {
        if (!file.exists() || file.length() < 16) {
            return InitramfsValidationResult(false, "Initramfs file is too small or empty.")
        }

        val entries = mutableMapOf<String, CpioArchiveEntry>()
        var foundInit = false
        var initEntry: CpioArchiveEntry? = null

        try {
            val rawStream = file.inputStream().buffered()
            val stream: InputStream = if (isGzip) {
                try {
                    GZIPInputStream(rawStream)
                } catch (e: Exception) {
                    return InitramfsValidationResult(false, "Corrupted gzip header or archive: ${e.message}")
                }
            } else {
                rawStream
            }

            stream.use { input ->
                val headerBuf = ByteArray(110)
                while (true) {
                    val read = readFully(input, headerBuf)
                    if (read < 110) break

                    val magic = String(headerBuf, 0, 6, Charsets.US_ASCII)
                    if (magic != "070701" && magic != "070702") {
                        if (entries.isEmpty()) {
                            return InitramfsValidationResult(false, "Invalid CPIO magic: expected 070701 or 070702, got '$magic'")
                        }
                        break
                    }

                    val mode = parseHexLong(headerBuf, 14, 8).toInt()
                    val fileSize = parseHexLong(headerBuf, 54, 8)
                    val nameSize = parseHexLong(headerBuf, 94, 8).toInt()

                    if (nameSize <= 0 || nameSize > 4096) break

                    val nameBuf = ByteArray(nameSize)
                    if (readFully(input, nameBuf) < nameSize) break

                    var nameStr = String(nameBuf, 0, if (nameBuf.last() == 0.toByte()) nameSize - 1 else nameSize, Charsets.US_ASCII)
                    nameStr = nameStr.trimStart('.', '/')

                    val namePad = (4 - ((110 + nameSize) % 4)) % 4
                    skipBytes(input, namePad.toLong())

                    if (nameStr == "TRAILER!!!") {
                        break
                    }

                    val isReg = (mode and 0xF000) == 0x8000
                    val isLnk = (mode and 0xF000) == 0xA000
                    val isExec = (mode and 0x49) != 0

                    val content: ByteArray? = if ((nameStr == "init" || nameStr == "bin/init" || nameStr == "sbin/init") && fileSize in 1..65536) {
                        val cBuf = ByteArray(fileSize.toInt())
                        readFully(input, cBuf)
                        cBuf
                    } else {
                        skipBytes(input, fileSize)
                        null
                    }

                    val dataPad = (4 - (fileSize % 4)) % 4
                    skipBytes(input, dataPad)

                    val entry = CpioArchiveEntry(
                        path = nameStr,
                        mode = mode,
                        size = fileSize,
                        isExecutable = isExec,
                        isRegularFile = isReg,
                        isSymlink = isLnk,
                        content = content
                    )
                    entries[nameStr] = entry

                    if (nameStr == "init") {
                        foundInit = true
                        initEntry = entry
                    }
                }
            }
        } catch (e: Exception) {
            return InitramfsValidationResult(false, "Failed to parse initramfs CPIO archive: ${e.message}")
        }

        if (entries.isEmpty()) {
            return InitramfsValidationResult(false, "Initramfs archive contains no valid CPIO entries (empty or gzip header only).")
        }

        if (!foundInit || initEntry == null) {
            initEntry = entries["bin/init"] ?: entries["sbin/init"]
            if (initEntry == null) {
                return InitramfsValidationResult(false, "Initramfs does not contain an /init executable.")
            }
        }

        val targetInit = initEntry
        if (!targetInit.isRegularFile && !targetInit.isSymlink) {
            return InitramfsValidationResult(false, "/init is not a regular file or symlink (mode 0%o).".format(targetInit.mode))
        }

        if (!targetInit.isExecutable) {
            return InitramfsValidationResult(false, "/init is not executable (missing execute permission bits, mode 0%o).".format(targetInit.mode))
        }

        val content = targetInit.content
        if (content != null && content.size >= 2) {
            if (content[0] == '#'.code.toByte() && content[1] == '!'.code.toByte()) {
                val firstLine = String(content, 0, minOf(content.size, 256), Charsets.UTF_8).lines().firstOrNull() ?: ""
                val interpreterLine = firstLine.removePrefix("#!").trim()
                val interpTokens = interpreterLine.split("\\s+".toRegex())
                val rawInterp = interpTokens.firstOrNull() ?: ""
                val interpName = rawInterp.trimStart('/')

                val hasInterp = entries.containsKey(interpName) ||
                                entries.containsKey("bin/sh") ||
                                entries.containsKey("bin/busybox") ||
                                entries.containsKey("usr/bin/sh") ||
                                entries.containsKey("bin/bash") ||
                                entries.containsKey("usr/bin/busybox")

                if (!hasInterp) {
                    return InitramfsValidationResult(
                        false,
                        "/init script specifies interpreter '$rawInterp' but '$rawInterp' (or /bin/sh / /bin/busybox) is missing from initramfs archive."
                    )
                }
            } else if (content.size >= 64 && content[0] == 0x7F.toByte() && content[1] == 'E'.code.toByte() && content[2] == 'L'.code.toByte() && content[3] == 'F'.code.toByte()) {
                val is64Bit = content[4] == 2.toByte()
                val eMachine = ((content[0x13].toInt() and 0xFF) shl 8) or (content[0x12].toInt() and 0xFF)
                if (!is64Bit || eMachine != 183 /* EM_AARCH64 */) {
                    return InitramfsValidationResult(false, "/init ELF binary is not ARM64 (64-bit=$is64Bit, e_machine=$eMachine).")
                }
            }
        }

        return InitramfsValidationResult(true, "Authentic CPIO initramfs verified with executable /init.")
    }

    private fun readFully(stream: InputStream, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val read = stream.read(buf, offset, buf.size - offset)
            if (read <= 0) break
            offset += read
        }
        return offset
    }

    private fun skipBytes(stream: InputStream, count: Long) {
        if (count <= 0) return
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                if (stream.read() == -1) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun parseHexLong(buf: ByteArray, offset: Int, length: Int): Long {
        val s = String(buf, offset, length, Charsets.US_ASCII).trim()
        return try {
            s.toLong(16)
        } catch (_: Exception) {
            0L
        }
    }
}
