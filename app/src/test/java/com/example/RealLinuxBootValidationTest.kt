package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vm.console.UartPL011ConsoleBackend
import com.example.vm.core.VMConfig
import com.example.vm.core.VMEngine
import com.example.vm.core.VMError
import com.example.vm.core.VMState
import com.example.vm.cpu.CPUBackendSelector
import com.example.vm.cpu.CPUBackendType
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.cpu.InterpreterArm64CPUBackend
import com.example.vm.devices.DeviceManager
import com.example.vm.display.VirtioGPUBitmapDisplayBackend
import com.example.vm.guest.initramfs.GuestInitramfsDownloader
import com.example.vm.guest.initramfs.GuestInitramfsManager
import com.example.vm.guest.kernel.GuestKernelDownloader
import com.example.vm.guest.kernel.GuestKernelManager
import com.example.vm.guest.ubuntu.UbuntuGuestManager
import com.example.vm.guest.ubuntu.UbuntuArtifactValidation
import com.example.vm.input.AndroidInputBackend
import com.example.vm.memory.HostByteBufferMemoryBackend
import com.example.vm.network.VirtualEthernetDevice
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
        val headerBytes = ByteArray(1024 * 1024)
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

    @Test
    fun `arm64 kernel validation rejects ISO image`() {
        val testIsoFile = File(context.cacheDir, "ubuntu-24.04-arm64.iso")
        testIsoFile.writeBytes(ByteArray(1024))

        val info = GuestKernelManager.inspectKernel(testIsoFile.absolutePath)
        assertFalse("ISO image must not be accepted as ARM64 kernel", info.isArm64Valid)
        assertTrue("ISO image flag must be true", info.isIsoImage)
        assertTrue(info.formatDescription.contains("ISO optical disc image detected"))

        testIsoFile.delete()
    }

    @Test
    fun `ubuntu prerequisites validation passes with valid artifacts and fails on ISO`() {
        val validKernelFile = File(context.cacheDir, "vmlinuz-generic")
        val headerBytes = ByteArray(128)
        headerBytes[0x38] = 0x41.toByte()
        headerBytes[0x39] = 0x52.toByte()
        headerBytes[0x3A] = 0x4D.toByte()
        headerBytes[0x3B] = 0x64.toByte()
        validKernelFile.writeBytes(headerBytes)

        val validInitrdFile = File(context.cacheDir, "initrd-generic")
        val gzipBytes = ByteArray(64)
        gzipBytes[0] = 0x1F.toByte()
        gzipBytes[1] = 0x8B.toByte()
        validInitrdFile.writeBytes(gzipBytes)

        val validDiskFile = File(diskBackend.getAuthorizedDisksDirectory(), "ubuntu_rootfs.img")
        validDiskFile.writeBytes(ByteArray(1024))

        // 1. Valid prerequisites check
        val validation = UbuntuGuestManager.validateUbuntuPrerequisites(
            kernelPath = validKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskPath = validDiskFile.absolutePath
        )
        assertTrue("Prerequisites must be satisfied", validation is UbuntuArtifactValidation.Ready)

        // 2. Rejecting when ISO is supplied instead of kernel
        val isoKernelFile = File(context.cacheDir, "ubuntu.iso")
        isoKernelFile.writeBytes(ByteArray(512))
        val isoValidation = UbuntuGuestManager.validateUbuntuPrerequisites(
            kernelPath = isoKernelFile.absolutePath,
            initramfsPath = validInitrdFile.absolutePath,
            diskPath = validDiskFile.absolutePath
        )
        assertTrue("ISO in kernel field must be rejected", isoValidation is UbuntuArtifactValidation.MissingPrerequisite)

        validKernelFile.delete()
        validInitrdFile.delete()
        validDiskFile.delete()
        isoKernelFile.delete()
    }

    @Test
    fun `disk backend imports raw disk image into authorized directory`() {
        val sourceData = ByteArray(1024) { 0x42.toByte() }
        val inputStream = ByteArrayInputStream(sourceData)

        val imported = diskBackend.importDiskImage(inputStream, "ubuntu_imported.img")
        assertNotNull("Imported disk file must not be null", imported)
        assertTrue("Imported disk must exist", imported!!.exists())
        assertTrue("Imported disk must be inside authorized directory", diskBackend.isPathAuthorized(imported.absolutePath))
        assertEquals(1024L, imported.length())

        imported.delete()
    }

    @Test
    fun `virtual disk read write operations enforce bounds and persist data`() {
        val testDisk = File(diskBackend.getAuthorizedDisksDirectory(), "bounds_test.img")
        val success = diskBackend.createDiskImage(testDisk.absolutePath, 1, false) // 1 GB sparse disk
        assertTrue("Disk creation must succeed", success)
        assertTrue("Disk must exist", testDisk.exists())

        val mbrInfo = diskBackend.inspectMBR(testDisk.absolutePath)
        assertNotNull("MBR info must be present", mbrInfo)
        assertTrue("MBR signature 0xAA55 must be valid", mbrInfo!!.isValidSignature)
        assertTrue("Linux partition must be detected", mbrInfo.partitions.any { it.typeHex == "0x83" })

        // Test writing sector at LBA 10
        val testPayload = ByteArray(512) { 0x7E.toByte() }
        val writeOk = diskBackend.writeSectors(testDisk.absolutePath, 10, testPayload)
        assertTrue("Writing sector 10 must succeed", writeOk)

        val readBack = diskBackend.readSectors(testDisk.absolutePath, 10, 1)
        assertNotNull("Read back data must not be null", readBack)
        assertEquals(512, readBack!!.size)
        assertEquals(0x7E.toByte(), readBack[0])

        // Test out-of-bounds sector write
        val oobLba = (1024L * 1024L * 1024L / 512L) + 10L
        val oobWrite = diskBackend.writeSectors(testDisk.absolutePath, oobLba, testPayload)
        assertFalse("Out-of-bounds write must be blocked", oobWrite)

        testDisk.delete()
    }

    @Test
    fun `ubuntu guest system profile defines correct kernel cmdline and virtio root`() {
        val profile = UbuntuGuestManager.getProfile()
        assertEquals("Ubuntu 24.04 LTS (Noble Numbat)", profile.releaseName)
        assertEquals("aarch64", profile.architecture)
        assertEquals("/dev/vda1", profile.virtioRootDevice)
        assertTrue(profile.defaultKernelCmdline.contains("root=/dev/vda1"))
        assertTrue(profile.defaultKernelCmdline.contains("console=ttyAMA0"))
        assertTrue(profile.defaultKernelCmdline.contains("earlycon=pl011"))
    }

    @Test
    fun `kvm unavailable selects software emulation backend`() {
        val resolution = CPUBackendSelector.resolve(
            guestArch = GuestArchitecture.ARM64,
            requestHardwareVirt = true,
            isKvmSupported = false,
            kvmReason = "KVM node /dev/kvm is unavailable on MediaTek MT6855"
        )
        assertFalse("Hardware virtualization must not be reported when KVM is unavailable", resolution.isHardwareAccelerated)
        assertTrue("Fallback emulation must be active", resolution.isFallbackEmulation)
        assertEquals(CPUBackendType.ARM64_EMULATION, resolution.backendType)
        assertTrue("Status message must explain KVM unexposed status", resolution.statusMessage.contains("unavailable") || resolution.statusMessage.contains("software emulation"))
    }

    @Test
    fun `initramfs missing usable init fails with exact message`() {
        val badInitrd = File(context.cacheDir, "no_init_initrd.cpio")
        val cpioData = "0707010000000000000000000000000000000000000001000000000000000000000000000000000000000000000000000000000000000B00000000TRAILER!!!\u0000\u0000\u0000\u0000".toByteArray()
        badInitrd.writeBytes(cpioData)

        val info = GuestInitramfsManager.inspectInitramfs(badInitrd.absolutePath)
        assertFalse("Initramfs without init must not have usable init", info.hasUsableInit)

        val err = VMError.initramfsMissingInit(badInitrd.absolutePath)
        assertEquals("REAL BOOT FAILED: initramfs does not contain a usable /init", err.summary)

        badInitrd.delete()
    }

    @Test
    fun `unsupported arm64 instruction halts emulator and reports exact opcode and pc`() {
        val cpu = InterpreterArm64CPUBackend()
        val mem = HostByteBufferMemoryBackend(16)
        val disp = VirtioGPUBitmapDisplayBackend()
        val console = UartPL011ConsoleBackend()
        val net = VirtualEthernetDevice()
        val input = AndroidInputBackend().virtualInputDevice
        val devMgr = DeviceManager(disp, console, net, input)

        cpu.pc = 0x100L
        mem.write32(0x100L, 0x00000000)

        val result = cpu.stepInstruction(mem, devMgr)
        assertTrue("Emulator must halt on unsupported instruction", cpu.isHalted)
        assertTrue("Result must contain TRAP: Unsupported ARM64 instruction", result.contains("TRAP: Unsupported ARM64 instruction"))
        assertTrue("Result must report exact opcode", result.contains("0x0") || result.contains("0x00000000"))
        assertTrue("Result must report exact PC", result.contains("0x100") || result.contains("100"))
    }

    @Test
    fun `uart mmio routes writes to pl011 serial console`() {
        val cpu = InterpreterArm64CPUBackend()
        val mem = HostByteBufferMemoryBackend(16)
        val disp = VirtioGPUBitmapDisplayBackend()
        val console = UartPL011ConsoleBackend()
        val net = VirtualEthernetDevice()
        val input = AndroidInputBackend().virtualInputDevice
        val devMgr = DeviceManager(disp, console, net, input)

        // STRB W0, [X1] -> Opcode: 0x39000020
        cpu.registers[1] = 0x09000000L // X1 = PL011 UARTDR
        cpu.registers[0] = 'H'.code.toLong()
        mem.write32(0x0L, 0x39000020)
        cpu.pc = 0x0L

        val step = cpu.stepInstruction(mem, devMgr)
        assertTrue("Step must disassemble STRB", step.contains("STRB"))
        val consoleOutput = console.terminalBuffer.value
        assertTrue("UART console must receive character 'H' from guest write", consoleOutput.contains("H"))
    }

    @Test
    fun `guest memory isolation blocks out of bounds accesses`() {
        val mem = HostByteBufferMemoryBackend(16)
        val maxValid = (16L * 1024L * 1024L) - 4L
        mem.write32(maxValid, 0x12345678)
        assertEquals(0x12345678, mem.read32(maxValid))

        var threw = false
        try {
            mem.read32(16L * 1024L * 1024L + 1024L)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("Memory access outside bounds must throw/fail safely", threw)
    }

    @Test
    fun `no fake cpu telemetry`() {
        val config = VMConfig(id = 105L, name = "TelemetryTest", ramSizeMb = 512)
        val engine = VMEngine(context, config)
        assertNull("cpuUsage must be null (unavailable) when real utilization is unmeasured", engine.cpuUsage.value)
        assertNotEquals(0.25f, engine.cpuUsage.value)
        engine.destroy()
    }

    @Test
    fun `vm lifecycle transitions through real states only`() {
        val config = VMConfig(id = 106L, name = "LifecycleTest", ramSizeMb = 512)
        val engine = VMEngine(context, config)
        assertEquals(VMState.CREATED, engine.state.value)
        engine.stop()
        assertEquals(VMState.STOPPED, engine.state.value)
        engine.destroy()
    }

    @Test
    fun `linux image provisioner rejects unconfigured environment and requires real assets`() = runBlocking {
        // Clear any leftover files in the guest os dir
        val dir = File(context.filesDir, "guest_os/ubuntu-24-04-cloud-arm64")
        if (dir.exists()) dir.deleteRecursively()

        // 1. Without configured kernel, provisioning must fail with explicit message
        val failureResult = com.example.vm.guest.linux.LinuxImageProvisioner.provisionDefaultLinuxEnvironment(
            context = context,
            vmName = "TestProvisionLinux",
            forceRecreate = true
        )
        assertTrue("Provisioning must fail when no kernel is configured", failureResult is com.example.vm.guest.linux.LinuxImageProvisioner.ProvisionResult.Failure)
        val failure = failureResult as com.example.vm.guest.linux.LinuxImageProvisioner.ProvisionResult.Failure
        assertTrue(failure.reason.contains("No Linux kernel configured"))

        // 2. Supply authentic verified assets
        dir.mkdirs()
        val kernelFile = File(dir, "vmlinuz")
        val kBytes = ByteArray(1024 * 1024)
        kBytes[0x38] = 0x41.toByte()
        kBytes[0x39] = 0x52.toByte()
        kBytes[0x3A] = 0x4D.toByte()
        kBytes[0x3B] = 0x64.toByte()
        FileOutputStream(kernelFile).use { it.write(kBytes) }

        val initrdFile = File(dir, "initrd")
        val archiveBytes = createTestCpioArchive(
            listOf(
                Pair("init", "#!/bin/sh\nexit 0\n".toByteArray()),
                Pair("bin/sh", "#!/bin/sh\n".toByteArray())
            )
        )
        FileOutputStream(initrdFile).use { fos ->
            java.util.zip.GZIPOutputStream(fos).use { gzip ->
                gzip.write(archiveBytes)
            }
        }

        val diskFile = File(dir, "rootfs.img")
        val diskBytes = ByteArray(2 * 1024 * 1024)
        diskBytes[0x438] = 0x53.toByte()
        diskBytes[0x439] = 0xEF.toByte()
        FileOutputStream(diskFile).use { it.write(diskBytes) }

        // 3. Now provisioning succeeds with verified authentic assets
        val successResult = com.example.vm.guest.linux.LinuxImageProvisioner.provisionDefaultLinuxEnvironment(
            context = context,
            vmName = "TestProvisionLinux",
            forceRecreate = false
        )
        assertTrue("Provisioning must succeed with authentic assets", successResult is com.example.vm.guest.linux.LinuxImageProvisioner.ProvisionResult.Success)
        val success = successResult as com.example.vm.guest.linux.LinuxImageProvisioner.ProvisionResult.Success

        // Verify kernel format
        val kernelInfo = GuestKernelManager.inspectKernel(kernelFile.absolutePath)
        assertTrue("Kernel must be valid ARM64", kernelInfo.isArm64Valid)
        assertEquals("ARM64 (AArch64)", kernelInfo.architecture)

        // Verify initramfs format
        val initrdInfo = GuestInitramfsManager.inspectInitramfs(initrdFile.absolutePath)
        assertTrue("Initramfs must be compressed gzip", initrdInfo.isCompressed)
        assertTrue("Initramfs must contain /init executable", initrdInfo.hasUsableInit)

        // Verify VMStartValidator passes
        val validation = com.example.vm.core.VMStartValidator.validate(context, success.config)
        assertTrue("Pre-flight validator must pass for provisioned config", validation is com.example.vm.core.VMStartValidator.ValidationResult.Valid)
    }

    private fun createTestCpioArchive(entries: List<Pair<String, ByteArray>>): ByteArray {
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

    @Test
    fun `vm start validator rejects unauthorized path traversal`() {
        val badConfig = VMConfig(
            id = 107L,
            name = "PathTraversalTest",
            kernelImagePath = "/system/bin/sh", // Outside app storage sandbox
            ramSizeMb = 1024
        )

        val validation = com.example.vm.core.VMStartValidator.validate(context, badConfig)
        assertTrue("Validator must reject unauthorized path traversal", validation is com.example.vm.core.VMStartValidator.ValidationResult.Invalid)
        val invalid = validation as com.example.vm.core.VMStartValidator.ValidationResult.Invalid
        assertEquals(com.example.vm.core.VMErrorCategory.KERNEL_MISSING, invalid.error.category)
        assertTrue(invalid.error.technicalDetails.contains("Security Violation") || invalid.error.technicalDetails.contains("sandbox"))
    }

    @Test
    fun `vm start validator rejects missing kernel with clear actionable error`() {
        val config = VMConfig(
            id = 108L,
            name = "MissingKernelTest",
            kernelImagePath = "",
            ramSizeMb = 512
        )
        val validation = com.example.vm.core.VMStartValidator.validate(context, config)
        assertTrue("Must be invalid when kernel is missing", validation is com.example.vm.core.VMStartValidator.ValidationResult.Invalid)
        val err = (validation as com.example.vm.core.VMStartValidator.ValidationResult.Invalid).error
        assertEquals(com.example.vm.core.VMErrorCategory.KERNEL_MISSING, err.category)
        assertTrue(err.suggestedRemedy.contains("Import") || err.suggestedRemedy.contains("kernel"))
    }

    @Test
    fun `vm start validator rejects empty kernel file`() {
        val kernelsDir = File(context.filesDir, "guest_kernels").apply { mkdirs() }
        val emptyKernel = File(kernelsDir, "empty_kernel.img").apply { writeBytes(ByteArray(0)) }
        val config = VMConfig(
            id = 109L,
            name = "EmptyKernelTest",
            kernelImagePath = emptyKernel.absolutePath,
            ramSizeMb = 512
        )
        val validation = com.example.vm.core.VMStartValidator.validate(context, config)
        assertTrue("Must be invalid when kernel is empty", validation is com.example.vm.core.VMStartValidator.ValidationResult.Invalid)
        val err = (validation as com.example.vm.core.VMStartValidator.ValidationResult.Invalid).error
        assertEquals(com.example.vm.core.VMErrorCategory.KERNEL_MISSING, err.category)
    }

    @Test
    fun `vm start validator rejects missing initramfs when path set but non-existent`() {
        val kernelsDir = File(context.filesDir, "guest_kernels").apply { mkdirs() }
        val dummyKernel = File(kernelsDir, "valid_header_kernel.img").apply {
            val header = ByteArray(64)
            // ARM64 magic "ARM\x64" at offset 0x38 (56)
            header[56] = 0x41.toByte()
            header[57] = 0x52.toByte()
            header[58] = 0x4D.toByte()
            header[59] = 0x64.toByte()
            writeBytes(header)
        }

        val config = VMConfig(
            id = 110L,
            name = "MissingInitrdTest",
            kernelImagePath = dummyKernel.absolutePath,
            initramfsPath = File(context.filesDir, "non_existent_initrd.cpio.gz").absolutePath,
            ramSizeMb = 512
        )
        val validation = com.example.vm.core.VMStartValidator.validate(context, config)
        assertTrue("Must be invalid when initramfs is missing", validation is com.example.vm.core.VMStartValidator.ValidationResult.Invalid)
        val err = (validation as com.example.vm.core.VMStartValidator.ValidationResult.Invalid).error
        assertEquals(com.example.vm.core.VMErrorCategory.INITRAMFS_MISSING, err.category)
    }

    @Test
    fun `vm start validator rejects invalid disk smaller than 512 bytes`() {
        val kernelsDir = File(context.filesDir, "guest_kernels").apply { mkdirs() }
        val dummyKernel = File(kernelsDir, "valid_k.img").apply {
            val header = ByteArray(64)
            header[56] = 0x41.toByte()
            header[57] = 0x52.toByte()
            header[58] = 0x4D.toByte()
            header[59] = 0x64.toByte()
            writeBytes(header)
        }
        val disksDir = File(context.filesDir, "app_disks").apply { mkdirs() }
        val tinyDisk = File(disksDir, "tiny_invalid.img").apply {
            writeBytes(ByteArray(100)) // < 512 bytes
        }

        val config = VMConfig(
            id = 111L,
            name = "TinyDiskTest",
            kernelImagePath = dummyKernel.absolutePath,
            diskImagePath = tinyDisk.absolutePath,
            ramSizeMb = 512
        )
        val validation = com.example.vm.core.VMStartValidator.validate(context, config)
        assertTrue("Must be invalid when disk is smaller than 512 bytes", validation is com.example.vm.core.VMStartValidator.ValidationResult.Invalid)
        val err = (validation as com.example.vm.core.VMStartValidator.ValidationResult.Invalid).error
        assertEquals(com.example.vm.core.VMErrorCategory.DISK_INVALID, err.category)
    }

    @Test
    fun `linux file import computes metadata and copies to private storage`() {
        val rawData = "Linux Boot Payload Data Test 12345".toByteArray(Charsets.UTF_8)
        val tempSource = File(context.cacheDir, "source_payload.bin").apply { writeBytes(rawData) }

        val metadata = com.example.vm.guest.linux.LinuxImageProvisioner.importBootFile(
            context = context,
            sourceFile = tempSource,
            targetSubdir = "imported_payloads",
            destFilename = "custom_payload.bin"
        )

        assertNotNull("Import must return metadata", metadata)
        assertEquals("custom_payload.bin", metadata.fileName)
        assertEquals(rawData.size.toLong(), metadata.sizeBytes)
        assertTrue("Imported file must exist", File(metadata.absolutePath).exists())
        assertEquals(64, metadata.sha256Hex.length) // Valid SHA-256 hash
    }

    @Test
    fun `vm start stop restart lifecycle cleanly resets state and releases resources`() = runBlocking {
        val dir = File(context.filesDir, "guest_os/ubuntu-24-04-cloud-arm64").apply { mkdirs() }
        val kernelFile = File(dir, "vmlinuz")
        if (!kernelFile.exists()) {
            val kBytes = ByteArray(1024 * 1024)
            kBytes[0x38] = 0x41.toByte()
            kBytes[0x39] = 0x52.toByte()
            kBytes[0x3A] = 0x4D.toByte()
            kBytes[0x3B] = 0x64.toByte()
            kernelFile.writeBytes(kBytes)
        }
        val initrdFile = File(dir, "initrd")
        if (!initrdFile.exists()) {
            val archiveBytes = createTestCpioArchive(
                listOf(
                    Pair("init", "#!/bin/sh\nexit 0\n".toByteArray()),
                    Pair("bin/sh", "#!/bin/sh\n".toByteArray())
                )
            )
            FileOutputStream(initrdFile).use { fos ->
                java.util.zip.GZIPOutputStream(fos).use { gzip ->
                    gzip.write(archiveBytes)
                }
            }
        }
        val diskFile = File(dir, "rootfs.img")
        if (!diskFile.exists()) {
            val diskBytes = ByteArray(2 * 1024 * 1024)
            diskBytes[0x438] = 0x53.toByte()
            diskBytes[0x439] = 0xEF.toByte()
            diskFile.writeBytes(diskBytes)
        }

        val result = com.example.vm.guest.linux.LinuxImageProvisioner.provisionDefaultLinuxEnvironment(
            context = context,
            vmName = "LifecycleCycleTest",
            forceRecreate = false
        )
        assertTrue(result is com.example.vm.guest.linux.LinuxImageProvisioner.ProvisionResult.Success)
        val config = (result as com.example.vm.guest.linux.LinuxImageProvisioner.ProvisionResult.Success).config

        val engine = VMEngine(context, config)
        
        // 1. First Start
        val err1 = engine.start()
        assertNull(err1)
        assertTrue(engine.state.value == VMState.RUNNING || engine.state.value == VMState.STARTING)

        // 2. Stop
        engine.stop()
        assertEquals(VMState.STOPPED, engine.state.value)

        // 3. Restart
        val err2 = engine.start()
        assertNull(err2)
        assertTrue(engine.state.value == VMState.RUNNING || engine.state.value == VMState.STARTING)

        // 4. Final Clean Stop & Destroy
        engine.stop()
        assertEquals(VMState.STOPPED, engine.state.value)
        engine.destroy()
        assertEquals(VMState.STOPPED, engine.state.value)
    }
}
