package com.example.vm.guest.windows

import androidx.test.core.app.ApplicationProvider
import com.example.vm.cpu.HostArchitecture
import com.example.vm.security.VirtualTpm
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VMWindowsGuestTest {

    @Test
    fun testWindowsProfileRequirements() {
        val profile = WindowsGuestManager.getProfile()
        assertEquals("Windows 11 ARM64", profile.edition)
        assertTrue(profile.minimumRamMb >= 4096)
        assertTrue(profile.minimumDiskGb >= 64)
        assertTrue(profile.minimumCores >= 2)
        assertEquals("\\EFI\\Boot\\bootaa64.efi", profile.bootPathEfi)
        assertEquals("\\EFI\\Microsoft\\Boot\\bootmgfw.efi", profile.windowsBootMgrPath)
    }

    @Test
    fun testWindowsVMConfigGeneration() {
        val config = WindowsGuestManager.createWindowsVMConfig(
            vmName = "Win11_Test_VM",
            isoPath = "/storage/win11_arm64.iso",
            targetDiskPath = "/storage/win11_disk.img",
            allocatedRamMb = 4096,
            allocatedCores = 4,
            diskSizeGb = 64
        )

        assertEquals("Windows ARM64", config.guestOsType)
        assertEquals("Windows 11 on ARM", config.osVersion)
        assertEquals("MODE_B_ISO_INSTALLER", config.installationMode)
        assertEquals("CD_ROM", config.bootOrder)
        assertEquals(4096, config.ramSizeMb)
        assertEquals(4, config.cpuCores)
        assertEquals(64, config.diskSizeGb)
        assertTrue(config.networkEnabled)
        assertEquals("NAT", config.networkMode)
    }

    @Test
    fun testHostSuitabilityChecks() {
        // Test ARM64 host with sufficient RAM
        val (okArm64, _) = WindowsGuestManager.checkHostSuitability(HostArchitecture.ARM64, 8192)
        assertTrue(okArm64)

        // Test non-ARM64 host
        val (okX86, msgX86) = WindowsGuestManager.checkHostSuitability(HostArchitecture.X86_64, 8192)
        assertFalse(okX86)
        assertTrue(msgX86.contains("ARM64"))

        // Test insufficient RAM
        val (okLowRam, msgLowRam) = WindowsGuestManager.checkHostSuitability(HostArchitecture.ARM64, 2048)
        assertFalse(okLowRam)
        assertTrue(msgLowRam.contains("RAM"))
    }

    @Test
    fun testVirtualTpmCrbInterface() {
        val tpm = VirtualTpm()
        assertTrue(tpm.isTpmMmio(0x0FED0000L))
        assertTrue(tpm.isTpmMmio(0x0FED0010L))
        assertFalse(tpm.isTpmMmio(0x0FED2000L))

        // Read TPM Interface ID
        val ifaceId = tpm.read32(0x0000L)
        assertEquals(0x00010000, ifaceId) // CRB active

        // Request Locality 0
        tpm.write32(0x0008L, 1)
        val locSts = tpm.read32(0x000CL)
        assertEquals(0x01, locSts)

        // Write and execute TPM command
        tpm.write32(0x001CL, 1) // Start
        val ctrlSts = tpm.read32(0x0014L)
        assertEquals(0, ctrlSts) // Idle/completed
    }

    @Test
    fun testWindowsBootStageEvaluation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = WindowsGuestManager.createWindowsVMConfig(
            vmName = "Win11_Test",
            isoPath = "/storage/win11.iso",
            targetDiskPath = "/storage/win11.img",
            allocatedRamMb = 4096,
            allocatedCores = 4,
            diskSizeGb = 64
        )

        // Test with KVM available
        val statusKvm = WindowsGuestManager.evaluateBootStatus(context, config, hasKvm = true)
        assertTrue(statusKvm.isStagePassed)
        assertEquals(WindowsBootStage.WINDOWS_BOOT_MANAGER, statusKvm.currentStage)
        assertTrue(statusKvm.isHardwareAccelerated)
        assertTrue(statusKvm.tpmActive)

        // Test with Software Emulation
        val statusEmul = WindowsGuestManager.evaluateBootStatus(context, config, hasKvm = false)
        assertTrue(statusEmul.isStagePassed)
        assertEquals(WindowsBootStage.WINDOWS_BOOT_MANAGER, statusEmul.currentStage)
        assertFalse(statusEmul.isHardwareAccelerated)
    }

    @Test
    fun testWindowsValidationRejectsMissingImage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = com.example.vm.core.VMConfig(
            id = 201L,
            name = "WinNoImage",
            guestOsType = "Windows ARM64",
            ramSizeMb = 2048,
            isoPath = "",
            diskImagePath = ""
        )

        val validation = com.example.vm.core.VMStartValidator.validate(context, config)
        assertTrue("Must be invalid when no Windows ISO or disk configured", validation is com.example.vm.core.VMStartValidator.ValidationResult.Invalid)
        val err = (validation as com.example.vm.core.VMStartValidator.ValidationResult.Invalid).error
        assertEquals(com.example.vm.core.VMErrorCategory.WINDOWS_IMAGE_NOT_CONFIGURED, err.category)
        assertTrue(err.summary.contains("WINDOWS_IMAGE_NOT_CONFIGURED") || err.technicalDetails.contains("Windows"))
    }

    @Test
    fun testWindowsValidationRejectsNotFoundImage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = com.example.vm.core.VMConfig(
            id = 202L,
            name = "WinNotFound",
            guestOsType = "Windows ARM64",
            ramSizeMb = 2048,
            isoPath = "/storage/emulated/0/nonexistent_windows_arm64.iso",
            diskImagePath = ""
        )

        val validation = com.example.vm.core.VMStartValidator.validate(context, config)
        assertTrue("Must be invalid when Windows image does not exist", validation is com.example.vm.core.VMStartValidator.ValidationResult.Invalid)
        val err = (validation as com.example.vm.core.VMStartValidator.ValidationResult.Invalid).error
        assertTrue(err.category == com.example.vm.core.VMErrorCategory.WINDOWS_IMAGE_INVALID || err.category == com.example.vm.core.VMErrorCategory.WINDOWS_IMAGE_NOT_FOUND)
    }

    @Test
    fun testWindowsValidationRejectsInvalidSmallIso() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mediaDir = java.io.File(context.filesDir, "guest_os/windows_arm64").apply { mkdirs() }
        val tinyIso = java.io.File(mediaDir, "tiny_win.iso").apply {
            writeBytes(ByteArray(100)) // < 1MB
        }

        val config = com.example.vm.core.VMConfig(
            id = 202L,
            name = "WinTinyIso",
            guestOsType = "Windows ARM64",
            ramSizeMb = 2048,
            isoPath = tinyIso.absolutePath
        )

        val validation = com.example.vm.core.VMStartValidator.validate(context, config)
        assertTrue("Must reject undersized ISO", validation is com.example.vm.core.VMStartValidator.ValidationResult.Invalid)
        val err = (validation as com.example.vm.core.VMStartValidator.ValidationResult.Invalid).error
        assertEquals(com.example.vm.core.VMErrorCategory.WINDOWS_IMAGE_INVALID, err.category)
        assertTrue(err.technicalDetails.contains("too small") || err.summary.contains("INVALID"))
    }

    @Test
    fun testWindowsValidationRejectsLowRam() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = com.example.vm.core.VMConfig(
            id = 203L,
            name = "WinLowRam",
            guestOsType = "Windows ARM64",
            ramSizeMb = 1024 // < 2048 MB required for Windows on ARM
        )

        val validation = com.example.vm.core.VMStartValidator.validate(context, config)
        assertTrue("Must reject Windows VM with < 2048 MB RAM", validation is com.example.vm.core.VMStartValidator.ValidationResult.Invalid)
        val err = (validation as com.example.vm.core.VMStartValidator.ValidationResult.Invalid).error
        assertEquals(com.example.vm.core.VMErrorCategory.INSUFFICIENT_RAM, err.category)
    }

    @Test
    fun testArm64AcpiTableGeneration() {
        val payload = com.example.vm.firmware.AcpiTableGenerator.generateArm64AcpiTables(
            baseAddress = 0x47000000L,
            numCores = 4,
            ramBase = 0x40000000L,
            ramSizeMb = 4096
        )
        assertNotNull(payload)
        assertEquals(0x47000000L, payload.rsdpAddress)
        assertEquals(0x47000040L, payload.xsdtAddress)
        assertEquals(0x47000100L, payload.madtAddress)
        assertEquals(0x47000200L, payload.fadtAddress)
        assertEquals(0x47000400L, payload.gtdtAddress)
        assertEquals(0x47000600L, payload.dsdtAddress)
        assertTrue(payload.tableBytes.size >= 65536)
    }

    @Test
    fun testWindowsMediaImportCalculatesMetadata() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val rawData = "Windows ARM64 ISO Payload Mock Data for Import Test".toByteArray(Charsets.UTF_8)
        val tempSource = java.io.File(context.cacheDir, "win_source.iso").apply { writeBytes(rawData) }

        val metadata = WindowsGuestManager.importWindowsBootMedia(
            context = context,
            sourceFile = tempSource,
            destFilename = "win11_arm64_imported.iso"
        )

        assertNotNull(metadata)
        assertEquals("win11_arm64_imported.iso", metadata.fileName)
        assertEquals(rawData.size.toLong(), metadata.sizeBytes)
        assertTrue(java.io.File(metadata.absolutePath).exists())
        assertEquals(64, metadata.sha256Hex.length)
    }

    @Test
    fun testWindowsVMLifecycleStartStopRestartReset() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mediaDir = java.io.File(context.filesDir, "guest_os/windows_arm64").apply { mkdirs() }
        val dummyIso = java.io.File(mediaDir, "win11_arm64.iso").apply {
            writeBytes(ByteArray(1024 * 1024 + 512)) // > 1MB
        }
        val disksDir = java.io.File(context.filesDir, "app_disks").apply { mkdirs() }
        val diskFile = java.io.File(disksDir, "win11_sys_disk.img").apply {
            val backend = com.example.vm.storage.AndroidStorageDiskBackend(context)
            backend.createDiskImage(absolutePath, 64, sparse = true)
        }

        val config = com.example.vm.core.VMConfig(
            id = 204L,
            name = "WinLifecycleTest",
            guestOsType = "Windows ARM64",
            ramSizeMb = 2048,
            isoPath = dummyIso.absolutePath,
            diskImagePath = diskFile.absolutePath,
            cpuCores = 4
        )

        // 1. Validation check
        val validation = com.example.vm.core.VMStartValidator.validate(context, config)
        assertTrue("Valid Windows configuration must pass pre-flight validation", validation is com.example.vm.core.VMStartValidator.ValidationResult.Valid)

        val engine = com.example.vm.core.VMEngine(context, config)

        // 2. Start
        val startErr = engine.start()
        assertNull(startErr)
        val s = engine.state.value
        assertTrue("State should be active or completed cleanly on simulated JVM", 
            s == com.example.vm.core.VMState.RUNNING || 
            s == com.example.vm.core.VMState.STARTING || 
            s == com.example.vm.core.VMState.STOPPED
        )

        // 3. Pause
        val pauseErr = engine.pause()
        assertNull(pauseErr)
        assertEquals(com.example.vm.core.VMState.PAUSED, engine.state.value)

        // 4. Resume
        val resumeErr = engine.resume()
        assertNull(resumeErr)
        assertTrue(engine.state.value == com.example.vm.core.VMState.RUNNING || engine.state.value == com.example.vm.core.VMState.STARTING)

        // 5. Restart
        val restartErr = engine.restart()
        assertNull(restartErr)
        assertTrue(engine.state.value == com.example.vm.core.VMState.RUNNING || engine.state.value == com.example.vm.core.VMState.STARTING)

        // 6. Stop
        val stopErr = engine.stop()
        assertNull(stopErr)
        assertEquals(com.example.vm.core.VMState.STOPPED, engine.state.value)

        // 7. Destroy
        engine.destroy()
        assertEquals(com.example.vm.core.VMState.STOPPED, engine.state.value)
    }
}
