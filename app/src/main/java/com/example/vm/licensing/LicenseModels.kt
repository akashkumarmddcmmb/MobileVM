package com.example.vm.licensing

/**
 * Tier levels for MobileVM proprietary licensing system.
 */
enum class LicenseTier(val displayName: String) {
    FREE("MobileVM Free / Community"),
    PRO("MobileVM Pro"),
    PREMIUM("MobileVM Premium"),
    ENTERPRISE("MobileVM Enterprise");

    companion object {
        fun fromString(name: String): LicenseTier {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: FREE
        }
    }
}

/**
 * Represents the current operational state of a license.
 */
enum class LicenseStatus(val displayName: String) {
    ACTIVE("Active / Valid"),
    TRIAL("Evaluation Trial"),
    OFFLINE_GRACE_PERIOD("Offline Grace Period"),
    EXPIRED("Expired"),
    REVOKED("Revoked / Blacklisted"),
    INVALID("Invalid / Unactivated");
}

data class LicenseEntitlements(
    val tier: LicenseTier,
    val maxCpuCores: Int,
    val maxRamMb: Long,
    val canExportVm: Boolean,
    val canCreateSnapshots: Boolean,
    val canUseCustomDisks: Boolean,
    val canUsePriorityCpu: Boolean,
    val canUseHeadlessMode: Boolean,
    val allowedFeatures: Set<String>
) {
    companion object {
        fun defaultFor(tier: LicenseTier): LicenseEntitlements {
            return when (tier) {
                LicenseTier.FREE -> LicenseEntitlements(
                    tier = LicenseTier.FREE,
                    maxCpuCores = 1,
                    maxRamMb = 512,
                    canExportVm = false,
                    canCreateSnapshots = false,
                    canUseCustomDisks = false,
                    canUsePriorityCpu = false,
                    canUseHeadlessMode = false,
                    allowedFeatures = setOf(
                        "feature_basic_vm",
                        "feature_serial_console",
                        "feature_official_os_download"
                    )
                )
                LicenseTier.PRO -> LicenseEntitlements(
                    tier = LicenseTier.PRO,
                    maxCpuCores = 4,
                    maxRamMb = 2048,
                    canExportVm = true,
                    canCreateSnapshots = true,
                    canUseCustomDisks = true,
                    canUsePriorityCpu = false,
                    canUseHeadlessMode = false,
                    allowedFeatures = setOf(
                        "feature_basic_vm",
                        "feature_serial_console",
                        "feature_official_os_download",
                        "feature_multi_core",
                        "feature_disk_snapshots",
                        "feature_custom_images",
                        "feature_hardware_key_injection"
                    )
                )
                LicenseTier.PREMIUM -> LicenseEntitlements(
                    tier = LicenseTier.PREMIUM,
                    maxCpuCores = 8,
                    maxRamMb = 4096,
                    canExportVm = true,
                    canCreateSnapshots = true,
                    canUseCustomDisks = true,
                    canUsePriorityCpu = true,
                    canUseHeadlessMode = true,
                    allowedFeatures = setOf(
                        "feature_basic_vm",
                        "feature_serial_console",
                        "feature_official_os_download",
                        "feature_multi_core",
                        "feature_disk_snapshots",
                        "feature_custom_images",
                        "feature_hardware_key_injection",
                        "feature_priority_cpu",
                        "feature_headless_mode",
                        "feature_high_res_framebuffer",
                        "feature_vm_export"
                    )
                )
                LicenseTier.ENTERPRISE -> LicenseEntitlements(
                    tier = LicenseTier.ENTERPRISE,
                    maxCpuCores = 16,
                    maxRamMb = 16384,
                    canExportVm = true,
                    canCreateSnapshots = true,
                    canUseCustomDisks = true,
                    canUsePriorityCpu = true,
                    canUseHeadlessMode = true,
                    allowedFeatures = setOf(
                        "feature_basic_vm",
                        "feature_serial_console",
                        "feature_official_os_download",
                        "feature_multi_core",
                        "feature_disk_snapshots",
                        "feature_custom_images",
                        "feature_hardware_key_injection",
                        "feature_priority_cpu",
                        "feature_headless_mode",
                        "feature_high_res_framebuffer",
                        "feature_vm_export",
                        "feature_unlimited_resources"
                    )
                )
            }
        }
    }
}

data class LicenseState(
    val status: LicenseStatus = LicenseStatus.INVALID,
    val tier: LicenseTier = LicenseTier.FREE,
    val licenseKey: String? = null,
    val issuedTo: String? = null,
    val deviceId: String = "",
    val activeDeviceCount: Int = 1,
    val maxDevices: Int = 1,
    val expiresAt: Long = 0L,
    val isPerpetual: Boolean = false,
    val lastVerifiedAt: Long = 0L,
    val lastValidatedAt: Long = 0L,
    val offlineGracePeriodRemainingMs: Long = 0L,
    val isTimeTampered: Boolean = false,
    val entitlements: LicenseEntitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE),
    val serverMessage: String? = null
)

data class LicenseRecord(
    val licenseKey: String,
    val licenseHash: String = "",
    val tier: LicenseTier,
    val status: LicenseStatus,
    val issuedTo: String,
    val issuedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long,
    val lastValidatedAt: Long = System.currentTimeMillis(),
    val activeDevices: List<String> = emptyList(),
    val maxDevices: Int = 3,
    val entitlements: LicenseEntitlements,
    val signature: String = ""
)

data class LicenseActivationRequest(
    val licenseKey: String,
    val deviceId: String,
    val deviceName: String = "",
    val packageId: String = "",
    val signatureFingerprint: String = "",
    val nonce: String = "",
    val clientTimestamp: Long = System.currentTimeMillis()
)

data class LicenseActivationResponse(
    val success: Boolean,
    val status: LicenseStatus,
    val tier: LicenseTier,
    val issuedTo: String,
    val expiresAt: Long,
    val entitlements: LicenseEntitlements,
    val activeDeviceCount: Int,
    val maxDevices: Int,
    val serverTimestamp: Long,
    val signature: String,
    val message: String? = null
)

data class LicenseValidationRequest(
    val licenseKey: String,
    val deviceId: String,
    val packageId: String = "",
    val signatureFingerprint: String = "",
    val nonce: String = "",
    val clientTimestamp: Long = System.currentTimeMillis()
)

data class LicenseValidationResponse(
    val success: Boolean,
    val status: LicenseStatus,
    val tier: LicenseTier,
    val expiresAt: Long,
    val entitlements: LicenseEntitlements,
    val serverTimestamp: Long,
    val signature: String,
    val message: String? = null
)

data class LicenseDeactivationRequest(
    val licenseKey: String,
    val deviceId: String,
    val clientTimestamp: Long = System.currentTimeMillis()
)

data class LicenseDeactivationResponse(
    val success: Boolean,
    val message: String? = null
)

data class LicenseRevocationRequest(
    val licenseKey: String,
    val adminSecret: String,
    val reason: String
)

data class LicenseRevocationResponse(
    val success: Boolean,
    val message: String? = null
)
