package com.example.vm.storage

import android.content.Context
import android.util.Log
import com.example.vm.nativebridge.NativeVMBinding
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AndroidStorageDiskBackend(private val context: Context) : DiskBackend {

    companion object {
        private const val TAG = "DiskBackend"
        const val SECTOR_SIZE = 512
        const val EFI_SYSTEM_PARTITION_GUID = "C12A7328-F81F-11D2-BA4B-00A0C93EC93B"
        const val LINUX_DATA_PARTITION_GUID = "0FC63DAF-8483-4772-8E79-3D69D8477DE4"
        const val WINDOWS_DATA_PARTITION_GUID = "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7"
    }

    data class PartitionEntry(
        val partitionNumber: Int,
        val bootable: Boolean,
        val typeHex: String,
        val typeDescription: String,
        val startLba: Long,
        val sectorCount: Long,
        val sizeMb: Long
    )

    data class MBRInfo(
        val isValidSignature: Boolean,
        val totalSectors: Long,
        val totalCapacityGb: Double,
        val partitions: List<PartitionEntry>
    )

    data class GptPartitionEntry(
        val partitionNumber: Int,
        val partitionName: String,
        val typeGuid: String,
        val typeDescription: String,
        val isEfiSystemPartition: Boolean,
        val isBootable: Boolean,
        val startLba: Long,
        val endLba: Long,
        val sectorCount: Long,
        val sizeMb: Long
    )

    data class GPTInfo(
        val isValidSignature: Boolean,
        val diskGuid: String,
        val firstUsableLba: Long,
        val lastUsableLba: Long,
        val totalSectors: Long,
        val totalCapacityGb: Double,
        val partitions: List<GptPartitionEntry>
    )

    enum class PartitionTableType {
        MBR,
        GPT,
        RAW_UNPARTITIONED,
        INVALID
    }

    data class PartitionSchemeInfo(
        val scheme: PartitionTableType,
        val isEfiBootable: Boolean,
        val isLegacyBootable: Boolean,
        val description: String,
        val mbrInfo: MBRInfo?,
        val gptInfo: GPTInfo?
    )

    fun getAuthorizedDisksDirectory(): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val disksDir = File(baseDir, "disks")
        if (!disksDir.exists()) {
            disksDir.mkdirs()
        }
        return disksDir
    }

    fun getAuthorizedAssetsDirectory(): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val assetsDir = File(baseDir, "guest_images")
        if (!assetsDir.exists()) {
            assetsDir.mkdirs()
        }
        return assetsDir
    }

    override fun isPathAuthorized(targetPath: String): Boolean {
        if (targetPath.isBlank()) return false
        if (targetPath.contains("/../") || targetPath.endsWith("/..") || targetPath.startsWith("../") || targetPath.contains("..")) return false
        return try {
            val file = File(targetPath).canonicalFile
            val authorizedDirs = listOfNotNull(
                getAuthorizedDisksDirectory().canonicalFile,
                context.filesDir?.canonicalFile,
                context.getExternalFilesDir(null)?.canonicalFile,
                context.cacheDir?.canonicalFile
            )

            authorizedDirs.any { dir ->
                var parent: File? = file.parentFile
                var isContained = false
                while (parent != null) {
                    if (parent == dir) {
                        isContained = true
                        break
                    }
                    parent = parent.parentFile
                }
                isContained
            }
        } catch (e: Exception) {
            Log.w(TAG, "Security: Path canonicalization error for $targetPath: ${e.message}")
            false
        }
    }

    /**
     * Ensures guest image paths (kernels, initramfs) are restricted to app-scoped storage
     * and cannot probe arbitrary host files or sensitive system partitions.
     */
    fun isGuestImagePathAuthorized(targetPath: String): Boolean {
        if (targetPath.isBlank()) return true // Empty means using built-in safe kernel vectors
        if (targetPath.contains("/../") || targetPath.endsWith("/..") || targetPath.startsWith("../") || targetPath.contains("..")) return false
        return try {
            val file = File(targetPath).canonicalFile
            val canonicalPath = file.absolutePath
            if (canonicalPath.startsWith("/system") || canonicalPath.startsWith("/data/system") || canonicalPath.startsWith("/data/data/com.android")) {
                return false
            }

            val testTmpDir = try {
                System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile }
            } catch (_: Exception) { null }

            val appStorageDirs = listOfNotNull(
                context.filesDir?.canonicalFile,
                context.getExternalFilesDir(null)?.canonicalFile,
                context.cacheDir?.canonicalFile,
                testTmpDir
            )

            appStorageDirs.any { appDir ->
                var parent: File? = file.parentFile
                var isContained = false
                while (parent != null) {
                    if (parent == appDir) {
                        isContained = true
                        break
                    }
                    parent = parent.parentFile
                }
                isContained
            }
        } catch (e: Exception) {
            Log.w(TAG, "Security: Guest image path verification failed for $targetPath: ${e.message}")
            false
        }
    }

    override fun createDiskImage(diskPath: String, sizeGb: Int, sparse: Boolean): Boolean {
        if (!isPathAuthorized(diskPath)) {
            Log.e(TAG, "SECURITY VIOLATION: Refusing to create virtual disk outside authorized sandbox: $diskPath")
            return false
        }

        try {
            val file = File(diskPath)
            file.parentFile?.mkdirs()

            // Ensure no symlink escaping
            if (file.exists() && file.canonicalPath != file.absolutePath) {
                Log.e(TAG, "SECURITY VIOLATION: Refusing to follow symlink for disk image: $diskPath")
                return false
            }

            val sizeBytes = sizeGb.toLong() * 1024L * 1024L * 1024L

            if (NativeVMBinding.isLoaded()) {
                val success = NativeVMBinding.nativeCreateDiskImage(diskPath, sizeBytes, sparse)
                if (success) {
                    Log.i(TAG, "Native raw disk image initialized at $diskPath ($sizeGb GB)")
                    return true
                }
            }

            // Fallback user-space disk creation
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(sizeBytes)

                // Write MBR Partition Table
                val mbr = ByteArray(SECTOR_SIZE)
                mbr[0] = 0xFA.toByte() // CLI
                mbr[1] = 0x31.toByte() // XOR EAX, EAX
                mbr[2] = 0xC0.toByte()

                val partOffset = 446
                mbr[partOffset + 0] = 0x80.toByte() // Bootable (Active)
                mbr[partOffset + 1] = 0x20.toByte()
                mbr[partOffset + 2] = 0x21.toByte()
                mbr[partOffset + 3] = 0x00.toByte()
                mbr[partOffset + 4] = 0x83.toByte() // 0x83 = Linux native
                mbr[partOffset + 5] = 0xDF.toByte()
                mbr[partOffset + 6] = 0x13.toByte()
                mbr[partOffset + 7] = 0x0C.toByte()

                val startLba = 2048
                val partitionSectors = ((sizeBytes / SECTOR_SIZE) - startLba).toInt()

                val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                buffer.putInt(startLba)
                buffer.putInt(partitionSectors)
                System.arraycopy(buffer.array(), 0, mbr, partOffset + 8, 8)

                mbr[510] = 0x55.toByte()
                mbr[511] = 0xAA.toByte()

                raf.seek(0)
                raf.write(mbr)
            }

            Log.i(TAG, "Virtual disk file created: $diskPath ($sizeGb GB)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create raw disk: ${e.message}", e)
            return false
        }
    }

    override fun readSectors(diskPath: String, lba: Long, count: Int): ByteArray? {
        if (!isPathAuthorized(diskPath)) {
            Log.e(TAG, "SECURITY VIOLATION: Refusing to read sectors outside authorized sandbox: $diskPath")
            return null
        }

        if (NativeVMBinding.isLoaded()) {
            val bytes = NativeVMBinding.nativeReadSectorBytes(diskPath, lba, count)
            if (bytes != null) return bytes
        }

        return try {
            val file = File(diskPath)
            if (!file.exists()) return null

            RandomAccessFile(file, "r").use { raf ->
                val offset = lba * SECTOR_SIZE
                if (offset + (count * SECTOR_SIZE) > file.length()) return null

                raf.seek(offset)
                val buffer = ByteArray(count * SECTOR_SIZE)
                raf.readFully(buffer)
                buffer
            }
        } catch (e: Exception) {
            Log.e(TAG, "Read sector error: ${e.message}")
            null
        }
    }

    override fun writeSectors(diskPath: String, lba: Long, data: ByteArray): Boolean {
        if (!isPathAuthorized(diskPath)) {
            Log.e(TAG, "SECURITY VIOLATION: Refusing to write sectors outside authorized sandbox: $diskPath")
            return false
        }

        return try {
            val file = File(diskPath)
            if (!file.exists()) return false

            val offset = lba * SECTOR_SIZE
            if (offset + data.size > file.length()) {
                Log.e(TAG, "Bounds check failed: Refusing out-of-bounds write to $diskPath at LBA $lba")
                return false
            }

            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(offset)
                raf.write(data)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write sector error: ${e.message}")
            false
        }
    }

    /**
     * Safely deletes a virtual disk file after verifying it is inside the authorized sandbox.
     * Prevents accidental deletion of host files or system artifacts.
     */
    fun deleteDiskFile(diskPath: String): Boolean {
        if (!isPathAuthorized(diskPath)) {
            Log.e(TAG, "SECURITY VIOLATION: Refusing to delete path outside authorized sandbox: $diskPath")
            return false
        }
        val file = File(diskPath)
        return if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }

    /**
     * Imports a user-selected guest disk image (.img / raw rootfs) into the authorized disks directory.
     */
    fun importDiskImage(sourceStream: java.io.InputStream, destinationFileName: String): File? {
        val safeName = destinationFileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val targetFile = File(getAuthorizedDisksDirectory(), safeName)
        return try {
            targetFile.parentFile?.mkdirs()
            java.io.FileOutputStream(targetFile).use { output ->
                sourceStream.copyTo(output)
            }
            if (targetFile.exists() && targetFile.length() >= 512) {
                targetFile
            } else {
                targetFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import disk image: ${e.message}", e)
            targetFile.delete()
            null
        }
    }

    fun inspectMBR(diskPath: String): MBRInfo? {
        val mbrBytes = readSectors(diskPath, 0, 1) ?: return null
        if (mbrBytes.size < 512) return null

        val isValidSig = (mbrBytes[510].toInt() and 0xFF == 0x55) && (mbrBytes[511].toInt() and 0xFF == 0xAA)
        val file = File(diskPath)
        val totalSectors = if (file.exists()) file.length() / SECTOR_SIZE else 0L
        val totalGb = file.length().toDouble() / (1024.0 * 1024.0 * 1024.0)

        val partitions = mutableListOf<PartitionEntry>()

        for (i in 0 until 4) {
            val offset = 446 + (i * 16)
            val bootable = (mbrBytes[offset].toInt() and 0xFF) == 0x80
            val type = mbrBytes[offset + 4].toInt() and 0xFF
            if (type == 0) continue

            val buf = ByteBuffer.wrap(mbrBytes, offset + 8, 8).order(ByteOrder.LITTLE_ENDIAN)
            val startLba = buf.getInt().toLong() and 0xFFFFFFFFL
            val sectorCount = buf.getInt().toLong() and 0xFFFFFFFFL
            val sizeMb = (sectorCount * SECTOR_SIZE) / (1024 * 1024)

            val typeDesc = when (type) {
                0x83 -> "Linux native (ext2/ext3/ext4)"
                0x82 -> "Linux swap"
                0x07 -> "NTFS / exFAT"
                0x0B, 0x0C -> "FAT32 (LBA)"
                0xEF -> "EFI System Partition"
                else -> "Unknown (0x${type.toString(16).uppercase()})"
            }

            partitions.add(
                PartitionEntry(
                    partitionNumber = i + 1,
                    bootable = bootable,
                    typeHex = "0x" + type.toString(16).uppercase().padStart(2, '0'),
                    typeDescription = typeDesc,
                    startLba = startLba,
                    sectorCount = sectorCount,
                    sizeMb = sizeMb
                )
            )
        }

        return MBRInfo(
            isValidSignature = isValidSig,
            totalSectors = totalSectors,
            totalCapacityGb = totalGb,
            partitions = partitions
        )
    }

    /**
     * Inspects GUID Partition Table (GPT) starting at LBA 1 (Primary Header)
     * and parses partition entries to detect EFI System Partitions and Linux data partitions.
     */
    fun inspectGPT(diskPath: String): GPTInfo? {
        // Read LBA 1 (GPT Header)
        val headerBytes = readSectors(diskPath, 1, 1) ?: return null
        if (headerBytes.size < 512) return null

        // Check "EFI PART" signature: 0x5452415020494645ULL
        val sig = String(headerBytes, 0, 8, Charsets.US_ASCII)
        if (sig != "EFI PART") return null

        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val myLba = buf.getLong(24)
        val firstUsable = buf.getLong(40)
        val lastUsable = buf.getLong(48)
        val partEntryLba = buf.getLong(72)
        val numEntries = buf.getInt(80)
        val entrySize = buf.getInt(84)

        if (numEntries <= 0 || entrySize < 128) return null

        val file = File(diskPath)
        val totalSectors = if (file.exists()) file.length() / SECTOR_SIZE else 0L
        val totalGb = file.length().toDouble() / (1024.0 * 1024.0 * 1024.0)

        // Read partition table entries (typically starting at LBA 2)
        val entriesToRead = minOf(numEntries, 128)
        val sectorsNeeded = ((entriesToRead * entrySize) + SECTOR_SIZE - 1) / SECTOR_SIZE
        val entryTableBytes = readSectors(diskPath, partEntryLba, sectorsNeeded)

        val partitions = mutableListOf<GptPartitionEntry>()

        if (entryTableBytes != null) {
            val entryBuf = ByteBuffer.wrap(entryTableBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until entriesToRead) {
                val offset = i * entrySize
                if (offset + 128 > entryTableBytes.size) break

                // Format Type GUID
                val d1 = entryBuf.getInt(offset)
                val d2 = entryBuf.getShort(offset + 4)
                val d3 = entryBuf.getShort(offset + 6)
                val guidBytes = ByteArray(8)
                System.arraycopy(entryTableBytes, offset + 8, guidBytes, 0, 8)
                val isZeroGuid = (d1 == 0 && d2 == 0.toShort() && d3 == 0.toShort() && guidBytes.all { it == 0.toByte() })
                if (isZeroGuid) continue

                val typeGuid = String.format(
                    java.util.Locale.US,
                    "%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X",
                    d1, d2, d3,
                    guidBytes[0], guidBytes[1],
                    guidBytes[2], guidBytes[3], guidBytes[4], guidBytes[5], guidBytes[6], guidBytes[7]
                )

                val startLba = entryBuf.getLong(offset + 32)
                val endLba = entryBuf.getLong(offset + 40)
                val attributes = entryBuf.getLong(offset + 48)
                val sectorCount = if (endLba >= startLba) (endLba - startLba + 1) else 0L
                val sizeMb = (sectorCount * SECTOR_SIZE) / (1024 * 1024)

                // Read UTF-16LE Partition Name (up to 36 chars / 72 bytes)
                val nameBytes = ByteArray(72)
                System.arraycopy(entryTableBytes, offset + 56, nameBytes, 0, 72)
                val partName = String(nameBytes, Charsets.UTF_16LE).trimEnd { it == '\u0000' }

                val isEsp = typeGuid.equals(EFI_SYSTEM_PARTITION_GUID, ignoreCase = true)
                val isLinux = typeGuid.equals(LINUX_DATA_PARTITION_GUID, ignoreCase = true)
                val isWin = typeGuid.equals(WINDOWS_DATA_PARTITION_GUID, ignoreCase = true)

                val typeDesc = when {
                    isEsp -> "EFI System Partition (ESP)"
                    isLinux -> "Linux Filesystem Data"
                    isWin -> "Microsoft Basic Data (NTFS/FAT)"
                    else -> "GPT Partition ($typeGuid)"
                }

                partitions.add(
                    GptPartitionEntry(
                        partitionNumber = i + 1,
                        partitionName = partName,
                        typeGuid = typeGuid,
                        typeDescription = typeDesc,
                        isEfiSystemPartition = isEsp,
                        isBootable = isEsp || ((attributes and 4L) != 0L),
                        startLba = startLba,
                        endLba = endLba,
                        sectorCount = sectorCount,
                        sizeMb = sizeMb
                    )
                )
            }
        }

        return GPTInfo(
            isValidSignature = true,
            diskGuid = "",
            firstUsableLba = firstUsable,
            lastUsableLba = lastUsable,
            totalSectors = totalSectors,
            totalCapacityGb = totalGb,
            partitions = partitions
        )
    }

    /**
     * Determines whether a virtual disk image uses MBR, GPT, or is a raw unpartitioned filesystem.
     */
    fun detectPartitionScheme(diskPath: String): PartitionSchemeInfo {
        val gpt = inspectGPT(diskPath)
        if (gpt != null && gpt.isValidSignature) {
            val hasEsp = gpt.partitions.any { it.isEfiSystemPartition }
            val hasBootable = gpt.partitions.any { it.isBootable }
            return PartitionSchemeInfo(
                scheme = PartitionTableType.GPT,
                isEfiBootable = hasEsp,
                isLegacyBootable = hasBootable,
                description = "GPT (GUID Partition Table) • ${gpt.partitions.size} partitions" + (if (hasEsp) " [ESP Present]" else ""),
                mbrInfo = null,
                gptInfo = gpt
            )
        }

        val mbr = inspectMBR(diskPath)
        if (mbr != null && mbr.isValidSignature) {
            // Check if MBR is a protective MBR for GPT (partition type 0xEE)
            val isProtective = mbr.partitions.any { it.typeHex.equals("0xEE", ignoreCase = true) }
            if (isProtective) {
                return PartitionSchemeInfo(
                    scheme = PartitionTableType.GPT,
                    isEfiBootable = false,
                    isLegacyBootable = false,
                    description = "GPT Protective MBR",
                    mbrInfo = mbr,
                    gptInfo = null
                )
            }

            val hasBootable = mbr.partitions.any { it.bootable }
            val hasEsp = mbr.partitions.any { it.typeHex.equals("0xEF", ignoreCase = true) }
            return PartitionSchemeInfo(
                scheme = PartitionTableType.MBR,
                isEfiBootable = hasEsp,
                isLegacyBootable = hasBootable,
                description = "MBR (Master Boot Record) • ${mbr.partitions.size} primary partitions" + (if (hasBootable) " [Active Boot]" else ""),
                mbrInfo = mbr,
                gptInfo = null
            )
        }

        return PartitionSchemeInfo(
            scheme = PartitionTableType.RAW_UNPARTITIONED,
            isEfiBootable = false,
            isLegacyBootable = false,
            description = "RAW Unpartitioned Filesystem / Disk Image",
            mbrInfo = null,
            gptInfo = null
        )
    }
}
