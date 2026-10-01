package com.example.vm.guest.os

enum class AssetVerificationStatus {
    UNVERIFIED,
    VERIFIED,
    FAILED
}

/**
 * Linux Guest Asset Specification Model.
 * Represents verified guest boot assets (kernel, initramfs, root filesystem disk, DTB).
 */
data class LinuxGuestAssets(
    val kernelPath: String = "",
    val initramfsPath: String = "",
    val diskPath: String = "",
    val dtbPath: String = "",
    val architecture: String = "ARM64",
    val format: String = "IMG",
    val kernelSha256: String = "",
    val initramfsSha256: String = "",
    val diskSha256: String = "",
    val sourceUrl: String = "",
    val verificationStatus: AssetVerificationStatus = AssetVerificationStatus.UNVERIFIED,
    val statusMessage: String = "Unverified"
)
