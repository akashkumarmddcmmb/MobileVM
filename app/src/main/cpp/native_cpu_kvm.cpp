#include "native_cpu_kvm.h"
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <sys/ioctl.h>
#include <sys/mman.h>

// Standard Linux KVM ABI ioctl codes and structures
#ifndef KVM_GET_API_VERSION
#define KVM_GET_API_VERSION _IO(0xAE, 0x00)
#endif

#ifndef KVM_CREATE_VM
#define KVM_CREATE_VM _IO(0xAE, 0x01)
#endif

#ifndef KVM_CHECK_EXTENSION
#define KVM_CHECK_EXTENSION _IO(0xAE, 0x03)
#endif

#ifndef KVM_GET_VCPU_MMAP_SIZE
#define KVM_GET_VCPU_MMAP_SIZE _IO(0xAE, 0x04)
#endif

#ifndef KVM_CREATE_VCPU
#define KVM_CREATE_VCPU _IO(0xAE, 0x41)
#endif

#ifndef KVM_SET_USER_MEMORY_REGION
#define KVM_SET_USER_MEMORY_REGION _IOW(0xAE, 0x46, struct kvm_userspace_memory_region)
#endif

#ifndef KVM_RUN
#define KVM_RUN _IO(0xAE, 0x80)
#endif

#ifndef KVM_ARM_VCPU_INIT
#define KVM_ARM_VCPU_INIT _IOW(0xAE, 0x9B, struct kvm_vcpu_init)
#endif

#ifndef KVM_ARM_PREFERRED_TARGET
#define KVM_ARM_PREFERRED_TARGET _IOR(0xAE, 0x99, struct kvm_vcpu_init)
#endif

#ifndef KVM_GET_ONE_REG
#define KVM_GET_ONE_REG _IOW(0xAE, 0xAB, struct kvm_one_reg)
#endif

#ifndef KVM_SET_ONE_REG
#define KVM_SET_ONE_REG _IOW(0xAE, 0xAC, struct kvm_one_reg)
#endif

// Capabilities
#ifndef KVM_CAP_USER_MEMORY
#define KVM_CAP_USER_MEMORY 3
#endif

#ifndef KVM_CAP_ARM_VM_IPA_SIZE
#define KVM_CAP_ARM_VM_IPA_SIZE 165
#endif

// KVM Exit Reasons
#define KVM_EXIT_UNKNOWN          0
#define KVM_EXIT_EXCEPTION        1
#define KVM_EXIT_IO               2
#define KVM_EXIT_HYPERCALL        3
#define KVM_EXIT_DEBUG            4
#define KVM_EXIT_HLT              5
#define KVM_EXIT_MMIO             6
#define KVM_EXIT_IRQ_WINDOW_OPEN  7
#define KVM_EXIT_SHUTDOWN         8
#define KVM_EXIT_FAIL_ENTRY       9
#define KVM_EXIT_INTR             10
#define KVM_EXIT_SYSTEM_EVENT     24

// ARM64 KVM Register IDs
#define KVM_REG_ARM64             0x6000000000000000ULL
#define KVM_REG_SIZE_U64          0x0030000000000000ULL
#define KVM_REG_ARM_CORE          0x0010000000000000ULL

#define ARM64_CORE_REG(offset_u32) (KVM_REG_ARM64 | KVM_REG_SIZE_U64 | KVM_REG_ARM_CORE | (offset_u32))
#define KVM_REG_ARM_X(i)          ARM64_CORE_REG((i) * 2)
#define KVM_REG_ARM_SP            ARM64_CORE_REG(62)
#define KVM_REG_ARM_PC            ARM64_CORE_REG(64)

struct kvm_userspace_memory_region {
    uint32_t slot;
    uint32_t flags;
    uint64_t guest_phys_addr;
    uint64_t memory_size;
    uint64_t userspace_addr;
};

struct kvm_vcpu_init {
    uint32_t target;
    uint32_t features[7];
};

struct kvm_one_reg {
    uint64_t id;
    uint64_t addr;
};

struct kvm_run {
    uint8_t request_interrupt_window;
    uint8_t immediate_exit;
    uint8_t padding1[6];
    uint32_t exit_reason;
    uint8_t send_sig;
    uint8_t padding2[3];
    uint32_t flags;
    union {
        struct {
            uint64_t phys_addr;
            uint8_t data[8];
            uint32_t len;
            uint8_t is_write;
        } mmio;
        struct {
            uint32_t type;
            uint64_t flags;
        } system_event;
        struct {
            uint64_t hardware_entry_failure_reason;
        } fail_entry;
        uint8_t padding[256];
    };
};

NativeCPUKVM::NativeCPUKVM()
    : kvmFd(-1),
      vmFd(-1),
      vcpuFd(-1),
      vcpuMmapSize(0),
      runStruct(nullptr),
      pc(0),
      sp(0x000FFFF0ULL),
      state(NativeCPUState::READY) {
    registers.fill(0);
}

NativeCPUKVM::~NativeCPUKVM() {
    if (runStruct && runStruct != MAP_FAILED && vcpuMmapSize > 0) {
        munmap(runStruct, vcpuMmapSize);
        runStruct = nullptr;
    }
    if (vcpuFd >= 0) {
        close(vcpuFd);
        vcpuFd = -1;
    }
    if (vmFd >= 0) {
        close(vmFd);
        vmFd = -1;
    }
    if (kvmFd >= 0) {
        close(kvmFd);
        kvmFd = -1;
    }
}

bool NativeCPUKVM::isAvailableOnHost() {
    HostArchitecture host = NativeArchDetector::detectHostArchitecture();
    if (host != HostArchitecture::ARM64) {
        return false;
    }

    int fd = open("/dev/kvm", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        return false;
    }

    int apiVersion = ioctl(fd, KVM_GET_API_VERSION, 0);
    close(fd);

    return (apiVersion == 12);
}

std::string NativeCPUKVM::getAvailabilityReason() {
    HostArchitecture host = NativeArchDetector::detectHostArchitecture();
    if (host != HostArchitecture::ARM64) {
        return "Host CPU is " + NativeArchDetector::getHostArchName(host) + ". ARM64 hardware virtualization requires an ARM64 host.";
    }

    int fd = open("/dev/kvm", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        int err = errno;
        if (err == ENOENT) {
            return "/dev/kvm device node does not exist in this Android kernel build.";
        } else if (err == EACCES || err == EPERM) {
            return "Permission denied accessing /dev/kvm (restricted by Android SELinux / user permissions).";
        }
        return "Cannot open /dev/kvm: " + std::string(strerror(err));
    }

    int apiVersion = ioctl(fd, KVM_GET_API_VERSION, 0);
    close(fd);

    if (apiVersion != 12) {
        return "Unsupported KVM API version: " + std::to_string(apiVersion) + " (expected 12).";
    }

    return "KVM/pKVM Hardware Virtualization is available and supported on this host.";
}

void NativeCPUKVM::reset() {
    registers.fill(0);
    pc = 0;
    sp = 0x000FFFF0ULL;
    state = NativeCPUState::READY;
    syncRegistersToKvm();
}

uint64_t NativeCPUKVM::getRegister(uint32_t index) const {
    if (index < 32) return registers[index];
    return 0;
}

void NativeCPUKVM::setRegister(uint32_t index, uint64_t value) {
    if (index < 31) {
        registers[index] = value;
        if (vcpuFd >= 0) {
            struct kvm_one_reg reg;
            reg.id = KVM_REG_ARM_X(index);
            reg.addr = reinterpret_cast<uint64_t>(&registers[index]);
            ioctl(vcpuFd, KVM_SET_ONE_REG, &reg);
        }
    }
}

void NativeCPUKVM::setPC(uint64_t val) {
    pc = val;
    if (vcpuFd >= 0) {
        struct kvm_one_reg reg;
        reg.id = KVM_REG_ARM_PC;
        reg.addr = reinterpret_cast<uint64_t>(&pc);
        ioctl(vcpuFd, KVM_SET_ONE_REG, &reg);
    }
}

void NativeCPUKVM::setSP(uint64_t val) {
    sp = val;
    if (vcpuFd >= 0) {
        struct kvm_one_reg reg;
        reg.id = KVM_REG_ARM_SP;
        reg.addr = reinterpret_cast<uint64_t>(&sp);
        ioctl(vcpuFd, KVM_SET_ONE_REG, &reg);
    }
}

bool NativeCPUKVM::syncRegistersToKvm() {
    if (vcpuFd < 0) return false;

    struct kvm_one_reg reg;
    for (uint32_t i = 0; i < 31; i++) {
        reg.id = KVM_REG_ARM_X(i);
        reg.addr = reinterpret_cast<uint64_t>(&registers[i]);
        ioctl(vcpuFd, KVM_SET_ONE_REG, &reg);
    }

    reg.id = KVM_REG_ARM_SP;
    reg.addr = reinterpret_cast<uint64_t>(&sp);
    ioctl(vcpuFd, KVM_SET_ONE_REG, &reg);

    reg.id = KVM_REG_ARM_PC;
    reg.addr = reinterpret_cast<uint64_t>(&pc);
    ioctl(vcpuFd, KVM_SET_ONE_REG, &reg);

    return true;
}

bool NativeCPUKVM::syncRegistersFromKvm() {
    if (vcpuFd < 0) return false;

    struct kvm_one_reg reg;
    for (uint32_t i = 0; i < 31; i++) {
        reg.id = KVM_REG_ARM_X(i);
        reg.addr = reinterpret_cast<uint64_t>(&registers[i]);
        ioctl(vcpuFd, KVM_GET_ONE_REG, &reg);
    }

    reg.id = KVM_REG_ARM_SP;
    reg.addr = reinterpret_cast<uint64_t>(&sp);
    ioctl(vcpuFd, KVM_GET_ONE_REG, &reg);

    reg.id = KVM_REG_ARM_PC;
    reg.addr = reinterpret_cast<uint64_t>(&pc);
    ioctl(vcpuFd, KVM_GET_ONE_REG, &reg);

    return true;
}

bool NativeCPUKVM::initKvmVcpu(NativeMemory& memory) {
    if (kvmFd < 0) {
        kvmFd = open("/dev/kvm", O_RDWR | O_CLOEXEC);
        if (kvmFd < 0) return false;
    }

    // Check user memory capability
    int capUserMem = ioctl(kvmFd, KVM_CHECK_EXTENSION, KVM_CAP_USER_MEMORY);
    if (capUserMem <= 0) {
        return false;
    }

    int mmapSize = ioctl(kvmFd, KVM_GET_VCPU_MMAP_SIZE, 0);
    if (mmapSize <= 0) {
        vcpuMmapSize = 4096;
    } else {
        vcpuMmapSize = static_cast<size_t>(mmapSize);
    }

    if (vmFd < 0) {
        vmFd = ioctl(kvmFd, KVM_CREATE_VM, 0);
        if (vmFd < 0) return false;

        struct kvm_userspace_memory_region memRegion;
        std::memset(&memRegion, 0, sizeof(memRegion));
        memRegion.slot = 0;
        memRegion.guest_phys_addr = 0;
        memRegion.memory_size = memory.getSize();
        memRegion.userspace_addr = reinterpret_cast<uint64_t>(memory.getRawBuffer());
        memRegion.flags = 0;

        if (ioctl(vmFd, KVM_SET_USER_MEMORY_REGION, &memRegion) < 0) {
            return false;
        }
    }

    if (vcpuFd < 0) {
        vcpuFd = ioctl(vmFd, KVM_CREATE_VCPU, 0);
        if (vcpuFd < 0) return false;

        // Initialize ARM64 vCPU preferred target with PSCI 0.2 feature enabled
        struct kvm_vcpu_init init;
        std::memset(&init, 0, sizeof(init));
        if (ioctl(vmFd, KVM_ARM_PREFERRED_TARGET, &init) >= 0) {
            init.features[0] |= (1u << 0); // KVM_ARM_VCPU_PSCI_0_2
            ioctl(vcpuFd, KVM_ARM_VCPU_INIT, &init);
        }

        void* runPtr = mmap(nullptr, vcpuMmapSize, PROT_READ | PROT_WRITE, MAP_SHARED, vcpuFd, 0);
        if (runPtr == MAP_FAILED) {
            runStruct = nullptr;
        } else {
            runStruct = static_cast<struct kvm_run*>(runPtr);
        }

        syncRegistersToKvm();
    }

    return true;
}

void NativeCPUKVM::handleMmioExit(NativeMemory& memory, NativeDeviceManager& devices) {
    if (!runStruct) return;

    uint64_t addr = runStruct->mmio.phys_addr;
    uint32_t len = runStruct->mmio.len;
    uint8_t isWrite = runStruct->mmio.is_write;

    if (isWrite) {
        if (len == 1) {
            uint8_t val = runStruct->mmio.data[0];
            if (!devices.handleMMIOWrite8(addr, val)) {
                memory.write8(addr, val);
            }
        } else if (len == 4) {
            uint32_t val;
            std::memcpy(&val, runStruct->mmio.data, 4);
            if (!devices.handleMMIOWrite32(addr, val, &memory)) {
                memory.write32(addr, val);
            }
        }
    } else {
        if (len == 1) {
            uint8_t val = devices.handleMMIORead8(addr);
            if (val == 0) val = memory.read8(addr);
            runStruct->mmio.data[0] = val;
        } else if (len == 4) {
            uint32_t val = devices.handleMMIORead32(addr);
            if (val == 0) val = memory.read32(addr);
            std::memcpy(runStruct->mmio.data, &val, 4);
        }
    }
}

NativeCPUState NativeCPUKVM::step(NativeMemory& memory, NativeDeviceManager& devices) {
    if (vcpuFd < 0) {
        if (!initKvmVcpu(memory)) {
            state = NativeCPUState::TRAP_FAULT;
            return state;
        }
    }

    int ret = ioctl(vcpuFd, KVM_RUN, 0);
    if (ret < 0) {
        if (errno == EINTR || errno == EAGAIN) {
            return state;
        }
        state = NativeCPUState::TRAP_FAULT;
        return state;
    }

    if (runStruct) {
        switch (runStruct->exit_reason) {
            case KVM_EXIT_MMIO:
                handleMmioExit(memory, devices);
                break;
            case KVM_EXIT_HLT:
                state = NativeCPUState::HALTED;
                break;
            case KVM_EXIT_SHUTDOWN:
                state = NativeCPUState::HALTED;
                break;
            case KVM_EXIT_FAIL_ENTRY:
                state = NativeCPUState::TRAP_FAULT;
                break;
            case KVM_EXIT_SYSTEM_EVENT:
                state = NativeCPUState::HALTED;
                break;
            default:
                break;
        }
    }

    syncRegistersFromKvm();
    return state;
}

uint64_t NativeCPUKVM::runCycles(NativeMemory& memory, NativeDeviceManager& devices, uint64_t maxCycles) {
    state = NativeCPUState::RUNNING;
    uint64_t executed = 0;
    while (executed < maxCycles && state == NativeCPUState::RUNNING) {
        step(memory, devices);
        executed++;
    }
    return executed;
}
