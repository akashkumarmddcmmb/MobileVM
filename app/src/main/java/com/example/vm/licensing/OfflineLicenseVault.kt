package com.example.vm.licensing

import android.content.Context
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mobile BM Offline License Vault.
 *
 * Provides tamper-resistant offline storage for the authenticated license record.
 * Protects against offline tampering using a cryptographic HMAC-SHA256 signature
 * bound to the device's unique hardware identifier.
 */
class OfflineLicenseVault(private val context: Context) {

    private val prefs = context.getSharedPreferences("mbm_license_vault", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LICENSE_KEY = "vlt_lic_key"
        private const val KEY_TIER = "vlt_tier"
        private const val KEY_STATUS = "vlt_status"
        private const val KEY_ISSUED_TO = "vlt_issued_to"
        private const val KEY_EXPIRES_AT = "vlt_expires_at"
        private const val KEY_LAST_VALIDATED_AT = "vlt_last_validated_at"
        private const val KEY_ACTIVE_DEVICES = "vlt_active_devices"
        private const val KEY_MAX_DEVICES = "vlt_max_devices"
        private const val KEY_FEATURES = "vlt_features"
        private const val KEY_HMAC_SIGNATURE = "vlt_hmac_sig"

        // Default offline grace period is 7 days (in milliseconds)
        const val DEFAULT_OFFLINE_GRACE_PERIOD_MS = 7 * 24 * 60 * 60 * 1000L
    }

    /**
     * Stores an authenticated license record with a cryptographic HMAC signature.
     */
    @Synchronized
    fun saveLicense(record: LicenseRecord, deviceId: String) {
        val hmac = computeVaultHmac(record, deviceId)

        prefs.edit()
            .putString(KEY_LICENSE_KEY, record.licenseKey)
            .putString(KEY_TIER, record.tier.name)
            .putString(KEY_STATUS, record.status.name)
            .putString(KEY_ISSUED_TO, record.issuedTo)
            .putLong(KEY_EXPIRES_AT, record.expiresAt)
            .putLong(KEY_LAST_VALIDATED_AT, record.lastValidatedAt)
            .putInt(KEY_ACTIVE_DEVICES, record.activeDevices.size)
            .putInt(KEY_MAX_DEVICES, record.maxDevices)
            .putStringSet(KEY_FEATURES, record.entitlements.allowedFeatures)
            .putString(KEY_HMAC_SIGNATURE, hmac)
            .apply()
    }

    /**
     * Reads the stored license record and verifies its cryptographic integrity.
     * Returns null if no record exists or if the record has been tampered with.
     */
    @Synchronized
    fun loadLicense(deviceId: String): LicenseRecord? {
        val licenseKey = prefs.getString(KEY_LICENSE_KEY, null) ?: return null
        val tierName = prefs.getString(KEY_TIER, LicenseTier.FREE.name) ?: LicenseTier.FREE.name
        val statusName = prefs.getString(KEY_STATUS, LicenseStatus.INVALID.name) ?: LicenseStatus.INVALID.name
        val issuedTo = prefs.getString(KEY_ISSUED_TO, "Valued User") ?: "Valued User"
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val lastValidatedAt = prefs.getLong(KEY_LAST_VALIDATED_AT, 0L)
        val activeDeviceCount = prefs.getInt(KEY_ACTIVE_DEVICES, 1)
        val maxDevices = prefs.getInt(KEY_MAX_DEVICES, 3)
        val storedHmac = prefs.getString(KEY_HMAC_SIGNATURE, null) ?: return null

        val tier = try { LicenseTier.valueOf(tierName) } catch (_: Exception) { LicenseTier.FREE }
        val status = try { LicenseStatus.valueOf(statusName) } catch (_: Exception) { LicenseStatus.INVALID }

        val entitlements = LicenseEntitlements.defaultFor(tier)

        val record = LicenseRecord(
            licenseKey = licenseKey,
            licenseHash = hashKey(licenseKey),
            tier = tier,
            status = status,
            issuedTo = issuedTo,
            issuedAt = lastValidatedAt,
            expiresAt = expiresAt,
            maxDevices = maxDevices,
            activeDevices = List(activeDeviceCount) { "dev_$it" },
            entitlements = entitlements,
            lastValidatedAt = lastValidatedAt,
            signature = storedHmac
        )

        // Verify cryptographic integrity
        val expectedHmac = computeVaultHmac(record, deviceId)
        if (storedHmac != expectedHmac) {
            // Tampering detected!
            clear()
            return null
        }

        return record
    }

    /**
     * Calculates the remaining offline grace period in milliseconds.
     */
    fun getRemainingOfflineGracePeriodMs(lastValidatedAt: Long, now: Long = System.currentTimeMillis()): Long {
        if (lastValidatedAt <= 0L) return 0L
        val elapsed = now - lastValidatedAt
        val remaining = DEFAULT_OFFLINE_GRACE_PERIOD_MS - elapsed
        return if (remaining > 0L) remaining else 0L
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun computeVaultHmac(record: LicenseRecord, deviceId: String): String {
        return try {
            val payload = "${record.licenseKey}:${record.tier.name}:${record.status.name}:" +
                    "${record.expiresAt}:${record.lastValidatedAt}:$deviceId:${context.packageName}"
            val secret = ("MBM_VAULT_KEY_" + deviceId).toByteArray(Charsets.UTF_8)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            val hmacBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            hmacBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "fallback_sig_${record.licenseKey.hashCode()}"
        }
    }

    private fun hashKey(key: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
