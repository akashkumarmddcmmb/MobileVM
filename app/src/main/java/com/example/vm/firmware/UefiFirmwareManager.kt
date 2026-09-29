package com.example.vm.firmware

import android.content.Context
import android.util.Log
import com.example.vm.cpu.GuestArchitecture
import java.io.File
import java.io.FileOutputStream

/**
 * Firmware Image Descriptor.
 */
data class FirmwareImageInfo(
    val name: String,
    val architecture: GuestArchitecture,
    val path: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val flashBaseAddress: Long,
    val isNvramConfigured: Boolean,
    val nvramPath: String
)

/**
 * UefiFirmwareManager: Manages UEFI / EDK2 firmware images and NVRAM variable stores
 * according to guest architecture.
 */
object UefiFirmwareManager {
    private const val TAG = "UefiFirmware"
    const val ARM64_FLASH_BASE = 0x00000000L
    const val ARM64_FLASH_SIZE = 64L * 1024L * 1024L // 64 MB Flash window

    /**
     * Resolves the default firmware configuration for a guest architecture.
     */
    fun getFirmwareForArch(context: Context, arch: GuestArchitecture): FirmwareImageInfo {
        val firmwareDir = File(context.filesDir, "firmware").apply { mkdirs() }

        val (fileName, flashBase) = when (arch) {
            GuestArchitecture.ARM64 -> Pair("QEMU_EFI.fd", ARM64_FLASH_BASE)
            GuestArchitecture.X86_64 -> Pair("OVMF_CODE.fd", 0xFFC00000L)
            GuestArchitecture.X86 -> Pair("OVMF_IA32.fd", 0xFFC00000L)
            GuestArchitecture.ARM32 -> Pair("QEMU_EFI_ARM32.fd", ARM64_FLASH_BASE)
        }

        val fwFile = File(firmwareDir, fileName)
        val nvramFile = File(firmwareDir, "${fileName}.vars")

        return FirmwareImageInfo(
            name = fileName,
            architecture = arch,
            path = fwFile.absolutePath,
            exists = fwFile.exists(),
            sizeBytes = if (fwFile.exists()) fwFile.length() else 0L,
            flashBaseAddress = flashBase,
            isNvramConfigured = nvramFile.exists(),
            nvramPath = nvramFile.absolutePath
        )
    }

    /**
     * Initializes a fresh NVRAM variable store for storing UEFI boot variables and boot order.
     */
    fun initializeNvramStore(nvramFile: File, sizeBytes: Long = 64L * 1024L * 1024L): Boolean {
        return try {
            if (!nvramFile.exists()) {
                FileOutputStream(nvramFile).use { fos ->
                    // Sparse zero fill for NVRAM flash store
                    fos.write(ByteArray(4096))
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NVRAM variable store: ${e.message}")
            false
        }
    }
}
