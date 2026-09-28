package com.example.vm.guest.os

import android.content.Context
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.storage.AndroidStorageDiskBackend
import java.io.File
import java.io.InputStream

data class ImportedImageInfo(
    val fileName: String,
    val filePath: String,
    val format: String, // "IMG", "ISO", "RAW", "QCOW2"
    val detectedArchitecture: GuestArchitecture,
    val sizeBytes: Long,
    val suggestedMode: InstallationMode,
    val isSupportedArch: Boolean,
    val warningMessage: String? = null
)

sealed class ImportResult {
    data class Success(val imageInfo: ImportedImageInfo, val importedFile: File) : ImportResult()
    data class Failure(val reason: String) : ImportResult()
}

object ImportManager {

    /**
     * Inspects an external image file before importing.
     * Enforces that x86_64 guest images are rejected with an explicit warning.
     */
    fun inspectImageFile(file: File): ImportedImageInfo {
        val fileName = file.name.lowercase()
        val size = file.length()

        val format = when {
            fileName.endsWith(".iso") -> "ISO"
            fileName.endsWith(".qcow2") -> "QCOW2"
            fileName.endsWith(".raw") -> "RAW"
            else -> "IMG"
        }

        val suggestedMode = if (format == "ISO") InstallationMode.MODE_B_ISO_INSTALLER else InstallationMode.MODE_A_PREINSTALLED

        // Architecture detection check
        val isX86 = fileName.contains("x86_64") || fileName.contains("amd64") || fileName.contains("x64") || fileName.contains("i386")
        val detectedArch = if (isX86) GuestArchitecture.X86_64 else GuestArchitecture.ARM64

        val isSupported = (detectedArch == GuestArchitecture.ARM64)
        val warning = if (!isSupported) {
            "x86_64 guest requires x86_64 emulation support, which is not currently available."
        } else null

        return ImportedImageInfo(
            fileName = file.name,
            filePath = file.absolutePath,
            format = format,
            detectedArchitecture = detectedArch,
            sizeBytes = size,
            suggestedMode = suggestedMode,
            isSupportedArch = isSupported,
            warningMessage = warning
        )
    }

    /**
     * Imports an external image into app private sandbox storage.
     */
    fun importImageStream(
        context: Context,
        inputStream: InputStream,
        destinationFileName: String,
        targetMode: InstallationMode
    ): ImportResult {
        return try {
            val diskBackend = AndroidStorageDiskBackend(context)
            val disksDir = diskBackend.getAuthorizedDisksDirectory()
            val targetFile = File(disksDir, destinationFileName)

            inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val info = inspectImageFile(targetFile)
            if (!info.isSupportedArch) {
                targetFile.delete()
                return ImportResult.Failure(info.warningMessage ?: "Unsupported architecture.")
            }

            ImportResult.Success(imageInfo = info, importedFile = targetFile)
        } catch (e: Exception) {
            ImportResult.Failure("Import failed: ${e.localizedMessage}")
        }
    }
}
