#ifndef NATIVE_CPU_FACTORY_H
#define NATIVE_CPU_FACTORY_H

#include <memory>
#include <string>
#include "native_cpu_backend.h"
#include "native_cpu_arm64.h"
#include "native_cpu_kvm.h"
#include "native_arch.h"

class NativeCPUFactory {
public:
    static std::unique_ptr<NativeCPUBackend> createBackend(
        GuestArchitecture guestArch,
        bool requestHardwareVirt,
        bool& outIsFallbackEmulation,
        std::string& outStatusMessage
    );
};

#endif // NATIVE_CPU_FACTORY_H
