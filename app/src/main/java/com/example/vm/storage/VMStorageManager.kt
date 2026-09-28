package com.example.vm.storage

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.example.vm.core.VMConfig
import com.example.vm.persistence.VMRepository
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class VMStorageManager(
    private val context: Context,
    private val repository: VMRepository,
    private val diskBackend: AndroidStorageDiskBackend
) {
    companion object {
        private const val TAG = "VMStorageManager"
    }

    fun getVmDisksDirectory(vmId: Long): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val vmDir = File(baseDir, "vms/vm_$vmId/disks")
        if (!vmDir.exists()) {
            vmDir.mkdirs()
        }
        return vmDir
    }

    fun getSnapshotsDirectory(vmId: Long): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, "vms/vm_$vmId/snapshots")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getBackupsDirectory(): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, "backups")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getAvailableHostStorageBytes(): Long {
        val path = context.filesDir.absolutePath
        val stat = StatFs(path)
        return stat.availableBytes
    }

    fun getTotalHostStorageBytes(): Long {
        val path = context.filesDir.absolutePath
        val stat = StatFs(path)
        return stat.totalBytes
    }

    suspend fun createDiskForVm(
        vmId: Long,
        diskName: String,
        sizeGb: Int,
        role: DiskRole = DiskRole.ROOTFS,
        isBootable: Boolean = false,
        isSparse: Boolean = true
    ): VmDisk? {
        val requiredBytes = sizeGb.toLong() * 1024L * 1024L * 1024L
        val freeBytes = getAvailableHostStorageBytes()

        if (!isSparse && freeBytes < requiredBytes) {
            Log.e(TAG, "Insufficient host storage: Need ${requiredBytes / (1024 * 1024)} MB, available ${freeBytes / (1024 * 1024)} MB")
            repository.logStorageOperation("DISK_CREATE", vmId, "FAILED", "Insufficient host storage space")
            return null
        }

        val targetDir = getVmDisksDirectory(vmId)
        val safeFileName = diskName.lowercase().replace("[^a-z0-9._-]".toRegex(), "_") + ".img"
        val diskFile = File(targetDir, safeFileName)

        val created = diskBackend.createDiskImage(diskFile.absolutePath, sizeGb, isSparse)
        if (!created) {
            repository.logStorageOperation("DISK_CREATE", vmId, "FAILED", "Backend failed to create disk image at ${diskFile.absolutePath}")
            return null
        }

        val diskEntity = VmDisk(
            vmId = vmId,
            name = diskName,
            diskPath = diskFile.absolutePath,
            sizeGb = sizeGb,
            actualAllocatedBytes = diskFile.length(),
            isBootable = isBootable,
            role = role,
            isSparse = isSparse
        )

        val diskId = repository.insertDisk(diskEntity)
        val resultDisk = diskEntity.copy(id = diskId)

        repository.logStorageOperation("DISK_CREATE", vmId, "SUCCESS", "Created disk '$diskName' ($sizeGb GB) at ${diskFile.absolutePath}")
        return resultDisk
    }

    suspend fun resizeDisk(disk: VmDisk, newSizeGb: Int): Boolean {
        if (newSizeGb <= disk.sizeGb) {
            Log.w(TAG, "Shrinking disks is prohibited to prevent guest filesystem corruption")
            repository.logStorageOperation("DISK_RESIZE", disk.vmId, "REJECTED", "Cannot shrink disk from ${disk.sizeGb} GB to $newSizeGb GB")
            return false
        }

        val diskFile = File(disk.diskPath)
        if (!diskFile.exists()) {
            repository.logStorageOperation("DISK_RESIZE", disk.vmId, "FAILED", "Disk file missing at ${disk.diskPath}")
            return false
        }

        return try {
            val newSizeBytes = newSizeGb.toLong() * 1024L * 1024L * 1024L
            RandomAccessFile(diskFile, "rw").use { raf ->
                raf.setLength(newSizeBytes)
            }

            val updatedDisk = disk.copy(
                sizeGb = newSizeGb,
                actualAllocatedBytes = diskFile.length(),
                lastModifiedTimestamp = System.currentTimeMillis()
            )
            repository.updateDisk(updatedDisk)

            repository.logStorageOperation("DISK_RESIZE", disk.vmId, "SUCCESS", "Expanded disk '${disk.name}' to $newSizeGb GB")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resize disk: ${e.message}", e)
            repository.logStorageOperation("DISK_RESIZE", disk.vmId, "ERROR", e.message ?: "Resize error")
            false
        }
    }

    suspend fun createSnapshot(disk: VmDisk, snapshotName: String): VmSnapshot? {
        val srcFile = File(disk.diskPath)
        if (!srcFile.exists()) {
            repository.logStorageOperation("SNAPSHOT_CREATE", disk.vmId, "FAILED", "Source disk does not exist")
            return null
        }

        val snapshotDir = getSnapshotsDirectory(disk.vmId)
        val safeName = snapshotName.lowercase().replace("[^a-z0-9._-]".toRegex(), "_") + ".snap"
        val snapFile = File(snapshotDir, safeName)

        return try {
            srcFile.copyTo(snapFile, overwrite = true)
            val snapshot = VmSnapshot(
                diskId = disk.id,
                vmId = disk.vmId,
                snapshotName = snapshotName,
                snapshotPath = snapFile.absolutePath,
                sizeBytes = snapFile.length()
            )
            val id = repository.insertSnapshot(snapshot)
            val result = snapshot.copy(id = id)

            repository.logStorageOperation("SNAPSHOT_CREATE", disk.vmId, "SUCCESS", "Created snapshot '$snapshotName' (${snapFile.length() / (1024*1024)} MB)")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create snapshot: ${e.message}", e)
            repository.logStorageOperation("SNAPSHOT_CREATE", disk.vmId, "ERROR", e.message ?: "Snapshot failure")
            null
        }
    }

    suspend fun createBackup(vmConfig: VMConfig, backupName: String): VmBackup? {
        val backupDir = getBackupsDirectory()
        val safeName = backupName.lowercase().replace("[^a-z0-9._-]".toRegex(), "_") + ".vmbackup"
        val archiveFile = File(backupDir, safeName)

        return try {
            ZipOutputStream(FileOutputStream(archiveFile)).use { zos ->
                // Write VM config metadata
                val configEntry = ZipEntry("vm_config.json")
                zos.putNextEntry(configEntry)
                val configJson = """
                    {
                        "name": "${vmConfig.name}",
                        "ramSizeMb": ${vmConfig.ramSizeMb},
                        "cpuCores": ${vmConfig.cpuCores},
                        "guestOsType": "${vmConfig.guestOsType}",
                        "guestArchCode": ${vmConfig.guestArchCode},
                        "diskSizeGb": ${vmConfig.diskSizeGb}
                    }
                """.trimIndent()
                zos.write(configJson.toByteArray())
                zos.closeEntry()

                // Include disk file if present
                if (vmConfig.diskImagePath.isNotBlank()) {
                    val diskFile = File(vmConfig.diskImagePath)
                    if (diskFile.exists()) {
                        val diskEntry = ZipEntry("rootfs.img")
                        zos.putNextEntry(diskEntry)
                        FileInputStream(diskFile).use { fis ->
                            fis.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                }
            }

            val backup = VmBackup(
                vmId = vmConfig.id,
                backupName = backupName,
                archivePath = archiveFile.absolutePath,
                sizeBytes = archiveFile.length(),
                vmConfigJson = "Config for ${vmConfig.name}"
            )

            val id = repository.insertBackup(backup)
            val result = backup.copy(id = id)

            repository.logStorageOperation("BACKUP_CREATE", vmConfig.id, "SUCCESS", "Created backup archive '$backupName' (${archiveFile.length() / (1024*1024)} MB)")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VM backup archive: ${e.message}", e)
            repository.logStorageOperation("BACKUP_CREATE", vmConfig.id, "ERROR", e.message ?: "Backup failed")
            null
        }
    }

    suspend fun restoreBackup(backup: VmBackup): VMConfig? {
        val archiveFile = File(backup.archivePath)
        if (!archiveFile.exists()) {
            repository.logStorageOperation("BACKUP_RESTORE", backup.vmId, "FAILED", "Backup archive file not found")
            return null
        }

        return try {
            var restoredName = "Restored_VM_${System.currentTimeMillis() % 1000}"
            var ramMb = 2048
            var cpuCores = 2
            var guestOsType = "UBUNTU_22_04"
            var arch = "ARM64"
            var diskSizeGb = 16

            val restoredVmDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "vms/restored_${System.currentTimeMillis()}")
            restoredVmDir.mkdirs()
            val restoredDiskFile = File(restoredVmDir, "rootfs.img")

            ZipInputStream(FileInputStream(archiveFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "vm_config.json") {
                        val content = zis.bufferedReader().readText()
                        if (content.contains("\"name\"")) {
                            restoredName = content.substringAfter("\"name\": \"").substringBefore("\"")
                        }
                    } else if (entry.name == "rootfs.img") {
                        FileOutputStream(restoredDiskFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            val newConfig = VMConfig(
                name = "$restoredName (Restored)",
                ramSizeMb = ramMb,
                cpuCores = cpuCores,
                guestOsType = guestOsType,
                diskSizeGb = diskSizeGb,
                diskImagePath = restoredDiskFile.absolutePath
            )

            val newVmId = repository.insertConfig(newConfig)
            val finalConfig = newConfig.copy(id = newVmId)

            repository.logStorageOperation("BACKUP_RESTORE", newVmId, "SUCCESS", "Restored VM backup as '$restoredName'")
            finalConfig
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore backup archive: ${e.message}", e)
            repository.logStorageOperation("BACKUP_RESTORE", backup.vmId, "ERROR", e.message ?: "Restore error")
            null
        }
    }
}
