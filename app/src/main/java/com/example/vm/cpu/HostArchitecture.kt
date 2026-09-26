package com.example.vm.cpu

import android.os.Build

enum class HostArchitecture(val code: Int, val displayName: String) {
    ARM64(0, "ARM64 (aarch64)"),
    ARM32(1, "ARM32 (armv7a)"),
    X86_64(2, "x86_64 (amd64)"),
    X86(3, "x86 (i686)"),
    UNKNOWN(4, "Unknown Host CPU");

    companion object {
        fun detect(): HostArchitecture {
            val abi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase() ?: ""
            return when {
                abi.startsWith("arm64") || abi.startsWith("aarch64") -> ARM64
                abi.startsWith("armeabi") || abi.startsWith("armv7") -> ARM32
                abi.startsWith("x86_64") -> X86_64
                abi.startsWith("x86") -> X86
                else -> {
                    val arch = System.getProperty("os.arch")?.lowercase() ?: ""
                    when {
                        arch.contains("aarch64") || arch.contains("arm64") -> ARM64
                        arch.contains("arm") -> ARM32
                        arch.contains("x86_64") || arch.contains("amd64") -> X86_64
                        arch.contains("i386") || arch.contains("i686") || arch.contains("x86") -> X86
                        else -> UNKNOWN
                    }
                }
            }
        }
    }
}
