package com.example.vm.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vm.core.VMConfig
import com.example.vm.core.VMEngine
import com.example.vm.core.VMErrorCategory
import com.example.vm.core.VMState
import com.example.vm.guest.linux.LinuxImageProvisioner
import com.example.vm.guest.windows.WindowsGuestManager
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
class VMLifecycleAndResourceManagementTest {

    private lateinit var context: Context
    private lateinit var validKernelFile: File
    private lateinit var validInitrdFile: File
    private lateinit var validDiskFile: File
    private lateinit var validWindowsIsoFile: File
    private lateinit var validWindowsDiskFile: File

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()

        // Provision valid Linux assets in application storage
        val res = LinuxImageProvisioner.provisionDefaultLinuxEnvironment(context, "LifecycleTestVM", forceRecreate = true)
        assertTrue("Provisioning must succeed", res is LinuxImageProvisioner.ProvisionResult.Success)
        val success = res as LinuxImageProvisioner.ProvisionResult.Success

        validKernelFile = File(success.kernelPath)
        validInitrdFile = File(success.initramfsPath)
        validDiskFile = File(success.diskPath)

        // Windows valid test media in authorized storage
        val winDir = File(context.filesDir, "guest_os/windows_arm64").apply { mkdirs() }
        validWindowsIsoFile = File(winDir, "win11_lifecycle.iso").apply {
            writeBytes(ByteArray(1024 * 1024 + 512)) // > 1MB
        }
        val appDisksDir = File(context.filesDir, "app_disks").apply { mkdirs() }
        validWindowsDiskFile = File(appDisksDir, "win11_lifecycle_disk.img").apply {
            val backend = com.example.vm.storage.AndroidStorageDiskBackend(context)
            backend.createDiskImage(absolutePath, 64, sparse = true)
        }
    }

    @Test
    fun testStartToStopLifecycle() {
        val config = VMConfig(
            name = "LinuxVM",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 2
        )
        val engine = VMEngine(context, config)
        assertEquals(VMState.CREATED, engine.state.value)

        // START
        val startError = engine.start()
        assertNull("VM start should succeed, but got: ${startError?.summary}", startError)
        assertTrue(engine.state.value == VMState.RUNNING || engine.state.value == VMState.STARTING)

        // STOP
        val stopMsg = engine.stop()
        assertNull("VM stop should succeed", stopMsg)
        assertEquals(VMState.STOPPED, engine.state.value)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testStartToRestartLifecycle() {
        val config = VMConfig(
            name = "RestartVM",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        val startError = engine.start()
        assertNull("Start error: ${startError?.summary}", startError)
        assertTrue(engine.state.value.isActive())

        // RESTART: Stop -> complete cleanup -> validate -> start
        val restartError = engine.restart()
        assertNull("Restart should cleanly succeed without error", restartError)
        assertTrue(engine.state.value.isActive())

        engine.stop()
        assertEquals(VMState.STOPPED, engine.state.value)
    }

    @Test
    fun testStartToResetLifecycle() {
        val config = VMConfig(
            name = "ResetVM",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        val startErr = engine.start()
        assertNull(startErr)
        assertTrue(engine.state.value.isActive())

        // RESET: Halts, resets CPU/memory, re-runs bootloader
        val resetError = engine.reset()
        assertNull("Reset should succeed without error", resetError)
        assertTrue(engine.state.value.isActive())

        engine.stop()
        assertEquals(VMState.STOPPED, engine.state.value)
    }

    @Test
    fun testDoubleStartRejection() {
        val config = VMConfig(
            name = "DoubleStartVM",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        val err1 = engine.start()
        assertNull(err1)

        // Attempting to call start again while running
        val err2 = engine.start()
        assertNotNull("Double start should be rejected with an error", err2)
        assertEquals(VMErrorCategory.LIFECYCLE_ERROR, err2?.category)

        engine.stop()
    }

    @Test
    fun testFailedKernelLoadCleanup() {
        val config = VMConfig(
            name = "MissingKernelVM",
            guestOsType = "Linux ARM64",
            kernelImagePath = "/non/existent/path/Image",
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNotNull("Start with invalid kernel must fail", err)
        assertEquals(VMErrorCategory.KERNEL_MISSING, err?.category)
        // Ensure no active dangling instance remains
        assertNull(engine.getActiveInstance())
        assertTrue(engine.state.value.isTerminal())
    }

    @Test
    fun testFailedInitramfsLoadCleanup() {
        val config = VMConfig(
            name = "MissingInitrdVM",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = "${context.filesDir}/guest_os/missing_initrd.cpio",
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNotNull("Start with missing initramfs must fail", err)
        assertEquals(VMErrorCategory.INITRAMFS_MISSING, err?.category)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testFailedWindowsDiskValidationCleanup() {
        val config = VMConfig(
            name = "MissingWinDiskVM",
            guestOsType = "Windows 11 ARM64",
            diskImagePath = "${context.filesDir}/app_disks/nonexistent_win11.vhdx",
            ramSizeMb = 2048,
            cpuCores = 4
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNotNull("Start with missing Windows disk must return structured error", err)
        assertTrue(err?.category == VMErrorCategory.WINDOWS_IMAGE_NOT_FOUND || err?.category == VMErrorCategory.WINDOWS_IMAGE_INVALID)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testExcessiveMemoryAllocationFailureCleanup() {
        val config = VMConfig(
            name = "HugeRamVM",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            ramSizeMb = 524288, // 512 GB (impossible on host)
            cpuCores = 2
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNotNull("Excessive RAM request must fail pre-flight validation", err)
        assertEquals(VMErrorCategory.INSUFFICIENT_RAM, err?.category)
        assertNull(engine.getActiveInstance())
    }

    @Test
    fun testDiskFilePreservationDuringLifecycle() {
        val config = VMConfig(
            name = "PersistentMediaVM",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        engine.start()
        engine.restart()
        engine.reset()
        engine.stop()
        engine.destroy()

        // Critical Check: files on disk must NEVER be deleted during VM lifecycle operations!
        assertTrue("Kernel image must remain intact", validKernelFile.exists())
        assertTrue("Initramfs image must remain intact", validInitrdFile.exists())
        assertTrue("Virtual disk must remain intact", validDiskFile.exists())
    }

    @Test
    fun testWindowsGuestEndToEndLifecycle() {
        val config = VMConfig(
            name = "Win11ARM64",
            guestOsType = "Windows 11 ARM64",
            isoPath = validWindowsIsoFile.absolutePath,
            diskImagePath = validWindowsDiskFile.absolutePath,
            ramSizeMb = 2048,
            cpuCores = 4
        )
        val engine = VMEngine(context, config)
        val err = engine.start()
        assertNull("Windows VM start with valid media should succeed, but got: ${err?.summary}", err)
        assertTrue(engine.state.value.isActive())

        val stopErr = engine.stop()
        assertNull("Windows VM stop should succeed", stopErr)
        assertEquals(VMState.STOPPED, engine.state.value)
        assertTrue("Windows ISO file must remain preserved", validWindowsIsoFile.exists())
        assertTrue("Windows disk file must remain preserved", validWindowsDiskFile.exists())
    }
}
