package com.example.vm.licensing

/**
 * MobileVM Proprietary Licensing System - Domain Models & Entitlements.
 *
 * Designed to enforce:
 *  1. Cryptographic license binding to device hardware & package ID.
 *  2. Multi-tier entitlements (FREE, PRO, PREMIUM, ENTERPRISE).
 *  3. Dynamic status tracking (ACTIVE, TRIAL, OFFLINE_GRACE_PERIOD, EXPIRED, REVOKED, INVALID).
 *  4. Tamper-evident serialization and verification.
 */

enum class LicenseStatus(val displayName: String) {
    ACTIVE("Active"),
    TRIAL("Trial"),
    OFFLINE_GRACE_PERIOD("Offline Grace Period"),
    EXPIRED("Expired"),
    REVOKED("Revoked"),
    INVALID("Invalid / Unactivated")
}

enum class LicenseTier(val displayName: String) {
    FREE("MobileVM Free"),
    PRO("MobileVM Pro"),
    PREMIUM("MobileVM Premium"),
    ENTERPRISE("MobileVM Enterprise")
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
                        "feature_enterprise_audit_log",
                        "feature_isolated_network_bridge",
                        "feature_unlimited_resources"
                    )
                )
            }
        }
    }
}

data class LicenseRecord(
    val licenseKey: String,
    val licenseHash: String,
    val tier: LicenseTier,
    val status: LicenseStatus,
    val issuedTo: String,
    val issuedAt: Long,
    val expiresAt: Long, // 0L means perpetual / lifetime
    val maxDevices: Int,
    val activeDevices: List<String>,
    val entitlements: LicenseEntitlements,
    val lastValidatedAt: Long,
    val signature: String
)

data class LicenseState(
    val status: LicenseStatus,
    val tier: LicenseTier,
    val licenseKey: String?,
    val issuedTo: String?,
    val expiresAt: Long,
    val isPerpetual: Boolean,
    val entitlements: LicenseEntitlements,
    val deviceId: String,
    val activeDeviceCount: Int,
    val maxDevices: Int,
    val offlineGracePeriodRemainingMs: Long,
    val isTimeTampered: Boolean,
    val lastValidatedAt: Long,
    val serverMessage: String?
)

// API Request and Response Models
data class LicenseActivationRequest(
    val licenseKey: String,
    val deviceId: String,
    val deviceName: String,
    val packageId: String,
    val signatureFingerprint: String,
    val clientTimestamp: Long,
    val nonce: String
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
    val message: String
)

data class LicenseValidationRequest(
    val licenseKey: String,
    val deviceId: String,
    val packageId: String,
    val signatureFingerprint: String,
    val clientTimestamp: Long,
    val nonce: String
)

data class LicenseValidationResponse(
    val success: Boolean,
    val status: LicenseStatus,
    val tier: LicenseTier,
    val expiresAt: Long,
    val entitlements: LicenseEntitlements,
    val serverTimestamp: Long,
    val signature: String,
    val message: String
)

data class LicenseDeactivationRequest(
    val licenseKey: String,
    val deviceId: String,
    val clientTimestamp: Long
)

data class LicenseDeactivationResponse(
    val success: Boolean,
    val message: String
)

data class LicenseRevocationRequest(
    val adminSecret: String,
    val licenseKey: String,
    val reason: String
)

data class LicenseRevocationResponse(
    val success: Boolean,
    val message: String
)
