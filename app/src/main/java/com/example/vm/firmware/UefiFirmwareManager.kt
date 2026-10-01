package com.example.vm.firmware

import android.content.Context
import android.util.Log
import com.example.vm.cpu.GuestArchitecture
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

sealed class FirmwareValidationResult {
    data class Valid(val info: FirmwareImageInfo, val details: String) : FirmwareValidationResult()
    data class Missing(val reason: String) : FirmwareValidationResult()
    data class Invalid(val reason: String) : FirmwareValidationResult()
}

sealed class NvramStoreResult {
    data class Valid(val path: String, val preserved: Boolean) : NvramStoreResult()
    data class Invalid(val reason: String) : NvramStoreResult()
}

/**
 * UefiFirmwareManager: Manages UEFI / EDK2 firmware images and NVRAM variable stores
 * according to guest architecture.
 */
object UefiFirmwareManager {
    private const val TAG = "UefiFirmware"
    const val ARM64_FLASH_BASE = 0x00000000L
    const val ARM64_FLASH_SIZE = 64L * 1024L * 1024L // 64 MB Flash window
    const val MIN_FIRMWARE_SIZE_BYTES = 1024L * 1024L // Minimum 1 MB for UEFI firmware

    // EDK2 Variable Store constants
    private const val VAR_STORE_FORMATTED: Byte = 0x5A
    private val VAR_STORE_HEALTHY: Byte = 0xFE.toByte()
    private const val VAR_STORE_MAGIC = 0x5AA55AA5

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
            isNvramConfigured = nvramFile.exists() && nvramFile.length() >= 512,
            nvramPath = nvramFile.absolutePath
        )
    }

    /**
     * Validates an ARM64 UEFI firmware binary.
     * Enforces file presence, readability, minimum size, and ARM64 PI Firmware Volume / AArch64 vector headers.
     */
    fun validateFirmware(context: Context, arch: GuestArchitecture, customPath: String? = null): FirmwareValidationResult {
        val info = if (!customPath.isNullOrBlank()) {
            val f = File(customPath)
            FirmwareImageInfo(
                name = f.name,
                architecture = arch,
                path = f.absolutePath,
                exists = f.exists(),
                sizeBytes = if (f.exists()) f.length() else 0L,
                flashBaseAddress = ARM64_FLASH_BASE,
                isNvramConfigured = false,
                nvramPath = ""
            )
        } else {
            getFirmwareForArch(context, arch)
        }

        val file = File(info.path)
        if (!file.exists()) {
            return FirmwareValidationResult.Missing("ARM64 UEFI firmware image not found at: ${info.path}")
        }

        if (!file.canRead()) {
            return FirmwareValidationResult.Invalid("ARM64 UEFI firmware file is not readable: ${info.path}")
        }

        if (file.length() < MIN_FIRMWARE_SIZE_BYTES) {
            return FirmwareValidationResult.Invalid("Firmware image size (${file.length()} bytes) is too small. Authentic ARM64 EDK2 firmware must be at least 1 MB.")
        }

        if (file.length() > ARM64_FLASH_SIZE) {
            return FirmwareValidationResult.Invalid("Firmware image size (${file.length()} bytes) exceeds maximum 64 MB flash window.")
        }

        // Validate ARM64 Firmware Volume / header signature
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(65536.coerceAtMost(file.length().toInt()))
                val read = stream.read(header)
                if (read < 64) {
                    return FirmwareValidationResult.Invalid("Cannot read firmware header.")
                }

                // Check for AArch64 B instruction (0x14000000 mask) or PI Firmware Volume signature "_FVH"
                var hasArm64Sig = false
                val firstWord = (header[3].toInt() and 0xFF shl 24) or
                        (header[2].toInt() and 0xFF shl 16) or
                        (header[1].toInt() and 0xFF shl 8) or
                        (header[0].toInt() and 0xFF)

                // 0x14000000..0x17FFFFFF is AArch64 B (unconditional branch)
                if ((firstWord and 0xFC000000.toInt()) == 0x14000000) {
                    hasArm64Sig = true
                }

                // Check for EDK2 Firmware Volume Header signature "_FVH" (0x5F 0x46 0x56 0x48)
                for (i in 0 until (read - 4)) {
                    if (header[i] == '_'.code.toByte() &&
                        header[i + 1] == 'F'.code.toByte() &&
                        header[i + 2] == 'V'.code.toByte() &&
                        header[i + 3] == 'H'.code.toByte()
                    ) {
                        hasArm64Sig = true
                        break
                    }
                }

                if (hasArm64Sig) {
                    FirmwareValidationResult.Valid(info, "Valid ARM64 EDK2/UEFI Firmware Volume (${file.length() / (1024 * 1024)} MB)")
                } else {
                    FirmwareValidationResult.Invalid("Firmware file does not contain valid ARM64 reset branch or EDK2 Firmware Volume signature (_FVH).")
                }
            }
        } catch (e: Exception) {
            FirmwareValidationResult.Invalid("Error inspecting firmware header: ${e.message}")
        }
    }

    /**
     * Initializes or verifies the persistent NVRAM variable store.
     * Preserves existing BootOrder, BootNext, and Boot#### variables across restarts.
     * Does NOT regenerate unnecessarily on every boot.
     */
    fun getOrInitializeNvramStore(context: Context? = null, arch: GuestArchitecture = GuestArchitecture.ARM64, customNvramPath: String? = null): NvramStoreResult {
        val nvramFile = if (!customNvramPath.isNullOrBlank()) {
            File(customNvramPath)
        } else if (context != null) {
            val firmwareDir = File(context.filesDir, "firmware").apply { mkdirs() }
            val fileName = when (arch) {
                GuestArchitecture.ARM64 -> "QEMU_EFI.fd.vars"
                GuestArchitecture.X86_64 -> "OVMF_CODE.fd.vars"
                GuestArchitecture.X86 -> "OVMF_IA32.fd.vars"
                GuestArchitecture.ARM32 -> "QEMU_EFI_ARM32.fd.vars"
            }
            File(firmwareDir, fileName)
        } else {
            return NvramStoreResult.Invalid("Context required when customNvramPath is not provided")
        }

        return try {
            if (nvramFile.exists() && nvramFile.length() >= 512) {
                // Validate existing variable store header
                nvramFile.inputStream().use { fis ->
                    val hdr = ByteArray(32)
                    fis.read(hdr)
                    val buf = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                    val magic = buf.int
                    val format = hdr[16]
                    val state = hdr[17]

                    if (magic == VAR_STORE_MAGIC && format == VAR_STORE_FORMATTED && state == VAR_STORE_HEALTHY) {
                        Log.i(TAG, "Preserving existing persistent NVRAM variable store (${nvramFile.length()} bytes)")
                        return NvramStoreResult.Valid(nvramFile.absolutePath, preserved = true)
                    }
                }
            }

            // Fresh initialization
            nvramFile.parentFile?.mkdirs()
            val totalSize = 64 * 1024 // 64 KB NVRAM store
            val storeBytes = ByteArray(totalSize)
            val buf = ByteBuffer.wrap(storeBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Variable Store Header
            buf.putInt(VAR_STORE_MAGIC) // Magic
            buf.putInt(totalSize) // Size
            buf.putLong(0L) // Reserved
            buf.put(VAR_STORE_FORMATTED) // Format = 0x5A
            buf.put(VAR_STORE_HEALTHY) // State = 0xFE
            buf.putShort(0.toShort()) // Reserved

            // Default BootOrder variable: Boot0000 (Windows Boot Manager), Boot0001 (CD/DVD)
            val varOffset = 32
            buf.position(varOffset)
            buf.putShort(0x55AA.toShort()) // Variable start ID
            buf.put(0x07.toByte()) // Attributes: NON_VOLATILE | BOOTSERVICE | RUNTIME
            buf.put(0.toByte()) // Reserved
            buf.putInt(4) // Data size (2 UINT16 values = 4 bytes)
            buf.putShort(0.toShort()) // Boot0000
            buf.putShort(1.toShort()) // Boot0001

            FileOutputStream(nvramFile).use { fos ->
                fos.write(storeBytes)
            }
            Log.i(TAG, "Initialized fresh persistent NVRAM variable store at: ${nvramFile.absolutePath}")
            NvramStoreResult.Valid(nvramFile.absolutePath, preserved = false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NVRAM variable store: ${e.message}")
            NvramStoreResult.Invalid("Cannot initialize NVRAM variable store: ${e.message}")
        }
    }

    /**
     * Initializes a fresh NVRAM variable store for storing UEFI boot variables and boot order.
     */
    fun initializeNvramStore(nvramFile: File, sizeBytes: Long = 64L * 1024L * 1024L): Boolean {
        val res = getOrInitializeNvramStore(
            context = null,
            arch = GuestArchitecture.ARM64,
            customNvramPath = nvramFile.absolutePath
        )
        return res is NvramStoreResult.Valid
    }
}
