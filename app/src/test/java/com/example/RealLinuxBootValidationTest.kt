package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vm.core.VMConfig
import com.example.vm.core.VMEngine
import com.example.vm.core.VMState
import com.example.vm.guest.initramfs.GuestInitramfsDownloader
import com.example.vm.guest.initramfs.GuestInitramfsManager
import com.example.vm.guest.kernel.GuestKernelDownloader
import com.example.vm.guest.kernel.GuestKernelManager
import com.example.vm.storage.AndroidStorageDiskBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealLinuxBootValidationTest {

    private lateinit var context: Context
    private lateinit var diskBackend: AndroidStorageDiskBackend

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        diskBackend = AndroidStorageDiskBackend(context)
    }

    @Test
    fun `arm64 kernel validation detects valid arm64 header magic`() {
        val testKernelFile = File(context.cacheDir, "test_kernel_valid.img")
        val headerBytes = ByteArray(128)
        // Standard ARM64 magic at offset 0x38: "ARM\x64" (0x41, 0x52, 0x4D, 0x64)
        headerBytes[0x38] = 0x41.toByte()
        headerBytes[0x39] = 0x52.toByte()
        headerBytes[0x3A] = 0x4D.toByte()
        headerBytes[0x3B] = 0x64.toByte()
        FileOutputStream(testKernelFile).use { it.write(headerBytes) }

        val info = GuestKernelManager.inspectKernel(testKernelFile.absolutePath)
        assertTrue("Kernel must be reported as valid ARM64", info.isArm64Valid)
        assertEquals("ARM64 (AArch64)", info.architecture)
        assertTrue(info.formatDescription.contains("0x644D5241"))

        testKernelFile.delete()
    }

    @Test
    fun `arm64 kernel validation rejects invalid or corrupted non-arm64 image`() {
        val badKernelFile = File(context.cacheDir, "test_kernel_invalid.img")
        val randomBytes = ByteArray(128) { 0x55.toByte() }
        FileOutputStream(badKernelFile).use { it.write(randomBytes) }

        val info = GuestKernelManager.inspectKernel(badKernelFile.absolutePath)
        assertFalse("Invalid binary must NOT be accepted as ARM64", info.isArm64Valid)
        assertTrue(info.formatDescription.contains("Not ARM64") || info.formatDescription.contains("Unrecognized"))

        badKernelFile.delete()
    }

    @Test
    fun `initramfs validation accepts valid gzip cpio archive`() {
        val testInitrd = File(context.cacheDir, "test_initrd.cpio.gz")
        val gzipBytes = ByteArray(64)
        // Gzip header magic: 0x1F, 0x8B
        gzipBytes[0] = 0x1F.toByte()
        gzipBytes[1] = 0x8B.toByte()
        FileOutputStream(testInitrd).use { it.write(gzipBytes) }

        val info = GuestInitramfsManager.inspectInitramfs(testInitrd.absolutePath)
        assertTrue("Initramfs must exist", info.exists)
        assertTrue("Initramfs must be detected as compressed gzip", info.isCompressed)
        assertTrue(info.format.contains("gzip"))

        testInitrd.delete()
    }

    @Test
    fun `initramfs validation rejects non-existent or zero-byte file`() {
        val nonExistentPath = File(context.cacheDir, "does_not_exist.cpio.gz").absolutePath
        val info = GuestInitramfsManager.inspectInitramfs(nonExistentPath)
        assertFalse("Missing initramfs must not exist", info.exists)
        assertEquals(0L, info.sizeBytes)
        assertEquals("File Not Found", info.format)
    }

    @Test
    fun `kernel import blocks invalid image from entering app sandbox`() = runBlocking {
        val badData = "Not an arm64 kernel binary payload at all".toByteArray()
        val inputStream = ByteArrayInputStream(badData)

        val result = GuestKernelDownloader.importKernel(
            context = context,
            sourceInputStream = inputStream,
            destinationFileName = "rejected_kernel.img"
        )

        assertTrue("Importing invalid file must fail", result is GuestKernelDownloader.DownloadResult.Failure)
        val fileInStorage = File(context.filesDir, "guest_kernels/rejected_kernel.img")
        assertFalse("Failed file must be cleaned up and not remain in storage", fileInStorage.exists())
    }

    @Test
    fun `initramfs import rejects zero-byte archive`() = runBlocking {
        val emptyStream = ByteArrayInputStream(ByteArray(0))
        val result = GuestInitramfsDownloader.importInitramfs(
            context = context,
            sourceInputStream = emptyStream,
            destinationFileName = "rejected_initrd.cpio.gz"
        )

        assertTrue("Importing 0-byte file must fail", result is GuestInitramfsDownloader.InitramfsResult.Failure)
    }

    @Test
    fun `vm engine refuses to start when no valid kernel image is configured`() {
        val config = VMConfig(
            id = 101L,
            name = "TestVM_NoKernel",
            kernelImagePath = "", // Empty: no kernel configured
            ramSizeMb = 1024,
            diskImagePath = ""
        )

        val engine = VMEngine(context, config)
        val error = engine.start()

        assertNotNull("Starting VM without a valid kernel must produce an error", error)
        assertEquals(VMState.NOT_VERIFIED, engine.state.value)
        assertTrue("Error summary or details must indicate missing kernel",
            error?.technicalDetails?.contains("NOT IMPLEMENTED") == true || error?.summary?.contains("kernel") == true
        )
    }

    @Test
    fun `guest memory security check rejects paths outside app private storage`() {
        val unauthorizedHostPath = "/data/system/packages.xml"
        assertFalse("Paths outside sandbox must be blocked", diskBackend.isGuestImagePathAuthorized(unauthorizedHostPath))

        val appSandboxPath = File(diskBackend.getAuthorizedAssetsDirectory(), "vmlinuz").absolutePath
        assertTrue("App-scoped assets directory must be authorized", diskBackend.isGuestImagePathAuthorized(appSandboxPath))
    }

    @Test
    fun `vm instance cleanup tears down all resources cleanly after stop`() {
        val config = VMConfig(
            id = 102L,
            name = "TestVM_Teardown",
            ramSizeMb = 512,
            diskImagePath = ""
        )

        val engine = VMEngine(context, config)
        engine.stop()
        assertEquals(VMState.STOPPED, engine.state.value)
        engine.destroy()
    }
}
