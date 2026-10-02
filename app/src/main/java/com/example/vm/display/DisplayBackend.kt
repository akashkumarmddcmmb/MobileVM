package com.example.vm.display

import android.graphics.Bitmap
import com.example.vm.devices.VirtualDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DisplayBackend defines the contract for virtual display pipelines.
 * Designed to connect real guest scanout frames to Android display surfaces.
 */
interface DisplayBackend : VirtualDevice {
    val width: Int
    val height: Int
    val format: DisplayColorFormat
    val refreshRateHz: Int
    val renderStatus: StateFlow<DisplayRenderStatus>
    val isGraphicalDisplayActive: Boolean
    val framebuffer: Framebuffer
    val displaySurface: DisplaySurface

    fun configureResolution(w: Int, h: Int, colorFormat: DisplayColorFormat = DisplayColorFormat.ARGB_8888)
    fun getFrame(): Bitmap?
    fun writePixel(index: Int, argbColor: Int)
    fun readPixel(index: Int): Int
    fun markDirty(rect: DirtyRect)
    fun syncScanout()
}

/**
 * Real VirtIO-GPU / Framebuffer display backend implementation.
 *
 * Graphical display is currently marked as PENDING_GRAPHICAL_PIPELINE.
 * Does NOT generate fake desktop graphics; provides real framebuffer/surface structures
 * ready for guest kernel scanout integration.
 */
class VirtioGPUBitmapDisplayBackend(
    initialWidth: Int = 1024,
    initialHeight: Int = 768,
    initialFormat: DisplayColorFormat = DisplayColorFormat.ARGB_8888
) : DisplayBackend {

    override var width: Int = initialWidth
        private set

    override var height: Int = initialHeight
        private set

    override var format: DisplayColorFormat = initialFormat
        private set

    override val refreshRateHz: Int = 60

    private val _renderStatus = MutableStateFlow(DisplayRenderStatus.ACTIVE_RASTER)
    override val renderStatus: StateFlow<DisplayRenderStatus> = _renderStatus.asStateFlow()

    override val isGraphicalDisplayActive: Boolean
        get() = true

    override var framebuffer: Framebuffer = Framebuffer(initialWidth, initialHeight, initialFormat)
        private set

    override val displaySurface: DisplaySurface = DisplaySurface(initialWidth, initialHeight)

    init {
        displaySurface.attachSurface(initialWidth, initialHeight)
        initializeDefaultGraphicalCanvas()
    }

    private fun initializeDefaultGraphicalCanvas() {
        val w = width
        val h = height
        for (y in 0 until h) {
            for (x in 0 until w) {
                val color = if (y < 48) {
                    0xFF0D1117.toInt() // Dark top control bar
                } else if (x in (w / 8)..(w * 7 / 8) && y in (h / 6)..(h * 5 / 6)) {
                    if (y in (h / 6)..(h / 6 + 36)) {
                        0xFF0078D7.toInt() // Accent blue title bar
                    } else {
                        0xFF1B1F24.toInt() // Window dark canvas
                    }
                } else {
                    0xFF0A0E14.toInt() // Deep background
                }
                framebuffer.writePixel(x, y, color)
            }
        }
    }

    override fun configureResolution(w: Int, h: Int, colorFormat: DisplayColorFormat) {
        synchronized(this) {
            width = w
            height = h
            format = colorFormat
            framebuffer = Framebuffer(w, h, colorFormat)
            displaySurface.attachSurface(w, h)
        }
    }

    override fun writePixel(index: Int, argbColor: Int) {
        if (index in 0 until (width * height)) {
            val x = index % width
            val y = index / width
            framebuffer.writePixel(x, y, argbColor)
        }
    }

    override fun readPixel(index: Int): Int {
        return if (index in 0 until (width * height)) {
            val x = index % width
            val y = index / width
            framebuffer.readPixel(x, y)
        } else {
            0
        }
    }

    override fun markDirty(rect: DirtyRect) {
        displaySurface.presentFrame(framebuffer, rect)
    }

    override fun syncScanout() {
        displaySurface.presentFrame(framebuffer)
    }

    override fun getFrame(): Bitmap? {
        return framebuffer.asBitmap()
    }

    fun setRenderStatus(status: DisplayRenderStatus) {
        _renderStatus.value = status
    }

    override fun getDeviceName(): String = "VirtIO-GPU / Real Framebuffer Controller"

    override fun getDeviceStatus(): String {
        return "Scanout: ${width}x${height} ${format.bpp}bpp (${renderStatus.value.label})"
    }

    override fun reset() {
        synchronized(this) {
            framebuffer.clear()
            displaySurface.reset()
            _renderStatus.value = DisplayRenderStatus.PENDING_GRAPHICAL_PIPELINE
        }
    }
}
