#ifndef NATIVE_ARCH_H
#define NATIVE_ARCH_H

#include <string>

enum class HostArchitecture {
    ARM64,
    ARM32,
    X86_64,
    X86,
    UNKNOWN
};

enum class GuestArchitecture {
    ARM64 = 0,
    X86_64 = 1,
    X86 = 2,
    ARM32 = 3
};

enum class NativeBackendType {
    ARM64_HARDWARE_VIRTUALIZATION = 0,
    ARM64_EMULATION = 1,
    X86_64_EMULATION = 2,
    X86_EMULATION = 3,
    UNSUPPORTED = 99
};

class NativeArchDetector {
public:
    static HostArchitecture detectHostArchitecture();
    static std::string getHostArchName(HostArchitecture arch);
    static std::string getGuestArchName(GuestArchitecture arch);
    static std::string getBackendTypeName(NativeBackendType type);
};

#endif // NATIVE_ARCH_H
