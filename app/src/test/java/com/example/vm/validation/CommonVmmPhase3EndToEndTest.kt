package com.example.vm.validation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vm.core.VMConfig
import com.example.vm.core.VMErrorCategory
import com.example.vm.core.VMMachineModel
import com.example.vm.core.VMStartValidator
import com.example.vm.core.VMState
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.cpu.HostArchitecture
import com.example.vm.firmware.AcpiTableGenerator
import com.example.vm.firmware.UefiFirmwareManager
import com.example.vm.guest.iso.ISOManager
import com.example.vm.guest.windows.WindowsGuestManager
import com.example.vm.monitor.DeviceSupportStatus
import com.example.vm.monitor.VMDeviceCapabilityReport
import com.example.vm.runtime.VMRuntimeManager
import com.example.vm.security.VirtualTpm
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CommonVmmPhase3EndToEndTest {

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "phase3_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @Test
    fun testAuthoritativeMachineModel_NoOverlapBetweenRamAndMmio() {
        assertTrue("RAM base must be 1GB boundary (0x40000000)", VMMachineModel.RAM_BASE == 0x40000000L)
        assertTrue("UART must be at 0x09000000", VMMachineModel.UART_BASE == 0x09000000L)
        assertTrue("GIC Dist must be at 0x08000000", VMMachineModel.GIC_DIST_BASE == 0x08000000L)
        assertTrue("VirtIO Block must be at 0x0A000000", VMMachineModel.VIRTIO_BLK_BASE == 0x0A000000L)
        assertTrue("VirtIO CD-ROM must be at 0x0A000200", VMMachineModel.VIRTIO_CDROM_BASE == 0x0A000200L)
        assertTrue("TPM 2.0 must be at 0x0FED0000", VMMachineModel.TPM_CRB_BASE == 0x0FED0000L)
        assertTrue("Display FB must be at 0x10000000", VMMachineModel.DISPLAY_FB_BASE == 0x10000000L)

        // Verify that RAM is strictly placed above all MMIO peripheral devices
        val maxMmioEnd = VMMachineModel.DISPLAY_FB_BASE + VMMachineModel.DISPLAY_FB_SIZE
        assertTrue("RAM base must begin strictly after the highest MMIO device window", VMMachineModel.RAM_BASE > maxMmioEnd)

        // Validate memory map helper
        assertTrue("1024 MB RAM must be valid and non-overlapping", VMMachineModel.validateMemoryLayout(1024))
        assertTrue("2048 MB RAM must be valid and non-overlapping", VMMachineModel.validateMemoryLayout(2048))

        // Verify MMIO address detector
        assertTrue("GIC is recognized as MMIO", VMMachineModel.isMmioAddress(0x08000000L))
        assertTrue("UART is recognized as MMIO", VMMachineModel.isMmioAddress(0x09000000L))
        assertTrue("VirtIO Blk is recognized as MMIO", VMMachineModel.isMmioAddress(0x0A000000L))
        assertTrue("VirtIO CDROM is recognized as MMIO", VMMachineModel.isMmioAddress(0x0A000200L))
        assertTrue("TPM is recognized as MMIO", VMMachineModel.isMmioAddress(0x0FED0000L))
        assertFalse("Guest RAM is NOT MMIO", VMMachineModel.isMmioAddress(0x40000000L))
        assertFalse("High RAM is NOT MMIO", VMMachineModel.isMmioAddress(0x40080000L))
    }

    @Test
    fun testRuntimeDeviceCapabilityReport_AccurateTiers() {
        val report = VMDeviceCapabilityReport.generate(context)
        assertNotNull("Capability report must not be null", report)
        assertTrue("Host architecture must be populated", report.hostArchitecture.isNotBlank())
        assertTrue("CPU cores must be >= 1", report.cpuCores >= 1)
        assertTrue("Total RAM must be positive", report.totalRamMb > 0)
        assertNotNull("Capabilities list must be non-empty", report.capabilities)

        // Verify distinction between SUPPORTED and IMPLEMENTED_BUT_UNAVAILABLE_ON_THIS_DEVICE
        val kvmCap = report.capabilities.find { it.name.contains("kvm", ignoreCase = true) }
        assertNotNull("KVM capability must be reported", kvmCap)
        if (!report.isKvmUsable) {
            assertEquals("KVM should be marked IMPLEMENTED_BUT_UNAVAILABLE when node not accessible",
                DeviceSupportStatus.IMPLEMENTED_BUT_UNAVAILABLE_ON_THIS_DEVICE, kvmCap?.status)
        } else {
            assertEquals(DeviceSupportStatus.SUPPORTED, kvmCap?.status)
        }
    }

    @Test
    fun testVMRuntimeManager_StrictLifecycleAndTeardown() {
        val manager = VMRuntimeManager(context)
        assertEquals("Initial state must be CREATED", VMState.CREATED, manager.state.value)

        val cfg = VMConfig(
            name = "TestLifecycleVM",
            guestOsType = "LINUX",
            guestArchCode = GuestArchitecture.ARM64.code,
            ramSizeMb = 512,
            cpuCores = 1,
            kernelImagePath = "", // Intentionally empty to test pre-flight failure
            diskImagePath = ""
        )

        // Attempting to start with missing kernel must fail validation and transition to FAILED
        var finalState: VMState? = null
        val err = manager.startVMSync(cfg) { state ->
            finalState = state
        }

        assertNotNull("Validation error must be returned", err)
        assertEquals("State must be FAILED on validation error", VMState.FAILED, manager.state.value)

        // Clean stop
        manager.stopVMSync()
        assertEquals("State must be STOPPED after stop", VMState.STOPPED, manager.state.value)

        manager.destroy()
    }

    @Test
    fun testEndToEndLinuxValidation_RejectsInvalidKernel() {
        // Create an invalid / truncated kernel image
        val fakeKernel = File(tempDir, "fake_vmlinux.bin").apply {
            writeBytes(ByteArray(100) { 0x55.toByte() }) // Under 512 KB
        }

        val cfg = VMConfig(
            name = "LinuxFailTest",
            guestOsType = "LINUX",
            guestArchCode = GuestArchitecture.ARM64.code,
            ramSizeMb = 1024,
            cpuCores = 2,
            kernelImagePath = fakeKernel.absolutePath
        )

        val validation = VMStartValidator.validate(context, cfg)
        assertTrue("Validation must detect truncated / synthetic kernel", validation is VMStartValidator.ValidationResult.Invalid)
        val err = (validation as VMStartValidator.ValidationResult.Invalid).error
        assertTrue("Category must be KERNEL_MISSING or KERNEL_FORMAT_UNVERIFIED",
            err.category == VMErrorCategory.KERNEL_MISSING || err.category == VMErrorCategory.KERNEL_FORMAT_UNVERIFIED)
    }

    @Test
    fun testEndToEndWindowsValidation_RejectsArbitraryISO() {
        // Create an ISO without valid El Torito / EFI structures
        val dummyIso = File(tempDir, "Windows_fake.iso").apply {
            writeBytes(ByteArray(4096) { 0x00 })
        }

        val cfg = VMConfig(
            name = "WindowsIsoTest",
            guestOsType = "WINDOWS",
            guestArchCode = GuestArchitecture.ARM64.code,
            ramSizeMb = 2048,
            cpuCores = 2,
            isoPath = dummyIso.absolutePath
        )

        val validation = VMStartValidator.validate(context, cfg)
        assertTrue("Arbitrary/corrupt ISO must be rejected", validation is VMStartValidator.ValidationResult.Invalid)
        val err = (validation as VMStartValidator.ValidationResult.Invalid).error
        assertTrue("Error must identify ISO format failure or missing files (got ${err.category})",
            err.category == VMErrorCategory.WINDOWS_ISO_INVALID ||
            err.category == VMErrorCategory.WINDOWS_IMAGE_INVALID ||
            err.category == VMErrorCategory.WINDOWS_ISO_ARCHITECTURE_UNVERIFIED ||
            err.category == VMErrorCategory.UEFI_MISSING)
    }

    @Test
    fun testAcpiTableGeneration_MatchesHardwareModel() {
        val payload = AcpiTableGenerator.generateArm64AcpiTables(
            baseAddress = 0x47000000L,
            numCores = 2,
            ramBase = 0x40000000L,
            ramSizeMb = 2048,
            gicDistBase = VMMachineModel.GIC_DIST_BASE,
            gicCpuBase = VMMachineModel.GIC_CPU_BASE
        )

        assertNotNull("ACPI payload must be generated", payload)
        assertTrue("ACPI table bytes must be >= 1536 bytes", payload.tableBytes.size >= 1536)

        // Verify RSDP signature "RSD PTR "
        val rsdpSig = String(payload.tableBytes, 0, 8, Charsets.US_ASCII)
        assertEquals("RSD PTR ", rsdpSig)

        // Verify XSDT signature "XSDT" at offset 64
        val xsdtSig = String(payload.tableBytes, 64, 4, Charsets.US_ASCII)
        assertEquals("XSDT", xsdtSig)

        // Verify MADT signature "APIC" at offset 256
        val madtSig = String(payload.tableBytes, 256, 4, Charsets.US_ASCII)
        assertEquals("APIC", madtSig)

        // Verify TPM2 signature at offset 1280
        val tpm2Sig = String(payload.tableBytes, 1280, 4, Charsets.US_ASCII)
        assertEquals("TPM2", tpm2Sig)
    }

    @Test
    fun testVirtualTpm_PcrExtendAndStatePersistence() {
        val stateFile = File(tempDir, "tpm_state.bin")
        val tpm = VirtualTpm(0x0FED0000L, stateFile)
        tpm.reset()

        // Read initial PCR 0 (must be 32 zeroes)
        val pcr0Initial = tpm.readPcr(0)
        assertEquals(32, pcr0Initial.size)
        assertTrue("PCR 0 must initially be zero", pcr0Initial.all { it == 0.toByte() })

        // Extend PCR 0 with test SHA-256 digest
        val digest = ByteArray(32) { (it + 1).toByte() }
        tpm.extendPcr(0, digest)

        val pcr0Extended = tpm.readPcr(0)
        assertFalse("PCR 0 must change after extend", pcr0Extended.contentEquals(pcr0Initial))

        // Create a new instance pointing to same file: verify NVRAM state persistence
        val tpmReloaded = VirtualTpm(0x0FED0000L, stateFile)
        val pcr0Reloaded = tpmReloaded.readPcr(0)
        assertArrayEquals("PCR state must persist across VirtualTpm restarts", pcr0Extended, pcr0Reloaded)
    }

    @Test
    fun testUefiFirmware_PersistentVariableStore() {
        val fwInfo = UefiFirmwareManager.getFirmwareForArch(context, GuestArchitecture.ARM64)
        assertNotNull("Firmware info must be resolved", fwInfo)
        assertEquals("QEMU_EFI.fd", fwInfo.name)
        assertEquals(VMMachineModel.FLASH0_BASE, fwInfo.flashBaseAddress)

        // Initialize persistent NVRAM variable storage
        val nvramRes = UefiFirmwareManager.getOrInitializeNvramStore(context, GuestArchitecture.ARM64)
        assertTrue("NVRAM result must be Valid", nvramRes is com.example.vm.firmware.NvramStoreResult.Valid)
        val nvramPath = (nvramRes as com.example.vm.firmware.NvramStoreResult.Valid).path
        val nvramFile = File(nvramPath)
        assertTrue("NVRAM file must exist", nvramFile.exists())
        assertTrue("NVRAM file size must be at least 64 KB", nvramFile.length() >= 64 * 1024)

        // Verify variable store header
        val headerBytes = nvramFile.readBytes().take(16).toByteArray()
        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        assertEquals("NVRAM header magic must match EDK2 specification", 0x5AA55AA5, magic)
    }
}
