#include "native_cpu_kvm.h"
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <sys/ioctl.h>

// Standard Linux KVM ABI ioctl codes and structures for universal NDK ABI compatibility
#ifndef KVM_GET_API_VERSION
#define KVM_GET_API_VERSION _IO(0xAE, 0x00)
#endif

#ifndef KVM_CREATE_VM
#define KVM_CREATE_VM _IO(0xAE, 0x01)
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

struct kvm_userspace_memory_region {
    uint32_t slot;
    uint32_t flags;
    uint64_t guest_phys_addr;
    uint64_t memory_size;
    uint64_t userspace_addr;
};

NativeCPUKVM::NativeCPUKVM() : kvmFd(-1), vmFd(-1), vcpuFd(-1), pc(0), sp(0x000FFFF0ULL), state(NativeCPUState::READY) {
    registers.fill(0);
}

NativeCPUKVM::~NativeCPUKVM() {
    if (vcpuFd >= 0) close(vcpuFd);
    if (vmFd >= 0) close(vmFd);
    if (kvmFd >= 0) close(kvmFd);
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
}

uint64_t NativeCPUKVM::getRegister(uint32_t index) const {
    if (index < 32) return registers[index];
    return 0;
}

void NativeCPUKVM::setRegister(uint32_t index, uint64_t value) {
    if (index < 31) {
        registers[index] = value;
    }
}

bool NativeCPUKVM::initKvmVcpu(NativeMemory& memory) {
    if (kvmFd < 0) {
        kvmFd = open("/dev/kvm", O_RDWR | O_CLOEXEC);
        if (kvmFd < 0) return false;
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
    }

    return true;
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
        state = NativeCPUState::TRAP_FAULT;
        return state;
    }

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
