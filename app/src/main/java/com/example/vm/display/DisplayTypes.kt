package com.example.vm.display

/**
 * Pixel color formats supported by the virtual display subsystem.
 */
enum class DisplayColorFormat(
    val bpp: Int,
    val bytesPerPixel: Int,
    val description: String
) {
    ARGB_8888(bpp = 32, bytesPerPixel = 4, description = "32-bit ARGB (8:8:8:8 standard Android format)"),
    XRGB_8888(bpp = 32, bytesPerPixel = 4, description = "32-bit XRGB (8:8:8:8 standard Linux DRM format)"),
    RGB_565(bpp = 16, bytesPerPixel = 2, description = "16-bit RGB (5:6:5 compact format)"),
    RGBA_8888(bpp = 32, bytesPerPixel = 4, description = "32-bit RGBA (8:8:8:8 OpenGL/Vulkan format)")
}

/**
 * Hardware rasterizer and guest scanout status.
 */
enum class DisplayRenderStatus(val label: String, val description: String) {
    UNINITIALIZED(
        label = "Uninitialized",
        description = "Display controller has not been configured by the guest"
    ),
    PENDING_GRAPHICAL_PIPELINE(
        label = "Graphical Display Pending",
        description = "Display interface active. Real guest graphical scanout pipeline integration is pending."
    ),
    ACTIVE_RASTER(
        label = "Active Raster",
        description = "Guest OS is actively writing scanout frames to the virtual framebuffer"
    ),
    STANDBY(
        label = "Display Standby",
        description = "Virtual display controller is powered down or VM is suspended"
    ),
    DISABLED(
        label = "Headless Mode",
        description = "VM running in pure headless / serial console only mode"
    )
}

/**
 * Encapsulates a dirty rectangle region for partial display updates.
 */
data class DirtyRect(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}
