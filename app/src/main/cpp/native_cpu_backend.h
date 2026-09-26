#ifndef NATIVE_CPU_BACKEND_H
#define NATIVE_CPU_BACKEND_H

#include <cstdint>
#include <array>
#include <string>
#include <memory>
#include "native_arch.h"
#include "native_memory.h"
#include "native_devices.h"

enum class NativeCPUState {
    READY,
    RUNNING,
    PAUSED,
    HALTED,
    TRAP_FAULT
};

class NativeCPUBackend {
public:
    virtual ~NativeCPUBackend() = default;

    virtual void reset() = 0;
    virtual NativeCPUState step(NativeMemory& memory, NativeDeviceManager& devices) = 0;
    virtual uint64_t runCycles(NativeMemory& memory, NativeDeviceManager& devices, uint64_t maxCycles) = 0;

    virtual uint64_t getPC() const = 0;
    virtual void setPC(uint64_t val) = 0;

    virtual uint64_t getSP() const = 0;
    virtual void setSP(uint64_t val) = 0;

    virtual uint64_t getRegister(uint32_t index) const = 0;
    virtual void setRegister(uint32_t index, uint64_t value) = 0;
    virtual const std::array<uint64_t, 32>& getRegisters() const = 0;

    virtual NativeCPUState getState() const = 0;
    virtual void setState(NativeCPUState s) = 0;

    virtual GuestArchitecture getGuestArchitecture() const = 0;
    virtual NativeBackendType getBackendType() const = 0;
    virtual bool isHardwareAccelerated() const = 0;
    virtual std::string getBackendDescription() const = 0;
};

#endif // NATIVE_CPU_BACKEND_H
