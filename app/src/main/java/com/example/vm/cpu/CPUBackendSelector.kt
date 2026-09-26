package com.example.vm.cpu

data class BackendResolution(
    val backendType: CPUBackendType,
    val hostArch: HostArchitecture,
    val guestArch: GuestArchitecture,
    val isHardwareAccelerated: Boolean,
    val isFallbackEmulation: Boolean,
    val statusMessage: String
)

object CPUBackendSelector {
    fun resolve(
        guestArch: GuestArchitecture,
        requestHardwareVirt: Boolean,
        hostArch: HostArchitecture = HostArchitecture.detect(),
        isKvmSupported: Boolean = false,
        kvmReason: String = ""
    ): BackendResolution {
        if (guestArch == GuestArchitecture.ARM64) {
            if (requestHardwareVirt) {
                if (hostArch == HostArchitecture.ARM64 && isKvmSupported) {
                    return BackendResolution(
                        backendType = CPUBackendType.ARM64_HARDWARE_VIRTUALIZATION,
                        hostArch = hostArch,
                        guestArch = guestArch,
                        isHardwareAccelerated = true,
                        isFallbackEmulation = false,
                        statusMessage = "ARM64 KVM/pKVM Hardware Virtualization active on ARM64 host."
                    )
                } else {
                    val fallbackReason = if (hostArch != HostArchitecture.ARM64) {
                        "Host CPU is ${hostArch.displayName}. Hardware virtualization requires ARM64 host."
                    } else {
                        kvmReason.ifEmpty { "KVM node /dev/kvm is unavailable on this device/kernel." }
                    }

                    return BackendResolution(
                        backendType = CPUBackendType.ARM64_EMULATION,
                        hostArch = hostArch,
                        guestArch = guestArch,
                        isHardwareAccelerated = false,
                        isFallbackEmulation = true,
                        statusMessage = "Hardware acceleration unavailable ($fallbackReason). Operating with real ARM64 software emulation fallback."
                    )
                }
            } else {
                return BackendResolution(
                    backendType = CPUBackendType.ARM64_EMULATION,
                    hostArch = hostArch,
                    guestArch = guestArch,
                    isHardwareAccelerated = false,
                    isFallbackEmulation = false,
                    statusMessage = "ARM64 Native Software Emulation active (configured by user)."
                )
            }
        }

        val targetType = when (guestArch) {
            GuestArchitecture.X86_64 -> CPUBackendType.X86_64_EMULATION
            GuestArchitecture.X86 -> CPUBackendType.X86_EMULATION
            else -> CPUBackendType.ARM64_EMULATION
        }

        return BackendResolution(
            backendType = targetType,
            hostArch = hostArch,
            guestArch = guestArch,
            isHardwareAccelerated = false,
            isFallbackEmulation = false,
            statusMessage = "${guestArch.displayName} guest execution is planned for subsequent phases. Only ARM64 guest execution is active in Phase 1."
        )
    }
}
