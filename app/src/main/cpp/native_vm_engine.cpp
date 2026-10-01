#include "native_vm_engine.h"
#include <vector>
#include <cstring>
#include <sstream>
#include <iomanip>
#include <android/log.h>

#define VM_LOG_TAG "MobileVM"
#define NLOGI(...) __android_log_print(ANDROID_LOG_INFO, VM_LOG_TAG, __VA_ARGS__)
#define NLOGE(...) __android_log_print(ANDROID_LOG_ERROR, VM_LOG_TAG, __VA_ARGS__)

NativeVMEngine::NativeVMEngine(
    size_t ramSizeMb,
    const std::string& diskPath,
    int numCores,
    GuestArchitecture gArch,
    bool useHardwareVirt,
    const std::string& kernelPath,
    const std::string& initramfsPath,
    const std::string& cmdline,
    const std::string& consoleDev,
    const std::string& isoPath
) : ramMb(ramSizeMb),
    diskImagePath(diskPath),
    isoImagePath(isoPath),
    cores(numCores),
    guestArch(gArch),
    requestHardwareVirt(useHardwareVirt),
    isFallback(false),
    memory(ramSizeMb),
    state(VMNativeState::CREATED) {

    std::lock_guard<std::recursive_mutex> lock(engineMutex);

    bootConfig.kernelPath = kernelPath;
    bootConfig.initramfsPath = initramfsPath;
    bootConfig.cmdline = cmdline;
    bootConfig.consoleDevice = consoleDev.empty() ? "ttyAMA0" : consoleDev;
    bootConfig.ramSizeBytes = ramSizeMb * 1024ULL * 1024ULL;
    bootConfig.cpuCount = numCores;

    cpu = NativeCPUFactory::createBackend(guestArch, requestHardwareVirt, isFallback, backendStatus);
    configure();
}

NativeVMEngine::~NativeVMEngine() {
    destroy();
}

bool NativeVMEngine::loadBootPayload() {
    if (!cpu) return false;

    std::string bootLog;
    bool ok = false;
    if (!bootConfig.kernelPath.empty()) {
        ok = NativeLinuxBootLoader::loadLinuxGuest(bootConfig, memory, *cpu, devices, bootLog);
    } else {
        ok = NativeLinuxBootLoader::loadWindowsGuest(bootConfig, diskImagePath, isoImagePath, memory, *cpu, devices, bootLog);
    }

    if (!ok) {
        // Report exact missing component to the virtual UART console
        for (char c : bootLog) {
            devices.getUART().writeByte(static_cast<uint8_t>(c));
        }
        backendStatus = bootLog;
        return false;
    }

    // Write boot initialization message to UART
    for (char c : bootLog) {
        devices.getUART().writeByte(static_cast<uint8_t>(c));
    }
    return true;
}

bool NativeVMEngine::configure() {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);

    if (!memory.isAllocated()) {
        backendStatus = memory.getAllocationError();
        state = VMNativeState::ERROR;
        return false;
    }
    memory.reset();
    devices.resetAll();

    if (!diskImagePath.empty()) {
        std::string err;
        if (!devices.getDisk().openRawDisk(diskImagePath, false, "", err)) {
            NLOGE("Failed to attach virtual disk %s: %s", diskImagePath.c_str(), err.c_str());
            // Partial cleanup: reset devices and memory
            devices.resetAll();
            memory.reset();
            backendStatus = "Virtual disk attach failed: " + err;
            state = VMNativeState::ERROR;
            return false;
        }
    }

    if (!isoImagePath.empty()) {
        std::string err;
        if (!devices.getCdrom().openCdrom(isoImagePath, "", err)) {
            NLOGE("Failed to attach virtual CD-ROM %s: %s", isoImagePath.c_str(), err.c_str());
            devices.getDisk().closeDisk();
            devices.resetAll();
            memory.reset();
            backendStatus = "Virtual CD/DVD attach failed: " + err;
            state = VMNativeState::ERROR;
            return false;
        }
    }

    if (cpu) cpu->reset();
    bool loaded = loadBootPayload();
    if (!loaded) {
        // Partial cleanup: If boot payload failed to load, close disk, cdrom and reset memory
        devices.getDisk().closeDisk();
        devices.getCdrom().closeDisk();
        devices.resetAll();
        memory.reset();
        state = VMNativeState::ERROR;
        return false;
    }

    state = VMNativeState::CONFIGURED;
    return true;
}

bool NativeVMEngine::start() {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);

    if (!memory.isAllocated()) {
        backendStatus = memory.getAllocationError();
        state = VMNativeState::ERROR;
        return false;
    }
    VMNativeState curState = state.load();
    if (curState != VMNativeState::CONFIGURED && curState != VMNativeState::STOPPED && curState != VMNativeState::CREATED) {
        return false;
    }
    if (!cpu) {
        state = VMNativeState::ERROR;
        return false;
    }
    // Set STARTING - real RUNNING is set only when guest instructions begin executing
    state = VMNativeState::STARTING;
    cpu->setState(NativeCPUState::RUNNING);
    return true;
}

bool NativeVMEngine::pause() {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);

    if (state.load() == VMNativeState::RUNNING && cpu) {
        state = VMNativeState::PAUSED;
        cpu->setState(NativeCPUState::PAUSED);
        return true;
    }
    return false;
}

bool NativeVMEngine::resume() {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);

    if (cpu && state.load() == VMNativeState::PAUSED) {
        state = VMNativeState::RUNNING;
        cpu->setState(NativeCPUState::RUNNING);
        return true;
    }
    return false;
}

bool NativeVMEngine::stop() {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);

    state = VMNativeState::STOPPING;
    if (cpu) cpu->setState(NativeCPUState::HALTED);
    bool flushOk = devices.getDisk().flush();
    if (!flushOk) {
        NLOGE("[VM][STORAGE] Disk flush failed during VM stop");
    }
    state = VMNativeState::STOPPED;
    return true;
}

bool NativeVMEngine::reset() {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);

    stop();
    if (!configure()) {
        return false;
    }
    return start();
}

void NativeVMEngine::destroy() {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);

    stop();
    devices.getDisk().closeDisk();
    devices.getCdrom().closeDisk();
    memory.reset();
    devices.resetAll();
    if (cpu) cpu->reset();
    state = VMNativeState::STOPPED;
}

int NativeVMEngine::stepCycles(int maxCycles) {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);

    VMNativeState curState = state.load();
    if ((curState != VMNativeState::RUNNING && curState != VMNativeState::STARTING) || !cpu) {
        return 0;
    }

    uint64_t executed = cpu->runCycles(memory, devices, static_cast<uint64_t>(maxCycles));
    if (executed > 0 && state.load() == VMNativeState::STARTING) {
        state = VMNativeState::RUNNING;
    }

    // Check CPU & Device state transitions
    NativePowerEvent powerEv = devices.pollPowerEvent();
    if (powerEv == NativePowerEvent::PAUSE || cpu->getState() == NativeCPUState::PAUSED) {
        state = VMNativeState::PAUSED;
    } else if (powerEv == NativePowerEvent::SHUTDOWN || cpu->getState() == NativeCPUState::HALTED) {
        state = VMNativeState::STOPPED;
    } else if (powerEv == NativePowerEvent::TRAP_ERROR || cpu->getState() == NativeCPUState::TRAP_FAULT) {
        state = VMNativeState::ERROR;
    }

    return static_cast<int>(executed);
}

VMNativeState NativeVMEngine::getState() const {
    return state.load();
}

std::vector<uint8_t> NativeVMEngine::fetchSerialTx() {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    return devices.getUART().readTxBuffer();
}

void NativeVMEngine::writeSerialRx(uint8_t byte) {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);

    if (state.load() != VMNativeState::RUNNING && state.load() != VMNativeState::STARTING) {
        return;
    }

    // Deliver keyboard input exclusively to guest UART RX register & assert UART IRQ
    devices.getUART().queueRxByte(byte);
    devices.getGIC().setInterruptPending(NativeGIC::IRQ_UART, true);
}

const uint32_t* NativeVMEngine::getFramebuffer() const {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    return devices.getDisplay().getFramebuffer();
}

uint32_t NativeVMEngine::getDisplayWidth() const {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    return devices.getDisplay().getWidth();
}

uint32_t NativeVMEngine::getDisplayHeight() const {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    return devices.getDisplay().getHeight();
}
