package com.example.vm.lifecycle

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.vm.core.VMConfig
import com.example.vm.core.VMState
import com.example.vm.ui.MemorySafetyStatus
import com.example.vm.ui.VMLifecycleHardwareViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VMLifecycleHardwareViewModelTest {

    private lateinit var application: Application
    private lateinit var viewModel: VMLifecycleHardwareViewModel

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        viewModel = VMLifecycleHardwareViewModel(application)
    }

    @Test
    fun testInitialLifecycleAndHardwareState() {
        val state = viewModel.vmLifecycleState.value
        assertNotNull(state)

        val hardware = viewModel.hardwareState.value
        assertNotNull(hardware.cpu)
        assertNotNull(hardware.ram)
        assertNotNull(hardware.storage)
        assertTrue(hardware.cpu.allocatedCores in 1..16)
        assertTrue(hardware.ram.allocatedMb > 0)
    }

    @Test
    fun testCpuResourceAllocation() {
        viewModel.allocateCpuCores(4)
        assertEquals(4, viewModel.hardwareState.value.cpu.allocatedCores)

        viewModel.allocateCpuCores(8)
        assertEquals(8, viewModel.hardwareState.value.cpu.allocatedCores)

        // Coerce checks
        viewModel.allocateCpuCores(0)
        assertEquals(1, viewModel.hardwareState.value.cpu.allocatedCores)

        viewModel.allocateCpuCores(32)
        assertEquals(16, viewModel.hardwareState.value.cpu.allocatedCores)
    }

    @Test
    fun testRamResourceAllocationAndSafetyStatus() {
        viewModel.allocateRam(2048)
        assertEquals(2048, viewModel.hardwareState.value.ram.allocatedMb)
        assertTrue(viewModel.hardwareState.value.ram.hostTotalMb >= 0)

        viewModel.allocateRam(4096)
        assertEquals(4096, viewModel.hardwareState.value.ram.allocatedMb)
        assertNotNull(viewModel.hardwareState.value.ram.safetyStatus)
    }

    @Test
    fun testStorageResourceAllocation() {
        viewModel.allocateStorage(64, isSparse = true, imagePath = "/path/to/test.img")
        val storage = viewModel.hardwareState.value.storage
        assertEquals(64, storage.allocatedGb)
        assertTrue(storage.isSparse)
        assertEquals("/path/to/test.img", storage.diskImagePath)
    }

    @Test
    fun testStartVMHardwareStateSynchronization() {
        val config = VMConfig(
            id = 1L,
            name = "Test_Ubuntu_VM",
            cpuCores = 4,
            ramSizeMb = 3072,
            diskSizeGb = 40,
            diskImagePath = "/test/path/ubuntu.img",
            useHardwareVirtualization = false
        )

        viewModel.startVM(config)

        assertEquals(config, viewModel.activeConfig.value)
        assertEquals(4, viewModel.hardwareState.value.cpu.allocatedCores)
        assertEquals(3072, viewModel.hardwareState.value.ram.allocatedMb)
        assertEquals(40, viewModel.hardwareState.value.storage.allocatedGb)
        assertEquals("/test/path/ubuntu.img", viewModel.hardwareState.value.storage.diskImagePath)
    }

    @Test
    fun testLifecycleControls() {
        // Verifies pause, resume, reset, and stop API calls do not throw exceptions
        viewModel.pauseVM()
        viewModel.resumeVM()
        viewModel.resetVM()
        viewModel.stopVM()
        viewModel.refreshHardwareTelemetry()
    }
}
