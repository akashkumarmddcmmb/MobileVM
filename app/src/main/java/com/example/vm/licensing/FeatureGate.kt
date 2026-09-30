package com.example.vm.licensing

import com.example.vm.core.VMConfig

/**
 * Mobile BM Entitlement & Feature Gatekeeper.
 *
 * Enforces server-issued license tier limits across the virtual machine hypervisor:
 *  - Free Tier: Max 1 vCPU, Max 512MB RAM, Standard Serial Console, Official OS downloads.
 *  - Pro Tier: Max 4 vCPUs, Max 2048MB RAM, Disk Snapshots, Custom Images, Hardware Keys.
 *  - Premium Tier: Max 8 vCPUs, Max 4096MB RAM, VM Export, Headless Mode, High-Res Framebuffer.
 *  - Enterprise Tier: Max 16 vCPUs, Max 16384MB RAM, Unlimited Resources, Isolated Network Bridges.
 */
object FeatureGate {

    sealed class GateResult {
        object Allowed : GateResult()
        data class Restricted(
            val reason: String,
            val requiredTier: LicenseTier,
            val currentTier: LicenseTier
        ) : GateResult()
    }

    /**
     * Authoritative validation of VM configurations against active license entitlements.
     */
    fun verifyVmConfig(config: VMConfig, entitlements: LicenseEntitlements): GateResult {
        // 1. Check vCPU allocation
        if (config.cpuCores > entitlements.maxCpuCores) {
            val required = when {
                config.cpuCores <= 4 -> LicenseTier.PRO
                config.cpuCores <= 8 -> LicenseTier.PREMIUM
                else -> LicenseTier.ENTERPRISE
            }
            return GateResult.Restricted(
                reason = "Allocation of ${config.cpuCores} vCPUs exceeds your license limit of ${entitlements.maxCpuCores} core(s).",
                requiredTier = required,
                currentTier = entitlements.tier
            )
        }

        // 2. Check RAM allocation
        if (config.ramSizeMb > entitlements.maxRamMb) {
            val required = when {
                config.ramSizeMb <= 2048 -> LicenseTier.PRO
                config.ramSizeMb <= 4096 -> LicenseTier.PREMIUM
                else -> LicenseTier.ENTERPRISE
            }
            return GateResult.Restricted(
                reason = "Allocation of ${config.ramSizeMb}MB RAM exceeds your license limit of ${entitlements.maxRamMb}MB.",
                requiredTier = required,
                currentTier = entitlements.tier
            )
        }

        return GateResult.Allowed
    }

    /**
     * Checks if a specific feature key is permitted by active entitlements.
     */
    fun isFeatureAllowed(featureKey: String, entitlements: LicenseEntitlements): Boolean {
        return entitlements.allowedFeatures.contains(featureKey)
    }

    fun canCreateSnapshots(entitlements: LicenseEntitlements): Boolean = entitlements.canCreateSnapshots

    fun canExportVm(entitlements: LicenseEntitlements): Boolean = entitlements.canExportVm

    fun canUseCustomDisks(entitlements: LicenseEntitlements): Boolean = entitlements.canUseCustomDisks

    fun canUsePriorityCpu(entitlements: LicenseEntitlements): Boolean = entitlements.canUsePriorityCpu

    fun canUseHeadlessMode(entitlements: LicenseEntitlements): Boolean = entitlements.canUseHeadlessMode
}
