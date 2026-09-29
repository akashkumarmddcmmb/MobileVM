package com.example.vm.dashboard

import androidx.test.core.app.ApplicationProvider
import com.example.vm.admin.AppModuleInfo
import com.example.vm.admin.ApplicationRegistry
import com.example.vm.admin.DiagnosticStatus
import com.example.vm.admin.FeatureInfo
import com.example.vm.core.VMConfig
import com.example.vm.core.VMState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdminDashboardTest {

    private lateinit var context: android.content.Context
    private lateinit var registry: ApplicationRegistry

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registry = ApplicationRegistry(context)
    }

    @Test
    fun testDefaultMobileVMModuleRegistration() {
        val module = registry.getModule("com.example.vm")
        assertNotNull(module)
        assertEquals("MobileVM", module!!.name)
        assertTrue(module.isEnabled)
        assertTrue(module.features.isNotEmpty())

        val features = module.features.map { it.id }
        assertTrue(features.contains("arm64_cpu"))
        assertTrue(features.contains("storage_manager"))
        assertTrue(features.contains("shared_folders"))
        assertTrue(features.contains("clipboard_sync"))
        assertTrue(features.contains("usb_passthrough"))
        assertTrue(features.contains("network_slirp"))
        assertTrue(features.contains("app_update"))
    }

    @Test
    fun testExtensibleFutureModuleRegistration() {
        val futureModule = AppModuleInfo(
            id = "com.example.terminal",
            name = "TerminalPro",
            versionName = "2.0.0",
            versionCode = 20,
            buildType = "release",
            isEnabled = true,
            features = listOf(
                FeatureInfo(
                    id = "ssh_client",
                    name = "SSH Client",
                    description = "Encrypted remote terminal shell",
                    isEnabled = true,
                    status = DiagnosticStatus.PASS,
                    diagnosticsSummary = "SSH v2 ready"
                )
            )
        )

        registry.registerModule(futureModule)
        val retrieved = registry.getModule("com.example.terminal")
        assertNotNull(retrieved)
        assertEquals("TerminalPro", retrieved!!.name)
        assertEquals(1, retrieved.features.size)
    }

    @Test
    fun testSystemSpecsRetrieval() {
        val specs = registry.getSystemSpecs()
        assertNotNull(specs.androidVersion)
        assertTrue(specs.apiLevel > 0)
        assertTrue(specs.totalPhysicalRamMb > 0)
    }

    @Test
    fun testVmStatusSummaryComputation() {
        val vms = listOf(
            VMConfig(id = 1L, name = "UbuntuVM"),
            VMConfig(id = 2L, name = "AlpineVM"),
            VMConfig(id = 3L, name = "DebianVM")
        )
        val states = mapOf(
            1L to VMState.RUNNING,
            2L to VMState.PAUSED,
            3L to VMState.STOPPED
        )

        val summary = registry.computeVmStatusSummary(vms, states)
        assertEquals(3, summary.totalVms)
        assertEquals(1, summary.runningVms)
        assertEquals(1, summary.pausedVms)
        assertEquals(1, summary.stoppedVms)
        assertEquals(0, summary.errorVms)
    }

    @Test
    fun testDiagnosticReportGenerationNoSecrets() {
        val vms = listOf(VMConfig(id = 1L, name = "UbuntuVM"))
        val states = mapOf(1L to VMState.RUNNING)

        val report = registry.generateDiagnosticReport(vms, states)
        assertNotNull(report)
        assertTrue(report.contains("MOBILEVM SYSTEM DIAGNOSTIC REPORT"))
        assertTrue(report.contains("UbuntuVM"))
        assertTrue(report.contains("ARM64 CPU Emulation"))

        // Ensure no private secrets are output in diagnostics
        assertFalse(report.contains("private_key"))
        assertFalse(report.contains("ghp_"))
    }
}
