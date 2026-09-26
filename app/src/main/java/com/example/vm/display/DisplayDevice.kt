package com.example.vm.display

import android.graphics.Bitmap
import com.example.vm.devices.VirtualDevice

/**
 * DisplayDevice delegates to the real VirtualDisplayDevice / Framebuffer abstraction.
 * Simulated graphical OS desktops have been removed in favor of real frame scanout architecture.
 */
class DisplayDevice(
    private val virtualDisplay: VirtualDisplayDevice = VirtualDisplayDevice()
) : VirtualDevice {

    val width: Int get() = virtualDisplay.backend.width
    val height: Int get() = virtualDisplay.backend.height

    fun configureResolution(w: Int, h: Int) {
        virtualDisplay.configureResolution(w, h)
    }

    fun getFrame(): Bitmap? {
        return virtualDisplay.getFrame()
    }

    override fun getDeviceName(): String = virtualDisplay.getDeviceName()

    override fun getDeviceStatus(): String = virtualDisplay.getDeviceStatus()

    override fun reset() {
        virtualDisplay.reset()
    }
}
