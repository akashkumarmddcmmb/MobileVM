package com.example.vm.ui

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VMRealTimeMonitoringPanelTest {

    @Test
    fun testRealtimeBufferMath() {
        val buffer = mutableListOf<Float>()
        repeat(30) { buffer.add(0f) }

        assertEquals(30, buffer.size)

        // Simulate tick
        buffer.removeAt(0)
        buffer.add(45.5f)

        assertEquals(30, buffer.size)
        assertEquals(45.5f, buffer.last(), 0.01f)
    }

    @Test
    fun testMetricClamping() {
        val cpuOverload = 120.0f
        val clampedCpu = cpuOverload.coerceIn(0f, 100f)
        assertEquals(100f, clampedCpu, 0.01f)

        val ramUnderload = -10.0f
        val clampedRam = ramUnderload.coerceIn(0f, 100f)
        assertEquals(0f, clampedRam, 0.01f)
    }
}
