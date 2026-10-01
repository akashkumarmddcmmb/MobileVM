package com.example.vm.validation

import com.example.vm.guest.kernel.GuestKernelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

class LinuxKernelValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testValidArm64UncompressedImage_PassesValidation() {
        val kernelFile = tempFolder.newFile("vmlinuz_valid_arm64")
        val buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x1400000A) // code0 branch
        buffer.position(0x38)
        buffer.putInt(0x644D5241) // Magic "ARMd"
        FileOutputStream(kernelFile).use { it.write(buffer.array()) }

        val info = GuestKernelManager.inspectKernel(kernelFile.absolutePath)
        assertTrue("Expected valid ARM64 kernel image", info.isArm64Valid)
        assertEquals("ARM64 (AArch64)", info.architecture)
        assertTrue(info.formatDescription.contains("uncompressed") || info.formatDescription.contains("0x644D5241"))
    }

    @Test
    fun testValidArm64Elf64Vmlinux_PassesValidation() {
        val kernelFile = tempFolder.newFile("vmlinux_arm64")
        val buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x7F.toByte())
        buffer.put('E'.code.toByte())
        buffer.put('L'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.put(2.toByte()) // 64-bit
        buffer.put(1.toByte()) // Little endian
        buffer.position(0x12)
        buffer.putShort(183.toShort()) // EM_AARCH64 = 183
        FileOutputStream(kernelFile).use { it.write(buffer.array()) }

        val info = GuestKernelManager.inspectKernel(kernelFile.absolutePath)
        assertTrue("Expected valid ARM64 ELF64 kernel", info.isArm64Valid)
        assertEquals("ARM64 (AArch64)", info.architecture)
        assertTrue(info.formatDescription.contains("vmlinux"))
    }

    @Test
    fun testValidArm64GzipVmlinuz_PassesValidation() {
        val kernelFile = tempFolder.newFile("vmlinuz.gz")
        val arm64Payload = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        arm64Payload.putInt(0x1400000A)
        arm64Payload.position(0x38)
        arm64Payload.putInt(0x644D5241) // Magic "ARMd"
        // Fill remaining buffer with non-zero dummy payload so gzip compressed size > 64 bytes
        for (i in 0x40 until 4096) {
            arm64Payload.put(i, (i and 0xFF).toByte())
        }

        FileOutputStream(kernelFile).use { fos ->
            GZIPOutputStream(fos).use { gzip ->
                gzip.write(arm64Payload.array())
            }
        }

        val info = GuestKernelManager.inspectKernel(kernelFile.absolutePath)
        assertTrue("Expected valid gzip ARM64 vmlinuz", info.isArm64Valid)
        assertEquals("ARM64 (AArch64)", info.architecture)
        assertTrue(info.formatDescription.contains("gzip-compressed"))
    }

    @Test
    fun testX86_64Kernel_FailsValidation() {
        val kernelFile = tempFolder.newFile("vmlinux_x86_64")
        val buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x7F.toByte())
        buffer.put('E'.code.toByte())
        buffer.put('L'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.put(2.toByte()) // 64-bit
        buffer.position(0x12)
        buffer.putShort(62.toShort()) // EM_X86_64 = 62
        FileOutputStream(kernelFile).use { it.write(buffer.array()) }

        val info = GuestKernelManager.inspectKernel(kernelFile.absolutePath)
        assertFalse("Expected x86_64 kernel to fail ARM64 validation", info.isArm64Valid)
        assertTrue(info.formatDescription.contains("x86_64 kernel binary rejected"))
    }

    @Test
    fun testArm32Kernel_FailsValidation() {
        val kernelFile = tempFolder.newFile("zImage_arm32")
        val buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x7F.toByte())
        buffer.put('E'.code.toByte())
        buffer.put('L'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.put(1.toByte()) // 32-bit
        buffer.position(0x12)
        buffer.putShort(40.toShort()) // EM_ARM = 40
        FileOutputStream(kernelFile).use { it.write(buffer.array()) }

        val info = GuestKernelManager.inspectKernel(kernelFile.absolutePath)
        assertFalse("Expected ARM32 kernel to fail ARM64 validation", info.isArm64Valid)
        assertTrue(info.formatDescription.contains("ARM32 kernel binary rejected"))
    }

    @Test
    fun testUbuntuCloudDiskImageSuppliedAsKernel_FailsValidationWithExplicitError() {
        val diskFile = tempFolder.newFile("ubuntu-24.04-server-cloudimg-arm64.img")
        val buffer = ByteArray(4096)
        // Simulate QCOW2 magic header: QFI\xfb
        buffer[0] = 'Q'.code.toByte()
        buffer[1] = 'F'.code.toByte()
        buffer[2] = 'I'.code.toByte()
        buffer[3] = 0xFB.toByte()
        FileOutputStream(diskFile).use { it.write(buffer) }

        val info = GuestKernelManager.inspectKernel(diskFile.absolutePath)
        assertFalse("Expected QCOW2 cloud disk image to fail kernel validation", info.isArm64Valid)
        assertTrue(
            "Expected explicit disk image error message, got: ${info.formatDescription}",
            info.formatDescription.contains("Disk image supplied where ARM64 kernel is required")
        )
    }

    @Test
    fun testIsoDiscSuppliedAsKernel_FailsValidationWithExplicitError() {
        val isoFile = tempFolder.newFile("ubuntu-installer.iso")
        val buffer = ByteArray(0x9010)
        // ISO 9660 magic "CD001" at offset 0x8001
        buffer[0x8001] = 'C'.code.toByte()
        buffer[0x8002] = 'D'.code.toByte()
        buffer[0x8003] = '0'.code.toByte()
        buffer[0x8004] = '0'.code.toByte()
        buffer[0x8005] = '1'.code.toByte()
        FileOutputStream(isoFile).use { it.write(buffer) }

        val info = GuestKernelManager.inspectKernel(isoFile.absolutePath)
        assertFalse("Expected ISO image to fail kernel validation", info.isArm64Valid)
        assertTrue(
            "Expected explicit ISO error message, got: ${info.formatDescription}",
            info.formatDescription.contains("Disk image supplied where ARM64 kernel is required")
        )
    }

    @Test
    fun testHtmlDownloadResponse_FailsValidation() {
        val htmlFile = tempFolder.newFile("kernel_404.html")
        val htmlContent = "<!DOCTYPE html><html><head><title>404 Not Found</title></head><body>404 Not Found</body></html>"
        FileOutputStream(htmlFile).use { it.write(htmlContent.toByteArray(Charsets.UTF_8)) }

        val info = GuestKernelManager.inspectKernel(htmlFile.absolutePath)
        assertFalse("Expected HTML download error to fail kernel validation", info.isArm64Valid)
        assertTrue(info.formatDescription.contains("HTML/text error response"))
    }

    @Test
    fun testEmptyOrTruncatedFile_FailsValidation() {
        val emptyFile = tempFolder.newFile("empty_kernel")
        val info = GuestKernelManager.inspectKernel(emptyFile.absolutePath)
        assertFalse("Expected empty file to fail kernel validation", info.isArm64Valid)
        assertTrue(info.formatDescription.contains("empty or truncated"))
    }
}
