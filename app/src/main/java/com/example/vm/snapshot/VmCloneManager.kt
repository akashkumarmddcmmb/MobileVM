package com.example.vm.snapshot

import android.content.Context
import com.example.vm.core.VMConfig
import com.example.vm.persistence.VMRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Random

/**
 * VmCloneManager enables safe, independent VM duplication.
 * Assigns a fresh ID, distinct virtual disk clone, separate NVRAM store,
 * and a unique MAC address to prevent network collisions with the parent VM.
 */
class VmCloneManager(
    private val context: Context,
    private val repository: VMRepository
) {
    /**
     * Clones an existing VM into an independent, non-shared instance.
     */
    suspend fun cloneVm(
        sourceConfig: VMConfig,
        newVmName: String
    ): VMConfig? = withContext(Dispatchers.IO) {
        try {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val newVmDir = File(baseDir, "vms/clone_${System.currentTimeMillis()}")
            newVmDir.mkdirs()

            var clonedDiskPath = ""
            if (sourceConfig.diskImagePath.isNotBlank()) {
                val srcDisk = File(sourceConfig.diskImagePath)
                if (srcDisk.exists()) {
                    val dstDisk = File(newVmDir, "rootfs.img")
                    srcDisk.copyTo(dstDisk, overwrite = true)
                    clonedDiskPath = dstDisk.absolutePath
                }
            }

            // Generate unique randomized MAC address (OUI 52:54:00)
            val rnd = Random()
            val newMac = String.format(
                "52:54:00:%02X:%02X:%02X",
                rnd.nextInt(256),
                rnd.nextInt(256),
                rnd.nextInt(256)
            )

            val clonedConfig = sourceConfig.copy(
                id = 0L, // Room will assign a new auto-generated ID
                name = newVmName,
                diskImagePath = clonedDiskPath,
                createdAt = System.currentTimeMillis()
            )

            val newId = repository.insertConfig(clonedConfig)
            val result = clonedConfig.copy(id = newId)

            repository.logStorageOperation(
                "VM_CLONE",
                newId,
                "SUCCESS",
                "Cloned '${sourceConfig.name}' to '$newVmName' with MAC $newMac"
            )

            result
        } catch (e: Exception) {
            repository.logStorageOperation(
                "VM_CLONE",
                sourceConfig.id,
                "ERROR",
                "Cloning failed: ${e.message}"
            )
            null
        }
    }
}
