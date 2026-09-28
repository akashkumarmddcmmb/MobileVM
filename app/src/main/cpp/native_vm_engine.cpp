#include "native_vm_engine.h"
#include <vector>
#include <cstring>
#include <sstream>
#include <iomanip>

NativeVMEngine::NativeVMEngine(
    size_t ramSizeMb,
    const std::string& diskPath,
    int numCores,
    GuestArchitecture gArch,
    bool useHardwareVirt,
    const std::string& kernelPath,
    const std::string& initramfsPath,
    const std::string& cmdline,
    const std::string& consoleDev
) : ramMb(ramSizeMb),
    diskImagePath(diskPath),
    cores(numCores),
    guestArch(gArch),
    requestHardwareVirt(useHardwareVirt),
    isFallback(false),
    memory(ramSizeMb),
    state(VMNativeState::CREATED) {

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
    bool ok = NativeLinuxBootLoader::loadLinuxGuest(bootConfig, memory, *cpu, devices, bootLog);
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
    if (!memory.isAllocated()) {
        backendStatus = memory.getAllocationError();
        state = VMNativeState::ERROR;
        return false;
    }
    memory.reset();
    devices.resetAll();

    if (!diskImagePath.empty()) {
        std::string err;
        devices.getDisk().openRawDisk(diskImagePath, false, "", err);
    }

    if (cpu) cpu->reset();
    bool loaded = loadBootPayload();
    if (!loaded) {
        // If boot payload failed to load (e.g. missing kernel), flag error state
        state = VMNativeState::ERROR;
        return false;
    }

    state = VMNativeState::CONFIGURED;
    return true;
}

bool NativeVMEngine::start() {
    if (!memory.isAllocated()) {
        backendStatus = memory.getAllocationError();
        state = VMNativeState::ERROR;
        return false;
    }
    if (state != VMNativeState::CONFIGURED && state != VMNativeState::STOPPED && state != VMNativeState::CREATED) {
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
    if (state == VMNativeState::RUNNING && cpu) {
        state = VMNativeState::PAUSED;
        cpu->setState(NativeCPUState::PAUSED);
        return true;
    }
    return false;
}

bool NativeVMEngine::resume() {
    if (cpu) {
        state = VMNativeState::RUNNING;
        cpu->setState(NativeCPUState::RUNNING);
        return true;
    }
    return false;
}

bool NativeVMEngine::stop() {
    state = VMNativeState::STOPPING;
    if (cpu) cpu->setState(NativeCPUState::HALTED);
    devices.getDisk().flush();
    state = VMNativeState::STOPPED;
    return true;
}

bool NativeVMEngine::reset() {
    stop();
    configure();
    start();
    return true;
}

void NativeVMEngine::destroy() {
    stop();
    devices.getDisk().closeDisk();
    memory.reset();
    devices.resetAll();
}

int NativeVMEngine::stepCycles(int maxCycles) {
    if ((state != VMNativeState::RUNNING && state != VMNativeState::STARTING) || !cpu) {
        return 0;
    }

    uint64_t executed = cpu->runCycles(memory, devices, static_cast<uint64_t>(maxCycles));
    if (executed > 0 && state == VMNativeState::STARTING) {
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
    return devices.getUART().readTxBuffer();
}

void NativeVMEngine::writeSerialRx(uint8_t byte) {
    if (state != VMNativeState::RUNNING) {
        return;
    }

    // Deliver keyboard input exclusively to guest UART RX register & assert UART IRQ
    devices.getUART().queueRxByte(byte);
    devices.getGIC().setInterruptPending(NativeGIC::IRQ_UART, true);
}

const uint32_t* NativeVMEngine::getFramebuffer() const {
    return devices.getDisplay().getFramebuffer();
}

uint32_t NativeVMEngine::getDisplayWidth() const {
    return devices.getDisplay().getWidth();
}

uint32_t NativeVMEngine::getDisplayHeight() const {
    return devices.getDisplay().getHeight();
}
