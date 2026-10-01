package com.example.vm.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostMemoryPlannerTest {

    @Test
    fun testRequested4096MBWith3402MBAvailableBlocksAndOffersSafeValue() {
        val hostInfo = HostMemoryInfo(
            totalMemMb = 8192L,
            availableMemMb = 3402L,
            lowMemory = false,
            thresholdMb = 512L
        )

        val plan = HostMemoryPlanner.calculateMemoryPlanForHostInfo(
            hostInfo = hostInfo,
            requestedRamMb = 4096,
            isKvmMode = true,
            isWindowsGuest = true
        )

        assertFalse("4096 MB allocation must be blocked when host only has 3402 MB available", plan.isSafe)
        assertTrue("Safe maximum RAM must be less than requested 4096 MB", plan.maximumSafeRamMb < 4096)
        assertTrue("Safe maximum RAM must be at least minimum supported RAM (512 MB)", plan.maximumSafeRamMb >= 512)
        assertEquals("Selected RAM should fall back to safe maximum", plan.maximumSafeRamMb, plan.selectedRamMb)
        assertTrue("Reason should describe insufficient memory", plan.reason.contains("not currently available") || plan.reason.contains("Safe maximum"))
        assertTrue("Recommended RAM for Windows guest should be safe and reasonable", plan.recommendedRamMb in 2048..plan.maximumSafeRamMb.coerceAtLeast(2048))
    }

    @Test
    fun testRequested2048MBWithSufficientHostMemoryAllows() {
        val hostInfo = HostMemoryInfo(
            totalMemMb = 8192L,
            availableMemMb = 6144L,
            lowMemory = false,
            thresholdMb = 512L
        )

        val plan = HostMemoryPlanner.calculateMemoryPlanForHostInfo(
            hostInfo = hostInfo,
            requestedRamMb = 2048,
            isKvmMode = true,
            isWindowsGuest = false
        )

        assertTrue("2048 MB allocation must be allowed when sufficient host memory is available", plan.isSafe)
        assertEquals(2048, plan.selectedRamMb)
        assertFalse(plan.isLowMemoryPressure)
    }

    @Test
    fun testLowMemoryStateBlocksLargeAllocation() {
        val hostInfo = HostMemoryInfo(
            totalMemMb = 4096L,
            availableMemMb = 1024L,
            lowMemory = true,
            thresholdMb = 512L
        )

        val plan = HostMemoryPlanner.calculateMemoryPlanForHostInfo(
            hostInfo = hostInfo,
            requestedRamMb = 2048,
            isKvmMode = true,
            isWindowsGuest = false
        )

        assertFalse("Low memory state must block large allocations", plan.isSafe)
        assertTrue("isLowMemoryPressure flag must be true", plan.isLowMemoryPressure)
        assertTrue("Reason must mention HOST_MEMORY_PRESSURE", plan.reason.contains("HOST_MEMORY_PRESSURE"))
    }

    @Test
    fun testAvailableMemoryChangesBeforeStartRecheck() {
        val initialHostInfo = HostMemoryInfo(
            totalMemMb = 8192L,
            availableMemMb = 5000L,
            lowMemory = false,
            thresholdMb = 512L
        )

        val initialPlan = HostMemoryPlanner.calculateMemoryPlanForHostInfo(
            hostInfo = initialHostInfo,
            requestedRamMb = 3072,
            isKvmMode = true,
            isWindowsGuest = false
        )
        assertTrue("Initial plan with 5000 MB free should be safe for 3072 MB", initialPlan.isSafe)

        // Memory drops right before start
        val droppedHostInfo = HostMemoryInfo(
            totalMemMb = 8192L,
            availableMemMb = 1800L,
            lowMemory = false,
            thresholdMb = 512L
        )

        val recheckPlan = HostMemoryPlanner.calculateMemoryPlanForHostInfo(
            hostInfo = droppedHostInfo,
            requestedRamMb = 3072,
            isKvmMode = true,
            isWindowsGuest = false
        )
        assertFalse("Recheck plan with reduced host RAM must block 3072 MB allocation", recheckPlan.isSafe)
    }

    @Test
    fun testKvmModeUsesKvmOverhead() {
        val hostInfo = HostMemoryInfo(
            totalMemMb = 8192L,
            availableMemMb = 4096L,
            lowMemory = false,
            thresholdMb = 512L
        )

        val plan = HostMemoryPlanner.calculateMemoryPlanForHostInfo(
            hostInfo = hostInfo,
            requestedRamMb = 2048,
            isKvmMode = true,
            isWindowsGuest = false
        )

        assertEquals("KVM mode must use KVM overhead (256 MB)", HostMemoryPlanner.KVM_OVERHEAD_MB, plan.overheadMb)
    }

    @Test
    fun testSoftwareEmulationUsesEmulatorOverhead() {
        val hostInfo = HostMemoryInfo(
            totalMemMb = 8192L,
            availableMemMb = 4096L,
            lowMemory = false,
            thresholdMb = 512L
        )

        val plan = HostMemoryPlanner.calculateMemoryPlanForHostInfo(
            hostInfo = hostInfo,
            requestedRamMb = 2048,
            isKvmMode = false,
            isWindowsGuest = false
        )

        assertEquals("Software emulation must use emulator overhead (384 MB)", HostMemoryPlanner.EMULATOR_OVERHEAD_MB, plan.overheadMb)
    }

    @Test
    fun testNoIntegerOverflowWithLargeRequestedRam() {
        val hostInfo = HostMemoryInfo(
            totalMemMb = 8192L,
            availableMemMb = 4096L,
            lowMemory = false,
            thresholdMb = 512L
        )

        val plan = HostMemoryPlanner.calculateMemoryPlanForHostInfo(
            hostInfo = hostInfo,
            requestedRamMb = Int.MAX_VALUE,
            isKvmMode = true,
            isWindowsGuest = false
        )

        assertFalse("Int.MAX_VALUE allocation must be rejected", plan.isSafe)
        assertTrue("Safe maximum should remain positive and bound to host limits", plan.maximumSafeRamMb > 0)
    }

    @Test
    fun testNegativeOrZeroGuestRamEnforcesMinimum() {
        val hostInfo = HostMemoryInfo(
            totalMemMb = 8192L,
            availableMemMb = 4096L,
            lowMemory = false,
            thresholdMb = 512L
        )

        val planZero = HostMemoryPlanner.calculateMemoryPlanForHostInfo(
            hostInfo = hostInfo,
            requestedRamMb = 0,
            isKvmMode = true,
            isWindowsGuest = false
        )
        assertEquals("Zero RAM request must be coerced to minimum supported RAM", HostMemoryPlanner.MIN_SUPPORTED_GUEST_RAM_MB, planZero.requestedRamMb)

        val planNegative = HostMemoryPlanner.calculateMemoryPlanForHostInfo(
            hostInfo = hostInfo,
            requestedRamMb = -1024,
            isKvmMode = true,
            isWindowsGuest = false
        )
        assertEquals("Negative RAM request must be coerced to minimum supported RAM", HostMemoryPlanner.MIN_SUPPORTED_GUEST_RAM_MB, planNegative.requestedRamMb)
    }

    @Test
    fun testMinimumSupportedGuestRamEnforced() {
        assertEquals("Minimum supported guest RAM must be 512 MB", 512, HostMemoryPlanner.MIN_SUPPORTED_GUEST_RAM_MB)
    }
}
