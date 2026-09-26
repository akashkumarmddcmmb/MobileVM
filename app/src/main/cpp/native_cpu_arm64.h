#ifndef NATIVE_CPU_ARM64_H
#define NATIVE_CPU_ARM64_H

#include "native_cpu_backend.h"

/**
 * NativeCPUARM64: An authentic ARM64 instruction-level execution core.
 *
 * Implements a genuine execution engine for documented ARM64 instructions:
 * - Immediate Data Processing: MOVZ, MOVK, ADD (imm12), SUB (imm12)
 * - Register Data Processing: ADD (reg64), SUB (reg64), AND (reg64), ORR (reg64), EOR (reg64), MVN (reg64)
 * - Shifts: LSL (Logical Shift Left), LSR (Logical Shift Right), ASR (Arithmetic Shift Right) via immediate/register
 * - Memory Access: STRB, STR (32-bit & 64-bit), LDRB, LDR (32-bit & 64-bit) with register and immediate offset
 * - Branches: B (imm26), BL (imm26 with LR link), BR (reg64), RET (LR/reg), CBZ, CBNZ
 * - System & Exceptions: HLT #0 (halt), WFI / YIELD (pause/sleep), NOP, MRS/MSR (stubbed NZCV/TPIDR_EL0)
 *
 * Any instruction outside this verified execution subset strictly raises TRAP_FAULT
 * and writes to the fault MMIO address. It NEVER fakes success or guesses operands.
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

    uint64_t getSP() const override { return sp; }
    void setSP(uint64_t val) override { sp = val; }

    uint64_t getRegister(uint32_t index) const override;
    void setRegister(uint32_t index, uint64_t value) override;
    const std::array<uint64_t, 32>& getRegisters() const override { return registers; }

    NativeCPUState getState() const override { return state; }
    void setState(NativeCPUState s) override { state = s; }

    uint32_t getPState() const { return pstate; }
    void setPState(uint32_t flags) { pstate = flags; }

    GuestArchitecture getGuestArchitecture() const override { return GuestArchitecture::ARM64; }
    NativeBackendType getBackendType() const override { return NativeBackendType::ARM64_EMULATION; }
    bool isHardwareAccelerated() const override { return false; }
    std::string getBackendDescription() const override {
        return "ARM64 Software Emulation Core (Native C++ Interpreter)";
    }

private:
    std::array<uint64_t, 32> registers;
    uint64_t pc;
    uint64_t sp;
    uint32_t pstate; // NZCV condition flags (bits 31:28)
    uint64_t tpidr_el0; // Thread pointer ID register EL0
    NativeCPUState state;

    void handleStore8(uint64_t address, uint8_t value, NativeMemory& memory, NativeDeviceManager& devices);
    void handleStore32(uint64_t address, uint32_t value, NativeMemory& memory, NativeDeviceManager& devices);
    void handleStore64(uint64_t address, uint64_t value, NativeMemory& memory, NativeDeviceManager& devices);
    uint8_t handleLoad8(uint64_t address, NativeMemory& memory, NativeDeviceManager& devices);
    uint32_t handleLoad32(uint64_t address, NativeMemory& memory, NativeDeviceManager& devices);
    uint64_t handleLoad64(uint64_t address, NativeMemory& memory, NativeDeviceManager& devices);
};

#endif // NATIVE_CPU_ARM64_H
