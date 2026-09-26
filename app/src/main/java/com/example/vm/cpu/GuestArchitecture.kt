package com.example.vm.cpu

enum class GuestArchitecture(
    val code: Int,
    val displayName: String,
    val isImplementedInPhase1: Boolean,
    val defaultKernelFormat: String
) {
    ARM64(
        code = 0,
        displayName = "ARM64 (AArch64)",
        isImplementedInPhase1 = true,
        defaultKernelFormat = "Image (ARM64 EFI/PE)"
    ),
    X86_64(
        code = 1,
        displayName = "x86_64 (AMD64)",
        isImplementedInPhase1 = false,
        defaultKernelFormat = "bzImage (x86_64)"
    ),
    X86(
        code = 2,
        displayName = "x86 (IA-32)",
        isImplementedInPhase1 = false,
        defaultKernelFormat = "bzImage (i386)"
    ),
    ARM32(
        code = 3,
        displayName = "ARM32 (ARMv7-A)",
        isImplementedInPhase1 = false,
        defaultKernelFormat = "zImage (ARMv7)"
    );

    companion object {
        fun fromCode(code: Int): GuestArchitecture {
            return entries.find { it.code == code } ?: ARM64
        }

        fun fromString(str: String): GuestArchitecture {
            return when {
                str.contains("x86_64", ignoreCase = true) || str.contains("amd64", ignoreCase = true) -> X86_64
                str.contains("x86", ignoreCase = true) || str.contains("i386", ignoreCase = true) -> X86
                str.contains("arm32", ignoreCase = true) || str.contains("armv7", ignoreCase = true) -> ARM32
                else -> ARM64
            }
        }
    }
}
