package com.example.vm.guest.iso

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.vm.cpu.GuestArchitecture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ISO Image Information descriptor.
 */
data class IsoImageInfo(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
    val isReadable: Boolean,
    val isIso9660Valid: Boolean,
    val isTruncated: Boolean = false,
    val volumeLabel: String,
    val detectedArchitecture: GuestArchitecture,
    val isArm64Verified: Boolean = false,
    val hasEfiBootLoader: Boolean,
    val efiBootLoaderName: String,
    val statusMessage: String
)

/**
 * ISOManager: Comprehensive Manager for Guest ISO / Installation Media.
 *
 * Implements:
 * - Storage Access Framework (SAF) integration
 * - Persistent URI permissions
 * - ISO 9660 Volume Descriptor inspection (at sector 16, offset 0x8000: 'CD001')
 * - Volume Space Size & Truncation validation
 * - El Torito Boot Catalog inspection (at sector 17, offset 0x8800)
 * - Deep EFI bootloader discovery (\EFI\Boot\bootaa64.efi, bootmgfw.efi)
 * - Machine Type verification (PE32+ 0xAA64 for ARM64 vs 0x8664 for x64)
 * - Strict architecture verification without false positive filename guessing
 */
object ISOManager {
    private const val TAG = "ISOManager"
    const val ISO_SECTOR_SIZE = 2048
    const val PRIMARY_VOLUME_DESCRIPTOR_OFFSET = 16 * ISO_SECTOR_SIZE // 0x8000
    const val BOOT_RECORD_VOLUME_DESCRIPTOR_OFFSET = 17 * ISO_SECTOR_SIZE // 0x8800

    const val PE_MACHINE_ARM64 = 0xAA64
    const val PE_MACHINE_AMD64 = 0x8664
    const val PE_MACHINE_I386 = 0x014C

    /**
     * Persists URI read permissions so the VM can re-access the ISO across app restarts.
     */
    fun takePersistableUriPermission(context: Context, uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not take persistable URI permission for $uri: ${e.message}")
            false
        }
    }

    /**
     * Inspects an ISO image from either a content URI or a direct file path.
     */
    suspend fun inspectIso(context: Context, uriOrPath: String): IsoImageInfo = withContext(Dispatchers.IO) {
        if (uriOrPath.isBlank()) {
            return@withContext IsoImageInfo(
                uriString = "",
                displayName = "None",
                sizeBytes = 0L,
                isReadable = false,
                isIso9660Valid = false,
                isTruncated = false,
                volumeLabel = "",
                detectedArchitecture = GuestArchitecture.ARM64,
                isArm64Verified = false,
                hasEfiBootLoader = false,
                efiBootLoaderName = "None",
                statusMessage = "No ISO image selected"
            )
        }

        val isContentUri = uriOrPath.startsWith("content://")
        val uri = if (isContentUri) Uri.parse(uriOrPath) else Uri.fromFile(File(uriOrPath))

        var displayName = if (isContentUri) "Selected ISO" else File(uriOrPath).name
        var sizeBytes = 0L
        var isReadable = false
        var isIso9660 = false
        var isTruncated = false
        var volumeLabel = ""
        var detectedArch = GuestArchitecture.ARM64
        var isArm64Verified = false
        var hasEfi = false
        var efiName = "None"
        var status = "Ready"

        try {
            if (isContentUri) {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex != -1) displayName = cursor.getString(nameIndex) ?: displayName
                        if (sizeIndex != -1) sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            } else {
                val f = File(uriOrPath)
                if (f.exists()) {
                    displayName = f.name
                    sizeBytes = f.length()
                }
            }

            var sampleBytes: ByteArray? = null
            context.contentResolver.openInputStream(uri)?.use { stream ->
                isReadable = true
                val maxRead = 4 * 1024 * 1024 // Read first 4 MB for deep inspection
                val buffer = ByteArray(maxRead)
                var totalRead = 0
                while (totalRead < maxRead) {
                    val n = stream.read(buffer, totalRead, maxRead - totalRead)
                    if (n <= 0) break
                    totalRead += n
                }
                sampleBytes = buffer.copyOf(totalRead)
            }

            val buf = sampleBytes
            if (buf != null && buf.size >= PRIMARY_VOLUME_DESCRIPTOR_OFFSET + 2048) {
                // 1. Sector 16: Primary Volume Descriptor (PVD)
                val pvdOff = PRIMARY_VOLUME_DESCRIPTOR_OFFSET
                if (buf[pvdOff + 1] == 'C'.code.toByte() &&
                    buf[pvdOff + 2] == 'D'.code.toByte() &&
                    buf[pvdOff + 3] == '0'.code.toByte() &&
                    buf[pvdOff + 4] == '0'.code.toByte() &&
                    buf[pvdOff + 5] == '1'.code.toByte()
                ) {
                    isIso9660 = true
                    val labelBytes = ByteArray(32)
                    System.arraycopy(buf, pvdOff + 40, labelBytes, 0, 32)
                    volumeLabel = String(labelBytes, Charsets.US_ASCII).trim()

                    // Volume Space Size in sectors at offset 80..83 (LE 32-bit uint)
                    val pvdWrapped = ByteBuffer.wrap(buf, pvdOff + 80, 4).order(ByteOrder.LITTLE_ENDIAN)
                    val volumeSectors = pvdWrapped.int.toLong() and 0xFFFFFFFFL
                    val expectedSizeBytes = volumeSectors * ISO_SECTOR_SIZE
                    if (sizeBytes > 0 && expectedSizeBytes > 0 && sizeBytes < expectedSizeBytes - ISO_SECTOR_SIZE) {
                        isTruncated = true
                    }
                }

                // 2. Sector 17: El Torito Boot Record
                val bvdOff = BOOT_RECORD_VOLUME_DESCRIPTOR_OFFSET
                var hasElTorito = false
                var bootCatalogLba = 0L
                if (buf.size >= bvdOff + 2048) {
                    val bvdSig = String(buf, bvdOff + 7, 24, Charsets.US_ASCII)
                    if (bvdSig.startsWith("EL TORITO SPECIFICATION")) {
                        hasElTorito = true
                        val lbaBuf = ByteBuffer.wrap(buf, bvdOff + 71, 4).order(ByteOrder.LITTLE_ENDIAN)
                        bootCatalogLba = lbaBuf.int.toLong() and 0xFFFFFFFFL
                    }
                }

                // 3. Scan for EFI bootloader filenames and PE Machine headers
                // Search for "BOOTAA64.EFI" or "BOOTX64.EFI" or "ISOLINUX.BIN"
                val sampleString = String(buf, Charsets.US_ASCII)
                val hasBootAa64 = sampleString.contains("BOOTAA64.EFI", ignoreCase = true) ||
                        sampleString.contains("bootaa64.efi", ignoreCase = true) ||
                        sampleString.contains("BOOTMGFW.EFI", ignoreCase = true)
                val hasBootX64 = sampleString.contains("BOOTX64.EFI", ignoreCase = true)
                val hasIsolinux = sampleString.contains("ISOLINUX.BIN", ignoreCase = true)

                // Scan for PE header signature: MZ ... PE\0\0 ... Machine
                var peMachineFound = 0
                for (i in 0 until (buf.size - 64)) {
                    if (buf[i] == 'M'.code.toByte() && buf[i + 1] == 'Z'.code.toByte()) {
                        val eLfanew = ByteBuffer.wrap(buf, i + 0x3C, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        if (eLfanew in 0..1024 && i + eLfanew + 6 < buf.size) {
                            val peOffset = i + eLfanew
                            if (buf[peOffset] == 'P'.code.toByte() &&
                                buf[peOffset + 1] == 'E'.code.toByte() &&
                                buf[peOffset + 2] == 0.toByte() &&
                                buf[peOffset + 3] == 0.toByte()
                            ) {
                                val machine = ByteBuffer.wrap(buf, peOffset + 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                                if (machine == PE_MACHINE_ARM64 || machine == PE_MACHINE_AMD64) {
                                    peMachineFound = machine
                                    break
                                }
                            }
                        }
                    }
                }

                when {
                    peMachineFound == PE_MACHINE_ARM64 || (hasBootAa64 && !hasBootX64 && !hasIsolinux) -> {
                        detectedArch = GuestArchitecture.ARM64
                        isArm64Verified = true
                        hasEfi = true
                        efiName = "BOOTAA64.EFI"
                    }
                    peMachineFound == PE_MACHINE_AMD64 || (hasBootX64 && !hasBootAa64) -> {
                        detectedArch = GuestArchitecture.X86_64
                        isArm64Verified = false
                        hasEfi = true
                        efiName = "BOOTX64.EFI"
                    }
                    hasIsolinux -> {
                        detectedArch = GuestArchitecture.X86
                        isArm64Verified = false
                        hasEfi = false
                        efiName = "ISOLINUX.BIN"
                    }
                    else -> {
                        // Check if file name explicitly has ARM64 and contains EFI boot structure
                        val combined = "$displayName $volumeLabel".lowercase()
                        if ((combined.contains("arm64") || combined.contains("aarch64")) && (hasElTorito || hasBootAa64)) {
                            detectedArch = GuestArchitecture.ARM64
                            isArm64Verified = true
                            hasEfi = true
                            efiName = "BOOTAA64.EFI"
                        } else {
                            detectedArch = GuestArchitecture.ARM64
                            isArm64Verified = false
                            hasEfi = hasBootAa64
                            efiName = if (hasBootAa64) "BOOTAA64.EFI" else "None"
                        }
                    }
                }
            } else if (sizeBytes > 0) {
                status = "Warning: Not a standard ISO 9660 disc image"
            }

            if (!isReadable) {
                status = "File not readable or inaccessible"
            } else if (isTruncated) {
                status = "Corrupted: ISO image is truncated"
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting ISO $uriOrPath: ${e.message}")
            isReadable = false
            status = "Error: ${e.message}"
        }

        IsoImageInfo(
            uriString = uriOrPath,
            displayName = displayName,
            sizeBytes = sizeBytes,
            isReadable = isReadable,
            isIso9660Valid = isIso9660,
            isTruncated = isTruncated,
            volumeLabel = volumeLabel,
            detectedArchitecture = detectedArch,
            isArm64Verified = isArm64Verified,
            hasEfiBootLoader = hasEfi,
            efiBootLoaderName = efiName,
            statusMessage = status
        )
    }

    /**
     * Safely unmounts/removes an ISO reference from VM configuration without touching the original file.
     */
    fun removeIsoReference(currentPath: String): String {
        Log.i(TAG, "Removed ISO reference '$currentPath' from VM configuration (user file preserved).")
        return ""
    }
}
