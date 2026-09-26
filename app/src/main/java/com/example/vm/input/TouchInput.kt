package com.example.vm.input

enum class TouchAction(val code: Int) {
    DOWN(0),
    UP(1),
    MOVE(2),
    CANCEL(3);

    companion object {
        fun fromCode(code: Int): TouchAction = entries.firstOrNull { it.code == code } ?: CANCEL
    }
}

data class TouchPointerState(
    val pointerId: Int,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val isDown: Boolean
)

data class TouchInputEvent(
    val action: TouchAction,
    val x: Float,
    val y: Float,
    val pressure: Float = 1.0f,
    val pointerId: Int = 0,
    val timestampMs: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Transforms Android view coordinates into virtual machine framebuffer/digitizer space.
         */
        fun fromViewCoordinates(
            action: TouchAction,
            viewX: Float,
            viewY: Float,
            viewWidth: Float,
            viewHeight: Float,
            targetWidth: Int = 1024,
            targetHeight: Int = 768,
            pressure: Float = 1.0f,
            pointerId: Int = 0
        ): TouchInputEvent {
            val clampedViewX = viewX.coerceIn(0f, viewWidth.coerceAtLeast(1f))
            val clampedViewY = viewY.coerceIn(0f, viewHeight.coerceAtLeast(1f))

            val scaleX = if (viewWidth > 0) targetWidth.toFloat() / viewWidth else 1.0f
            val scaleY = if (viewHeight > 0) targetHeight.toFloat() / viewHeight else 1.0f

            val vmX = (clampedViewX * scaleX).coerceIn(0f, targetWidth.toFloat())
            val vmY = (clampedViewY * scaleY).coerceIn(0f, targetHeight.toFloat())

            return TouchInputEvent(
                action = action,
                x = vmX,
                y = vmY,
                pressure = pressure.coerceIn(0.0f, 1.0f),
                pointerId = pointerId
            )
        }
    }
}

/**
 * TouchInput manages multi-touch digitizer coordinate translation and direct dispatch
 * to the underlying VirtIO touch / evdev virtual hardware.
 */
class TouchInput(
    private val virtualDevice: VirtualInputDevice
) {
    private val activePointers = mutableMapOf<Int, TouchPointerState>()

    fun processMotionEvent(
        viewX: Float,
        viewY: Float,
        viewWidth: Float,
        viewHeight: Float,
        action: TouchAction,
        pressure: Float = 1.0f,
        pointerId: Int = 0
    ) {
        val vmEvent = TouchInputEvent.fromViewCoordinates(
            action = action,
            viewX = viewX,
            viewY = viewY,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            targetWidth = virtualDevice.screenWidth,
            targetHeight = virtualDevice.screenHeight,
            pressure = pressure,
            pointerId = pointerId
        )
        processTouchEvent(vmEvent)
    }

    fun processTouchEvent(event: TouchInputEvent) {
        when (event.action) {
            TouchAction.DOWN, TouchAction.MOVE -> {
                activePointers[event.pointerId] = TouchPointerState(
                    pointerId = event.pointerId,
                    x = event.x,
                    y = event.y,
                    pressure = event.pressure,
                    isDown = true
                )
            }
            TouchAction.UP, TouchAction.CANCEL -> {
                activePointers.remove(event.pointerId)
            }
        }
        // Direct event dispatch to real virtual HID/input device
        virtualDevice.sendTouchEvent(event)
    }

    fun getActivePointers(): Map<Int, TouchPointerState> = activePointers.toMap()

    fun reset() {
        activePointers.clear()
    }
}
