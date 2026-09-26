package com.example.vm.display

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DisplaySurface represents the presentation target on the Android host side.
 * It provides the link between the guest Framebuffer and Android UI rendering.
 */
class DisplaySurface(
    var surfaceWidth: Int = 1024,
    var surfaceHeight: Int = 768
) {
    private val _isSurfaceReady = MutableStateFlow(false)
    val isSurfaceReady: StateFlow<Boolean> = _isSurfaceReady.asStateFlow()

    private val _framesPresented = MutableStateFlow(0L)
    val framesPresented: StateFlow<Long> = _framesPresented.asStateFlow()

    private val _lastDirtyRect = MutableStateFlow<DirtyRect?>(null)
    val lastDirtyRect: StateFlow<DirtyRect?> = _lastDirtyRect.asStateFlow()

    var onFrameRendered: ((Bitmap) -> Unit)? = null

    fun attachSurface(width: Int = surfaceWidth, height: Int = surfaceHeight) {
        this.surfaceWidth = width
        this.surfaceHeight = height
        _isSurfaceReady.value = true
    }

    fun detachSurface() {
        _isSurfaceReady.value = false
    }

    /**
     * Called when a new scanout frame has been produced by the virtual display device.
     */
    fun presentFrame(framebuffer: Framebuffer, dirtyRect: DirtyRect? = null) {
        if (!_isSurfaceReady.value) return

        _framesPresented.value++
        _lastDirtyRect.value = dirtyRect

        val bmp = framebuffer.asBitmap()
        onFrameRendered?.invoke(bmp)
    }

    fun reset() {
        _framesPresented.value = 0L
        _lastDirtyRect.value = null
    }
}
