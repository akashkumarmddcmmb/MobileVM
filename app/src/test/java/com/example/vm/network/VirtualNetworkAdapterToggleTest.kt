package com.example.vm.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.vm.core.VMConfig
import com.example.vm.core.VMEngine
import com.example.vm.ui.VMViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VirtualNetworkAdapterToggleTest {

    private lateinit var application: Application

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testVMEngineNetworkAdapterToggle() {
        val config = VMConfig(
            id = 1L,
            name = "Test_Network_VM",
            networkEnabled = true
        )
        val engine = VMEngine(application, config)

        assertTrue(engine.isNetworkEnabled.value)

        // Toggle network off
        engine.setNetworkEnabled(false)
        assertFalse(engine.isNetworkEnabled.value)
        assertEquals(NetworkImplementationStatus.DISCONNECTED, engine.networkDevice.networkState.value.status)

        // Toggle network back on
        engine.setNetworkEnabled(true)
        assertTrue(engine.isNetworkEnabled.value)
        assertEquals(NetworkImplementationStatus.CONNECTED, engine.networkDevice.networkState.value.status)
    }

    @Test
    fun testVirtualNetworkDeviceDirectLinkToggle() {
        val device = VirtualNetworkDevice()
        device.setLinkUp(true)
        assertEquals(NetworkImplementationStatus.CONNECTED, device.networkState.value.status)

        device.setLinkUp(false)
        assertEquals(NetworkImplementationStatus.DISCONNECTED, device.networkState.value.status)
    }
}
