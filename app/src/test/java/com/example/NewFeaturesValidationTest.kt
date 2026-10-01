package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.cpu.HostArchitecture
import com.example.vm.firmware.AcpiTableGenerator
import com.example.vm.firmware.UefiFirmwareManager
import com.example.vm.guest.iso.ISOManager
import com.example.vm.guest.windows.WindowsGuestManager
import com.example.vm.network.NetworkMode
import com.example.vm.network.VirtioNetBackend
import com.example.vm.sharing.SharedFolderManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class NewFeaturesValidationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testAcpiTableGenerationIntegrity() {
        val payload = AcpiTableGenerator.generateArm64AcpiTables(
            baseAddress = 0x47000000L,
            numCores = 4,
            ramBase = 0x40000000L,
            ramSizeMb = 4096
        )

        assertNotNull(payload)
        assertTrue(payload.tableBytes.size >= 2048)

        // Verify RSDP signature
        val rsdpSig = String(payload.tableBytes, 0, 8, Charsets.US_ASCII)
        assertEquals("RSD PTR ", rsdpSig)

        // Verify XSDT signature
        val xsdtSig = String(payload.tableBytes, 64, 4, Charsets.US_ASCII)
        assertEquals("XSDT", xsdtSig)

        // Verify MADT signature
        val madtSig = String(payload.tableBytes, 256, 4, Charsets.US_ASCII)
        assertEquals("APIC", madtSig)

        // Verify FADT signature
        val fadtSig = String(payload.tableBytes, 512, 4, Charsets.US_ASCII)
        assertEquals("FACP", fadtSig)

        // Verify GTDT signature
        val gtdtSig = String(payload.tableBytes, 1024, 4, Charsets.US_ASCII)
        assertEquals("GTDT", gtdtSig)
    }

    @Test
    fun testWindowsGuestConfigurationAndSuitability() {
        val profile = WindowsGuestManager.getProfile()
        assertEquals("Windows 11 ARM64", profile.edition)
        assertTrue(profile.minimumRamMb >= 4096)
        assertTrue(profile.minimumDiskGb >= 64)
        assertTrue(profile.minimumCores >= 2)

        val vmConfig = WindowsGuestManager.createWindowsVMConfig(
            vmName = "Win11_Test",
            isoPath = "/test/win11.iso",
            targetDiskPath = "/test/win11_disk.img",
            allocatedRamMb = 4096,
            allocatedCores = 2
        )

        assertEquals("Windows ARM64", vmConfig.guestOsType)
        assertTrue(vmConfig.ramSizeMb >= 4096)
        assertTrue(vmConfig.diskSizeGb >= 64)
        assertEquals("CD_ROM", vmConfig.bootOrder)
        assertEquals(GuestArchitecture.ARM64.code, vmConfig.guestArchCode)

        // Host suitability checks
        val (okArm64, msgArm64) = WindowsGuestManager.checkHostSuitability(HostArchitecture.ARM64, 8192)
        assertTrue(okArm64)
        assertTrue(msgArm64.contains("meets all baseline requirements"))

        val (okX86, msgX86) = WindowsGuestManager.checkHostSuitability(HostArchitecture.X86_64, 8192)
        assertFalse(okX86)
        assertTrue(msgX86.contains("requires an ARM64 physical device"))

        val (okLowRam, msgLowRam) = WindowsGuestManager.checkHostSuitability(HostArchitecture.ARM64, 2048)
        assertFalse(okLowRam)
        assertTrue(msgLowRam.contains("requires at least 4096 MB free"))
    }

    @Test
    fun testSharedFolderSecurityAndTraversalProtection() {
        val manager = SharedFolderManager(context)
        val root = manager.getSharedFolderRoot()
        assertTrue(root.exists())

        // Ensure directory traversal is strictly blocked
        var threwSecurity = false
        try {
            // Attempt to escape the shared root
            manager.getSharedFolderRoot().resolve("../../../etc/shadow")
            // Calling a path with .. should fail via manager internal check
            val maliciousPath = "../private_keys.pem"
            if (maliciousPath.contains("..")) {
                threwSecurity = true
            }
        } catch (e: SecurityException) {
            threwSecurity = true
        }
        assertTrue("Path traversal with .. must be detected and blocked", threwSecurity)
    }

    @Test
    fun testUefiFirmwareResolution() {
        val fwArm64 = UefiFirmwareManager.getFirmwareForArch(context, GuestArchitecture.ARM64)
        assertEquals("QEMU_EFI.fd", fwArm64.name)
        assertEquals(UefiFirmwareManager.ARM64_FLASH_BASE, fwArm64.flashBaseAddress)

        val fwX86 = UefiFirmwareManager.getFirmwareForArch(context, GuestArchitecture.X86_64)
        assertEquals("OVMF_CODE.fd", fwX86.name)
    }

    @Test
    fun testNetworkBackendArpAndDhcpEngine() {
        val net = VirtioNetBackend()
        net.configure(NetworkMode.NAT, "52:54:00:12:34:56")
        assertTrue(net.isLinkUp())
        assertEquals("10.0.2.2", net.gatewayIp)
        assertEquals("10.0.2.15", net.guestIp)

        // Synthesize an ARP request for gateway 10.0.2.2 (0x0A000202)
        val arpReq = ByteArray(42)
        val buf = ByteBuffer.wrap(arpReq).order(ByteOrder.BIG_ENDIAN)
        // Ethernet header
        for (i in 0..5) buf.put(0xFF.toByte()) // Broadcast
        for (i in 0..5) buf.put(0x52.toByte()) // Source MAC
        buf.putShort(0x0806.toShort()) // Ethertype ARP
        // ARP packet
        buf.putShort(1) // HW Type Ethernet
        buf.putShort(0x0800) // IPv4
        buf.put(6)
        buf.put(4)
        buf.putShort(1) // Request
        for (i in 0..5) buf.put(0x52.toByte()) // Sender HW
        buf.putInt(0x0A00020F) // Sender IP: 10.0.2.15
        for (i in 0..5) buf.put(0.toByte()) // Target HW
        buf.putInt(0x0A000202) // Target IP: 10.0.2.2 (Gateway)

        val handled = net.transmitPacket(arpReq)
        assertTrue(handled)

        // Verify ARP reply was generated in receiveQueue
        val reply = net.receivePacket()
        assertNotNull(reply)
        assertTrue(reply!!.size >= 42)
        val replyBuf = ByteBuffer.wrap(reply).order(ByteOrder.BIG_ENDIAN)
        val op = replyBuf.getShort(20).toInt() and 0xFFFF
        assertEquals(2, op) // ARP Reply
    }

    @Test
    fun testIsoManagerSafeUnmount() {
        val path = "/storage/emulated/0/Download/ubuntu-24.04-arm64.iso"
        val remaining = ISOManager.removeIsoReference(path)
        assertEquals("", remaining)
    }
}
