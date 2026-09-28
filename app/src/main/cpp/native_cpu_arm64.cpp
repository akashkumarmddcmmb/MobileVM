#include "native_cpu_arm64.h"
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <cstdio>
#include <sstream>
#include <iomanip>

NativeCPUARM64::NativeCPUARM64() {
    reset();
}

void NativeCPUARM64::reset() {
    registers.fill(0);
    pc = 0x00080000ULL;
    sp_el0 = 0x000FFFF0ULL;
    sp_el1 = 0x000FFFF0ULL;
    
    // PSTATE: Start in EL1h (EL=1, SP=1), Interrupts masked (DAIF = 0b1111)
    // Bits: [31:28] NZCV, [9:6] DAIF=0xF (0x3C0), [3:2] EL=1 (0x4), [0] SPSel=1 (0x1) -> 0x3C5
    pstate = 0x000003C5;

    sctlr_el1 = 0x0000000030D00800ULL; // Standard reset value (MMU disabled initially)
    tcr_el1 = 0;
    ttbr0_el1 = 0;
    ttbr1_el1 = 0;
    mair_el1 = 0;
    vbar_el1 = 0;
    esr_el1 = 0;
    far_el1 = 0;
    elr_el1 = 0;
    spsr_el1 = 0;
    cpacr_el1 = 0;
    contextidr_el1 = 0;
    tpidr_el0 = 0;
    tpidrro_el0 = 0;
    tpidr_el1 = 0;

    cntfrq_el0 = 62500000ULL; // 62.5 MHz generic timer
    cntvct_el0 = 0;
    cntv_ctl_el0 = 0;
    cntv_cval_el0 = 0xFFFFFFFFFFFFFFFFULL;
    cntp_ctl_el0 = 0;
    cntp_cval_el0 = 0xFFFFFFFFFFFFFFFFULL;

    exclusiveAddress = 0;
    exclusiveActive = false;

    lastFaultPC = 0;
    lastFaultOpcode = 0;
    lastFaultReason.clear();
    state = NativeCPUState::READY;
}

uint32_t NativeCPUARM64::getCurrentEL() const {
    return (pstate >> 2) & 0x3;
}

void NativeCPUARM64::setExceptionLevel(uint32_t el) {
    pstate = (pstate & ~0x0C) | ((el & 0x3) << 2);
}

uint64_t NativeCPUARM64::getSP() const {
    uint32_t el = getCurrentEL();
    uint32_t spsel = pstate & 0x1;
    if (el == 0 || spsel == 0) {
        return sp_el0;
    }
    return sp_el1;
}

void NativeCPUARM64::setSP(uint64_t val) {
    uint32_t el = getCurrentEL();
    uint32_t spsel = pstate & 0x1;
    if (el == 0 || spsel == 0) {
        sp_el0 = val;
    } else {
        sp_el1 = val;
    }
}

uint64_t NativeCPUARM64::getRegister(uint32_t index) const {
    if (index < 31) return registers[index];
    return 0; // XZR (X31)
}

void NativeCPUARM64::setRegister(uint32_t index, uint64_t value) {
    if (index < 31) {
        registers[index] = value;
    }
}

bool NativeCPUARM64::evaluateCondition(uint32_t cond) const {
    bool n = (pstate & (1u << 31)) != 0;
    bool z = (pstate & (1u << 30)) != 0;
    bool c = (pstate & (1u << 29)) != 0;
    bool v = (pstate & (1u << 28)) != 0;

    switch (cond & 0xF) {
        case 0x0: return z;                 // EQ (Equal)
        case 0x1: return !z;                // NE (Not Equal)
        case 0x2: return c;                 // CS / HS (Carry Set / Unsigned Higher or Same)
        case 0x3: return !c;                // CC / LO (Carry Clear / Unsigned Lower)
        case 0x4: return n;                 // MI (Minus / Negative)
        case 0x5: return !n;                // PL (Plus / Positive or Zero)
        case 0x6: return v;                 // VS (Overflow)
        case 0x7: return !v;                // VC (No Overflow)
        case 0x8: return c && !z;           // HI (Unsigned Higher)
        case 0x9: return !c || z;           // LS (Unsigned Lower or Same)
        case 0xA: return n == v;            // GE (Signed Greater Than or Equal)
        case 0xB: return n != v;            // LT (Signed Less Than)
        case 0xC: return !z && (n == v);    // GT (Signed Greater Than)
        case 0xD: return z || (n != v);     // LE (Signed Less Than or Equal)
        case 0xE: return true;              // AL (Always)
        case 0xF: return true;              // NV (Always)
        default: return true;
    }
}

void NativeCPUARM64::updateTimer(uint64_t cycles, NativeDeviceManager& devices) {
    cntvct_el0 += cycles;

    // Check Virtual Timer
    bool vEnabled = (cntv_ctl_el0 & 1) != 0;
    bool vMasked = (cntv_ctl_el0 & 2) != 0;

    if (vEnabled && (cntvct_el0 >= cntv_cval_el0)) {
        cntv_ctl_el0 |= (1u << 2); // Set ISTATUS
        if (!vMasked) {
            devices.getGIC().setInterruptPending(NativeGIC::IRQ_VIRTUAL_TIMER, true);
        }
    } else {
        cntv_ctl_el0 &= ~(1u << 2); // Clear ISTATUS
        if (vMasked || !vEnabled) {
            devices.getGIC().setInterruptPending(NativeGIC::IRQ_VIRTUAL_TIMER, false);
        }
    }
}

bool NativeCPUARM64::translateAddress(
    uint64_t va,
    AccessType access,
    uint64_t& outPa,
    uint32_t& outFaultSyndrome,
    const NativeMemory& memory
) {
    // If MMU disabled in SCTLR_EL1 (bit 0 = 0), identity mapping
    if ((sctlr_el1 & 1) == 0) {
        outPa = va;
        return true;
    }

    // Determine Translation Table Base Register (TTBR0 vs TTBR1)
    bool isUpper = (va & 0x8000000000000000ULL) != 0;
    uint64_t ttbr = isUpper ? ttbr1_el1 : ttbr0_el1;
    uint32_t tsz = isUpper ? ((tcr_el1 >> 16) & 0x3F) : (tcr_el1 & 0x3F);
    if (tsz == 0) tsz = 16; // Default 48-bit address space (64 - 16 = 48)

    uint32_t curEL = getCurrentEL();
    uint32_t ec = (access == AccessType::EXECUTE) ? ((curEL == 0) ? 0x20 : 0x21)
                                                  : ((curEL == 0) ? 0x24 : 0x25);

    uint64_t tableBase = ttbr & 0x0000FFFFFFFFF000ULL;
    if (tableBase == 0 || !memory.isValidAddress(tableBase, 4096)) {
        outFaultSyndrome = (ec << 26) | (1u << 25) | (access == AccessType::WRITE ? (1u << 6) : 0) | 0x04; // Translation Fault Level 0
        return false;
    }

    // 4-level 4KB page table walking
    // Level 0 (bits 47:39), Level 1 (bits 38:30), Level 2 (bits 29:21), Level 3 (bits 20:12)
    uint64_t l0Idx = (va >> 39) & 0x1FFULL;
    uint64_t l0EntryAddr = tableBase + l0Idx * 8;
    if (!memory.isValidAddress(l0EntryAddr, 8)) {
        outFaultSyndrome = (ec << 26) | (1u << 25) | (access == AccessType::WRITE ? (1u << 6) : 0) | 0x04;
        return false;
    }
    uint64_t l0Desc = memory.read64(l0EntryAddr);
    if ((l0Desc & 1) == 0) { // Invalid Level 0
        outFaultSyndrome = (ec << 26) | (1u << 25) | (access == AccessType::WRITE ? (1u << 6) : 0) | 0x04;
        return false;
    }

    // Level 1
    uint64_t l1Table = l0Desc & 0x0000FFFFFFFFF000ULL;
    uint64_t l1Idx = (va >> 30) & 0x1FFULL;
    uint64_t l1EntryAddr = l1Table + l1Idx * 8;
    if (!memory.isValidAddress(l1EntryAddr, 8)) {
        outFaultSyndrome = (ec << 26) | (1u << 25) | (access == AccessType::WRITE ? (1u << 6) : 0) | 0x05;
        return false;
    }
    uint64_t l1Desc = memory.read64(l1EntryAddr);
    if ((l1Desc & 1) == 0) { // Invalid Level 1
        outFaultSyndrome = (ec << 26) | (1u << 25) | (access == AccessType::WRITE ? (1u << 6) : 0) | 0x05;
        return false;
    }
    if ((l1Desc & 2) == 0) { // 1GB Block mapping
        outPa = (l1Desc & 0x0000FFFFC0000000ULL) | (va & 0x3FFFFFFFULL);
        return true;
    }

    // Level 2
    uint64_t l2Table = l1Desc & 0x0000FFFFFFFFF000ULL;
    uint64_t l2Idx = (va >> 21) & 0x1FFULL;
    uint64_t l2EntryAddr = l2Table + l2Idx * 8;
    if (!memory.isValidAddress(l2EntryAddr, 8)) {
        outFaultSyndrome = (ec << 26) | (1u << 25) | (access == AccessType::WRITE ? (1u << 6) : 0) | 0x06;
        return false;
    }
    uint64_t l2Desc = memory.read64(l2EntryAddr);
    if ((l2Desc & 1) == 0) { // Invalid Level 2
        outFaultSyndrome = (ec << 26) | (1u << 25) | (access == AccessType::WRITE ? (1u << 6) : 0) | 0x06;
        return false;
    }
    if ((l2Desc & 2) == 0) { // 2MB Block mapping
        outPa = (l2Desc & 0x0000FFFFFFE00000ULL) | (va & 0x1FFFFFULL);
        return true;
    }

    // Level 3 (4KB Page)
    uint64_t l3Table = l2Desc & 0x0000FFFFFFFFF000ULL;
    uint64_t l3Idx = (va >> 12) & 0x1FFULL;
    uint64_t l3EntryAddr = l3Table + l3Idx * 8;
    if (!memory.isValidAddress(l3EntryAddr, 8)) {
        outFaultSyndrome = (ec << 26) | (1u << 25) | (access == AccessType::WRITE ? (1u << 6) : 0) | 0x07;
        return false;
    }
    uint64_t l3Desc = memory.read64(l3EntryAddr);
    if ((l3Desc & 1) == 0) { // Invalid Level 3 Page
        outFaultSyndrome = (ec << 26) | (1u << 25) | (access == AccessType::WRITE ? (1u << 6) : 0) | 0x07;
        return false;
    }

    // Permission checks: AP[2:1] bits [7:6]
    uint32_t ap = (l3Desc >> 6) & 0x3;
    bool isReadOnly = (ap & 2) != 0;
    bool isEl0Accessible = (ap & 1) != 0;

    if (curEL == 0 && !isEl0Accessible) {
        outFaultSyndrome = (ec << 26) | (1u << 25) | (access == AccessType::WRITE ? (1u << 6) : 0) | 0x0F; // Permission Fault Level 3
        return false;
    }
    if (access == AccessType::WRITE && isReadOnly) {
        outFaultSyndrome = (ec << 26) | (1u << 25) | (1u << 6) | 0x0F; // Permission Fault Level 3
        return false;
    }

    outPa = (l3Desc & 0x0000FFFFFFFFF000ULL) | (va & 0x0FFFULL);
    return true;
}

void NativeCPUARM64::takeException(ExceptionType type, uint32_t syndrome, uint64_t farVal) {
    uint32_t curEL = getCurrentEL();
    uint32_t spsel = pstate & 1;

    uint64_t vectorOffset = 0;
    if (curEL == 0) {
        // Exception from Lower EL (AArch64)
        switch (type) {
            case ExceptionType::SYNC:   vectorOffset = 0x400; break;
            case ExceptionType::IRQ:    vectorOffset = 0x480; break;
            case ExceptionType::FIQ:    vectorOffset = 0x500; break;
            case ExceptionType::SERROR: vectorOffset = 0x580; break;
        }
    } else {
        // Exception from Current EL
        if (spsel == 0) {
            switch (type) {
                case ExceptionType::SYNC:   vectorOffset = 0x000; break;
                case ExceptionType::IRQ:    vectorOffset = 0x080; break;
                case ExceptionType::FIQ:    vectorOffset = 0x100; break;
                case ExceptionType::SERROR: vectorOffset = 0x180; break;
            }
        } else {
            switch (type) {
                case ExceptionType::SYNC:   vectorOffset = 0x200; break;
                case ExceptionType::IRQ:    vectorOffset = 0x280; break;
                case ExceptionType::FIQ:    vectorOffset = 0x300; break;
                case ExceptionType::SERROR: vectorOffset = 0x380; break;
            }
        }
    }

    elr_el1 = pc;
    spsr_el1 = pstate;
    esr_el1 = syndrome;
    if (type == ExceptionType::SYNC) {
        far_el1 = farVal;
    }

    // Transition to EL1, SP_EL1, mask DAIF interrupts
    pstate = (pstate & ~0x0C) | (1 << 2); // Set EL = 1
    pstate |= 1;                          // Set SPSel = 1 (SP_EL1)
    pstate |= 0x3C0;                      // Mask D, A, I, F (bits 9:6)

    pc = (vbar_el1 & ~0x7FFULL) + vectorOffset;
}

void NativeCPUARM64::executeERET() {
    pc = elr_el1;
    pstate = static_cast<uint32_t>(spsr_el1);
}

bool NativeCPUARM64::readMemory8(uint64_t va, uint8_t& outVal, NativeMemory& memory, NativeDeviceManager& devices) {
    uint64_t pa = 0;
    uint32_t fault = 0;
    if (!translateAddress(va, AccessType::READ, pa, fault, memory)) {
        takeException(ExceptionType::SYNC, fault, va);
        return false;
    }
    if (devices.isMMIOAddress(pa)) {
        outVal = devices.handleMMIORead8(pa);
    } else {
        outVal = memory.read8(pa);
    }
    return true;
}

bool NativeCPUARM64::readMemory16(uint64_t va, uint16_t& outVal, NativeMemory& memory, NativeDeviceManager& devices) {
    uint8_t b0 = 0, b1 = 0;
    if (!readMemory8(va, b0, memory, devices) || !readMemory8(va + 1, b1, memory, devices)) return false;
    outVal = static_cast<uint16_t>(b0) | (static_cast<uint16_t>(b1) << 8);
    return true;
}

bool NativeCPUARM64::readMemory32(uint64_t va, uint32_t& outVal, NativeMemory& memory, NativeDeviceManager& devices) {
    uint64_t pa = 0;
    uint32_t fault = 0;
    if (!translateAddress(va, AccessType::READ, pa, fault, memory)) {
        takeException(ExceptionType::SYNC, fault, va);
        return false;
    }
    if (devices.isMMIOAddress(pa)) {
        outVal = devices.handleMMIORead32(pa);
    } else {
        outVal = memory.read32(pa);
    }
    return true;
}

bool NativeCPUARM64::readMemory64(uint64_t va, uint64_t& outVal, NativeMemory& memory, NativeDeviceManager& devices) {
    uint32_t low = 0, high = 0;
    if (!readMemory32(va, low, memory, devices) || !readMemory32(va + 4, high, memory, devices)) return false;
    outVal = (static_cast<uint64_t>(high) << 32) | static_cast<uint64_t>(low);
    return true;
}

bool NativeCPUARM64::writeMemory8(uint64_t va, uint8_t val, NativeMemory& memory, NativeDeviceManager& devices) {
    uint64_t pa = 0;
    uint32_t fault = 0;
    if (!translateAddress(va, AccessType::WRITE, pa, fault, memory)) {
        takeException(ExceptionType::SYNC, fault, va);
        return false;
    }
    if (!devices.handleMMIOWrite8(pa, val)) {
        memory.write8(pa, val);
    }
    return true;
}

bool NativeCPUARM64::writeMemory16(uint64_t va, uint16_t val, NativeMemory& memory, NativeDeviceManager& devices) {
    return writeMemory8(va, static_cast<uint8_t>(val & 0xFF), memory, devices) &&
           writeMemory8(va + 1, static_cast<uint8_t>((val >> 8) & 0xFF), memory, devices);
}

bool NativeCPUARM64::writeMemory32(uint64_t va, uint32_t val, NativeMemory& memory, NativeDeviceManager& devices) {
    uint64_t pa = 0;
    uint32_t fault = 0;
    if (!translateAddress(va, AccessType::WRITE, pa, fault, memory)) {
        takeException(ExceptionType::SYNC, fault, va);
        return false;
    }
    if (!devices.handleMMIOWrite32(pa, val, &memory)) {
        memory.write32(pa, val);
    }
    return true;
}

bool NativeCPUARM64::writeMemory64(uint64_t va, uint64_t val, NativeMemory& memory, NativeDeviceManager& devices) {
    uint32_t low = static_cast<uint32_t>(val & 0xFFFFFFFFULL);
    uint32_t high = static_cast<uint32_t>(val >> 32);
    return writeMemory32(va, low, memory, devices) && writeMemory32(va + 4, high, memory, devices);
}

bool NativeCPUARM64::handleMRS(uint32_t inst, uint32_t rt) {
    // MRS Xd, <system_register>
    // inst encoding: [31:20] = 0xD53, [19:5] = op0:op1:CRn:CRm:op2, [4:0] = Rt
    uint32_t sysReg = (inst >> 5) & 0x7FFF;
    uint64_t val = 0;

    switch (sysReg) {
        case 0x5E08: val = static_cast<uint64_t>(pstate); break;       // NZCV (3,3,4,2,0)
        case 0x5E09: val = (pstate >> 6) & 0xF; break;                  // DAIF (3,3,4,2,1)
        case 0x4210: val = static_cast<uint64_t>(getCurrentEL() << 2); break; // CurrentEL (3,0,4,2,2)
        case 0x4200: val = pstate & 1; break;                           // SPSel (3,0,4,2,0)
        case 0x4080: val = sctlr_el1; break;                           // SCTLR_EL1 (3,0,1,0,0)
        case 0x4082: val = cpacr_el1; break;                           // CPACR_EL1 (3,0,1,0,2)
        case 0x4102: val = tcr_el1; break;                             // TCR_EL1 (3,0,2,0,2)
        case 0x4100: val = ttbr0_el1; break;                           // TTBR0_EL1 (3,0,2,0,0)
        case 0x4101: val = ttbr1_el1; break;                           // TTBR1_EL1 (3,0,2,0,1)
        case 0x4510: val = mair_el1; break;                            // MAIR_EL1 (3,0,10,2,0)
        case 0x4600: val = vbar_el1; break;                            // VBAR_EL1 (3,0,12,0,0)
        case 0x42A0: val = esr_el1; break;                             // ESR_EL1 (3,0,5,2,0)
        case 0x4300: val = far_el1; break;                             // FAR_EL1 (3,0,6,0,0)
        case 0x4202: val = elr_el1; break;                             // ELR_EL1 (3,0,4,0,1)
        case 0x4201: val = spsr_el1; break;                            // SPSR_EL1 (3,0,4,0,0)
        case 0x4681: val = contextidr_el1; break;                      // CONTEXTIDR_EL1 (3,0,13,0,1)
        case 0x5E82: val = tpidr_el0; break;                           // TPIDR_EL0 (3,3,13,0,2)
        case 0x5E83: val = tpidrro_el0; break;                         // TPIDRRO_EL0 (3,3,13,0,3)
        case 0x4684: val = tpidr_el1; break;                           // TPIDR_EL1 (3,0,13,0,4)
        case 0x4000: val = 0x410FD034ULL; break;                       // MIDR_EL1 (Cortex-A53)
        case 0x4005: val = 0x80000000ULL; break;                       // MPIDR_EL1 (Aff0 = 0)
        case 0x4020: val = 0x0000000000001111ULL; break;               // ID_AA64PFR0_EL1 (EL0-EL3 64-bit)
        case 0x4028: val = 0x0000000010305106ULL; break;               // ID_AA64ISAR0_EL1 (AES, SHA, CRC32)
        case 0x4030: val = 0x0000000000001124ULL; break;               // ID_AA64MMFR0_EL1 (4KB/64KB 48-bit PA)
        case 0x5F00: val = cntfrq_el0; break;                          // CNTFRQ_EL0 (3,3,14,0,0)
        case 0x5F01: val = cntvct_el0; break;                          // CNTPCT_EL0
        case 0x5F02: val = cntvct_el0; break;                          // CNTVCT_EL0
        case 0x5F19: val = cntv_ctl_el0; break;                        // CNTV_CTL_EL0
        case 0x5F1A: val = cntv_cval_el0; break;                       // CNTV_CVAL_EL0
        case 0x5F18: val = cntv_cval_el0 - cntvct_el0; break;          // CNTV_TVAL_EL0
        default:
            val = 0;
            break;
    }

    setRegister(rt, val);
    return true;
}

bool NativeCPUARM64::handleMSR(uint32_t inst, uint32_t rt) {
    uint32_t sysReg = (inst >> 5) & 0x7FFF;
    uint64_t val = getRegister(rt);

    switch (sysReg) {
        case 0x5E08: pstate = (pstate & 0x0FFFFFFF) | (static_cast<uint32_t>(val) & 0xF0000000); break; // NZCV
        case 0x5E09: pstate = (pstate & ~0x3C0) | ((static_cast<uint32_t>(val) & 0xF) << 6); break;     // DAIF
        case 0x4200: pstate = (pstate & ~1) | (static_cast<uint32_t>(val) & 1); break;                  // SPSel
        case 0x4080: sctlr_el1 = val; break;                                                            // SCTLR_EL1
        case 0x4082: cpacr_el1 = val; break;                                                            // CPACR_EL1
        case 0x4102: tcr_el1 = val; break;                                                              // TCR_EL1
        case 0x4100: ttbr0_el1 = val; break;                                                            // TTBR0_EL1
        case 0x4101: ttbr1_el1 = val; break;                                                            // TTBR1_EL1
        case 0x4510: mair_el1 = val; break;                                                             // MAIR_EL1
        case 0x4600: vbar_el1 = val; break;                                                             // VBAR_EL1
        case 0x42A0: esr_el1 = val; break;                                                              // ESR_EL1
        case 0x4300: far_el1 = val; break;                                                              // FAR_EL1
        case 0x4202: elr_el1 = val; break;                                                              // ELR_EL1
        case 0x4201: spsr_el1 = val; break;                                                             // SPSR_EL1
        case 0x4681: contextidr_el1 = val; break;                                                       // CONTEXTIDR_EL1
        case 0x5E82: tpidr_el0 = val; break;                                                            // TPIDR_EL0
        case 0x5E83: tpidrro_el0 = val; break;                                                          // TPIDRRO_EL0
        case 0x4684: tpidr_el1 = val; break;                                                            // TPIDR_EL1
        case 0x5F00: cntfrq_el0 = val; break;                                                           // CNTFRQ_EL0
        case 0x5F19: cntv_ctl_el0 = static_cast<uint32_t>(val & 0x7); break;                            // CNTV_CTL_EL0
        case 0x5F1A: cntv_cval_el0 = val; break;                                                        // CNTV_CVAL_EL0
        case 0x5F18: {                                                                                  // CNTV_TVAL_EL0
            int64_t sval = static_cast<int64_t>(static_cast<int32_t>(val));
            cntv_cval_el0 = cntvct_el0 + sval;
            break;
        }
        default:
            break;
    }
    return true;
}

void NativeCPUARM64::reportUnsupportedInstruction(uint32_t inst, NativeDeviceManager& devices) {
    lastFaultPC = pc;
    lastFaultOpcode = inst;

    char buf[512];
    std::snprintf(buf, sizeof(buf),
        "\r\n=== ARM64 CPU TRAP: Unsupported Instruction ===\r\n"
        "PC: 0x%016llX  Opcode: 0x%08X  EL: %u  SP: 0x%016llX\r\n"
        "PSTATE: 0x%08X (NZCV: %c%c%c%c, DAIF: %u%u%u%u)\r\n"
        "X0: 0x%016llX  X1: 0x%016llX  X2: 0x%016llX  X3: 0x%016llX\r\n"
        "X4: 0x%016llX  X5: 0x%016llX  X6: 0x%016llX  X7: 0x%016llX\r\n"
        "X28:0x%016llX  FP: 0x%016llX  LR: 0x%016llX\r\n"
        "SCTLR: 0x%016llX  TCR: 0x%016llX  TTBR0: 0x%016llX\r\n"
        "VBAR:  0x%016llX  ESR: 0x%016llX  FAR:   0x%016llX\r\n",
        static_cast<unsigned long long>(pc), inst, getCurrentEL(),
        static_cast<unsigned long long>(getSP()),
        pstate,
        (pstate & (1u << 31)) ? 'N' : 'n',
        (pstate & (1u << 30)) ? 'Z' : 'z',
        (pstate & (1u << 29)) ? 'C' : 'c',
        (pstate & (1u << 28)) ? 'V' : 'v',
        (pstate >> 9) & 1, (pstate >> 8) & 1, (pstate >> 7) & 1, (pstate >> 6) & 1,
        static_cast<unsigned long long>(getRegister(0)), static_cast<unsigned long long>(getRegister(1)),
        static_cast<unsigned long long>(getRegister(2)), static_cast<unsigned long long>(getRegister(3)),
        static_cast<unsigned long long>(getRegister(4)), static_cast<unsigned long long>(getRegister(5)),
        static_cast<unsigned long long>(getRegister(6)), static_cast<unsigned long long>(getRegister(7)),
        static_cast<unsigned long long>(getRegister(28)), static_cast<unsigned long long>(getRegister(29)),
        static_cast<unsigned long long>(getRegister(30)),
        static_cast<unsigned long long>(sctlr_el1), static_cast<unsigned long long>(tcr_el1),
        static_cast<unsigned long long>(ttbr0_el1), static_cast<unsigned long long>(vbar_el1),
        static_cast<unsigned long long>(esr_el1), static_cast<unsigned long long>(far_el1)
    );

    lastFaultReason = buf;
    for (int i = 0; buf[i] != '\0'; ++i) {
        devices.getUART().writeByte(static_cast<uint8_t>(buf[i]));
    }
    state = NativeCPUState::TRAP_FAULT;
    devices.handleMMIOWrite8(0x08000000ULL, 0x03);
}

NativeCPUState NativeCPUARM64::step(NativeMemory& memory, NativeDeviceManager& devices) {
    if (state == NativeCPUState::HALTED || state == NativeCPUState::TRAP_FAULT) {
        return state;
    }

    // Advance generic timer
    updateTimer(1, devices);

    // Check for GIC IRQ injection if IRQ unmasked (PSTATE.I == 0, bit 7)
    if ((pstate & (1u << 7)) == 0 && devices.getGIC().hasPendingIRQ()) {
        takeException(ExceptionType::IRQ, 0, 0);
        return state;
    }

    // Fetch 32-bit instruction at PC with MMU address translation
    uint64_t pa = 0;
    uint32_t fault = 0;
    if (!translateAddress(pc, AccessType::EXECUTE, pa, fault, memory)) {
        takeException(ExceptionType::SYNC, fault, pc);
        return state;
    }

    if (!memory.isValidAddress(pa, 4)) {
        reportUnsupportedInstruction(0, devices);
        return state;
    }

    uint32_t inst = memory.read32(pa);

    // --- Branch & Exception Return ---
    // HLT #0 (0xD4400000)
    if (inst == 0xD4400000) {
        state = NativeCPUState::HALTED;
        devices.handleMMIOWrite8(0x08000000ULL, 0x02);
        return state;
    }

    // ERET (0xD69F03E0)
    if (inst == 0xD69F03E0) {
        executeERET();
        return state;
    }

    // WFI / YIELD / NOP / WFE
    if (inst == 0xD503207F || inst == 0xD503205F || inst == 0xD503203F || inst == 0xD503201F) {
        pc += 4;
        return state;
    }

    // Barriers: ISB (0xD5033FDF), DSB (0xD503309F..), DMB
    if ((inst & 0xFFFFF01F) == 0xD503301F) {
        pc += 4;
        return state;
    }

    // TLBI / IC / DC instructions (System cache/TLB invalidations -> treat as successful sync)
    if ((inst & 0xFFE00000) == 0xD5080000 || (inst & 0xFFE00000) == 0xD50B0000) {
        pc += 4;
        return state;
    }

    // MSR DAIFSet, #imm4 (0xD50340DF)
    if ((inst & 0xFFFFF0DF) == 0xD50340DF) {
        uint32_t imm4 = (inst >> 8) & 0xF;
        pstate |= (imm4 << 6);
        pc += 4;
        return state;
    }

    // MSR DAIFClr, #imm4 (0xD50340FF)
    if ((inst & 0xFFFFF0DF) == 0xD50340FF) {
        uint32_t imm4 = (inst >> 8) & 0xF;
        pstate &= ~(imm4 << 6);
        pc += 4;
        return state;
    }

    // MSR SPSel, #imm (0xD50040BF)
    if ((inst & 0xFFFFFFDF) == 0xD50040BF) {
        uint32_t imm1 = (inst >> 5) & 1;
        pstate = (pstate & ~1) | imm1;
        pc += 4;
        return state;
    }

    // MRS Xd, sysreg (0xD5300000)
    if ((inst & 0xFFD00000) == 0xD5300000) {
        uint32_t rd = inst & 0x1F;
        handleMRS(inst, rd);
        pc += 4;
        return state;
    }

    // MSR sysreg, Xn (0xD5100000)
    if ((inst & 0xFFD00000) == 0xD5100000) {
        uint32_t rn = inst & 0x1F;
        handleMSR(inst, rn);
        pc += 4;
        return state;
    }

    // SVC / HVC / SMC
    if ((inst & 0xFFE0001F) == 0xD4000001) { // SVC
        uint32_t imm16 = (inst >> 5) & 0xFFFF;
        uint32_t syndrome = (0x15 << 26) | (1u << 25) | imm16;
        takeException(ExceptionType::SYNC, syndrome, 0);
        return state;
    }

    // B unconditional branch (0x14000000)
    if ((inst & 0xFC000000) == 0x14000000) {
        int32_t imm26 = inst & 0x3FFFFFF;
        if (imm26 & 0x2000000) imm26 |= ~0x3FFFFFF;
        pc += (imm26 * 4);
        return state;
    }

    // BL branch with link (0x94000000)
    if ((inst & 0xFC000000) == 0x94000000) {
        int32_t imm26 = inst & 0x3FFFFFF;
        if (imm26 & 0x2000000) imm26 |= ~0x3FFFFFF;
        setRegister(30, pc + 4);
        pc += (imm26 * 4);
        return state;
    }

    // BR (0xD61F0000) / BLR (0xD63F0000) / RET (0xD65F0000)
    if ((inst & 0xFFFFFC1F) == 0xD61F0000) { // BR
        uint32_t rn = (inst >> 5) & 0x1F;
        pc = getRegister(rn);
        return state;
    }
    if ((inst & 0xFFFFFC1F) == 0xD63F0000) { // BLR
        uint32_t rn = (inst >> 5) & 0x1F;
        uint64_t target = getRegister(rn);
        setRegister(30, pc + 4);
        pc = target;
        return state;
    }
    if ((inst & 0xFFFFFC1F) == 0xD65F0000) { // RET
        uint32_t rn = (inst >> 5) & 0x1F;
        pc = (rn == 0) ? getRegister(30) : getRegister(rn);
        return state;
    }

    // B.cond conditional branch (0x54000000)
    if ((inst & 0xFF000010) == 0x54000000) {
        uint32_t cond = inst & 0xF;
        int32_t imm19 = (inst >> 5) & 0x7FFFF;
        if (imm19 & 0x40000) imm19 |= ~0x7FFFF;
        if (evaluateCondition(cond)) {
            pc += (imm19 * 4);
        } else {
            pc += 4;
        }
        return state;
    }

    // CBZ / CBNZ (0x34000000 / 0x35000000 / 0xB4000000 / 0xB5000000)
    if ((inst & 0x7E000000) == 0x34000000) {
        bool is64 = (inst & (1u << 31)) != 0;
        bool isCbnz = (inst & (1u << 24)) != 0;
        uint32_t rt = inst & 0x1F;
        int32_t imm19 = (inst >> 5) & 0x7FFFF;
        if (imm19 & 0x40000) imm19 |= ~0x7FFFF;
        uint64_t val = is64 ? getRegister(rt) : (getRegister(rt) & 0xFFFFFFFFULL);
        bool match = isCbnz ? (val != 0) : (val == 0);
        if (match) {
            pc += (imm19 * 4);
        } else {
            pc += 4;
        }
        return state;
    }

    // TBZ / TBNZ (0x36000000 / 0x37000000)
    if ((inst & 0x7E000000) == 0x36000000) {
        bool isTbnz = (inst & (1u << 24)) != 0;
        uint32_t bitPos = ((inst >> 19) & 0x1F) | (((inst >> 31) & 1) << 5);
        uint32_t rt = inst & 0x1F;
        int32_t imm14 = (inst >> 5) & 0x3FFF;
        if (imm14 & 0x2000) imm14 |= ~0x3FFF;
        bool bitSet = ((getRegister(rt) >> bitPos) & 1) != 0;
        bool branch = isTbnz ? bitSet : !bitSet;
        if (branch) {
            pc += (imm14 * 4);
        } else {
            pc += 4;
        }
        return state;
    }

    // ADR / ADRP (0x10000000 / 0x90000000)
    if ((inst & 0x9F000000) == 0x10000000) { // ADR
        uint32_t rd = inst & 0x1F;
        uint64_t immlo = (inst >> 29) & 0x3;
        uint64_t immhi = (inst >> 5) & 0x7FFFF;
        int64_t imm21 = static_cast<int64_t>((immhi << 2) | immlo);
        if (imm21 & 0x100000) imm21 |= ~0x1FFFFFLL;
        setRegister(rd, pc + imm21);
        pc += 4;
        return state;
    }
    if ((inst & 0x9F000000) == 0x90000000) { // ADRP
        uint32_t rd = inst & 0x1F;
        uint64_t immlo = (inst >> 29) & 0x3;
        uint64_t immhi = (inst >> 5) & 0x7FFFF;
        int64_t imm21 = static_cast<int64_t>((immhi << 2) | immlo);
        if (imm21 & 0x100000) imm21 |= ~0x1FFFFFLL;
        uint64_t base = pc & ~0xFFFULL;
        setRegister(rd, base + (imm21 << 12));
        pc += 4;
        return state;
    }

    // MOVZ / MOVN / MOVK (0x12800000 / 0x52800000 / 0x72800000 / 0x92800000 / 0xD2800000 / 0xF2800000)
    if ((inst & 0x7F800000) == 0x52800000) { // MOVZ
        bool is64 = (inst & (1u << 31)) != 0;
        uint32_t rd = inst & 0x1F;
        uint64_t imm16 = (inst >> 5) & 0xFFFF;
        uint32_t hw = (inst >> 21) & 0x3;
        uint64_t res = imm16 << (hw * 16);
        setRegister(rd, is64 ? res : (res & 0xFFFFFFFFULL));
        pc += 4;
        return state;
    }
    if ((inst & 0x7F800000) == 0x12800000) { // MOVN
        bool is64 = (inst & (1u << 31)) != 0;
        uint32_t rd = inst & 0x1F;
        uint64_t imm16 = (inst >> 5) & 0xFFFF;
        uint32_t hw = (inst >> 21) & 0x3;
        uint64_t res = ~(imm16 << (hw * 16));
        setRegister(rd, is64 ? res : (res & 0xFFFFFFFFULL));
        pc += 4;
        return state;
    }
    if ((inst & 0x7F800000) == 0x72800000) { // MOVK
        bool is64 = (inst & (1u << 31)) != 0;
        uint32_t rd = inst & 0x1F;
        uint64_t imm16 = (inst >> 5) & 0xFFFF;
        uint32_t hw = (inst >> 21) & 0x3;
        uint64_t mask = ~(0xFFFFULL << (hw * 16));
        uint64_t cur = getRegister(rd);
        uint64_t res = (cur & mask) | (imm16 << (hw * 16));
        setRegister(rd, is64 ? res : (res & 0xFFFFFFFFULL));
        pc += 4;
        return state;
    }

    // ADD / ADDS / SUB / SUBS immediate (0x11000000 / 0x31000000 / 0x51000000 / 0x71000000 / 0x91000000 / 0xB1000000 / 0xD1000000 / 0xF1000000)
    if ((inst & 0x1F000000) == 0x11000000) {
        bool is64 = (inst & (1u << 31)) != 0;
        bool isSub = (inst & (1u << 30)) != 0;
        bool setFlags = (inst & (1u << 29)) != 0;
        uint32_t rd = inst & 0x1F;
        uint32_t rn = (inst >> 5) & 0x1F;
        uint64_t imm12 = (inst >> 10) & 0xFFF;
        if (inst & (1u << 22)) imm12 <<= 12; // Shift by 12

        uint64_t opA = (rn == 31) ? getSP() : getRegister(rn);
        uint64_t res = isSub ? (opA - imm12) : (opA + imm12);

        if (setFlags) {
            // Update NZCV
            bool n = is64 ? ((res >> 63) & 1) : ((res >> 31) & 1);
            bool z = is64 ? (res == 0) : ((res & 0xFFFFFFFFULL) == 0);
            bool c = isSub ? (opA >= imm12) : (res < opA);
            bool v = isSub ? ((opA ^ imm12) & (opA ^ res) & (is64 ? (1ULL<<63) : (1ULL<<31))) != 0
                           : (~(opA ^ imm12) & (opA ^ res) & (is64 ? (1ULL<<63) : (1ULL<<31))) != 0;
            pstate = (pstate & 0x0FFFFFFF) | (n ? (1u<<31) : 0) | (z ? (1u<<30) : 0) | (c ? (1u<<29) : 0) | (v ? (1u<<28) : 0);
        }

        if (rd == 31 && !setFlags) {
            setSP(res);
        } else {
            setRegister(rd, is64 ? res : (res & 0xFFFFFFFFULL));
        }
        pc += 4;
        return state;
    }

    // ADD / SUB shifted register (0x0B000000 / 0x4B000000 / 0x8B000000 / 0xCB000000)
    if ((inst & 0x1F200000) == 0x0B000000) {
        bool is64 = (inst & (1u << 31)) != 0;
        bool isSub = (inst & (1u << 30)) != 0;
        bool setFlags = (inst & (1u << 29)) != 0;
        uint32_t shiftType = (inst >> 22) & 3;
        uint32_t rm = (inst >> 16) & 0x1F;
        uint32_t shiftAmount = (inst >> 10) & 0x3F;
        uint32_t rn = (inst >> 5) & 0x1F;
        uint32_t rd = inst & 0x1F;

        uint64_t opB = getRegister(rm);
        if (shiftType == 0) opB <<= shiftAmount;
        else if (shiftType == 1) opB >>= shiftAmount;
        else if (shiftType == 2) opB = static_cast<uint64_t>(static_cast<int64_t>(opB) >> shiftAmount);

        uint64_t opA = getRegister(rn);
        uint64_t res = isSub ? (opA - opB) : (opA + opB);

        if (setFlags) {
            bool n = is64 ? ((res >> 63) & 1) : ((res >> 31) & 1);
            bool z = is64 ? (res == 0) : ((res & 0xFFFFFFFFULL) == 0);
            bool c = isSub ? (opA >= opB) : (res < opA);
            bool v = isSub ? ((opA ^ opB) & (opA ^ res) & (is64 ? (1ULL<<63) : (1ULL<<31))) != 0
                           : (~(opA ^ opB) & (opA ^ res) & (is64 ? (1ULL<<63) : (1ULL<<31))) != 0;
            pstate = (pstate & 0x0FFFFFFF) | (n ? (1u<<31) : 0) | (z ? (1u<<30) : 0) | (c ? (1u<<29) : 0) | (v ? (1u<<28) : 0);
        }

        setRegister(rd, is64 ? res : (res & 0xFFFFFFFFULL));
        pc += 4;
        return state;
    }

    // Logical shifted register: AND, BIC, ORR, ORN, EOR, EON, ANDS, BICS (0x0A000000 / 0x8A000000 / 0xAA000000 / 0xCA000000 / 0xEA000000)
    if ((inst & 0x1F000000) == 0x0A000000) {
        bool is64 = (inst & (1u << 31)) != 0;
        uint32_t opc = (inst >> 29) & 0x3;
        bool invert = (inst & (1u << 21)) != 0;
        uint32_t rm = (inst >> 16) & 0x1F;
        uint32_t shiftAmount = (inst >> 10) & 0x3F;
        uint32_t rn = (inst >> 5) & 0x1F;
        uint32_t rd = inst & 0x1F;

        uint64_t opB = getRegister(rm);
        uint32_t shiftType = (inst >> 22) & 3;
        if (shiftType == 0) opB <<= shiftAmount;
        else if (shiftType == 1) opB >>= shiftAmount;
        else if (shiftType == 2) opB = static_cast<uint64_t>(static_cast<int64_t>(opB) >> shiftAmount);
        if (invert) opB = ~opB;

        uint64_t opA = getRegister(rn);
        uint64_t res = 0;
        if (opc == 0) res = opA & opB;        // AND / BIC
        else if (opc == 1) res = opA | opB;   // ORR / ORN
        else if (opc == 2) res = opA ^ opB;   // EOR / EON
        else if (opc == 3) res = opA & opB;   // ANDS / BICS

        if (opc == 3) { // ANDS / TST
            bool n = is64 ? ((res >> 63) & 1) : ((res >> 31) & 1);
            bool z = is64 ? (res == 0) : ((res & 0xFFFFFFFFULL) == 0);
            pstate = (pstate & 0x0FFFFFFF) | (n ? (1u<<31) : 0) | (z ? (1u<<30) : 0);
        }

        setRegister(rd, is64 ? res : (res & 0xFFFFFFFFULL));
        pc += 4;
        return state;
    }

    // Conditional Select: CSEL / CSINC / CSINV / CSNEG (0x1A800000 / 0x9A800000)
    if ((inst & 0x1FE00000) == 0x1A800000) {
        bool is64 = (inst & (1u << 31)) != 0;
        uint32_t rm = (inst >> 16) & 0x1F;
        uint32_t cond = (inst >> 12) & 0xF;
        uint32_t op = (inst >> 10) & 0x3;
        uint32_t rn = (inst >> 5) & 0x1F;
        uint32_t rd = inst & 0x1F;

        uint64_t res = 0;
        if (evaluateCondition(cond)) {
            res = getRegister(rn);
        } else {
            uint64_t valB = getRegister(rm);
            if (op == 0) res = valB;             // CSEL
            else if (op == 1) res = valB + 1;    // CSINC / CSET
            else if (op == 2) res = ~valB;       // CSINV / CSETM
            else if (op == 3) res = -valB;       // CSNEG
        }

        setRegister(rd, is64 ? res : (res & 0xFFFFFFFFULL));
        pc += 4;
        return state;
    }

    // Multiply / Divide: MADD, MSUB, SDIV, UDIV (0x1B000000 / 0x9B000000)
    if ((inst & 0x1F000000) == 0x1B000000) {
        bool is64 = (inst & (1u << 31)) != 0;
        uint32_t rm = (inst >> 16) & 0x1F;
        uint32_t ra = (inst >> 10) & 0x1F;
        uint32_t rn = (inst >> 5) & 0x1F;
        uint32_t rd = inst & 0x1F;
        bool isSub = (inst & (1u << 15)) != 0;

        uint64_t opA = getRegister(rn);
        uint64_t opB = getRegister(rm);
        uint64_t opC = getRegister(ra);

        uint64_t res = isSub ? (opC - (opA * opB)) : (opC + (opA * opB));
        setRegister(rd, is64 ? res : (res & 0xFFFFFFFFULL));
        pc += 4;
        return state;
    }

    // LDP / STP (Load / Store Pair) 64-bit and 32-bit (0x29000000 / 0x28000000 / 0xA9000000 / 0xA8000000)
    if ((inst & 0x7E400000) == 0x28000000 || (inst & 0x7E400000) == 0x29000000) {
        bool is64 = (inst & (1u << 31)) != 0;
        bool isLoad = (inst & (1u << 22)) != 0;
        uint32_t rt1 = inst & 0x1F;
        uint32_t rn = (inst >> 5) & 0x1F;
        uint32_t rt2 = (inst >> 10) & 0x1F;
        int32_t imm7 = (inst >> 15) & 0x7F;
        if (imm7 & 0x40) imm7 |= ~0x7F;

        uint32_t scale = is64 ? 8 : 4;
        int64_t offset = static_cast<int64_t>(imm7) * scale;
        uint64_t baseAddr = (rn == 31) ? getSP() : getRegister(rn);
        uint64_t effectiveAddr = baseAddr;

        // Post-indexed (0x28800000) vs Pre-indexed (0x29800000) vs Signed offset (0x29000000)
        uint32_t indexMode = (inst >> 23) & 3;
        if (indexMode == 3) effectiveAddr += offset; // Pre-indexed

        if (isLoad) {
            if (is64) {
                uint64_t v1 = 0, v2 = 0;
                if (!readMemory64(effectiveAddr, v1, memory, devices) ||
                    !readMemory64(effectiveAddr + 8, v2, memory, devices)) return state;
                setRegister(rt1, v1);
                setRegister(rt2, v2);
            } else {
                uint32_t v1 = 0, v2 = 0;
                if (!readMemory32(effectiveAddr, v1, memory, devices) ||
                    !readMemory32(effectiveAddr + 4, v2, memory, devices)) return state;
                setRegister(rt1, v1);
                setRegister(rt2, v2);
            }
        } else {
            if (is64) {
                if (!writeMemory64(effectiveAddr, getRegister(rt1), memory, devices) ||
                    !writeMemory64(effectiveAddr + 8, getRegister(rt2), memory, devices)) return state;
            } else {
                if (!writeMemory32(effectiveAddr, static_cast<uint32_t>(getRegister(rt1) & 0xFFFFFFFFULL), memory, devices) ||
                    !writeMemory32(effectiveAddr + 4, static_cast<uint32_t>(getRegister(rt2) & 0xFFFFFFFFULL), memory, devices)) return state;
            }
        }

        if (indexMode == 1) { // Post-indexed
            uint64_t newBase = baseAddr + offset;
            if (rn == 31) setSP(newBase); else setRegister(rn, newBase);
        } else if (indexMode == 3) { // Pre-indexed
            if (rn == 31) setSP(effectiveAddr); else setRegister(rn, effectiveAddr);
        }

        pc += 4;
        return state;
    }

    // STR / LDR (Single register load / store)
    // 0x38000000 to 0xF9C00000
    if ((inst & 0x3B000000) == 0x39000000 || (inst & 0x3B000000) == 0x38000000) {
        uint32_t size = (inst >> 30) & 3; // 0=8-bit, 1=16-bit, 2=32-bit, 3=64-bit
        bool isLoad = (inst & (1u << 22)) != 0;
        uint32_t rt = inst & 0x1F;
        uint32_t rn = (inst >> 5) & 0x1F;
        uint64_t baseAddr = (rn == 31) ? getSP() : getRegister(rn);
        uint64_t targetAddr = baseAddr;

        if ((inst & (1u << 24)) != 0) { // Unsigned immediate offset
            uint64_t imm12 = (inst >> 10) & 0xFFF;
            targetAddr += (imm12 << size);
        } else { // Register offset or Unscaled immediate
            int32_t imm9 = (inst >> 12) & 0x1FF;
            if (imm9 & 0x100) imm9 |= ~0x1FF;
            targetAddr += imm9;
        }

        if (isLoad) {
            if (size == 0) {
                uint8_t v = 0;
                if (!readMemory8(targetAddr, v, memory, devices)) return state;
                setRegister(rt, v);
            } else if (size == 1) {
                uint16_t v = 0;
                if (!readMemory16(targetAddr, v, memory, devices)) return state;
                setRegister(rt, v);
            } else if (size == 2) {
                uint32_t v = 0;
                if (!readMemory32(targetAddr, v, memory, devices)) return state;
                setRegister(rt, v);
            } else if (size == 3) {
                uint64_t v = 0;
                if (!readMemory64(targetAddr, v, memory, devices)) return state;
                setRegister(rt, v);
            }
        } else {
            if (size == 0) {
                if (!writeMemory8(targetAddr, static_cast<uint8_t>(getRegister(rt) & 0xFF), memory, devices)) return state;
            } else if (size == 1) {
                if (!writeMemory16(targetAddr, static_cast<uint16_t>(getRegister(rt) & 0xFFFF), memory, devices)) return state;
            } else if (size == 2) {
                if (!writeMemory32(targetAddr, static_cast<uint32_t>(getRegister(rt) & 0xFFFFFFFFULL), memory, devices)) return state;
            } else if (size == 3) {
                if (!writeMemory64(targetAddr, getRegister(rt), memory, devices)) return state;
            }
        }

        pc += 4;
        return state;
    }

    // Undefined / Unsupported instruction trap diagnostic
    reportUnsupportedInstruction(inst, devices);
    return state;
}

uint64_t NativeCPUARM64::runCycles(NativeMemory& memory, NativeDeviceManager& devices, uint64_t maxCycles) {
    state = NativeCPUState::RUNNING;
    uint64_t executed = 0;
    while (executed < maxCycles && state == NativeCPUState::RUNNING) {
        step(memory, devices);
        executed++;
    }
    return executed;
}
