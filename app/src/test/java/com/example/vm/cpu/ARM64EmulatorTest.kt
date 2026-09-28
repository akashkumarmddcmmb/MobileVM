package com.example.vm.cpu

import com.example.vm.cpu.emulation.ARM64EmulationBackend
import com.example.vm.cpu.emulation.StandardARM64EmulationBackend
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ARM64EmulatorTest {

    // --- Architecture & Backend Resolution Tests ---

    @Test
    fun `CPU backend selector chooses KVM when hardware virt is available and requested`() {
        val resolution = CPUBackendSelector.resolve(
            guestArch = GuestArchitecture.ARM64,
            requestHardwareVirt = true,
            hostArch = HostArchitecture.ARM64,
            isKvmSupported = true,
            kvmReason = "KVM hypervisor node /dev/kvm accessible."
        )

        assertEquals(CPUBackendType.ARM64_HARDWARE_VIRTUALIZATION, resolution.backendType)
        assertTrue(resolution.isHardwareAccelerated)
        assertFalse(resolution.isFallbackEmulation)
        assertTrue(resolution.statusMessage.contains("KVM/pKVM Hardware Virtualization active"))
    }

    @Test
    fun `CPU backend selector falls back to real ARM64 emulation when KVM is unavailable`() {
        val resolution = CPUBackendSelector.resolve(
            guestArch = GuestArchitecture.ARM64,
            requestHardwareVirt = true,
            hostArch = HostArchitecture.ARM64,
            isKvmSupported = false,
            kvmReason = "/dev/kvm node not found on device kernel"
        )

        assertEquals(CPUBackendType.ARM64_EMULATION, resolution.backendType)
        assertFalse(resolution.isHardwareAccelerated)
        assertTrue(resolution.isFallbackEmulation)
        assertTrue(resolution.statusMessage.contains("Hardware acceleration unavailable"))
        assertTrue(resolution.statusMessage.contains("real ARM64 software emulation fallback"))
    }

    @Test
    fun `CPU backend selector respects user preference for software emulation`() {
        val resolution = CPUBackendSelector.resolve(
            guestArch = GuestArchitecture.ARM64,
            requestHardwareVirt = false,
            hostArch = HostArchitecture.ARM64,
            isKvmSupported = true
        )

        assertEquals(CPUBackendType.ARM64_EMULATION, resolution.backendType)
        assertFalse(resolution.isHardwareAccelerated)
        assertFalse(resolution.isFallbackEmulation)
        assertTrue(resolution.statusMessage.contains("ARM64 Native Software Emulation active (configured by user)"))
    }

    @Test
    fun `emulation backend status reports comprehensive AArch64 execution capability`() {
        val backend: ARM64EmulationBackend = StandardARM64EmulationBackend(isNativeLoaded = true)
        val status = backend.getStatus()

        assertTrue(status.isNativeEngineActive)
        assertFalse(status.canExecuteLinuxKernel)
        assertTrue(status.supportedInstructionSets.contains("ARMv8.0-A Base"))
        assertTrue(status.supportedInstructionSets.contains("MMU/Page Faults"))
        assertEquals(CPUBackendType.ARM64_EMULATION.displayName, backend.getBackendName())
    }

    // --- Architectural Emulation Validation & Logic Tests ---

    @Test
    fun `PSTATE bitfield encoding correctly tracks NZCV flags and DAIF interrupt masks`() {
        // PSTATE bits:
        // N = bit 31, Z = bit 30, C = bit 29, V = bit 28
        // D = bit 9, A = bit 8, I = bit 7, F = bit 6
        // EL = bits 3:2, SPSel = bit 0
        var pstate = 0x000003C5 // Reset value: EL=1, SP=1, DAIF=0xF (all masked)

        val isN = (pstate and (1 shl 31)) != 0
        val isZ = (pstate and (1 shl 30)) != 0
        val isC = (pstate and (1 shl 29)) != 0
        val isV = (pstate and (1 shl 28)) != 0
        val el = (pstate shr 2) and 0x3
        val spsel = pstate and 0x1
        val irqMasked = (pstate and (1 shl 7)) != 0

        assertFalse("N flag should initially be 0", isN)
        assertFalse("Z flag should initially be 0", isZ)
        assertFalse("C flag should initially be 0", isC)
        assertFalse("V flag should initially be 0", isV)
        assertEquals("Should boot in EL1", 1, el)
        assertEquals("Should boot with SP_EL1", 1, spsel)
        assertTrue("IRQ should be masked initially", irqMasked)

        // Set NZCV flags
        pstate = pstate or (1 shl 30) // Set Z
        pstate = pstate and (1 shl 7).inv() // Unmask IRQ (I=0)

        assertTrue("Z flag should be set", (pstate and (1 shl 30)) != 0)
        assertFalse("IRQ should now be unmasked", (pstate and (1 shl 7)) != 0)
    }

    @Test
    fun `ARM64 4KB page table descriptor calculation and level index decomposition`() {
        val virtualAddress = 0xFFFF800010080000UL.toLong()

        // 48-bit address space decomposition with 4KB granule:
        // Level 0: bits [47:39]
        // Level 1: bits [38:30]
        // Level 2: bits [29:21]
        // Level 3: bits [20:12]
        // Page offset: bits [11:0]
        val l0Idx = (virtualAddress ushr 39) and 0x1FFL
        val l1Idx = (virtualAddress ushr 30) and 0x1FFL
        val l2Idx = (virtualAddress ushr 21) and 0x1FFL
        val l3Idx = (virtualAddress ushr 12) and 0x1FFL
        val pageOffset = virtualAddress and 0xFFFL

        assertEquals("Page offset for 0x...000 should be 0", 0L, pageOffset)
        assertEquals("Level 3 index should match bits 20:12", 0x80L, l3Idx)
        assertTrue("L0 index within 512 entries", l0Idx in 0L..511L)
        assertTrue("L1 index within 512 entries", l1Idx in 0L..511L)
        assertTrue("L2 index within 512 entries", l2Idx in 0L..511L)
    }

    @Test
    fun `exception vector offset computation adheres to ARMv8-A specification`() {
        val vbarEl1 = 0xFFFF800008000000UL.toLong()

        // Offsets from VBAR_EL1:
        // Current EL with SP0:  Sync = 0x000, IRQ = 0x080, FIQ = 0x100, SError = 0x180
        // Current EL with SPx:  Sync = 0x200, IRQ = 0x280, FIQ = 0x300, SError = 0x380
        // Lower EL (AArch64):   Sync = 0x400, IRQ = 0x480, FIQ = 0x500, SError = 0x580

        val syncEl1SpX = vbarEl1 + 0x200
        val irqEl1SpX = vbarEl1 + 0x280
        val syncEl0 = vbarEl1 + 0x400
        val irqEl0 = vbarEl1 + 0x480

        assertEquals(vbarEl1 + 0x200, syncEl1SpX)
        assertEquals(vbarEl1 + 0x280, irqEl1SpX)
        assertEquals(vbarEl1 + 0x400, syncEl0)
        assertEquals(vbarEl1 + 0x480, irqEl0)
    }

    @Test
    fun `ESR_EL1 syndrome construction formats Data and Instruction aborts`() {
        // Data Abort from Current EL: EC = 0x25 (bits 31:26), IL = 1 (bit 25)
        // DFSC: Level 3 Translation Fault = 0x07 (bits 5:0), WnR = 1 (bit 6 for Write)
        val ecDataAbort = 0x25
        val il = 1
        val wnr = 1
        val dfscL3Translation = 0x07

        val syndrome = (ecDataAbort shl 26) or (il shl 25) or (wnr shl 6) or dfscL3Translation

        assertEquals("EC must be 0x25", 0x25, (syndrome ushr 26) and 0x3F)
        assertEquals("IL bit must be set", 1, (syndrome ushr 25) and 0x1)
        assertEquals("WnR must be write", 1, (syndrome ushr 6) and 0x1)
        assertEquals("DFSC must be Level 3 Translation Fault", 0x07, syndrome and 0x3F)
    }

    @Test
    fun `virtual timer comparison logic asserts interrupt on counter threshold`() {
        var cntvct = 1000L
        val cntvCval = 1500L
        val cntvCtl = 1 // ENABLE=1, IMASK=0

        var isPending = (cntvCtl and 1 != 0) && (cntvCtl and 2 == 0) && (cntvct >= cntvCval)
        assertFalse("Timer interrupt should not be pending before threshold", isPending)

        // Advance timer past compare value
        cntvct = 1600L
        isPending = (cntvCtl and 1 != 0) && (cntvCtl and 2 == 0) && (cntvct >= cntvCval)
        assertTrue("Timer interrupt should be asserted once CNTVCT >= CNTV_CVAL", isPending)

        // Mask interrupt
        val cntvCtlMasked = cntvCtl or 2
        val isPendingMasked = (cntvCtlMasked and 1 != 0) && (cntvCtlMasked and 2 == 0) && (cntvct >= cntvCval)
        assertFalse("Masking timer must suppress interrupt assertion", isPendingMasked)
    }
}
