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
        return try {
            val authorizedDir = getAuthorizedDisksDirectory().canonicalFile
            val file = File(targetPath).canonicalFile
            
            // Must strictly reside inside authorized disks directory
            var parent: File? = file.parentFile
            var isContained = false
            while (parent != null) {
                if (parent == authorizedDir) {
                    isContained = true
                    break
                }
                parent = parent.parentFile
            }
            isContained
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
        return try {
            val file = File(targetPath).canonicalFile
            val appStorageDirs = listOfNotNull(
                context.filesDir?.canonicalFile,
                context.getExternalFilesDir(null)?.canonicalFile,
                context.cacheDir?.canonicalFile
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

            RandomAccessFile(file, "rw").use { raf ->
                val offset = lba * SECTOR_SIZE
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
}
