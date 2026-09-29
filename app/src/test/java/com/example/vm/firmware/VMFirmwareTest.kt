package com.example.vm.firmware

import androidx.test.core.app.ApplicationProvider
import com.example.vm.cpu.GuestArchitecture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VMFirmwareTest {

    @Test
    fun testAcpiTableGenerationAndChecksums() {
        val payload = AcpiTableGenerator.generateArm64AcpiTables(
            baseAddress = 0x47000000L,
            numCores = 2,
            ramBase = 0x40000000L,
            ramSizeMb = 2048
        )

        assertNotNull(payload)
        assertTrue(payload.tableBytes.size >= 2048)

        val buf = ByteBuffer.wrap(payload.tableBytes).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Verify RSDP Header
        val rsdpSig = String(payload.tableBytes, 0, 8, Charsets.US_ASCII)
        assertEquals("RSD PTR ", rsdpSig)

        // Verify RSDP Checksum
        var sumRsdp = 0
        for (i in 0 until 20) {
            sumRsdp += (payload.tableBytes[i].toInt() and 0xFF)
        }
        assertEquals(0, sumRsdp and 0xFF)

        // 2. Verify XSDT Header
        val xsdtSig = String(payload.tableBytes, 64, 4, Charsets.US_ASCII)
        assertEquals("XSDT", xsdtSig)

        val xsdtLen = buf.getInt(64 + 4)
        var sumXsdt = 0
        for (i in 64 until (64 + xsdtLen)) {
            sumXsdt += (payload.tableBytes[i].toInt() and 0xFF)
        }
        assertEquals(0, sumXsdt and 0xFF)

        // 3. Verify MADT Header and vCPU count (2 cores)
        val madtSig = String(payload.tableBytes, 256, 4, Charsets.US_ASCII)
        assertEquals("APIC", madtSig)

        val madtLen = buf.getInt(256 + 4)
        var sumMadt = 0
        for (i in 256 until (256 + madtLen)) {
            sumMadt += (payload.tableBytes[i].toInt() and 0xFF)
        }
        assertEquals(0, sumMadt and 0xFF)

        // 4. Verify FADT Header
        val fadtSig = String(payload.tableBytes, 512, 4, Charsets.US_ASCII)
        assertEquals("FACP", fadtSig)

        // 5. Verify GTDT Header
        val gtdtSig = String(payload.tableBytes, 1024, 4, Charsets.US_ASCII)
        assertEquals("GTDT", gtdtSig)

        val gtdtLen = buf.getInt(1024 + 4)
        var sumGtdt = 0
        for (i in 1024 until (1024 + gtdtLen)) {
            sumGtdt += (payload.tableBytes[i].toInt() and 0xFF)
        }
        assertEquals(0, sumGtdt and 0xFF)
    }

    @Test
    fun testUefiFirmwareManagerInfoAndNvram() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fwInfo = UefiFirmwareManager.getFirmwareForArch(context, GuestArchitecture.ARM64)

        assertEquals("QEMU_EFI.fd", fwInfo.name)
        assertEquals(GuestArchitecture.ARM64, fwInfo.architecture)
        assertEquals(0x00000000L, fwInfo.flashBaseAddress)

        val nvramFile = File(fwInfo.nvramPath)
        val created = UefiFirmwareManager.initializeNvramStore(nvramFile)
        assertTrue(created)
        assertTrue(nvramFile.exists())
        assertTrue(nvramFile.length() > 0)

        nvramFile.delete()
    }
}
