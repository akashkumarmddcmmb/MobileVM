#ifndef NATIVE_CPU_ARM64_H
#define NATIVE_CPU_ARM64_H

#include <cstdint>
#include <array>
#include <string>
#include "native_cpu_backend.h"
#include "native_memory.h"
#include "native_devices.h"

enum class ExceptionType {
    SYNC = 0,
    IRQ = 1,
    FIQ = 2,
    SERROR = 3
};

enum class AccessType {
    READ = 0,
    WRITE = 1,
    EXECUTE = 2
};

/**
 * NativeCPUARM64: An authentic ARM64 (AArch64) execution core and software fallback emulator.
 *
 * Implements:
 * - EL0 / EL1 Exception Levels
 * - PSTATE (NZCV, DAIF, EL, SPsel)
 * - System Registers: SCTLR_EL1, TCR_EL1, TTBR0_EL1, TTBR1_EL1, MAIR_EL1,
 *   VBAR_EL1, ESR_EL1, FAR_EL1, ELR_EL1, SPSR_EL1, CurrentEL, SPSel, CPACR_EL1,
 *   CONTEXTIDR_EL1, TPIDR_EL0, TPIDRRO_EL0, TPIDR_EL1, MIDR_EL1, MPIDR_EL1,
 *   ID_AA64* registers, CNTFRQ_EL0, CNTPCT_EL0, CNTVCT_EL0, CNTV_CTL_EL0,
 *   CNTV_CVAL_EL0, CNTV_TVAL_EL0, CNTP_CTL_EL0, CNTP_CVAL_EL0, CNTP_TVAL_EL0.
 * - 4KB Page Table Walker & MMU translation / faults (Translation Faults, Permission Faults)
 * - Vector-based Exception Routing (Sync, IRQ, FIQ, SError) and ERET sequence
 * - GICv2 Hardware Interrupt injection & Generic Timer interrupt generation
 * - Comprehensive A64 Instruction Set decoding with detailed diagnostics on unsupported opcodes.
 */
class NativeCPUARM64 : public NativeCPUBackend {
public:
    NativeCPUARM64();
    ~NativeCPUARM64() override = default;

    void reset() override;
    NativeCPUState step(NativeMemory& memory, NativeDeviceManager& devices) override;
    uint64_t runCycles(NativeMemory& memory, NativeDeviceManager& devices, uint64_t maxCycles) override;

    uint64_t getPC() const override { return pc; }
    void setPC(uint64_t val) override { pc = val; }

    uint64_t getSP() const override;
    void setSP(uint64_t val) override;

    uint64_t getRegister(uint32_t index) const override;
    void setRegister(uint32_t index, uint64_t value) override;
    const std::array<uint64_t, 32>& getRegisters() const override { return registers; }

    NativeCPUState getState() const override { return state; }
    void setState(NativeCPUState s) override { state = s; }

    uint32_t getPState() const { return pstate; }
    void setPState(uint32_t flags) { pstate = flags; }

    uint32_t getCurrentEL() const;
    void setExceptionLevel(uint32_t el);

    GuestArchitecture getGuestArchitecture() const override { return GuestArchitecture::ARM64; }
    NativeBackendType getBackendType() const override { return NativeBackendType::ARM64_EMULATION; }
    bool isHardwareAccelerated() const override { return false; }
    std::string getBackendDescription() const override {
        return "ARM64 Software Emulation Core (AArch64 MMU + GIC + Timer)";
    }

    uint64_t getLastFaultPC() const { return lastFaultPC; }
    uint32_t getLastFaultOpcode() const { return lastFaultOpcode; }
    std::string getLastFaultReason() const { return lastFaultReason; }

    // System Register accessors for diagnostics and unit testing
    uint64_t getSCTLR_EL1() const { return sctlr_el1; }
    void setSCTLR_EL1(uint64_t v) { sctlr_el1 = v; }
    uint64_t getTCR_EL1() const { return tcr_el1; }
    void setTCR_EL1(uint64_t v) { tcr_el1 = v; }
    uint64_t getTTBR0_EL1() const { return ttbr0_el1; }
    void setTTBR0_EL1(uint64_t v) { ttbr0_el1 = v; }
    uint64_t getTTBR1_EL1() const { return ttbr1_el1; }
    void setTTBR1_EL1(uint64_t v) { ttbr1_el1 = v; }
    uint64_t getMAIR_EL1() const { return mair_el1; }
    void setMAIR_EL1(uint64_t v) { mair_el1 = v; }
    uint64_t getVBAR_EL1() const { return vbar_el1; }
    void setVBAR_EL1(uint64_t v) { vbar_el1 = v; }
    uint64_t getESR_EL1() const { return esr_el1; }
    void setESR_EL1(uint64_t v) { esr_el1 = v; }
    uint64_t getFAR_EL1() const { return far_el1; }
    void setFAR_EL1(uint64_t v) { far_el1 = v; }
    uint64_t getELR_EL1() const { return elr_el1; }
    void setELR_EL1(uint64_t v) { elr_el1 = v; }
    uint64_t getSPSR_EL1() const { return spsr_el1; }
    void setSPSR_EL1(uint64_t v) { spsr_el1 = v; }

    uint64_t getCNTVCT() const { return cntvct_el0; }
    void setCNTVCT(uint64_t v) { cntvct_el0 = v; }
    uint32_t getCNTV_CTL() const { return cntv_ctl_el0; }
    void setCNTV_CTL(uint32_t v) { cntv_ctl_el0 = v; }
    uint64_t getCNTV_CVAL() const { return cntv_cval_el0; }
    void setCNTV_CVAL(uint64_t v) { cntv_cval_el0 = v; }

    // Exception & MMU methods for tests and internal execution
    void takeException(ExceptionType type, uint32_t syndrome, uint64_t farVal);
    void executeERET();
    bool translateAddress(uint64_t va, AccessType access, uint64_t& outPa, uint32_t& outFaultSyndrome, const NativeMemory& memory);

private:
    std::array<uint64_t, 32> registers; // X0..X30 (X31 = XZR)
    uint64_t pc;
    uint64_t sp_el0;
    uint64_t sp_el1;
    uint32_t pstate; // NZCV (31:28), DAIF (9:6), EL (3:2), SPSel (0)

    // System Registers
    uint64_t sctlr_el1;
    uint64_t tcr_el1;
    uint64_t ttbr0_el1;
    uint64_t ttbr1_el1;
    uint64_t mair_el1;
    uint64_t vbar_el1;
    uint64_t esr_el1;
    uint64_t far_el1;
    uint64_t elr_el1;
    uint64_t spsr_el1;
    uint64_t cpacr_el1;
    uint64_t contextidr_el1;
    uint64_t tpidr_el0;
    uint64_t tpidrro_el0;
    uint64_t tpidr_el1;

    // Generic Timer Registers
    uint64_t cntfrq_el0;
    uint64_t cntvct_el0;
    uint32_t cntv_ctl_el0;
    uint64_t cntv_cval_el0;
    uint32_t cntp_ctl_el0;
    uint64_t cntp_cval_el0;

    // Exclusive monitor address
    uint64_t exclusiveAddress;
    bool exclusiveActive;

    NativeCPUState state;

    uint64_t lastFaultPC;
    uint32_t lastFaultOpcode;
    std::string lastFaultReason;

    // System Register MRS/MSR handlers
    bool handleMRS(uint32_t inst, uint32_t rt);
    bool handleMSR(uint32_t inst, uint32_t rt);
    bool handleSystemInstruction(uint32_t inst);

    // Memory access with MMU translation
    bool readMemory8(uint64_t va, uint8_t& outVal, NativeMemory& memory, NativeDeviceManager& devices);
    bool readMemory16(uint64_t va, uint16_t& outVal, NativeMemory& memory, NativeDeviceManager& devices);
    bool readMemory32(uint64_t va, uint32_t& outVal, NativeMemory& memory, NativeDeviceManager& devices);
    bool readMemory64(uint64_t va, uint64_t& outVal, NativeMemory& memory, NativeDeviceManager& devices);

    bool writeMemory8(uint64_t va, uint8_t val, NativeMemory& memory, NativeDeviceManager& devices);
    bool writeMemory16(uint64_t va, uint16_t val, NativeMemory& memory, NativeDeviceManager& devices);
    bool writeMemory32(uint64_t va, uint32_t val, NativeMemory& memory, NativeDeviceManager& devices);
    bool writeMemory64(uint64_t va, uint64_t val, NativeMemory& memory, NativeDeviceManager& devices);

    void updateTimer(uint64_t cycles, NativeDeviceManager& devices);
    bool evaluateCondition(uint32_t cond) const;
    void reportUnsupportedInstruction(uint32_t inst, NativeDeviceManager& devices);
};

#endif // NATIVE_CPU_ARM64_H
