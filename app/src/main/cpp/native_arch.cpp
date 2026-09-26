#include "native_arch.h"

HostArchitecture NativeArchDetector::detectHostArchitecture() {
#if defined(__aarch64__)
    return HostArchitecture::ARM64;
#elif defined(__arm__)
    return HostArchitecture::ARM32;
#elif defined(__x86_64__)
    return HostArchitecture::X86_64;
#elif defined(__i386__)
    return HostArchitecture::X86;
#else
    return HostArchitecture::UNKNOWN;
#endif
}

std::string NativeArchDetector::getHostArchName(HostArchitecture arch) {
    switch (arch) {
        case HostArchitecture::ARM64: return "ARM64 (aarch64)";
        case HostArchitecture::ARM32: return "ARM32 (armv7a)";
        case HostArchitecture::X86_64: return "x86_64 (amd64)";
        case HostArchitecture::X86: return "x86 (i686)";
        default: return "Unknown Host Architecture";
    }
}

std::string NativeArchDetector::getGuestArchName(GuestArchitecture arch) {
    switch (arch) {
        case GuestArchitecture::ARM64: return "ARM64 (AArch64)";
        case GuestArchitecture::X86_64: return "x86_64 (AMD64)";
        case GuestArchitecture::X86: return "x86 (IA-32)";
        case GuestArchitecture::ARM32: return "ARM32 (ARMv7-A)";
        default: return "Unknown Guest Architecture";
    }
}

std::string NativeArchDetector::getBackendTypeName(NativeBackendType type) {
    switch (type) {
        case NativeBackendType::ARM64_HARDWARE_VIRTUALIZATION:
            return "ARM64 Hardware Virtualization (KVM/pKVM)";
        case NativeBackendType::ARM64_EMULATION:
            return "ARM64 Software Emulation (Native C++ Core)";
        case NativeBackendType::X86_64_EMULATION:
            return "x86_64 Software Emulation (Planned)";
        case NativeBackendType::X86_EMULATION:
            return "x86 Software Emulation (Planned)";
        default:
            return "Unsupported Backend Configuration";
    }
}
