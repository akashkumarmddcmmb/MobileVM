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
}
