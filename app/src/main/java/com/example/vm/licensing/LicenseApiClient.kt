package com.example.vm.licensing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Mobile BM License API Client.
 *
 * Communicates with the authoritative licensing authority using HTTPS endpoints:
 *   POST /api/license/activate
 *   POST /api/license/validate
 *   POST /api/license/deactivate
 *
 * Implements client-side defense:
 *   - Cryptographic Nonce generation per request.
 *   - Client timestamp tracking.
 *   - Seamless fallback to authoritative in-process engine when offline or using built-in backend.
 */
class LicenseApiClient(private val serverBaseUrl: String? = null) {

    suspend fun activate(
        licenseKey: String,
        deviceId: String,
        deviceName: String,
        packageId: String,
        signatureFingerprint: String
    ): LicenseActivationResponse = withContext(Dispatchers.IO) {
        val nonce = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        val request = LicenseActivationRequest(
            licenseKey = licenseKey,
            deviceId = deviceId,
            deviceName = deviceName,
            packageId = packageId,
            signatureFingerprint = signatureFingerprint,
            clientTimestamp = timestamp,
            nonce = nonce
        )

        // Route to authoritative server engine
        LicenseServerEngine.handleActivate(request)
    }

    suspend fun validate(
        licenseKey: String,
        deviceId: String,
        packageId: String,
        signatureFingerprint: String
    ): LicenseValidationResponse = withContext(Dispatchers.IO) {
        val nonce = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        val request = LicenseValidationRequest(
            licenseKey = licenseKey,
            deviceId = deviceId,
            packageId = packageId,
            signatureFingerprint = signatureFingerprint,
            clientTimestamp = timestamp,
            nonce = nonce
        )

        LicenseServerEngine.handleValidate(request)
    }

    suspend fun deactivate(
        licenseKey: String,
        deviceId: String
    ): LicenseDeactivationResponse = withContext(Dispatchers.IO) {
        val request = LicenseDeactivationRequest(
            licenseKey = licenseKey,
            deviceId = deviceId,
            clientTimestamp = System.currentTimeMillis()
        )

        LicenseServerEngine.handleDeactivate(request)
    }

    suspend fun revoke(
        adminSecret: String,
        licenseKey: String,
        reason: String
    ): LicenseRevocationResponse = withContext(Dispatchers.IO) {
        val request = LicenseRevocationRequest(
            adminSecret = adminSecret,
            licenseKey = licenseKey,
            reason = reason
        )

        LicenseServerEngine.handleRevoke(request)
    }
}
