package com.example.vm.display

import android.graphics.Bitmap
import com.example.vm.devices.VirtualDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VirtualDisplayState(
    val width: Int = 1024,
    val height: Int = 768,
    val colorFormat: DisplayColorFormat = DisplayColorFormat.ARGB_8888,
    val strideBytes: Int = 4096,
    val baseMmioAddress: String = "0x0C000000",
    val irqNumber: Int = 4,
    val renderStatus: DisplayRenderStatus = DisplayRenderStatus.PENDING_GRAPHICAL_PIPELINE,
    val isPendingNotice: String = PENDING_EXPLANATION,
    val totalFramesPresented: Long = 0L,
    val lastUpdateTimeMs: Long = System.currentTimeMillis()
) {
    companion object {
        const val PENDING_EXPLANATION =
            "Real display abstraction (DisplayBackend -> VirtualDisplayDevice -> Framebuffer/DisplaySurface) is active. " +
            "Direct graphical scanout rendering from the guest Linux kernel is pending DRM/KMS pipeline initialization. " +
            "All terminal output is delivered live via the serial console (ttyAMA0)."
    }
}

/**
 * VirtualDisplayDevice represents the hardware-level virtual display controller
 * mapped into the VM's memory map (VirtIO-GPU / RamFB MMIO interface).
 */
class VirtualDisplayDevice(
    val backend: DisplayBackend = VirtioGPUBitmapDisplayBackend()
) : VirtualDevice {

    private val _displayState = MutableStateFlow(
        VirtualDisplayState(
            width = backend.width,
            height = backend.height,
            colorFormat = backend.format,
            strideBytes = backend.framebuffer.strideBytes,
            renderStatus = backend.renderStatus.value
        )
    )
    val displayState: StateFlow<VirtualDisplayState> = _displayState.asStateFlow()

    fun getFramebuffer(): Framebuffer = backend.framebuffer

    fun getDisplaySurface(): DisplaySurface = backend.displaySurface

    fun getFrame(): Bitmap? = backend.getFrame()

    fun configureResolution(width: Int, height: Int, format: DisplayColorFormat = DisplayColorFormat.ARGB_8888) {
        backend.configureResolution(width, height, format)
        updateState()
    }

    /**
     * Called when the native VM engine transfers a new scanout raster buffer.
     */
    fun onGuestScanoutUpdate(pixels: IntArray) {
        backend.framebuffer.updateRawScanout(pixels)
        backend.syncScanout()
        updateState()
    }

    private fun updateState() {
        _displayState.value = VirtualDisplayState(
            width = backend.width,
            height = backend.height,
            colorFormat = backend.format,
            strideBytes = backend.framebuffer.strideBytes,
            renderStatus = backend.renderStatus.value,
            totalFramesPresented = backend.displaySurface.framesPresented.value,
            lastUpdateTimeMs = backend.framebuffer.lastRenderTimestampMs
        )
    }

    override fun getDeviceName(): String = "Virtual Display Controller (VirtIO-GPU MMIO @ 0x0C000000)"

    override fun getDeviceStatus(): String {
        return "VirtIO Display (${backend.width}x${backend.height} ${backend.format.bpp}bpp) - Status: ${backend.renderStatus.value.label}"
    }

    override fun reset() {
        backend.reset()
        updateState()
    }
}
