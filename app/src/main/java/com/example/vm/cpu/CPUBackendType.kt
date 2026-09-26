package com.example.vm.cpu

enum class CPUBackendType(
    val id: String,
    val displayName: String,
    val guestArch: GuestArchitecture,
    val isHardwareAccelerated: Boolean,
    val isAvailableInPhase1: Boolean
) {
    ARM64_HARDWARE_VIRTUALIZATION(
        id = "arm64_kvm",
        displayName = "ARM64 Hardware Virtualization (KVM/pKVM)",
        guestArch = GuestArchitecture.ARM64,
        isHardwareAccelerated = true,
        isAvailableInPhase1 = true
    ),
    ARM64_EMULATION(
        id = "arm64_emu",
        displayName = "ARM64 Software Emulation Core (Native C++)",
        guestArch = GuestArchitecture.ARM64,
        isHardwareAccelerated = false,
        isAvailableInPhase1 = true
    ),
    X86_64_EMULATION(
        id = "x86_64_emu",
        displayName = "x86_64 CPU Emulation (Planned)",
        guestArch = GuestArchitecture.X86_64,
        isHardwareAccelerated = false,
        isAvailableInPhase1 = false
    ),
    X86_EMULATION(
        id = "x86_emu",
        displayName = "x86 CPU Emulation (Planned)",
        guestArch = GuestArchitecture.X86,
        isHardwareAccelerated = false,
        isAvailableInPhase1 = false
    )
}
