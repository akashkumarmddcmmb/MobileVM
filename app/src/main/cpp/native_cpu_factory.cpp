#include "native_cpu_factory.h"

std::unique_ptr<NativeCPUBackend> NativeCPUFactory::createBackend(
    GuestArchitecture guestArch,
    bool requestHardwareVirt,
    bool& outIsFallbackEmulation,
    std::string& outStatusMessage
) {
    outIsFallbackEmulation = false;

    if (guestArch == GuestArchitecture::ARM64) {
        if (requestHardwareVirt) {
            if (NativeCPUKVM::isAvailableOnHost()) {
                outIsFallbackEmulation = false;
                outStatusMessage = "ARM64 KVM/pKVM Hardware Virtualization active";
                return std::make_unique<NativeCPUKVM>();
            } else {
                // Device does not support KVM or /dev/kvm is unavailable.
                // Do NOT pretend it works! Fall back gracefully to real ARM64 emulation core.
                outIsFallbackEmulation = true;
                outStatusMessage = "KVM hardware acceleration unavailable (" + 
                                   NativeCPUKVM::getAvailabilityReason() + 
                                   "). Operating in real ARM64 Native CPU Emulation fallback mode.";
                return std::make_unique<NativeCPUARM64>();
            }
        } else {
            outIsFallbackEmulation = false;
            outStatusMessage = "ARM64 Software Emulation Core active (user preference).";
            return std::make_unique<NativeCPUARM64>();
        }
    }

    // For other guest targets (x86_64, x86)
    outIsFallbackEmulation = false;
    outStatusMessage = "Guest architecture " + NativeArchDetector::getGuestArchName(guestArch) + 
                       " is planned for subsequent phases. Only ARM64 guest execution is active in Phase 1.";
    return nullptr;
}
