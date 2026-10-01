package com.example.vm.validation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vm.core.VMConfig
import com.example.vm.core.VMEngine
import com.example.vm.core.VMErrorCategory
import com.example.vm.core.VMState
import com.example.vm.guest.linux.LinuxImageProvisioner
import com.example.vm.guest.windows.WindowsGuestManager
import com.example.vm.storage.AndroidStorageDiskBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VMValidationAndStressTest {

    private lateinit var context: Context
    private lateinit var validKernelFile: File
    private lateinit var validInitrdFile: File
    private lateinit var validDiskFile: File
    private lateinit var validWindowsIsoFile: File
    private lateinit var validWindowsDiskFile: File

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()

        // 1. Provision valid Linux guest assets
        val dir = File(context.filesDir, "guest_os/ubuntu-24-04-cloud-arm64").apply { mkdirs() }
        val kernelFile = File(dir, "vmlinuz")
        val kBytes = ByteArray(1024 * 1024)
        kBytes[0x38] = 0x41.toByte()
        kBytes[0x39] = 0x52.toByte()
        kBytes[0x3A] = 0x4D.toByte()
        kBytes[0x3B] = 0x64.toByte()
        kernelFile.writeBytes(kBytes)

        val initrdFile = File(dir, "initrd")
        val archiveBytes = createTestCpio(listOf(
            Pair("init", "#!/bin/sh\nexit 0\n".toByteArray()),
            Pair("bin/sh", "#!/bin/sh\n".toByteArray())
        ))
        java.io.FileOutputStream(initrdFile).use { fos ->
            java.util.zip.GZIPOutputStream(fos).use { gzip ->
                gzip.write(archiveBytes)
            }
        }

        val diskFile = File(dir, "rootfs.img")
        val diskBytes = ByteArray(2 * 1024 * 1024)
        diskBytes[0x438] = 0x53.toByte()
        diskBytes[0x439] = 0xEF.toByte()
        diskFile.writeBytes(diskBytes)

        val linuxResult = LinuxImageProvisioner.provisionDefaultLinuxEnvironment(context, "ValidationVM", forceRecreate = false)
        assertTrue(linuxResult is LinuxImageProvisioner.ProvisionResult.Success)
        val success = linuxResult as LinuxImageProvisioner.ProvisionResult.Success

        validKernelFile = File(success.kernelPath)
        validInitrdFile = File(success.initramfsPath)
        validDiskFile = File(success.diskPath)

        // 2. Provision valid Windows guest media in app storage
        val winDir = File(context.filesDir, "guest_os/windows_arm64").apply { mkdirs() }
        validWindowsIsoFile = File(winDir, "win11_arm64_test.iso").apply {
            writeBytes(ByteArray(1024 * 1024 + 512)) // > 1MB
        }
        val appDisksDir = File(context.filesDir, "app_disks").apply { mkdirs() }
        validWindowsDiskFile = File(appDisksDir, "win11_system_drive.img").apply {
            val backend = AndroidStorageDiskBackend(context)
            backend.createDiskImage(absolutePath, 64, sparse = true)
        }
    }

    // ==========================================
    // PART E — LINUX TEST MATRIX
    // ==========================================

    @Test
    fun testL1_ValidLinuxStartThroughActualNativePath() {
        val config = VMConfig(
            name = "TestL1_Linux",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 2
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNull("L1: Linux VM should start without errors", err)
        assertTrue(engine.state.value == VMState.RUNNING || engine.state.value == VMState.STARTING || engine.state.value == VMState.BOOTING)
        engine.stop()
        assertEquals(VMState.STOPPED, engine.state.value)
    }

    @Test
    fun testL2_KernelMissing() {
        val config = VMConfig(
            name = "TestL2_KernelMissing",
            guestOsType = "Linux ARM64",
            kernelImagePath = "",
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNotNull("L2: Start must fail when kernel is missing", err)
        assertEquals(VMErrorCategory.KERNEL_MISSING, err?.category)
        assertNull("No active instance allowed", engine.getActiveInstance())
    }

    @Test
    fun testL3_KernelUnreadableOrNonExistent() {
        val nonExistent = File(context.filesDir, "non_existent_kernel_image")
        val config = VMConfig(
            name = "TestL3_KernelUnreadable",
            guestOsType = "Linux ARM64",
            kernelImagePath = nonExistent.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNotNull("L3: Unreadable/non-existent kernel must fail gracefully", err)
        assertEquals(VMErrorCategory.KERNEL_MISSING, err?.category)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testL4_InvalidKernelFormat() {
        val invalidKernel = File(context.filesDir, "corrupted_kernel.bin").apply {
            writeBytes(byteArrayOf(0x00, 0x01, 0x02)) // Too small / corrupted
        }
        val config = VMConfig(
            name = "TestL4_InvalidKernel",
            guestOsType = "Linux ARM64",
            kernelImagePath = invalidKernel.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNotNull("L4: Corrupted kernel must fail validation", err)
        assertEquals(VMErrorCategory.KERNEL_MISSING, err?.category)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testL5_InitramfsMissing() {
        val config = VMConfig(
            name = "TestL5_InitramfsMissing",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = "${context.filesDir}/nonexistent_initrd.cpio",
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNotNull("L5: Missing initramfs must fail", err)
        assertEquals(VMErrorCategory.INITRAMFS_MISSING, err?.category)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testL6_InvalidInitramfs() {
        val invalidInitrd = File(context.filesDir, "zero_initrd.cpio").apply {
            writeBytes(ByteArray(0)) // 0 byte file
        }
        val config = VMConfig(
            name = "TestL6_InvalidInitrd",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = invalidInitrd.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNotNull("L6: 0-byte initramfs must fail validation", err)
        assertEquals(VMErrorCategory.INITRAMFS_MISSING, err?.category)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testL7_RootfsMissing() {
        val emptyDisk = File(context.filesDir, "app_disks/empty_rootfs.raw").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(0))
        }
        val config = VMConfig(
            name = "TestL7_RootfsMissing",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = emptyDisk.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNotNull("L7: Empty rootfs must fail validation", err)
        assertEquals(VMErrorCategory.DISK_INVALID, err?.category)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testL8_InvalidRootfsDisk() {
        val emptyDisk = File(context.filesDir, "app_disks/empty_disk.img").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(0))
        }
        val config = VMConfig(
            name = "TestL8_InvalidDisk",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = emptyDisk.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNotNull("L8: Empty virtual disk must fail validation", err)
        assertEquals(VMErrorCategory.DISK_INVALID, err?.category)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testL10_LinuxStartStopCompleteNativeCleanup() {
        val config = VMConfig(
            name = "TestL10_StartStop",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNull(err)
        assertTrue(engine.state.value.isActive())

        val stopErr = engine.stop()
        assertNull(stopErr)
        assertEquals(VMState.STOPPED, engine.state.value)
        assertNull(engine.getActiveInstance())
        assertEquals(0L, engine.nativeHandle)
    }

    @Test
    fun testL11_LinuxStartRestartNoStaleHandles() {
        val config = VMConfig(
            name = "TestL11_Restart",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        engine.start()
        assertTrue(engine.state.value.isActive())

        val restartErr = engine.restart()
        assertNull("Restart must succeed", restartErr)
        assertTrue(engine.state.value.isActive())

        engine.stop()
        assertEquals(VMState.STOPPED, engine.state.value)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testL12_LinuxFailedBootToStartAgain() {
        // First attempt with missing kernel
        val badConfig = VMConfig(
            name = "TestL12_FailedBoot",
            guestOsType = "Linux ARM64",
            kernelImagePath = "/invalid/path",
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine1 = VMEngine(context, badConfig)
        val err1 = engine1.start()
        assertNotNull(err1)
        assertNull(engine1.getActiveInstance())

        // Second attempt with valid configuration
        val goodConfig = VMConfig(
            name = "TestL12_ValidBoot",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine2 = VMEngine(context, goodConfig)
        val err2 = engine2.start()
        assertNull("Fresh start after failed VM must cleanly succeed", err2)
        assertTrue(engine2.state.value.isActive())
        engine2.stop()
    }

    // ==========================================
    // PART F — WINDOWS TEST MATRIX
    // ==========================================

    @Test
    fun testW1_ValidWindowsARM64Start() {
        val config = VMConfig(
            name = "TestW1_Windows",
            guestOsType = "Windows 11 ARM64",
            isoPath = validWindowsIsoFile.absolutePath,
            diskImagePath = validWindowsDiskFile.absolutePath,
            ramSizeMb = 2048,
            cpuCores = 4
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNull("W1: Valid Windows VM should start cleanly", err)
        assertTrue(engine.state.value.isActive())
        engine.stop()
        assertEquals(VMState.STOPPED, engine.state.value)
    }

    @Test
    fun testW2_WindowsImageMissing() {
        val config = VMConfig(
            name = "TestW2_MissingWinImage",
            guestOsType = "Windows 11 ARM64",
            isoPath = "",
            diskImagePath = "",
            ramSizeMb = 2048,
            cpuCores = 2
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNotNull("W2: Missing Windows media must return structured error", err)
        assertEquals(VMErrorCategory.WINDOWS_IMAGE_NOT_CONFIGURED, err?.category)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testW3_WindowsImageUnreadable() {
        val nonExistent = File(context.filesDir, "guest_os/windows_arm64/non_existent_win11.iso")
        val config = VMConfig(
            name = "TestW3_UnreadableWinImage",
            guestOsType = "Windows 11 ARM64",
            isoPath = nonExistent.absolutePath,
            diskImagePath = validWindowsDiskFile.absolutePath,
            ramSizeMb = 2048,
            cpuCores = 2
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNotNull("W3: Unreadable Windows ISO must fail", err)
        assertEquals(VMErrorCategory.WINDOWS_IMAGE_NOT_FOUND, err?.category)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testW4_InvalidWindowsImage() {
        val invalidIso = File(context.filesDir, "guest_os/windows_arm64/corrupt_win.iso").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(100)) // Too small
        }
        val config = VMConfig(
            name = "TestW4_InvalidWinImage",
            guestOsType = "Windows 11 ARM64",
            isoPath = invalidIso.absolutePath,
            diskImagePath = validWindowsDiskFile.absolutePath,
            ramSizeMb = 2048,
            cpuCores = 2
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNotNull("W4: Corrupt Windows media must fail validation", err)
        assertEquals(VMErrorCategory.WINDOWS_IMAGE_INVALID, err?.category)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testW7_WindowsStartStopCompleteCleanup() {
        val config = VMConfig(
            name = "TestW7_WinStartStop",
            guestOsType = "Windows 11 ARM64",
            isoPath = validWindowsIsoFile.absolutePath,
            diskImagePath = validWindowsDiskFile.absolutePath,
            ramSizeMb = 2048,
            cpuCores = 4
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNull(err)
        assertTrue(engine.state.value.isActive())

        val stopErr = engine.stop()
        assertNull(stopErr)
        assertEquals(VMState.STOPPED, engine.state.value)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testW8_WindowsStartRestartFreshResources() {
        val config = VMConfig(
            name = "TestW8_WinRestart",
            guestOsType = "Windows 11 ARM64",
            isoPath = validWindowsIsoFile.absolutePath,
            diskImagePath = validWindowsDiskFile.absolutePath,
            ramSizeMb = 2048,
            cpuCores = 4
        )
        val engine = VMEngine(context, config)
        engine.start()
        assertTrue(engine.state.value.isActive())

        val restartErr = engine.restart()
        assertNull("Windows restart must execute cleanly", restartErr)
        assertTrue(engine.state.value.isActive())

        engine.stop()
        assertEquals(VMState.STOPPED, engine.state.value)
    }

    // ==========================================
    // PART H — STRESS TESTS (10x Cycles)
    // ==========================================

    @Test
    fun testStress10xStartStopCycles() {
        val config = VMConfig(
            name = "StressStartStop",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 512,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)

        for (i in 1..10) {
            val startErr = engine.start()
            assertNull("Iteration $i: start must succeed", startErr)
            assertTrue("Iteration $i: state must be active", engine.state.value.isActive())

            val stopErr = engine.stop()
            assertNull("Iteration $i: stop must succeed", stopErr)
            assertEquals("Iteration $i: state must be STOPPED", VMState.STOPPED, engine.state.value)
            assertNull("Iteration $i: active instance must be cleared", engine.getActiveInstance())
        }
    }

    @Test
    fun testStress10xStartRestartCycles() {
        val config = VMConfig(
            name = "StressStartRestart",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 512,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        engine.start()

        for (i in 1..10) {
            val restartErr = engine.restart()
            assertNull("Iteration $i: restart must succeed", restartErr)
            assertTrue("Iteration $i: state must be active", engine.state.value.isActive())
        }
        engine.stop()
        assertEquals(VMState.STOPPED, engine.state.value)
    }

    @Test
    fun testStress10xFailedStartRecovery() {
        val badConfig = VMConfig(
            name = "StressFailedStart",
            guestOsType = "Linux ARM64",
            kernelImagePath = "/invalid/image/file",
            ramSizeMb = 512,
            cpuCores = 1
        )
        val goodConfig = VMConfig(
            name = "StressGoodStart",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 512,
            cpuCores = 1
        )

        for (i in 1..10) {
            val badEngine = VMEngine(context, badConfig)
            val err1 = badEngine.start()
            assertNotNull("Iteration $i: bad config must fail", err1)
            assertNull(badEngine.getActiveInstance())

            val goodEngine = VMEngine(context, goodConfig)
            val err2 = goodEngine.start()
            assertNull("Iteration $i: good config must start after failure", err2)
            goodEngine.stop()
            assertEquals(VMState.STOPPED, goodEngine.state.value)
        }
    }

    // ==========================================
    // PART J — STORAGE & PERSISTENCE
    // ==========================================

    @Test
    fun testStorageMediaPreservationAcrossAllOperations() {
        val config = VMConfig(
            name = "PreservationVM",
            guestOsType = "Windows 11 ARM64",
            isoPath = validWindowsIsoFile.absolutePath,
            diskImagePath = validWindowsDiskFile.absolutePath,
            ramSizeMb = 2048,
            cpuCores = 2
        )
        val engine = VMEngine(context, config)

        engine.start()
        engine.restart()
        engine.reset()
        engine.stop()
        engine.destroy()

        // Critical requirement: Imported VM images must never be deleted
        assertTrue("Windows ISO must remain intact", validWindowsIsoFile.exists() && validWindowsIsoFile.length() > 0)
        assertTrue("Windows Disk must remain intact", validWindowsDiskFile.exists() && validWindowsDiskFile.length() > 0)
        assertTrue("Linux Kernel must remain intact", validKernelFile.exists() && validKernelFile.length() > 0)
        assertTrue("Linux Rootfs must remain intact", validDiskFile.exists() && validDiskFile.length() > 0)
    }

    private fun createTestCpio(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        for ((index, entry) in entries.withIndex()) {
            val nameBytes = (entry.first + "\u0000").toByteArray(Charsets.US_ASCII)
            val nameSize = nameBytes.size
            val fileSize = entry.second.size
            val header = String.format(
                "070701%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X",
                index + 1, 0x81ED, 0, 0, 1, System.currentTimeMillis() / 1000,
                fileSize, 3, 1, 0, 0, nameSize, 0
            ).toByteArray(Charsets.US_ASCII)
            bos.write(header)
            bos.write(nameBytes)
            val namePad = (4 - ((110 + nameSize) % 4)) % 4
            for (p in 0 until namePad) bos.write(0)
            bos.write(entry.second)
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
