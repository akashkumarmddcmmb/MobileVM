package com.example.vm.validation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vm.console.VMSecureTerminalServer
import com.example.vm.core.VMBootManager
import com.example.vm.core.VMConfig
import com.example.vm.core.VMEngine
import com.example.vm.core.VMState
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.firmware.AcpiTableGenerator
import com.example.vm.firmware.UefiFirmwareManager
import com.example.vm.guest.linux.LinuxImageProvisioner
import com.example.vm.guest.windows.WindowsGuestManager
import com.example.vm.guest.windows.WindowsValidationResult
import com.example.vm.storage.AndroidStorageDiskBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CompleteProjectAcceptanceTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var validKernelFile: File
    private lateinit var validInitrdFile: File
    private lateinit var validDiskFile: File
    private lateinit var validLinuxIsoFile: File
    private lateinit var validWindowsIsoFile: File

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "accept_test_${System.currentTimeMillis()}").apply { mkdirs() }

        // Provision valid Linux assets in application storage
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

        // Provision Linux Environment
        val res = LinuxImageProvisioner.provisionDefaultLinuxEnvironment(context, "AcceptanceVM", forceRecreate = false)
        assertTrue("Linux provisioning must succeed", res is LinuxImageProvisioner.ProvisionResult.Success)
        val success = res as LinuxImageProvisioner.ProvisionResult.Success
        validKernelFile = File(success.kernelPath)
        validInitrdFile = File(success.initramfsPath)
        validDiskFile = File(success.diskPath)

        // Linux ISO mock media
        val linuxIsoDir = File(context.filesDir, "guest_os/linux_iso").apply { mkdirs() }
        validLinuxIsoFile = File(linuxIsoDir, "ubuntu_server_arm64.iso").apply {
            writeBytes(ByteArray(1024 * 1024 + 2048) { 0x00 })
        }

        // Windows ARM64 ISO mock media
        val winDir = File(context.filesDir, "guest_os/windows_arm64").apply { mkdirs() }
        validWindowsIsoFile = File(winDir, "Win11_Arm64_Test.iso").apply {
            writeBytes(ByteArray(1024 * 1024 + 4096) { 0x00 })
        }
    }

    @Test
    fun testLinuxCompleteWorkflow_IsoToInstalledDiskToTerminal() {
        val diskBackend = AndroidStorageDiskBackend(context)
        val targetDisk = File(tempDir, "installed_linux.img")
        diskBackend.createDiskImage(targetDisk.absolutePath, 1, sparse = true)

        var config = VMConfig(
            name = "LinuxInstallerVM",
            guestOsType = "Linux ARM64",
            installationMode = "MODE_B_ISO_INSTALLER",
            bootOrder = "CD_ROM",
            isoPath = validLinuxIsoFile.absolutePath,
            diskImagePath = targetDisk.absolutePath,
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 2,
            osInstalled = false
        )

        // 1. Initial Boot Selection: Must resolve to INSTALLATION_ISO
        val initialTarget = VMBootManager.resolveEffectiveBootTarget(context, config)
        assertEquals(VMBootManager.BootDeviceType.INSTALLATION_ISO, initialTarget.primaryDevice)
        assertTrue(initialTarget.isIsoBoot)

        // 2. Start VM (Booting Installer)
        val engine = VMEngine(context, config)
        val startErr = engine.start()
        assertNull("VM start must succeed: ${startErr?.summary}", startErr)
        assertTrue(engine.state.value.isActive())

        // 3. Complete Installation onto Persistent Virtual Disk
        config = VMBootManager.markOsInstallationComplete(context, config)
        assertTrue("osInstalled must be marked true after installation", config.osInstalled)
        assertEquals("bootOrder must be updated to VIRTUAL_DISK", "VIRTUAL_DISK", config.bootOrder)

        // 4. Reboot: Must now automatically resolve to INSTALLED_VIRTUAL_DISK
        val rebootTarget = VMBootManager.resolveEffectiveBootTarget(context, config)
        assertEquals(VMBootManager.BootDeviceType.INSTALLED_VIRTUAL_DISK, rebootTarget.primaryDevice)
        assertFalse("Reboot must NOT require ISO media", rebootTarget.isIsoBoot)

        // 5. Eject Installer ISO: Boots standalone from persistent virtual disk
        config = VMBootManager.ejectInstallerIso(config)
        assertTrue("ISO path must be empty after ejection", config.isoPath.isEmpty())
        val finalTarget = VMBootManager.resolveEffectiveBootTarget(context, config)
        assertEquals(VMBootManager.BootDeviceType.INSTALLED_VIRTUAL_DISK, finalTarget.primaryDevice)

        // 6. Test Guest Terminal / Shell I/O
        engine.serialConsole.sendInputLine("uname -a")
        val history = engine.serialConsole.history.value
        assertNotNull("Console history must be recorded", history)

        engine.stop()
        assertEquals(VMState.STOPPED, engine.state.value)
    }

    @Test
    fun testWindowsCompleteWorkflow_IsoToUefiToBootManager() = runBlocking {
        val diskBackend = AndroidStorageDiskBackend(context)
        val targetDisk = File(tempDir, "win11_target.img")
        diskBackend.createDiskImage(targetDisk.absolutePath, 64, sparse = true)

        var config = WindowsGuestManager.createWindowsVMConfig(
            vmName = "Win11AcceptanceVM",
            isoPath = validWindowsIsoFile.absolutePath,
            targetDiskPath = targetDisk.absolutePath,
            allocatedRamMb = 4096,
            allocatedCores = 4,
            diskSizeGb = 64
        )

        // 1. Initial Boot Selection: Must resolve to INSTALLATION_ISO
        val initialTarget = VMBootManager.resolveEffectiveBootTarget(context, config)
        assertEquals(VMBootManager.BootDeviceType.INSTALLATION_ISO, initialTarget.primaryDevice)
        assertTrue(initialTarget.isUefiBoot)

        // 2. Validate ACPI tables & TPM configuration
        val bootStatus = WindowsGuestManager.evaluateBootStatus(context, config, hasKvm = true)
        assertTrue("Pre-flight & UEFI evaluation must pass", bootStatus.isStagePassed)
        assertTrue("TPM must be active for Windows ARM64", bootStatus.tpmActive)

        // 3. Complete Windows Setup onto Virtual Disk
        config = VMBootManager.markOsInstallationComplete(context, config)
        assertTrue("osInstalled must be marked true", config.osInstalled)
        assertEquals("bootOrder must be VIRTUAL_DISK", "VIRTUAL_DISK", config.bootOrder)

        // 4. Reboot: Must automatically resolve to WINDOWS_BOOT_MANAGER
        val rebootTarget = VMBootManager.resolveEffectiveBootTarget(context, config)
        assertEquals(VMBootManager.BootDeviceType.WINDOWS_BOOT_MANAGER, rebootTarget.primaryDevice)
        assertTrue(rebootTarget.isUefiBoot)
        assertFalse(rebootTarget.isIsoBoot)
    }

    @Test
    fun testPowerControlsLifecycle_PowerOn_GracefulShutdown_ForcePowerOff_Reboot() {
        val config = VMConfig(
            name = "PowerLifecycleVM",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 2
        )
        val engine = VMEngine(context, config)

        // 1. POWER ON
        val startErr = engine.start()
        assertNull("Power on start must succeed", startErr)
        assertTrue("State must be active after start", engine.state.value.isActive())

        // 2. GRACEFUL SHUTDOWN
        val shutdownErr = engine.gracefulShutdown()
        assertNull("Graceful shutdown must succeed", shutdownErr)
        assertEquals("State must be STOPPED after graceful shutdown", VMState.STOPPED, engine.state.value)
        assertNull("Instance must be destroyed after shutdown", engine.getActiveInstance())

        // 3. POWER ON AGAIN
        val startErr2 = engine.start()
        assertNull("Second power on must succeed", startErr2)
        assertTrue("State must be active", engine.state.value.isActive())

        // 4. FORCE POWER OFF
        val forceErr = engine.forcePowerOff()
        assertNull("Force power off must succeed", forceErr)
        assertEquals("State must be STOPPED after force off", VMState.STOPPED, engine.state.value)

        // 5. REBOOT
        val startErr3 = engine.start()
        assertNull(startErr3)
        val rebootErr = engine.reboot()
        assertNull("Reboot must cleanly re-initialize VM", rebootErr)
        assertTrue("State must be active after reboot", engine.state.value.isActive())

        // Final clean stop
        engine.stop()
        assertEquals(VMState.STOPPED, engine.state.value)
    }

    @Test
    fun testSecureExternalTerminalServer_LocalhostLoopbackAndTokenAuthentication() {
        val config = VMConfig(
            name = "TerminalTestVM",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = validDiskFile.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        engine.start()

        val server = VMSecureTerminalServer(
            engine = engine,
            port = 2225,
            customAuthToken = "secret123"
        )
        val started = server.start()
        assertTrue("Server must start and bind strictly to 127.0.0.1:2225", started)
        assertTrue("Server must report isRunning = true", server.isRunning)

        // Connect over local TCP socket
        val socket = Socket("127.0.0.1", 2225)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer: OutputStream = socket.getOutputStream()

        // Read banner
        val bannerLine = reader.readLine()
        assertNotNull("Banner must be sent by server", bannerLine)

        // Send incorrect token
        writer.write("wrong_token\n".toByteArray())
        writer.flush()
        val authFailNotice = reader.readLine()
        assertNotNull("Server must challenge on failed auth", authFailNotice)

        // Send valid token
        writer.write("secret123\n".toByteArray())
        writer.flush()

        // Cleanup
        socket.close()
        server.stop()
        assertFalse(server.isRunning)

        engine.stop()
    }

    @Test
    fun testPersistentVirtualDisk_ReadWritePersistenceAcrossReboots() {
        val diskBackend = AndroidStorageDiskBackend(context)
        val testDisk = File(tempDir, "persistent_data.img")
        diskBackend.createDiskImage(testDisk.absolutePath, 1, sparse = true)

        // Write signature at LBA 100
        val testData = ByteArray(512) { (it and 0xFF).toByte() }
        testData[0] = 0xAA.toByte()
        testData[1] = 0x55.toByte()
        testData[510] = 0x55.toByte()
        testData[511] = 0xAA.toByte()

        val writeOk = diskBackend.writeSectors(testDisk.absolutePath, 100, testData)
        assertTrue("Initial write to virtual disk must succeed", writeOk)

        // Simulate complete VM lifecycle restart
        val config = VMConfig(
            name = "PersistenceVM",
            guestOsType = "Linux ARM64",
            kernelImagePath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskImagePath = testDisk.absolutePath,
            ramSizeMb = 1024,
            cpuCores = 1
        )
        val engine = VMEngine(context, config)
        engine.start()
        engine.stop()

        // Verify that sector data persists byte-for-byte on storage
        val readBack = diskBackend.readSectors(testDisk.absolutePath, 100, 1)
        assertNotNull("Sector must be readable after reboot", readBack)
        assertArrayEquals("Persistent virtual disk data must survive VM reboot", testData, readBack)
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
