package com.example.vm.boot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vm.boot.linux.LinuxBootManager
import com.example.vm.boot.windows.BcdValidationResult
import com.example.vm.boot.windows.WindowsBcdManager
import com.example.vm.boot.windows.WindowsBootManager
import com.example.vm.boot.windows.WindowsBootValidator
import com.example.vm.core.VMConfig
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.firmware.FirmwareValidationResult
import com.example.vm.firmware.UefiFirmwareManager
import com.example.vm.storage.EfiSystemPartition
import com.example.vm.uefi.UefiBootVariable
import com.example.vm.uefi.UefiNvramStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealUefiBootManagerValidationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `test UEFI NVRAM store persistence and variable operations`() {
        val nvram = UefiNvramStore.getInstance(101L)
        nvram.restore(context)

        val entry1 = UefiBootVariable(
            id = "Boot0000",
            displayName = "Test Windows Boot Manager",
            type = "WINDOWS_BOOT_MANAGER",
            efiPath = "\\EFI\\Microsoft\\Boot\\bootmgfw.efi",
            bcdPath = "\\EFI\\Microsoft\\Boot\\BCD"
        )
        val entry2 = UefiBootVariable(
            id = "Boot0001",
            displayName = "Test Linux EFI Loader",
            type = "LINUX_EFI_LOADER",
            efiPath = "\\EFI\\BOOT\\BOOTAA64.EFI"
        )

        nvram.createEntry(entry1)
        nvram.createEntry(entry2)
        nvram.setBootOrder(listOf("Boot0000", "Boot0001"))
        nvram.setBootNext("Boot0001")
        nvram.setTimeout(10)
        nvram.persist(context)

        // Restore in new store instance
        val nvram2 = UefiNvramStore.getInstance(101L)
        nvram2.restore(context)

        assertEquals("Boot0001", nvram2.getBootNext())
        assertEquals(10, nvram2.getTimeout())
        assertEquals(listOf("Boot0000", "Boot0001"), nvram2.getBootOrder())
        assertNotNull(nvram2.getEntry("Boot0000"))
    }

    @Test
    fun `test ESP inspection and EFI file resolution`() {
        val disksDir = File(context.filesDir, "disks").apply { mkdirs() }
        val diskFile = File(disksDir, "esp_test_disk.img").apply { writeBytes(ByteArray(2 * 1024 * 1024)) }

        val res = EfiSystemPartition.inspectPartition(context, diskFile.absolutePath, "\\EFI\\Microsoft\\Boot\\bootmgfw.efi")
        assertTrue(res.diskExists)
        assertTrue(res.espExists)
        assertTrue(res.loaderFound)
        assertTrue(res.bcdFound)

        val efiFiles = EfiSystemPartition.listEfiFiles(context, diskFile.absolutePath)
        assertTrue(efiFiles.isNotEmpty())
    }

    @Test
    fun `test Windows BCD manager validation`() {
        val disksDir = File(context.filesDir, "disks").apply { mkdirs() }
        val diskFile = File(disksDir, "bcd_test_disk.img").apply { writeBytes(ByteArray(2 * 1024 * 1024)) }

        // Trigger ESP layout creation
        EfiSystemPartition.inspectPartition(context, diskFile.absolutePath)

        val bcdRes = WindowsBcdManager.validateBcd(context, diskFile.absolutePath)
        assertTrue(bcdRes is BcdValidationResult.Valid)
        val valid = bcdRes as BcdValidationResult.Valid
        assertTrue(valid.entries.isNotEmpty())
        assertNotNull(valid.defaultLoader)
    }

    @Test
    fun `test BootManager BootNext one-time boot sequence`() {
        val config = VMConfig(
            id = 505L,
            name = "BootNextVM",
            diskImagePath = File(context.filesDir, "disks/bootnext_disk.img").apply { parentFile?.mkdirs(); writeBytes(ByteArray(2 * 1024 * 1024)) }.absolutePath,
            ramSizeMb = 4096,
            cpuCores = 2
        )

        // Seed firmware
        val fwFile = File(context.filesDir, "firmware/QEMU_EFI.fd").apply { parentFile?.mkdirs() }
        if (!fwFile.exists()) {
            val fwBytes = ByteArray(2 * 1024 * 1024)
            fwBytes[0] = 0x00.toByte(); fwBytes[1] = 0x00.toByte(); fwBytes[2] = 0x00.toByte(); fwBytes[3] = 0x14.toByte()
            fwFile.writeBytes(fwBytes)
        }

        BootManager.discoverBootEntries(context, config)
        val resOnce = BootManager.bootOnce(context, config, "Boot0001")
        assertTrue(resOnce.success)

        val nvram = UefiNvramStore.getInstance(config.id)
        assertNull("BootNext must be cleared after single boot execution", nvram.getBootNext())
    }

    @Test
    fun `test BootManager recovery fallback when boot targets are missing`() {
        val config = VMConfig(
            id = 606L,
            name = "RecoveryVM",
            diskImagePath = "",
            kernelImagePath = "",
            isoPath = "",
            ramSizeMb = 2048,
            cpuCores = 2
        )

        val res = BootManager.bootDefault(context, config)
        assertTrue("BootManager must fallback to Recovery environment when no valid boot target exists", res.success || res.recoveryAvailable)
    }
}
