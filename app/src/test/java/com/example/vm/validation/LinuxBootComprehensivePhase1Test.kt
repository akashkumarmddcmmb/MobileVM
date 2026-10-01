package com.example.vm.validation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vm.core.VMConfig
import com.example.vm.core.VMErrorCategory
import com.example.vm.core.VMStartValidator
import com.example.vm.guest.initramfs.GuestInitramfsManager
import com.example.vm.guest.kernel.GuestKernelManager
import com.example.vm.guest.linux.LinuxRootfsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
class LinuxBootComprehensivePhase1Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ==========================================
    // 1. KERNEL VALIDATION TESTS
    // ==========================================

    @Test
    fun `kernel validation rejects fake 256-byte AArch64 ELF`() {
        val kernelFile = tempFolder.newFile("fake_256b_kernel.elf")
        val buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x7F.toByte())
        buffer.put('E'.code.toByte())
        buffer.put('L'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.put(2.toByte()) // 64-bit
        buffer.put(1.toByte()) // Little-endian
        buffer.position(0x12)
        buffer.putShort(183.toShort()) // EM_AARCH64
        buffer.position(120)
        buffer.putInt(0xD503207F.toInt()) // WFI
        buffer.putInt(0x14000000) // B .
        FileOutputStream(kernelFile).use { it.write(buffer.array()) }

        val info = GuestKernelManager.inspectKernel(kernelFile.absolutePath)
        assertFalse("Fake 256-byte AArch64 ELF must be rejected", info.isArm64Valid)
        assertTrue(info.formatDescription.contains("rejected") || info.formatDescription.contains("too small"))
    }

    @Test
    fun `kernel validation rejects random ELF without kernel signatures`() {
        val kernelFile = tempFolder.newFile("random_user_binary.elf")
        val size = 1024 * 1024 // 1 MB
        val buffer = ByteArray(size)
        buffer[0] = 0x7F.toByte()
        buffer[1] = 'E'.code.toByte()
        buffer[2] = 'L'.code.toByte()
        buffer[3] = 'F'.code.toByte()
        buffer[4] = 2.toByte() // 64-bit
        buffer[5] = 1.toByte() // Little-endian
        buffer[0x12] = 0xB7.toByte() // EM_AARCH64 (183)
        buffer[0x13] = 0.toByte()
        // Random bytes in body, no Linux kernel markers
        for (i in 64 until 1024) {
            buffer[i] = (i and 0xFF).toByte()
        }
        FileOutputStream(kernelFile).use { it.write(buffer) }

        val info = GuestKernelManager.inspectKernel(kernelFile.absolutePath)
        assertFalse("Random non-kernel ELF must be rejected", info.isArm64Valid)
        assertTrue(info.formatDescription.contains("KERNEL_FORMAT_UNVERIFIED"))
    }

    @Test
    fun `kernel validation rejects wrong architecture x86_64`() {
        val kernelFile = tempFolder.newFile("vmlinux_x86_64.elf")
        val size = 1024 * 1024
        val buffer = ByteArray(size)
        buffer[0] = 0x7F.toByte()
        buffer[1] = 'E'.code.toByte()
        buffer[2] = 'L'.code.toByte()
        buffer[3] = 'F'.code.toByte()
        buffer[4] = 2.toByte()
        buffer[5] = 1.toByte()
        buffer[0x12] = 62.toByte() // EM_X86_64
        buffer[0x13] = 0.toByte()
        FileOutputStream(kernelFile).use { it.write(buffer) }

        val info = GuestKernelManager.inspectKernel(kernelFile.absolutePath)
        assertFalse("x86_64 kernel must be rejected", info.isArm64Valid)
        assertEquals("x86_64", info.architecture)
    }

    @Test
    fun `kernel validation accepts real ARM64 Image`() {
        val kernelFile = tempFolder.newFile("Image_valid_arm64")
        val size = 1024 * 1024 // 1 MB
        val buffer = ByteArray(size)
        // Set ARM64 magic at offset 0x38: "ARM\x64" (0x644D5241)
        buffer[0x38] = 0x41.toByte()
        buffer[0x39] = 0x52.toByte()
        buffer[0x3A] = 0x4D.toByte()
        buffer[0x3B] = 0x64.toByte()
        FileOutputStream(kernelFile).use { it.write(buffer) }

        val info = GuestKernelManager.inspectKernel(kernelFile.absolutePath)
        assertTrue("Real ARM64 Image must be accepted", info.isArm64Valid)
        assertEquals("ARM64 (AArch64)", info.architecture)
    }

    @Test
    fun `kernel validation accepts real ARM64 vmlinux with kernel signatures`() {
        val kernelFile = tempFolder.newFile("vmlinux_real_arm64")
        val size = 1024 * 1024 // 1 MB
        val buffer = ByteArray(size)
        buffer[0] = 0x7F.toByte()
        buffer[1] = 'E'.code.toByte()
        buffer[2] = 'L'.code.toByte()
        buffer[3] = 'F'.code.toByte()
        buffer[4] = 2.toByte()
        buffer[5] = 1.toByte()
        buffer[0x12] = 183.toByte() // EM_AARCH64
        buffer[0x13] = 0.toByte()

        val sig = "Linux version 6.6.0-arm64 (gcc-13) #1 SMP PREEMPT".toByteArray(Charsets.US_ASCII)
        System.arraycopy(sig, 0, buffer, 512, sig.size)
        FileOutputStream(kernelFile).use { it.write(buffer) }

        val info = GuestKernelManager.inspectKernel(kernelFile.absolutePath)
        assertTrue("Real ARM64 vmlinux must be accepted", info.isArm64Valid)
        assertEquals("ARM64 (AArch64)", info.architecture)
    }

    // ==========================================
    // 2. INITRAMFS VALIDATION TESTS
    // ==========================================

    @Test
    fun `initramfs validation rejects gzip header only archive`() {
        val initrdFile = tempFolder.newFile("gzip_header_only.cpio.gz")
        val gzipBytes = ByteArray(10)
        gzipBytes[0] = 0x1F.toByte()
        gzipBytes[1] = 0x8B.toByte()
        gzipBytes[2] = 0x08.toByte() // Deflate
        FileOutputStream(initrdFile).use { it.write(gzipBytes) }

        val info = GuestInitramfsManager.inspectInitramfs(initrdFile.absolutePath)
        assertFalse("Gzip header only archive must be rejected", info.hasUsableInit)
    }

    @Test
    fun `initramfs validation rejects invalid CPIO`() {
        val initrdFile = tempFolder.newFile("corrupt_cpio.cpio")
        val garbage = ByteArray(512) { 0xAA.toByte() }
        FileOutputStream(initrdFile).use { it.write(garbage) }

        val info = GuestInitramfsManager.inspectInitramfs(initrdFile.absolutePath)
        assertFalse("Corrupted CPIO archive must be rejected", info.hasUsableInit)
    }

    @Test
    fun `initramfs validation rejects archive when init is missing`() {
        val initrdFile = tempFolder.newFile("missing_init.cpio")
        val archiveBytes = createCpioArchive(
            listOf(
                CpioTestEntry("etc/issue", 0x81A4, "Test OS\n".toByteArray()),
                CpioTestEntry("bin/ls", 0x81ED, "#!/bin/sh\n".toByteArray())
            )
        )
        FileOutputStream(initrdFile).use { it.write(archiveBytes) }

        val info = GuestInitramfsManager.inspectInitramfs(initrdFile.absolutePath)
        assertFalse("Initramfs without /init must be rejected", info.hasUsableInit)
    }

    @Test
    fun `initramfs validation rejects archive when init is not executable`() {
        val initrdFile = tempFolder.newFile("non_exec_init.cpio")
        val archiveBytes = createCpioArchive(
            listOf(
                CpioTestEntry("init", 0x81A4 /* 0644 non-executable */, "#!/bin/sh\nexit 0\n".toByteArray()),
                CpioTestEntry("bin/sh", 0x81ED /* 0755 */, "echo shell".toByteArray())
            )
        )
        FileOutputStream(initrdFile).use { it.write(archiveBytes) }

        val info = GuestInitramfsManager.inspectInitramfs(initrdFile.absolutePath)
        assertFalse("Initramfs with non-executable /init must be rejected", info.hasUsableInit)
    }

    @Test
    fun `initramfs validation rejects archive when init specifies missing interpreter`() {
        val initrdFile = tempFolder.newFile("missing_sh_init.cpio")
        val archiveBytes = createCpioArchive(
            listOf(
                CpioTestEntry("init", 0x81ED /* 0755 */, "#!/bin/sh\necho hi\n".toByteArray())
                // bin/sh and bin/busybox are omitted
            )
        )
        FileOutputStream(initrdFile).use { it.write(archiveBytes) }

        val info = GuestInitramfsManager.inspectInitramfs(initrdFile.absolutePath)
        assertFalse("Initramfs where /init uses /bin/sh but /bin/sh is missing must be rejected", info.hasUsableInit)
    }

    @Test
    fun `initramfs validation accepts valid initramfs with executable init and shell`() {
        val initrdFile = tempFolder.newFile("valid_initrd.cpio.gz")
        val archiveBytes = createCpioArchive(
            listOf(
                CpioTestEntry("init", 0x81ED, "#!/bin/sh\necho \"System initialized\"\n".toByteArray()),
                CpioTestEntry("bin/sh", 0x81ED, "#!/bin/sh\n".toByteArray()),
                CpioTestEntry("etc/issue", 0x81A4, "MobileVM\n".toByteArray())
            )
        )

        FileOutputStream(initrdFile).use { fos ->
            GZIPOutputStream(fos).use { gzip ->
                gzip.write(archiveBytes)
            }
        }

        val info = GuestInitramfsManager.inspectInitramfs(initrdFile.absolutePath)
        assertTrue("Valid initramfs archive must be accepted", info.hasUsableInit)
        assertTrue(info.isCompressed)
    }

    // ==========================================
    // 3. ROOTFS VALIDATION TESTS
    // ==========================================

    @Test
    fun `rootfs validation rejects empty sparse image`() {
        val diskFile = tempFolder.newFile("empty_sparse.img")
        val emptyBytes = ByteArray(2 * 1024 * 1024) // 2 MB of zeroes
        FileOutputStream(diskFile).use { it.write(emptyBytes) }

        val info = LinuxRootfsManager.inspectRootfs(diskFile.absolutePath)
        assertFalse("Empty sparse disk must be rejected", info.isVerified)
        assertTrue(info.statusMessage.contains("ROOTFS_UNVERIFIED"))
    }

    @Test
    fun `rootfs validation rejects invalid random filesystem image`() {
        val diskFile = tempFolder.newFile("corrupt_disk.img")
        val garbage = ByteArray(2 * 1024 * 1024) { 0x7E.toByte() }
        FileOutputStream(diskFile).use { it.write(garbage) }

        val info = LinuxRootfsManager.inspectRootfs(diskFile.absolutePath)
        assertFalse("Invalid filesystem must be rejected", info.isVerified)
        assertTrue(info.statusMessage.contains("ROOTFS_UNVERIFIED"))
    }

    @Test
    fun `rootfs validation accepts valid ext4 image`() {
        val diskFile = tempFolder.newFile("valid_ext4.img")
        val size = 2 * 1024 * 1024
        val buf = ByteArray(size)
        // ext4 superblock magic at offset 0x438: 0x53, 0xEF
        buf[0x438] = 0x53.toByte()
        buf[0x439] = 0xEF.toByte()
        // add userspace marker
        val marker = "/etc/os-release /usr/bin/sh".toByteArray()
        System.arraycopy(marker, 0, buf, 4096, marker.size)
        FileOutputStream(diskFile).use { it.write(buf) }

        val info = LinuxRootfsManager.inspectRootfs(diskFile.absolutePath)
        assertTrue("Valid ext4 rootfs must be accepted", info.isVerified)
        assertEquals("ext4", info.format)
    }

    // ==========================================
    // 4. MEMORY MAP AND BACKEND TESTS
    // ==========================================

    @Test
    fun `vm start validator rejects explicit KVM when KVM is unavailable`() {
        val config = VMConfig(
            name = "KvmStrictTest",
            guestOsType = "Ubuntu",
            cpuBackendPreference = "KVM", // Strictly requires KVM
            kernelImagePath = tempFolder.newFile("dummy_k").apply {
                val b = ByteArray(1024 * 1024)
                b[0x38] = 0x41.toByte()
                b[0x39] = 0x52.toByte()
                b[0x3A] = 0x4D.toByte()
                b[0x3B] = 0x64.toByte()
                FileOutputStream(this).use { it.write(b) }
            }.absolutePath,
            ramSizeMb = 1024
        )

        val result = VMStartValidator.validate(context, config)
        if (result is VMStartValidator.ValidationResult.Invalid) {
            assertEquals("KVM unavailable must produce KVM_UNAVAILABLE error",
                VMErrorCategory.KVM_UNAVAILABLE, result.error.category)
        }
    }

    // ==========================================
    // HELPER: CPIO NEWC ARCHIVE BUILDER
    // ==========================================

    private data class CpioTestEntry(
        val name: String,
        val mode: Int,
        val content: ByteArray
    )

    private fun createCpioArchive(entries: List<CpioTestEntry>): ByteArray {
        val bos = ByteArrayOutputStream()

        for ((index, entry) in entries.withIndex()) {
            val nameBytes = (entry.name + "\u0000").toByteArray(Charsets.US_ASCII)
            val nameSize = nameBytes.size
            val fileSize = entry.content.size

            val header = String.format(
                "070701%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X",
                index + 1, entry.mode, 0, 0, 1, System.currentTimeMillis() / 1000,
                fileSize, 3, 1, 0, 0, nameSize, 0
            ).toByteArray(Charsets.US_ASCII)

            bos.write(header)
            bos.write(nameBytes)
            val namePad = (4 - ((110 + nameSize) % 4)) % 4
            for (p in 0 until namePad) bos.write(0)

            bos.write(entry.content)
            val contentPad = (4 - (fileSize % 4)) % 4
            for (p in 0 until contentPad) bos.write(0)
        }

        val trailerName = "TRAILER!!!\u0000".toByteArray(Charsets.US_ASCII)
        val trailerHeader = String.format(
            "070701%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X",
            0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, trailerName.size, 0
        ).toByteArray(Charsets.US_ASCII)

        bos.write(trailerHeader)
        bos.write(trailerName)
        val trailerPad = (4 - ((110 + trailerName.size) % 4)) % 4
        for (p in 0 until trailerPad) bos.write(0)

        return bos.toByteArray()
    }
}
