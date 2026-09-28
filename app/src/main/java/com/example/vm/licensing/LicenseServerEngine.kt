package com.example.vm.licensing

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mobile BM Authoritative License Server & Admin Engine.
 *
 * Implements the server-side business logic, security validations, and database
 * operations corresponding to:
 *   - POST /api/license/activate
 *   - POST /api/license/validate
 *   - POST /api/license/deactivate
 *   - POST /api/license/revoke
 *   - Admin License Generation & Audit Log Tracking
 *
 * Security Features:
 *   1. Nonce & Timestamp Anti-Replay: Rejects duplicate nonces or requests drifted > 5 min.
 *   2. Cryptographic Hashing: Keys stored by SHA-256 hash, never in plaintext.
 *   3. HMAC Signature: Server responses are digitally signed using server authority key.
 *   4. Multi-Device Quota: Limits concurrent activations to license quota (e.g. 3 devices).
 *   5. Instant Revocation: Admin revocation prevents any further validation.
 */
object LicenseServerEngine {

    private val SERVER_SIGNING_SECRET = "MOBILE_BM_SERVER_AUTHORITY_MASTER_KEY_2026".toByteArray(Charsets.UTF_8)
    private const val ADMIN_MASTER_SECRET = "MBM_ADMIN_SECRET_AUTH_KEY_2026"
    private const val MAX_CLOCK_DRIFT_MS = 300_000L // 5 minutes

    // Internal In-Memory Server Database (Simulating PostgreSQL/MySQL table)
    data class ServerLicenseRecord(
        val keyHash: String,
        val keyMasked: String,
        val tier: LicenseTier,
        var status: LicenseStatus,
        val issuedTo: String,
        val issuedAt: Long,
        val expiresAt: Long, // 0 for perpetual
        val maxDevices: Int,
        val activeDeviceIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val entitlements: LicenseEntitlements
    )

    data class ServerAuditLog(
        val timestamp: Long,
        val action: String,
        val keyHash: String,
        val deviceId: String,
        val status: String,
        val message: String
    )

    private val licenseDb = ConcurrentHashMap<String, ServerLicenseRecord>()
    private val processedNonces = ConcurrentHashMap.newKeySet<String>()
    private val auditLogs = mutableListOf<ServerAuditLog>()

    init {
        seedInitialLicenses()
    }

    private fun seedInitialLicenses() {
        // Pre-seed official demonstration & test keys for all tiers (Supports both MVM and legacy MBM prefixes)
        listOf("MVM", "MBM").forEach { prefix ->
            createLicenseInternal(
                rawKey = "$prefix-PRO-2026-TEST-7890-ABCD",
                tier = LicenseTier.PRO,
                issuedTo = "Professional Developer",
                expiresAt = System.currentTimeMillis() + (365L * 24 * 60 * 60 * 1000L), // 1 year
                maxDevices = 3
            )

            createLicenseInternal(
                rawKey = "$prefix-PREMIUM-2026-POWER-4321-EFGH",
                tier = LicenseTier.PREMIUM,
                issuedTo = "Power User",
                expiresAt = 0L, // Lifetime / Perpetual
                maxDevices = 5
            )

            createLicenseInternal(
                rawKey = "$prefix-ENTERPRISE-2026-CORP-9999-XYZW",
                tier = LicenseTier.ENTERPRISE,
                issuedTo = "Enterprise Infrastructure Lab",
                expiresAt = 0L, // Lifetime
                maxDevices = 20
            )

            createLicenseInternal(
                rawKey = "$prefix-REVOKED-2026-BADK-0000-FAIL",
                tier = LicenseTier.PRO,
                issuedTo = "Revoked Test Account",
                expiresAt = 0L,
                maxDevices = 1
            ).also {
                it.status = LicenseStatus.REVOKED
            }
        }
    }

    /**
     * Endpoint: POST /api/license/activate
     */
    @Synchronized
    fun handleActivate(request: LicenseActivationRequest): LicenseActivationResponse {
        val now = System.currentTimeMillis()

        // 1. Validate Nonce & Clock Drift (Replay Protection)
        if (!validateNonceAndTimestamp(request.nonce, request.clientTimestamp, now)) {
            logAudit("ACTIVATE", request.licenseKey, request.deviceId, "FAILED", "Replay or clock drift detected")
            return LicenseActivationResponse(
                success = false,
                status = LicenseStatus.INVALID,
                tier = LicenseTier.FREE,
                issuedTo = "Unknown",
                expiresAt = 0L,
                entitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE),
                activeDeviceCount = 0,
                maxDevices = 0,
                serverTimestamp = now,
                signature = "",
                message = "Activation rejected: Request expired or duplicate nonce."
            )
        }

        // 2. Look up license by key hash
        val keyHash = computeSha256(request.licenseKey.trim().uppercase())
        val record = licenseDb[keyHash]

        if (record == null) {
            logAudit("ACTIVATE", request.licenseKey, request.deviceId, "FAILED", "License key not found")
            return LicenseActivationResponse(
                success = false,
                status = LicenseStatus.INVALID,
                tier = LicenseTier.FREE,
                issuedTo = "Unknown",
                expiresAt = 0L,
                entitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE),
                activeDeviceCount = 0,
                maxDevices = 0,
                serverTimestamp = now,
                signature = "",
                message = "Invalid license key: Key does not exist."
            )
        }

        // 3. Check Revocation & Expiration
        if (record.status == LicenseStatus.REVOKED) {
            logAudit("ACTIVATE", request.licenseKey, request.deviceId, "FAILED", "License revoked")
            return LicenseActivationResponse(
                success = false,
                status = LicenseStatus.REVOKED,
                tier = record.tier,
                issuedTo = record.issuedTo,
                expiresAt = record.expiresAt,
                entitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE),
                activeDeviceCount = record.activeDeviceIds.size,
                maxDevices = record.maxDevices,
                serverTimestamp = now,
                signature = "",
                message = "This license key has been revoked by administration."
            )
        }

        if (record.expiresAt > 0L && now > record.expiresAt) {
            record.status = LicenseStatus.EXPIRED
            logAudit("ACTIVATE", request.licenseKey, request.deviceId, "FAILED", "License expired")
            return LicenseActivationResponse(
                success = false,
                status = LicenseStatus.EXPIRED,
                tier = record.tier,
                issuedTo = record.issuedTo,
                expiresAt = record.expiresAt,
                entitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE),
                activeDeviceCount = record.activeDeviceIds.size,
                maxDevices = record.maxDevices,
                serverTimestamp = now,
                signature = "",
                message = "This license key has expired."
            )
        }

        // 4. Check Device Activation Quota
        val alreadyActiveOnThisDevice = record.activeDeviceIds.contains(request.deviceId)
        if (!alreadyActiveOnThisDevice && record.activeDeviceIds.size >= record.maxDevices) {
            logAudit("ACTIVATE", request.licenseKey, request.deviceId, "FAILED", "Device limit reached (${record.maxDevices})")
            return LicenseActivationResponse(
                success = false,
                status = record.status,
                tier = record.tier,
                issuedTo = record.issuedTo,
                expiresAt = record.expiresAt,
                entitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE),
                activeDeviceCount = record.activeDeviceIds.size,
                maxDevices = record.maxDevices,
                serverTimestamp = now,
                signature = "",
                message = "Device limit reached! This license is already active on ${record.maxDevices} devices. Deactivate another device first."
            )
        }

        // 5. Activate device
        record.activeDeviceIds.add(request.deviceId)
        record.status = LicenseStatus.ACTIVE

        val signature = computeServerSignature(
            keyHash = keyHash,
            deviceId = request.deviceId,
            tier = record.tier,
            status = record.status,
            expiresAt = record.expiresAt,
            serverTimestamp = now
        )

        logAudit("ACTIVATE", request.licenseKey, request.deviceId, "SUCCESS", "Activated on device ${request.deviceId}")

        return LicenseActivationResponse(
            success = true,
            status = LicenseStatus.ACTIVE,
            tier = record.tier,
            issuedTo = record.issuedTo,
            expiresAt = record.expiresAt,
            entitlements = record.entitlements,
            activeDeviceCount = record.activeDeviceIds.size,
            maxDevices = record.maxDevices,
            serverTimestamp = now,
            signature = signature,
            message = "License successfully activated for ${record.tier.displayName}."
        )
    }

    /**
     * Endpoint: POST /api/license/validate
     */
    @Synchronized
    fun handleValidate(request: LicenseValidationRequest): LicenseValidationResponse {
        val now = System.currentTimeMillis()

        if (!validateNonceAndTimestamp(request.nonce, request.clientTimestamp, now)) {
            return LicenseValidationResponse(
                success = false,
                status = LicenseStatus.INVALID,
                tier = LicenseTier.FREE,
                expiresAt = 0L,
                entitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE),
                serverTimestamp = now,
                signature = "",
                message = "Validation rejected: Replay or clock drift detected."
            )
        }

        val keyHash = computeSha256(request.licenseKey.trim().uppercase())
        val record = licenseDb[keyHash]

        if (record == null) {
            return LicenseValidationResponse(
                success = false,
                status = LicenseStatus.INVALID,
                tier = LicenseTier.FREE,
                expiresAt = 0L,
                entitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE),
                serverTimestamp = now,
                signature = "",
                message = "License key not recognized."
            )
        }

        if (record.status == LicenseStatus.REVOKED) {
            return LicenseValidationResponse(
                success = false,
                status = LicenseStatus.REVOKED,
                tier = record.tier,
                expiresAt = record.expiresAt,
                entitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE),
                serverTimestamp = now,
                signature = "",
                message = "License has been revoked."
            )
        }

        if (record.expiresAt > 0L && now > record.expiresAt) {
            record.status = LicenseStatus.EXPIRED
            return LicenseValidationResponse(
                success = false,
                status = LicenseStatus.EXPIRED,
                tier = record.tier,
                expiresAt = record.expiresAt,
                entitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE),
                serverTimestamp = now,
                signature = "",
                message = "License has expired."
            )
        }

        // Confirm device is currently registered
        if (!record.activeDeviceIds.contains(request.deviceId)) {
            return LicenseValidationResponse(
                success = false,
                status = LicenseStatus.INVALID,
                tier = record.tier,
                expiresAt = record.expiresAt,
                entitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE),
                serverTimestamp = now,
                signature = "",
                message = "Device not activated for this license key."
            )
        }

        val signature = computeServerSignature(
            keyHash = keyHash,
            deviceId = request.deviceId,
            tier = record.tier,
            status = LicenseStatus.ACTIVE,
            expiresAt = record.expiresAt,
            serverTimestamp = now
        )

        return LicenseValidationResponse(
            success = true,
            status = LicenseStatus.ACTIVE,
            tier = record.tier,
            expiresAt = record.expiresAt,
            entitlements = record.entitlements,
            serverTimestamp = now,
            signature = signature,
            message = "License validated successfully."
        )
    }

    /**
     * Endpoint: POST /api/license/deactivate
     */
    @Synchronized
    fun handleDeactivate(request: LicenseDeactivationRequest): LicenseDeactivationResponse {
        val keyHash = computeSha256(request.licenseKey.trim().uppercase())
        val record = licenseDb[keyHash]

        if (record == null) {
            return LicenseDeactivationResponse(success = false, message = "License key not found.")
        }

        record.activeDeviceIds.remove(request.deviceId)
        logAudit("DEACTIVATE", request.licenseKey, request.deviceId, "SUCCESS", "Deactivated device slot")

        return LicenseDeactivationResponse(
            success = true,
            message = "Device slot freed successfully. Remaining active: ${record.activeDeviceIds.size}/${record.maxDevices}"
        )
    }

    /**
     * Endpoint: POST /api/license/revoke (Admin only)
     */
    @Synchronized
    fun handleRevoke(request: LicenseRevocationRequest): LicenseRevocationResponse {
        if (request.adminSecret != ADMIN_MASTER_SECRET) {
            return LicenseRevocationResponse(success = false, message = "Unauthorized: Invalid admin secret.")
        }

        val keyHash = computeSha256(request.licenseKey.trim().uppercase())
        val record = licenseDb[keyHash] ?: return LicenseRevocationResponse(success = false, message = "License key not found.")

        record.status = LicenseStatus.REVOKED
        record.activeDeviceIds.clear()
        logAudit("REVOKE", request.licenseKey, "ADMIN", "SUCCESS", "Revoked: ${request.reason}")

        return LicenseRevocationResponse(
            success = true,
            message = "License successfully revoked. Reason: ${request.reason}"
        )
    }

    /**
     * Admin: Generate a new proprietary license key with cryptographic formatting.
     * Format: MBM-<TIER>-<YEAR>-<RANDOM_ALPHANUMERIC>-<CHECKSUM>
     */
    @Synchronized
    fun generateNewLicense(
        tier: LicenseTier,
        issuedTo: String,
        expiresAt: Long,
        maxDevices: Int,
        adminSecret: String = ADMIN_MASTER_SECRET
    ): String {
        require(adminSecret == ADMIN_MASTER_SECRET) { "Admin authentication required" }

        val uuidSegment = UUID.randomUUID().toString().replace("-", "").uppercase().take(8)
        val tierPrefix = when (tier) {
            LicenseTier.FREE -> "FREE"
            LicenseTier.PRO -> "PRO"
            LicenseTier.PREMIUM -> "PREMIUM"
            LicenseTier.ENTERPRISE -> "ENTERPRISE"
        }
        val baseKey = "MVM-$tierPrefix-2026-$uuidSegment"
        val checksum = computeKeyChecksum(baseKey)
        val fullKey = "$baseKey-$checksum"

        createLicenseInternal(
            rawKey = fullKey,
            tier = tier,
            issuedTo = issuedTo,
            expiresAt = expiresAt,
            maxDevices = maxDevices
        )

        return fullKey
    }

    private fun createLicenseInternal(
        rawKey: String,
        tier: LicenseTier,
        issuedTo: String,
        expiresAt: Long,
        maxDevices: Int
    ): ServerLicenseRecord {
        val keyHash = computeSha256(rawKey.trim().uppercase())
        val masked = rawKey.take(8) + "-****-" + rawKey.takeLast(4)
        val entitlements = LicenseEntitlements.defaultFor(tier)

        val record = ServerLicenseRecord(
            keyHash = keyHash,
            keyMasked = masked,
            tier = tier,
            status = LicenseStatus.ACTIVE,
            issuedTo = issuedTo,
            issuedAt = System.currentTimeMillis(),
            expiresAt = expiresAt,
            maxDevices = maxDevices,
            entitlements = entitlements
        )
        licenseDb[keyHash] = record
        return record
    }

    private fun validateNonceAndTimestamp(nonce: String, clientTimestamp: Long, serverNow: Long): Boolean {
        if (nonce.isBlank()) return false
        if (processedNonces.contains(nonce)) return false

        // Check clock drift (must be within 5 minutes)
        if (kotlin.math.abs(serverNow - clientTimestamp) > MAX_CLOCK_DRIFT_MS) {
            return false
        }

        processedNonces.add(nonce)
        // Cleanup old nonces if set grows excessively
        if (processedNonces.size > 10_000) {
            processedNonces.clear()
        }
        return true
    }

    private fun computeServerSignature(
        keyHash: String,
        deviceId: String,
        tier: LicenseTier,
        status: LicenseStatus,
        expiresAt: Long,
        serverTimestamp: Long
    ): String {
        return try {
            val payload = "$keyHash:$deviceId:${tier.name}:${status.name}:$expiresAt:$serverTimestamp"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(SERVER_SIGNING_SECRET, "HmacSHA256"))
            val hmac = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            hmac.joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            "SIG_FALLBACK_${keyHash.hashCode()}"
        }
    }

    fun verifyServerSignature(
        keyHash: String,
        deviceId: String,
        tier: LicenseTier,
        status: LicenseStatus,
        expiresAt: Long,
        serverTimestamp: Long,
        signature: String
    ): Boolean {
        val expected = computeServerSignature(keyHash, deviceId, tier, status, expiresAt, serverTimestamp)
        return expected.equals(signature, ignoreCase = true)
    }

    private fun computeSha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun computeKeyChecksum(baseKey: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(baseKey.toByteArray(Charsets.UTF_8))
        return hash.take(4).joinToString("") { "%02X".format(it) }
    }

    private fun logAudit(action: String, key: String, deviceId: String, status: String, message: String) {
        val keyHash = computeSha256(key.trim().uppercase())
        auditLogs.add(
            ServerAuditLog(
                timestamp = System.currentTimeMillis(),
                action = action,
                keyHash = keyHash.take(12) + "...",
                deviceId = deviceId,
                status = status,
                message = message
            )
        )
    }

    fun getAuditLogs(): List<ServerAuditLog> = auditLogs.toList()

    fun getRegisteredKeyCount(): Int = licenseDb.size

    @Synchronized
    fun resetForTesting() {
        licenseDb.clear()
        processedNonces.clear()
        auditLogs.clear()
        seedInitialLicenses()
    }
}
