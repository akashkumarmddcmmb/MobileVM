package com.example.vm.ui

import com.example.vm.core.VMConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VMCustomHardwareConfigScreenTest {

    @Test
    fun testVMConfigHardwareCustomizationDataModel() {
        val customConfig = VMConfig(
            id = 0L,
            name = "Custom_Ubuntu_Server",
            guestOsType = "Ubuntu 24.04 ARM64",
            cpuCores = 8,
            ramSizeMb = 4096,
            diskSizeGb = 64,
            useHardwareVirtualization = true,
            networkEnabled = true
        )

        assertEquals("Custom_Ubuntu_Server", customConfig.name)
        assertEquals(8, customConfig.cpuCores)
        assertEquals(4096, customConfig.ramSizeMb)
        assertEquals(64, customConfig.diskSizeGb)
        assertTrue(customConfig.useHardwareVirtualization)
        assertTrue(customConfig.networkEnabled)
    }

    @Test
    fun testCustomHardwareBoundaryCalculations() {
        val customRamMb = 3072
        val ramInGb = customRamMb / 1024.0
        assertEquals(3.0, ramInGb, 0.01)

        val diskCapacityGb = 100
        val diskCapacityBytes = diskCapacityGb * 1024L * 1024L * 1024L
        assertEquals(107374182400L, diskCapacityBytes)
    }
}
