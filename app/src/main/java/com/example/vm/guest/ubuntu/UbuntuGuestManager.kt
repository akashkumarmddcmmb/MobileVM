package com.example.vm.guest.ubuntu

/**
 * Ubuntu ARM64 Guest Module.
 * Manages Ubuntu root filesystem specifications, package ecosystem prerequisites,
 * dynamic linker requirements, and lightweight desktop environments (XFCE4).
 */
data class UbuntuSystemProfile(
    val releaseName: String = "Ubuntu 24.04 LTS (Noble Numbat)",
    val architecture: String = "aarch64",
    val dynamicLinkerPath: String = "/lib/ld-linux-aarch64.so.1",
    val defaultShell: String = "/bin/bash",
    val corePackages: List<String> = listOf("apt", "bash", "python3", "gcc", "g++", "git", "curl", "sudo"),
    val recommendedDesktop: String = "XFCE4 (Lightweight X11 Desktop)",
    val rootfsMinimumSizeGb: Int = 8,
    val recommendedRamMb: Int = 2048
)

object UbuntuGuestManager {
    fun getProfile(): UbuntuSystemProfile = UbuntuSystemProfile()

    fun verifyRootfsLayout(hasValidLinker: Boolean, hasEtcOsRelease: Boolean): Boolean {
        return hasValidLinker && hasEtcOsRelease
    }
}
