#ifndef NATIVE_CPU_ARM64_H
#define NATIVE_CPU_ARM64_H

#include "native_cpu_backend.h"

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
    uint32_t pstate;
    NativeCPUState state;

    void handleStore8(uint64_t address, uint8_t value, NativeMemory& memory, NativeDeviceManager& devices);
    void handleStore32(uint64_t address, uint32_t value, NativeMemory& memory, NativeDeviceManager& devices);
    uint8_t handleLoad8(uint64_t address, NativeMemory& memory, NativeDeviceManager& devices);
    uint32_t handleLoad32(uint64_t address, NativeMemory& memory, NativeDeviceManager& devices);
};

#endif // NATIVE_CPU_ARM64_H
