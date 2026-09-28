package com.example.vm.licensing

import android.content.Context
import com.example.vm.security.ProjectProtectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Mobile BM Central License Orchestrator & Protection Coordinator.
 *
 * Implements:
 *  - Machine-locking to Device Hardware Fingerprint.
 *  - Tamper & Clock Rollback Detection via [TimeTamperDetector].
 *  - Encrypted Offline Vault Management with 7-Day Grace Period via [OfflineLicenseVault].
 *  - Authoritative Server-Side Validation via [LicenseApiClient].
 *  - Dynamic Feature Entitlement distribution via [FeatureGate].
 */
class LicenseManager private constructor(private val context: Context) {

    private val deviceId = DeviceFingerprintGenerator.getDeviceId(context)
    private val timeTamperDetector = TimeTamperDetector(context)
    private val offlineVault = OfflineLicenseVault(context)
    private val apiClient = LicenseApiClient()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val _licenseState = MutableStateFlow(createInitialState())
    val licenseState: StateFlow<LicenseState> = _licenseState.asStateFlow()

    companion object {
        @Volatile
        private var INSTANCE: LicenseManager? = null

        fun getInstance(context: Context): LicenseManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LicenseManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun resetForTesting(context: Context): LicenseManager {
            return synchronized(this) {
                val newMgr = LicenseManager(context.applicationContext)
                INSTANCE = newMgr
                newMgr
            }
        }
    }

    init {
        initializeLicenseState()
    }

    private fun createInitialState(): LicenseState {
        val freeEntitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE)
        return LicenseState(
            status = LicenseStatus.INVALID,
            tier = LicenseTier.FREE,
            licenseKey = null,
            issuedTo = null,
            expiresAt = 0L,
            isPerpetual = true,
            entitlements = freeEntitlements,
            deviceId = deviceId,
            activeDeviceCount = 1,
            maxDevices = 1,
            offlineGracePeriodRemainingMs = 0L,
            isTimeTampered = false,
            lastValidatedAt = 0L,
            serverMessage = "Running Free Tier (Default)"
        )
    }

    /**
     * Loads the offline license, verifies integrity, checks clock tampering,
     * and sets up the active state.
     */
    fun initializeLicenseState() {
        // 1. Check time tampering / clock rollback
        val isClockManipulated = timeTamperDetector.isClockManipulated()

        // 2. Load stored license from vault
        val record = offlineVault.loadLicense(deviceId)

        if (record == null) {
            // No stored license, remain on Free
            _licenseState.value = createInitialState().copy(
                isTimeTampered = isClockManipulated
            )
            return
        }

        if (isClockManipulated) {
            // Clock tampering detected! Invalidate grace period
            _licenseState.value = _licenseState.value.copy(
                status = LicenseStatus.INVALID,
                tier = LicenseTier.FREE,
                licenseKey = record.licenseKey,
                entitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE),
                isTimeTampered = true,
                serverMessage = "SECURITY ALERT: System clock rollback detected! Offline license invalidated."
            )
            return
        }

        // 3. Check expiration
        val now = System.currentTimeMillis()
        if (record.expiresAt > 0L && now > record.expiresAt) {
            _licenseState.value = _licenseState.value.copy(
                status = LicenseStatus.EXPIRED,
                tier = record.tier,
                licenseKey = record.licenseKey,
                issuedTo = record.issuedTo,
                expiresAt = record.expiresAt,
                isPerpetual = false,
                entitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE),
                lastValidatedAt = record.lastValidatedAt,
                serverMessage = "Your license for ${record.tier.displayName} expired on ${java.util.Date(record.expiresAt)}."
            )
            return
        }

        // 4. Calculate remaining offline grace period
        val graceRemaining = offlineVault.getRemainingOfflineGracePeriodMs(record.lastValidatedAt, now)
        val status = if (graceRemaining > 0L) {
            if (record.status == LicenseStatus.TRIAL) LicenseStatus.TRIAL else LicenseStatus.OFFLINE_GRACE_PERIOD
        } else {
            LicenseStatus.EXPIRED
        }

        val effectiveTier = if (status == LicenseStatus.EXPIRED) LicenseTier.FREE else record.tier
        val effectiveEntitlements = LicenseEntitlements.defaultFor(effectiveTier)

        _licenseState.value = LicenseState(
            status = status,
            tier = record.tier,
            licenseKey = record.licenseKey,
            issuedTo = record.issuedTo,
            expiresAt = record.expiresAt,
            isPerpetual = record.expiresAt == 0L,
            entitlements = effectiveEntitlements,
            deviceId = deviceId,
            activeDeviceCount = record.activeDevices.size,
            maxDevices = record.maxDevices,
            offlineGracePeriodRemainingMs = graceRemaining,
            isTimeTampered = false,
            lastValidatedAt = record.lastValidatedAt,
            serverMessage = if (status == LicenseStatus.OFFLINE_GRACE_PERIOD) {
                "Offline Grace Period active (${graceRemaining / (1000 * 60 * 60)} hours remaining)"
            } else {
                "Active license for ${record.tier.displayName}"
            }
        )

        // Asynchronously revalidate with server in background if possible
        coroutineScope.launch {
            try {
                refreshValidation()
            } catch (_: Exception) {
                // Keep offline grace period if network unavailable
            }
        }
    }

    /**
     * Activates a new license key on this device.
     */
    suspend fun activateKey(rawKey: String): Result<LicenseActivationResponse> {
        val trimmedKey = rawKey.trim().uppercase()
        if (trimmedKey.isBlank()) {
            return Result.failure(IllegalArgumentException("License key cannot be empty."))
        }

        // Run security / integrity audit
        val audit = ProjectProtectionManager.performProtectionAudit(context)
        if (!audit.isPackageAuthentic) {
            return Result.failure(IllegalStateException("License activation blocked: Modified or cloned package identity."))
        }

        return try {
            val response = apiClient.activate(
                licenseKey = trimmedKey,
                deviceId = deviceId,
                deviceName = DeviceFingerprintGenerator.getDeviceFriendlyName(),
                packageId = context.packageName,
                signatureFingerprint = audit.signatureHash
            )

            if (response.success && response.status == LicenseStatus.ACTIVE) {
                val now = System.currentTimeMillis()
                timeTamperDetector.recordCheckpoint(response.serverTimestamp)

                val record = LicenseRecord(
                    licenseKey = trimmedKey,
                    licenseHash = trimmedKey.hashCode().toString(),
                    tier = response.tier,
                    status = LicenseStatus.ACTIVE,
                    issuedTo = response.issuedTo,
                    issuedAt = now,
                    expiresAt = response.expiresAt,
                    maxDevices = response.maxDevices,
                    activeDevices = List(response.activeDeviceCount) { "device_$it" },
                    entitlements = response.entitlements,
                    lastValidatedAt = now,
                    signature = response.signature
                )

                offlineVault.saveLicense(record, deviceId)

                _licenseState.value = LicenseState(
                    status = LicenseStatus.ACTIVE,
                    tier = response.tier,
                    licenseKey = trimmedKey,
                    issuedTo = response.issuedTo,
                    expiresAt = response.expiresAt,
                    isPerpetual = response.expiresAt == 0L,
                    entitlements = response.entitlements,
                    deviceId = deviceId,
                    activeDeviceCount = response.activeDeviceCount,
                    maxDevices = response.maxDevices,
                    offlineGracePeriodRemainingMs = OfflineLicenseVault.DEFAULT_OFFLINE_GRACE_PERIOD_MS,
                    isTimeTampered = false,
                    lastValidatedAt = now,
                    serverMessage = response.message
                )
                Result.success(response)
            } else {
                Result.failure(IllegalStateException(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Revalidates the currently active license key with the server authority.
     */
    suspend fun refreshValidation(): Result<LicenseValidationResponse> {
        val currentKey = _licenseState.value.licenseKey ?: return Result.failure(IllegalStateException("No license active to validate."))
        val audit = ProjectProtectionManager.performProtectionAudit(context)

        return try {
            val response = apiClient.validate(
                licenseKey = currentKey,
                deviceId = deviceId,
                packageId = context.packageName,
                signatureFingerprint = audit.signatureHash
            )

            val now = System.currentTimeMillis()
            if (response.success && response.status == LicenseStatus.ACTIVE) {
                timeTamperDetector.recordCheckpoint(response.serverTimestamp)

                val existing = offlineVault.loadLicense(deviceId)
                val updatedRecord = LicenseRecord(
                    licenseKey = currentKey,
                    licenseHash = currentKey.hashCode().toString(),
                    tier = response.tier,
                    status = LicenseStatus.ACTIVE,
                    issuedTo = existing?.issuedTo ?: "Registered User",
                    issuedAt = existing?.issuedAt ?: now,
                    expiresAt = response.expiresAt,
                    maxDevices = existing?.maxDevices ?: 3,
                    activeDevices = existing?.activeDevices ?: listOf(deviceId),
                    entitlements = response.entitlements,
                    lastValidatedAt = now,
                    signature = response.signature
                )

                offlineVault.saveLicense(updatedRecord, deviceId)

                _licenseState.value = _licenseState.value.copy(
                    status = LicenseStatus.ACTIVE,
                    tier = response.tier,
                    expiresAt = response.expiresAt,
                    entitlements = response.entitlements,
                    offlineGracePeriodRemainingMs = OfflineLicenseVault.DEFAULT_OFFLINE_GRACE_PERIOD_MS,
                    lastValidatedAt = now,
                    serverMessage = response.message
                )
                Result.success(response)
            } else {
                // If revoked or expired, downgrade immediately
                if (response.status == LicenseStatus.REVOKED || response.status == LicenseStatus.EXPIRED) {
                    offlineVault.clear()
                    _licenseState.value = createInitialState().copy(
                        status = response.status,
                        serverMessage = response.message
                    )
                }
                Result.failure(IllegalStateException(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deactivates the current license key and frees the device slot on the server.
     */
    suspend fun deactivateCurrentKey(): Result<Boolean> {
        val currentKey = _licenseState.value.licenseKey ?: return Result.success(true)

        return try {
            val response = apiClient.deactivate(currentKey, deviceId)
            offlineVault.clear()
            _licenseState.value = createInitialState().copy(
                serverMessage = "License successfully deactivated. Device slot released."
            )
            Result.success(response.success)
        } catch (e: Exception) {
            offlineVault.clear()
            _licenseState.value = createInitialState()
            Result.success(true)
        }
    }

    /**
     * Activates a 14-day evaluation trial for Pro tier.
     */
    fun startEvaluationTrial(): Boolean {
        val now = System.currentTimeMillis()
        val trialExpires = now + (14L * 24 * 60 * 60 * 1000L) // 14 days
        val trialKey = "MBM-TRIAL-PRO-EVAL-2026"
        val proEntitlements = LicenseEntitlements.defaultFor(LicenseTier.PRO)

        val record = LicenseRecord(
            licenseKey = trialKey,
            licenseHash = trialKey.hashCode().toString(),
            tier = LicenseTier.PRO,
            status = LicenseStatus.TRIAL,
            issuedTo = "Trial Evaluation User",
            issuedAt = now,
            expiresAt = trialExpires,
            maxDevices = 1,
            activeDevices = listOf(deviceId),
            entitlements = proEntitlements,
            lastValidatedAt = now,
            signature = "EVAL_TRIAL_SIGNATURE"
        )

        offlineVault.saveLicense(record, deviceId)
        timeTamperDetector.recordCheckpoint(now)

        _licenseState.value = LicenseState(
            status = LicenseStatus.TRIAL,
            tier = LicenseTier.PRO,
            licenseKey = trialKey,
            issuedTo = "Trial Evaluation User",
            expiresAt = trialExpires,
            isPerpetual = false,
            entitlements = proEntitlements,
            deviceId = deviceId,
            activeDeviceCount = 1,
            maxDevices = 1,
            offlineGracePeriodRemainingMs = OfflineLicenseVault.DEFAULT_OFFLINE_GRACE_PERIOD_MS,
            isTimeTampered = false,
            lastValidatedAt = now,
            serverMessage = "14-Day Pro Trial Active."
        )
        return true
    }

    fun getCurrentEntitlements(): LicenseEntitlements = _licenseState.value.entitlements

    fun getDeviceId(): String = deviceId
}
