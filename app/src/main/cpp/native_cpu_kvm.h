#ifndef NATIVE_CPU_KVM_H
#define NATIVE_CPU_KVM_H

#include "native_cpu_backend.h"
#include <string>

class NativeCPUKVM : public NativeCPUBackend {
public:
    NativeCPUKVM();
    ~NativeCPUKVM() override;

    static bool isAvailableOnHost();
    static std::string getAvailabilityReason();

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
    NativeBackendType getBackendType() const override { return NativeBackendType::ARM64_HARDWARE_VIRTUALIZATION; }
    bool isHardwareAccelerated() const override { return true; }
    std::string getBackendDescription() const override {
        return "ARM64 Hardware Virtualization (KVM/pKVM Hypervisor)";
    }

private:
    int kvmFd;
    int vmFd;
    int vcpuFd;
    std::array<uint64_t, 32> registers;
    uint64_t pc;
    uint64_t sp;
    NativeCPUState state;

    bool initKvmVcpu(NativeMemory& memory);
};

#endif // NATIVE_CPU_KVM_H
