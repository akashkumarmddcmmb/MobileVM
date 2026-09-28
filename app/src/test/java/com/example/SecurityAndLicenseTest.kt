package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vm.security.LegalLicenseManager
import com.example.vm.security.ProjectProtectionManager
import com.example.vm.security.SecurityManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityAndLicenseTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `proprietary copyright notice prohibits unauthorized cloning and reverse engineering`() {
        val notice = LegalLicenseManager.PROPRIETARY_COPYRIGHT_NOTICE
        assertTrue("Notice must state Copyright 2026", notice.contains("COPYRIGHT © 2026"))
        assertTrue("Notice must state All Rights Reserved", notice.contains("ALL RIGHTS RESERVED"))
        assertTrue("Notice must prohibit unauthorized copying", notice.contains("NO UNAUTHORIZED COPYING"))
        assertTrue("Notice must prohibit reverse engineering", notice.contains("NO REVERSE ENGINEERING"))
    }

    @Test
    fun `license registry covers all third-party services and operating systems without infringement`() {
        val licenses = LegalLicenseManager.LICENSES
        assertTrue("License registry must have at least 5 entries", licenses.size >= 5)

        val gpl = licenses.find { it.id == "linux_kernel_gplv2" }
        assertNotNull("Linux Kernel GPLv2 license must be registered", gpl)
        assertTrue("GPL license must point to kernel.org", gpl!!.officialSourceUrl.contains("kernel.org"))

        val ubuntu = licenses.find { it.id == "ubuntu_canonical" }
        assertNotNull("Ubuntu Canonical IP policy must be registered", ubuntu)
        assertTrue("Ubuntu license must declare Canonical Ltd ownership", ubuntu!!.owner.contains("Canonical"))

        val alpine = licenses.find { it.id == "alpine_linux" }
        assertNotNull("Alpine Linux license must be registered", alpine)

        val proprietary = licenses.find { it.id == "mobile_bm_proprietary" || it.id == "mobilevm_proprietary" }
        assertNotNull("MobileVM proprietary license must be registered", proprietary)
        assertFalse("MobileVM license is not third party", proprietary!!.isThirdParty)
    }

    @Test
    fun `anti-piracy protection audit executes signature and package integrity checks`() {
        val audit = ProjectProtectionManager.performProtectionAudit(context)
        assertNotNull("Audit must not be null", audit)
        assertTrue("Package name must be valid", audit.packageName.isNotEmpty())
        assertTrue("Signature hash must be computed", audit.signatureHash.isNotEmpty())
        assertTrue("Storage seal token must be generated", audit.storageSealToken.isNotEmpty())
        assertTrue("Copyright must be enforced", audit.copyrightEnforced)
        assertTrue("Integrity score must be high on clean build", audit.integrityScore >= 70)
    }

    @Test
    fun `storage seal verification matches generated token and rejects forged tokens`() {
        val validToken = ProjectProtectionManager.generateDeviceStorageSeal(context)
        assertTrue("Valid token must verify successfully", ProjectProtectionManager.verifyStorageSeal(context, validToken))

        val forgedToken = "tampered_stolen_token_xyz"
        assertFalse("Forged/stolen token must be rejected", ProjectProtectionManager.verifyStorageSeal(context, forgedToken))
    }

    @Test
    fun `security manager audit enforces scoped sandbox and blocks path traversal`() {
        val audit = SecurityManager.runAudit(context)
        assertFalse("Root must not be required", audit.isRootRequired)
        assertTrue("App must be sandboxed", audit.isAppSandboxed)
        assertTrue("Bootloader must be untouched", audit.isBootloaderUntouched)
        assertTrue("Signature must be verified", audit.isSignatureVerified)

        val safePath = File(context.filesDir, "vm_disks/test.raw").absolutePath
        assertTrue("Files inside app filesDir are safe", SecurityManager.isPathWithinSandbox(context, safePath))

        val traversalPath = File(context.filesDir, "../../system/bin/sh").canonicalPath
        assertFalse("Path traversal outside sandbox must be blocked", SecurityManager.isPathWithinSandbox(context, traversalPath))
    }

    @Test
    fun `eula text contains complete user terms and warranty disclaimers`() {
        val eula = LegalLicenseManager.EULA_TEXT
        assertTrue("EULA must contain grant of license", eula.contains("GRANT OF LICENSE"))
        assertTrue("EULA must prohibit unauthorized redistribution", eula.contains("RESTRICTIONS ON USE"))
        assertTrue("EULA must contain disclaimer of warranty", eula.contains("DISCLAIMER OF WARRANTY"))
    }
}
