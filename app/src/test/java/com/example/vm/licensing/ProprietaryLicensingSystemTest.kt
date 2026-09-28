package com.example.vm.licensing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vm.core.VMConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProprietaryLicensingSystemTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LicenseServerEngine.resetForTesting()
        OfflineLicenseVault(context).clear()
        TimeTamperDetector(context).resetForTesting()
        LicenseManager.resetForTesting(context)
    }

    @Test
    fun `device fingerprint generator outputs valid hardware ID format`() {
        val deviceId = DeviceFingerprintGenerator.getDeviceId(context)
        assertTrue("Device ID should start with MBM-DEV-", deviceId.startsWith("MBM-DEV-"))
        val friendlyName = DeviceFingerprintGenerator.getDeviceFriendlyName()
        assertTrue("Friendly name should not be blank", friendlyName.isNotBlank())
    }

    @Test
    fun `server engine activates valid Pro key and tracks device slots`() {
        val request1 = LicenseActivationRequest(
            licenseKey = "MBM-PRO-2026-TEST-7890-ABCD",
            deviceId = "MBM-DEV-TEST-DEVICE-01",
            deviceName = "Android Device 1",
            packageId = context.packageName,
            signatureFingerprint = "SIG_HASH_01",
            clientTimestamp = System.currentTimeMillis(),
            nonce = "nonce_activate_01"
        )

        val response1 = LicenseServerEngine.handleActivate(request1)
        assertTrue("Activation must succeed for valid key", response1.success)
        assertEquals("Tier must be PRO", LicenseTier.PRO, response1.tier)
        assertEquals("Status must be ACTIVE", LicenseStatus.ACTIVE, response1.status)
        assertEquals("Active device count must be 1", 1, response1.activeDeviceCount)
        assertEquals("Max devices for Pro must be 3", 3, response1.maxDevices)
        assertTrue("Response must contain server signature", response1.signature.isNotEmpty())
    }

    @Test
    fun `server engine enforces maximum device limit per license key`() {
        // Activate on device 1, 2, and 3 (Pro allows 3 devices)
        for (i in 1..3) {
            val req = LicenseActivationRequest(
                licenseKey = "MBM-PRO-2026-TEST-7890-ABCD",
                deviceId = "MBM-DEV-SLOT-0$i",
                deviceName = "Android Device $i",
                packageId = context.packageName,
                signatureFingerprint = "SIG_$i",
                clientTimestamp = System.currentTimeMillis(),
                nonce = "nonce_quota_$i"
            )
            val resp = LicenseServerEngine.handleActivate(req)
            assertTrue("Device $i activation should succeed", resp.success)
        }

        // 4th device activation must be rejected because max quota is 3
        val req4 = LicenseActivationRequest(
            licenseKey = "MBM-PRO-2026-TEST-7890-ABCD",
            deviceId = "MBM-DEV-SLOT-04",
            deviceName = "Android Device 4",
            packageId = context.packageName,
            signatureFingerprint = "SIG_4",
            clientTimestamp = System.currentTimeMillis(),
            nonce = "nonce_quota_4"
        )
        val resp4 = LicenseServerEngine.handleActivate(req4)
        assertFalse("4th device activation must fail due to quota limit", resp4.success)
        assertTrue("Error message should mention device limit", resp4.message.contains("Device limit reached"))

        // Now deactivate device 1 to free a slot
        val deactReq = LicenseDeactivationRequest(
            licenseKey = "MBM-PRO-2026-TEST-7890-ABCD",
            deviceId = "MBM-DEV-SLOT-01",
            clientTimestamp = System.currentTimeMillis()
        )
        val deactResp = LicenseServerEngine.handleDeactivate(deactReq)
        assertTrue("Deactivation should succeed", deactResp.success)

        // Now device 4 can activate!
        val req4Retry = req4.copy(nonce = "nonce_quota_4_retry")
        val resp4Retry = LicenseServerEngine.handleActivate(req4Retry)
        assertTrue("Device 4 should now succeed after slot is freed", resp4Retry.success)
    }

    @Test
    fun `server engine rejects invalid and revoked license keys`() {
        // Test invalid key
        val invalidReq = LicenseActivationRequest(
            licenseKey = "MBM-FAKE-KEY-0000-INVALID",
            deviceId = "MBM-DEV-TEST",
            deviceName = "Test Phone",
            packageId = context.packageName,
            signatureFingerprint = "SIG",
            clientTimestamp = System.currentTimeMillis(),
            nonce = "nonce_invalid_key"
        )
        val invalidResp = LicenseServerEngine.handleActivate(invalidReq)
        assertFalse("Invalid key must fail", invalidResp.success)
        assertEquals(LicenseStatus.INVALID, invalidResp.status)

        // Test revoked key
        val revokedReq = LicenseActivationRequest(
            licenseKey = "MBM-REVOKED-2026-BADK-0000-FAIL",
            deviceId = "MBM-DEV-TEST",
            deviceName = "Test Phone",
            packageId = context.packageName,
            signatureFingerprint = "SIG",
            clientTimestamp = System.currentTimeMillis(),
            nonce = "nonce_revoked_key"
        )
        val revokedResp = LicenseServerEngine.handleActivate(revokedReq)
        assertFalse("Revoked key must fail", revokedResp.success)
        assertEquals(LicenseStatus.REVOKED, revokedResp.status)
        assertTrue("Message must mention revocation", revokedResp.message.contains("revoked"))
    }

    @Test
    fun `admin license generator creates verifiable keys with valid checksums`() {
        val newKey = LicenseServerEngine.generateNewLicense(
            tier = LicenseTier.PREMIUM,
            issuedTo = "Enterprise Partner",
            expiresAt = 0L, // Lifetime
            maxDevices = 10
        )
        assertTrue("Key must start with MVM-PREMIUM-2026- or MBM-PREMIUM-2026-", newKey.startsWith("MVM-PREMIUM-2026-") || newKey.startsWith("MBM-PREMIUM-2026-"))

        val activateReq = LicenseActivationRequest(
            licenseKey = newKey,
            deviceId = "MBM-DEV-ADMIN-GEN-01",
            deviceName = "Partner Tablet",
            packageId = context.packageName,
            signatureFingerprint = "SIG_PARTNER",
            clientTimestamp = System.currentTimeMillis(),
            nonce = "nonce_admin_gen"
        )
        val resp = LicenseServerEngine.handleActivate(activateReq)
        assertTrue("Generated key must activate cleanly", resp.success)
        assertEquals(LicenseTier.PREMIUM, resp.tier)
    }

    @Test
    fun `time tamper detector catches system clock rollback`() {
        val detector = TimeTamperDetector(context)
        val initialTime = System.currentTimeMillis() + 86400000L // 1 day in the future
        detector.recordCheckpoint(initialTime)

        assertEquals("Recorded checkpoint must match", initialTime, detector.getLastRecordedTimestamp())
        // Checking now when wall clock is older than checkpoint
        val isManipulated = detector.isClockManipulated()
        assertTrue("Clock rollback should be detected when current time is earlier than checkpoint", isManipulated)
    }

    @Test
    fun `offline vault saves and verifies license with HMAC signature`() {
        val vault = OfflineLicenseVault(context)
        val deviceId = "MBM-DEV-SECURE-VAULT-01"

        val entitlements = LicenseEntitlements.defaultFor(LicenseTier.PRO)
        val record = LicenseRecord(
            licenseKey = "MBM-PRO-2026-TEST-7890-ABCD",
            licenseHash = "hash123",
            tier = LicenseTier.PRO,
            status = LicenseStatus.ACTIVE,
            issuedTo = "Test User",
            issuedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 86400000L,
            maxDevices = 3,
            activeDevices = listOf(deviceId),
            entitlements = entitlements,
            lastValidatedAt = System.currentTimeMillis(),
            signature = "sig123"
        )

        vault.saveLicense(record, deviceId)

        val loaded = vault.loadLicense(deviceId)
        assertNotNull("Saved license must be loaded successfully", loaded)
        assertEquals("License key must match", record.licenseKey, loaded!!.licenseKey)
        assertEquals("Tier must match", LicenseTier.PRO, loaded.tier)

        // Tampering with device ID must fail HMAC verification
        val wrongDeviceLoaded = vault.loadLicense("MBM-DEV-FORGED-ID")
        assertNull("Loading on forged device ID must fail integrity check", wrongDeviceLoaded)
    }

    @Test
    fun `feature gate restricts higher CPU and RAM allocations on lower tiers`() {
        val freeEntitlements = LicenseEntitlements.defaultFor(LicenseTier.FREE)
        val proEntitlements = LicenseEntitlements.defaultFor(LicenseTier.PRO)

        // 1. VM with 1 CPU and 512MB RAM on Free tier -> Allowed
        val validFreeConfig = VMConfig(
            name = "Free VM",
            cpuCores = 1,
            ramSizeMb = 512
        )
        val result1 = FeatureGate.verifyVmConfig(validFreeConfig, freeEntitlements)
        assertTrue("1 vCPU, 512MB RAM must be allowed on Free tier", result1 is FeatureGate.GateResult.Allowed)

        // 2. VM with 4 CPUs on Free tier -> Restricted!
        val multiCoreConfig = VMConfig(
            name = "Pro VM",
            cpuCores = 4,
            ramSizeMb = 512
        )
        val result2 = FeatureGate.verifyVmConfig(multiCoreConfig, freeEntitlements)
        assertTrue("4 vCPUs must be restricted on Free tier", result2 is FeatureGate.GateResult.Restricted)
        val restricted2 = result2 as FeatureGate.GateResult.Restricted
        assertEquals("Must require Pro tier", LicenseTier.PRO, restricted2.requiredTier)

        // 3. Same 4 CPU config on Pro tier -> Allowed
        val result3 = FeatureGate.verifyVmConfig(multiCoreConfig, proEntitlements)
        assertTrue("4 vCPUs must be allowed on Pro tier", result3 is FeatureGate.GateResult.Allowed)

        // 4. Feature checks
        assertFalse("Snapshots not allowed on Free tier", FeatureGate.canCreateSnapshots(freeEntitlements))
        assertTrue("Snapshots allowed on Pro tier", FeatureGate.canCreateSnapshots(proEntitlements))
        assertFalse("VM export not allowed on Free tier", FeatureGate.canExportVm(freeEntitlements))
        assertTrue("VM export allowed on Pro tier", FeatureGate.canExportVm(proEntitlements))
    }

    @Test
    fun `license manager full activation and trial lifecycle`() = runBlocking {
        val manager = LicenseManager.getInstance(context)

        // Initial state should be Free
        val initial = manager.licenseState.value
        assertEquals("Initial tier should be FREE", LicenseTier.FREE, initial.tier)

        // Activate 14-day trial
        manager.startEvaluationTrial()
        val trialState = manager.licenseState.value
        assertEquals("Tier should be PRO during trial", LicenseTier.PRO, trialState.tier)
        assertEquals("Status should be TRIAL", LicenseStatus.TRIAL, trialState.status)
        assertTrue("Trial should expire in the future", trialState.expiresAt > System.currentTimeMillis())

        // Activate official Pro key
        val actResult = manager.activateKey("MBM-PRO-2026-TEST-7890-ABCD")
        assertTrue("Key activation must succeed", actResult.isSuccess)
        val activeState = manager.licenseState.value
        assertEquals("Tier should be PRO", LicenseTier.PRO, activeState.tier)
        assertEquals("Status should be ACTIVE", LicenseStatus.ACTIVE, activeState.status)

        // Deactivate
        manager.deactivateCurrentKey()
        val deactivatedState = manager.licenseState.value
        assertEquals("After deactivation should revert to FREE", LicenseTier.FREE, deactivatedState.tier)
    }

    @Test
    fun `root proprietary license agreement file exists and contains essential terms`() {
        val candidates = listOf(
            File("/LICENSE"),
            File("LICENSE"),
            File("../LICENSE"),
            File("app/LICENSE")
        )
        val licenseFile = candidates.firstOrNull { it.exists() }
        assertNotNull("Root LICENSE file must exist", licenseFile)
        val text = licenseFile!!.readText()
        assertTrue("Must state product name", text.contains("Mobile BM") || text.contains("MobileVM"))
        assertTrue("Must state Copyright 2026", text.contains("2026"))
        assertTrue("Must state Akash Kumar", text.contains("Akash Kumar"))
        assertTrue("Must prohibit redistribution", text.contains("PROHIBITED USES"))
        assertTrue("Must state third party components remain under respective licenses", text.contains("THIRD-PARTY AND OPEN-SOURCE"))
    }
}
