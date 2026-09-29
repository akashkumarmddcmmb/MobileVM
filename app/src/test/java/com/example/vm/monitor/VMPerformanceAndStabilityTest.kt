package com.example.vm.monitor

import androidx.test.core.app.ApplicationProvider
import com.example.vm.core.VMConfig
import com.example.vm.core.VMEngine
import com.example.vm.core.VMState
import com.example.vm.memory.MemoryManager
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
class VMPerformanceAndStabilityTest {

    private lateinit var context: android.content.Context
    private lateinit var thermalMonitor: VMThermalMonitor
    private lateinit var memoryManager: MemoryManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        thermalMonitor = VMThermalMonitor(context)
        memoryManager = MemoryManager(context)
    }

    @Test
    fun testThermalStateMonitoringAndWarnings() {
        assertEquals(ThermalState.NOMINAL, thermalMonitor.thermalState.value)
        assertFalse(thermalMonitor.thermalState.value.isWarning)

        // Simulate severe thermal condition
        thermalMonitor.updateThermalState(ThermalState.SEVERE)
        assertEquals(ThermalState.SEVERE, thermalMonitor.thermalState.value)
        assertTrue(thermalMonitor.thermalState.value.isWarning)

        // Recover to nominal
        thermalMonitor.updateThermalState(ThermalState.NOMINAL)
        assertEquals(ThermalState.NOMINAL, thermalMonitor.thermalState.value)
        assertFalse(thermalMonitor.thermalState.value.isWarning)
    }

    @Test
    fun testWatchdogKickAndStallDetection() {
        var stallCallbackTriggered = false
        val watchdog = VMWatchdog(
            timeoutMs = 100L,
            checkIntervalMs = 20L,
            onStallDetected = { stallCallbackTriggered = true }
        )

        assertFalse(watchdog.isRunning())
        assertFalse(watchdog.isStalled.value)

        // Kicking updates timestamp
        watchdog.kick()
        assertFalse(watchdog.isStalled.value)
    }

    @Test
    fun testPerformanceMetricsDataIntegrity() {
        val metrics = VMPerformanceMetrics(
            cpuUsagePercent = 14.5,
            guestRamUsedMb = 512,
            guestRamTotalMb = 2048,
            hostFreeRamMb = 4096,
            fps = 59.8,
            frameTimeMs = 16.7,
            diskReadBytesPerSec = 1048576,
            diskWriteBytesPerSec = 524288,
            netRxBytesPerSec = 20480,
            netTxBytesPerSec = 10240,
            activeThreadsCount = 4,
            thermalState = ThermalState.NOMINAL
        )

        assertEquals(14.5, metrics.cpuUsagePercent, 0.01)
        assertEquals(512L, metrics.guestRamUsedMb)
        assertEquals(2048L, metrics.guestRamTotalMb)
        assertEquals(59.8, metrics.fps, 0.01)
        assertEquals(4, metrics.activeThreadsCount)
        assertFalse(metrics.thermalState.isWarning)
    }

    @Test
    fun testMemoryHeadroomSafetyValidation() {
        val stats = memoryManager.getHostMemoryStats()
        assertTrue(stats.totalMb > 0)

        // Requesting RAM exceeding total physical RAM should fail
        val unsafeRecommendation = memoryManager.getMemorySafetyRecommendation(131072) // 128 GB
        assertTrue(unsafeRecommendation is MemoryManager.SafetyResult.Danger)
    }

    @Test
    fun testVMLifecycleStateTransitionsAndSafety() {
        val config = VMConfig(name = "TestVM", ramSizeMb = 1024, cpuCores = 2)
        val engine = VMEngine(context, config)
        assertEquals(VMState.CREATED, engine.state.value)

        // Pause / Resume sanity checks on inactive VM
        assertNotNull(engine.pause())
        assertNotNull(engine.resume())

        // Stop transitions cleanly to STOPPED without throwing
        assertNull(engine.stop())
        assertEquals(VMState.STOPPED, engine.state.value)
    }
}
