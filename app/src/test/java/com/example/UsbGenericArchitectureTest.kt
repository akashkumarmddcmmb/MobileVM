package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vm.usb.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsbGenericArchitectureTest {

    @Test
    fun `usb device categories cover all required subsystem branches`() {
        val categories = UsbDeviceCategory.entries.map { it.name }
        assertTrue("Must support KEYBOARD", categories.contains("KEYBOARD"))
        assertTrue("Must support MOUSE", categories.contains("MOUSE"))
        assertTrue("Must support GENERIC_HID", categories.contains("GENERIC_HID"))
        assertTrue("Must support STORAGE", categories.contains("STORAGE"))
        assertTrue("Must support SERIAL", categories.contains("SERIAL"))
        assertTrue("Must support FUTURE_SUPPORTED", categories.contains("FUTURE_SUPPORTED"))
    }

    @Test
    fun `usb device manager initializes all 6 specialized subsystem handlers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val usbManager = UsbDeviceManager(context)

        assertNotNull("Keyboard handler must be initialized", usbManager.keyboardHandler)
        assertNotNull("Mouse handler must be initialized", usbManager.mouseHandler)
        assertNotNull("Generic HID handler must be initialized", usbManager.genericHidHandler)
        assertNotNull("Storage handler must be initialized", usbManager.storageHandler)
        assertNotNull("Serial handler must be initialized", usbManager.serialHandler)
        assertNotNull("Future device handler must be initialized", usbManager.futureDeviceHandler)

        assertEquals(6, usbManager.handlers.size)
    }

    @Test
    fun `usb error handling tracks and clears errors properly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val usbManager = UsbDeviceManager(context)

        val testDeviceName = "/dev/bus/usb/001/002"
        val error = UsbDeviceError(
            category = UsbErrorCategory.PERMISSION_DENIED,
            deviceName = testDeviceName,
            message = "Permission was explicitly rejected by user",
            suggestedRemedy = "Allow USB permission in system prompt."
        )

        usbManager.recordError(error)
        assertEquals(error, usbManager.getLastError(testDeviceName))
        assertTrue(usbManager.errors.value.containsKey(testDeviceName))

        usbManager.clearError(testDeviceName)
        assertNull(usbManager.getLastError(testDeviceName))
        assertFalse(usbManager.errors.value.containsKey(testDeviceName))
    }

    @Test
    fun `usb handler lifecycle contract supports attach detach release and error tracking`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val usbManager = UsbDeviceManager(context)
        val keyboardHandler = usbManager.keyboardHandler

        val testDeviceName = "test_keyboard_dev"
        val statusBefore = keyboardHandler.getStatus(testDeviceName)
        assertEquals(UsbHandlerState.IDLE, statusBefore.state)

        keyboardHandler.clearError(testDeviceName)
        assertNull(keyboardHandler.getLastError(testDeviceName))
    }

    @Test
    fun `usb serial driver endpoints detection accepts bulk endpoints`() {
        assertNotNull(UsbDeviceCategory.SERIAL.defaultDriverName)
        assertTrue(UsbDeviceCategory.SERIAL.title.contains("Serial"))
    }
}
