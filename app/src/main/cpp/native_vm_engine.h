#ifndef NATIVE_VM_ENGINE_H
#define NATIVE_VM_ENGINE_H

#include <cstdint>
#include <string>
#include <memory>
#include <atomic>
#include <vector>
#include "native_memory.h"
#include "native_cpu_backend.h"
#include "native_cpu_factory.h"
#include "native_devices.h"
#include "native_arch.h"
#include "native_linux_boot.h"

enum class VMNativeState {
    CREATED = 0,
    STARTING = 1,
    RUNNING = 2,
    PAUSED = 3,
    STOPPING = 4,
    STOPPED = 5,
    ERROR = 6
};

class NativeVMEngine {
public:
    NativeVMEngine(
        size_t ramSizeMb,
        const std::string& diskPath,
        int numCores,
        GuestArchitecture guestArch,
        bool useHardwareVirt,
        const std::string& kernelPath = "",
        const std::string& initramfsPath = "",
        const std::string& cmdline = "",
        const std::string& consoleDev = ""
    );
    ~NativeVMEngine();

    bool configure();
    bool start();
    bool pause();
    bool resume();
    bool stop();
    bool reset();
    void destroy();

    int stepCycles(int maxCycles);

    VMNativeState getState() const;
    NativeCPUBackend& getCPU() { return *cpu; }
    NativeMemory& getMemory() { return memory; }
    NativeDeviceManager& getDevices() { return devices; }

    GuestArchitecture getGuestArchitecture() const { return guestArch; }
    bool isFallbackEmulation() const { return isFallback; }
    std::string getBackendStatus() const { return backendStatus; }
    std::string getBackendDescription() const { return cpu ? cpu->getBackendDescription() : "None"; }
    bool isHardwareAccelerated() const { return cpu ? cpu->isHardwareAccelerated() : false; }

    std::vector<uint8_t> fetchSerialTx();
    void writeSerialRx(uint8_t byte);
    const uint32_t* getFramebuffer() const;
    uint32_t getDisplayWidth() const;
    uint32_t getDisplayHeight() const;

private:
    size_t ramMb;
    std::string diskImagePath;
    int cores;
    GuestArchitecture guestArch;
    bool requestHardwareVirt;
    bool isFallback;
    std::string backendStatus;

    LinuxBootConfig bootConfig;

    NativeMemory memory;
    std::unique_ptr<NativeCPUBackend> cpu;
    NativeDeviceManager devices;

    std::atomic<VMNativeState> state;

    bool loadBootPayload();
};

#endif // NATIVE_VM_ENGINE_H
