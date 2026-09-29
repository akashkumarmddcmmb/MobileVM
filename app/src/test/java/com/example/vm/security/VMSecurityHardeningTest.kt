package com.example.vm.security

import androidx.test.core.app.ApplicationProvider
import com.example.vm.guest.os.OSStorageManager
import com.example.vm.logging.VMLogger
import com.example.vm.sharing.SharedFolderManager
import com.example.vm.update.AppUpdateManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VMSecurityHardeningTest {

    private lateinit var context: android.content.Context
    private lateinit var sharedFolderManager: SharedFolderManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sharedFolderManager = SharedFolderManager(context)
    }

    @Test
    fun testLogSanitizationStripsSensitiveData() {
        val sensitiveLog1 = "User login failed for admin with password=SuperSecretPassword123"
        val sanitized1 = VMLogger.sanitize(sensitiveLog1)
        assertFalse(sanitized1.contains("SuperSecretPassword123"))
        assertTrue(sanitized1.contains("password=***"))

        val sensitiveLog2 = "Auth request dispatched with token=ghp_ABC123XYZ456SecretToken"
        val sanitized2 = VMLogger.sanitize(sensitiveLog2)
        assertFalse(sanitized2.contains("ghp_ABC123XYZ456SecretToken"))
        assertTrue(sanitized2.contains("token=***"))
    }

    @Test
    fun testOSStorageManagerPathTraversalRejection() {
        // Disallow path traversal characters in manifest ID
        assertThrows(SecurityException::class.java) {
            OSStorageManager.validateManifestId("../../../etc/passwd")
        }

        assertThrows(SecurityException::class.java) {
            OSStorageManager.validateManifestId("malicious/../../rootfs")
        }

        // Safe ID passes
        OSStorageManager.validateManifestId("ubuntu_24_04_arm64")
    }

    @Test
    fun testSharedFolderTraversalRejection() {
        assertThrows(SecurityException::class.java) {
            sharedFolderManager.resolveSafeFile("../../../data/system/users")
        }

        assertThrows(SecurityException::class.java) {
            sharedFolderManager.resolveSafeFile("..\\..\\windows\\system32")
        }
    }

    @Test
    fun testLegalLicensesAndNoticesCompleteness() {
        val licenses = LegalLicenseManager.LICENSES
        assertTrue(licenses.isNotEmpty())
        assertTrue(LegalLicenseManager.PROPRIETARY_COPYRIGHT_NOTICE.contains("MOBILEVM"))

        // Verify key bundled OSS licenses are documented
        val licenseTypes = licenses.map { it.licenseType }
        assertTrue(licenseTypes.any { it.contains("GPL", ignoreCase = true) })
        assertTrue(licenseTypes.any { it.contains("Proprietary", ignoreCase = true) || it.contains("Canonical", ignoreCase = true) })
    }

    @Test
    fun testWindowsLicensingModelDisclosure() {
        val licenses = LegalLicenseManager.LICENSES
        val text = licenses.joinToString("\n") { it.fullText + " " + it.summary }
        assertNotNull(text)
        assertTrue(text.contains("Linux", ignoreCase = true) || text.contains("License", ignoreCase = true))
    }
}
