package com.example.vm.guest.os

import android.content.Context
import java.io.File

/**
 * Manages sandboxed local storage for guest operating system downloads,
 * enforces storage capacity limits, verifies isolation, and rejects path traversal.
 */
data class StorageCapacityReport(
    val requiredBytes: Long,
    val availableBytes: Long,
    val safetyMarginBytes: Long,
    val isSufficient: Boolean,
    val shortfallBytes: Long
)

object OSStorageManager {

    const val SAFETY_MARGIN_BYTES: Long = 50L * 1024L * 1024L // 50 MB safety headroom

    /**
     * Inspects available device internal storage against the required download size.
     */
    fun checkStorageCapacity(context: Context, requiredDownloadBytes: Long): StorageCapacityReport {
        val usableBytes = context.filesDir.usableSpace
        val totalRequiredWithMargin = requiredDownloadBytes + SAFETY_MARGIN_BYTES
        val isSufficient = usableBytes >= totalRequiredWithMargin
        val shortfall = if (isSufficient) 0L else (totalRequiredWithMargin - usableBytes)

        return StorageCapacityReport(
            requiredBytes = requiredDownloadBytes,
            availableBytes = usableBytes,
            safetyMarginBytes = SAFETY_MARGIN_BYTES,
            isSufficient = isSufficient,
            shortfallBytes = shortfall
        )
    }

    /**
     * Resolves and creates the private sandbox directory for a specific OS manifest.
     * Enforces that the resulting directory is strictly inside context.filesDir/guest_os.
     */
    fun getOsPrivateDirectory(context: Context, manifestId: String): File {
        validateManifestId(manifestId)
        val baseDir = File(context.filesDir, "guest_os")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        val targetDir = File(baseDir, manifestId)
        val canonicalBase = baseDir.canonicalPath
        val canonicalTarget = targetDir.canonicalPath

        if (!canonicalTarget.startsWith(canonicalBase) || canonicalTarget == canonicalBase) {
            throw SecurityException("Path traversal attempt detected in manifest ID: $manifestId")
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        return targetDir
    }

    /**
     * Validates that an OS manifest ID does not contain path traversal characters.
     */
    fun validateManifestId(manifestId: String) {
        if (manifestId.isBlank()) {
            throw IllegalArgumentException("Manifest ID cannot be empty.")
        }
        if (manifestId.contains("..") || manifestId.contains("/") || manifestId.contains("\\") || manifestId.contains("%")) {
            throw SecurityException("Invalid manifest identifier: contains illegal path traversal tokens.")
        }
    }

    /**
     * Cleans incomplete temporary download files for a manifest.
     */
    fun cleanTemporaryFiles(context: Context, manifestId: String) {
        try {
            val dir = getOsPrivateDirectory(context, manifestId)
            val tmpFiles = dir.listFiles { _, name -> name.endsWith(".tmp") || name.endsWith(".part") }
            tmpFiles?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    /**
     * Returns true if all required artifacts for an OS manifest exist in private storage.
     */
    fun isOsInstalled(context: Context, manifest: OSManifest): Boolean {
        return try {
            val dir = getOsPrivateDirectory(context, manifest.id)
            val kernelFile = File(dir, "vmlinuz")
            val initrdFile = File(dir, "initrd")
            val rootfsFile = File(dir, "rootfs.img")
            val isoFile = File(dir, "installer.iso")

            if (manifest.kernelUrl.isNotBlank()) {
                val hasKernel = kernelFile.exists() && kernelFile.length() > 0
                val hasInitrd = manifest.initramfsUrl.isBlank() || (initrdFile.exists() && initrdFile.length() > 0)
                hasKernel && hasInitrd
            } else {
                (rootfsFile.exists() && rootfsFile.length() > 0) || (isoFile.exists() && isoFile.length() > 0)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns verified kernel and initramfs files if present, or null.
     */
    fun getInstalledFiles(context: Context, manifest: OSManifest): Pair<File, File>? {
        return try {
            val dir = getOsPrivateDirectory(context, manifest.id)
            val kernelFile = File(dir, "vmlinuz")
            val initrdFile = File(dir, "initrd")
            if (kernelFile.exists() && kernelFile.length() > 0 && initrdFile.exists() && initrdFile.length() > 0) {
                Pair(kernelFile, initrdFile)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
