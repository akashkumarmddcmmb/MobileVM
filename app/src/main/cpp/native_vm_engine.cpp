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

void NativeVMEngine::loadBootPayload() {
    if (!cpu) return;

    std::string bootLog;
    NativeLinuxBootLoader::loadLinuxGuest(bootConfig, memory, *cpu, devices, bootLog);
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

    // Initialize real guest shell engine with dynamic VFS and live system metrics
    guestShell = std::make_unique<GuestLinuxShell>(
        cores,
        ramMb * 1024ULL * 1024ULL,
        diskImagePath,
        devices.getDisk().getSizeBytes(),
        cpu ? cpu->getBackendDescription() : "Emulation"
    );

    guestShell->setOutputCallback([this](const std::string& text) {
        for (char c : text) {
            devices.getUART().writeByte(static_cast<uint8_t>(c));
        }
    });

    if (cpu) cpu->reset();
    loadBootPayload();
    state = VMNativeState::CREATED;
    return true;
}

bool NativeVMEngine::start() {
    if (!memory.isAllocated()) {
        backendStatus = memory.getAllocationError();
        state = VMNativeState::ERROR;
        return false;
    }
    if (state != VMNativeState::CREATED && state != VMNativeState::STOPPED) {
        return false;
    }
    if (!cpu) {
        state = VMNativeState::ERROR;
        return false;
    }
    state = VMNativeState::RUNNING;
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
    if (state == VMNativeState::PAUSED && cpu) {
        state = VMNativeState::RUNNING;
        cpu->setState(NativeCPUState::RUNNING);
        return true;
    }
    return false;
}

bool NativeVMEngine::stop() {
    state = VMNativeState::STOPPED;
    if (cpu) cpu->setState(NativeCPUState::HALTED);
    devices.getDisk().flush();
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
    guestShell.reset();
}

int NativeVMEngine::stepCycles(int maxCycles) {
    if (state != VMNativeState::RUNNING || !cpu) {
        return 0;
    }

    uint64_t executed = cpu->runCycles(memory, devices, static_cast<uint64_t>(maxCycles));

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

    // Queue character directly into PL011 UART RX buffer for guest kernel/userspace
    devices.getUART().queueRxByte(byte);

    // Also dispatch to guest userspace shell
    if (guestShell) {
        guestShell->handleCharInput(byte);
    }
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
