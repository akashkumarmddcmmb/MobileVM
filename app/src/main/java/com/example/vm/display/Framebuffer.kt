package com.example.vm.display

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encapsulates the raw scanout memory buffer of a virtual display.
 * Designed to hold real guest framebuffer data and convert it for Android presentation.
 */
class Framebuffer(
    val width: Int = 1024,
    val height: Int = 768,
    val format: DisplayColorFormat = DisplayColorFormat.ARGB_8888
) {
    val bytesPerPixel: Int = format.bytesPerPixel
    val strideBytes: Int = width * bytesPerPixel
    val sizeBytes: Long = (height.toLong() * strideBytes.toLong())

    private val pixelArray = IntArray(width * height)
    private var cachedBitmap: Bitmap? = null
    var lastRenderTimestampMs: Long = System.currentTimeMillis()
        private set

    init {
        clear(Color.BLACK)
    }

    @Synchronized
    fun clear(argbColor: Int = Color.BLACK) {
        pixelArray.fill(argbColor)
        cachedBitmap?.eraseColor(argbColor)
        lastRenderTimestampMs = System.currentTimeMillis()
    }

    @Synchronized
    fun writePixel(x: Int, y: Int, argbColor: Int) {
        if (x in 0 until width && y in 0 until height) {
            val idx = y * width + x
            pixelArray[idx] = argbColor
            cachedBitmap?.setPixel(x, y, argbColor)
            lastRenderTimestampMs = System.currentTimeMillis()
        }
    }

    @Synchronized
    fun readPixel(x: Int, y: Int): Int {
        return if (x in 0 until width && y in 0 until height) {
            pixelArray[y * width + x]
        } else {
            0
        }
    }

    /**
     * Copies raw scanout pixel bytes from native guest memory or direct MMIO buffer into this framebuffer.
     */
    @Synchronized
    fun updateRawScanout(sourcePixels: IntArray, offset: Int = 0, length: Int = minOf(sourcePixels.size, pixelArray.size)) {
        System.arraycopy(sourcePixels, offset, pixelArray, 0, length)
        cachedBitmap?.setPixels(pixelArray, 0, width, 0, 0, width, height)
        lastRenderTimestampMs = System.currentTimeMillis()
    }

    /**
     * Copies raw scanout bytes from a native ByteBuffer (e.g. DRM dumb buffer / VirtIO GPU 2D transfer).
     */
    @Synchronized
    fun updateRawByteBuffer(buffer: ByteBuffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val copyCount = minOf(buffer.remaining() / 4, pixelArray.size)
        buffer.asIntBuffer().get(pixelArray, 0, copyCount)
        cachedBitmap?.setPixels(pixelArray, 0, width, 0, 0, width, height)
        lastRenderTimestampMs = System.currentTimeMillis()
    }

    /**
     * Returns an Android Bitmap representation of the current framebuffer.
     */
    @Synchronized
    fun asBitmap(): Bitmap {
        var bmp = cachedBitmap
        if (bmp == null || bmp.width != width || bmp.height != height || bmp.isRecycled) {
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixelArray, 0, width, 0, 0, width, height)
            cachedBitmap = bmp
        }
        return bmp
    }

    /**
     * Directly copies pixels into an external output integer array (e.g. for JNI transfers).
     */
    @Synchronized
    fun copyPixelsTo(destArray: IntArray, destOffset: Int = 0): Int {
        val count = minOf(pixelArray.size, destArray.size - destOffset)
        System.arraycopy(pixelArray, 0, destArray, destOffset, count)
        return count
    }

    fun getPixelArray(): IntArray = pixelArray
}
