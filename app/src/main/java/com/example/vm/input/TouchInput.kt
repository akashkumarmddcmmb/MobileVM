package com.example.vm.input

import kotlin.math.sqrt

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

data class TouchGestureRecord(
    val startX: Float,
    val startY: Float,
    val startTimeMs: Long,
    var lastX: Float,
    var lastY: Float,
    var isLongPressTriggered: Boolean = false,
    var isDragging: Boolean = false
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
 * to the underlying VirtIO touch / evdev virtual hardware as well as translating
 * single taps (Left click), long presses (Right click), drags, and wheel scrolls into
 * standard mouse events for guest operating systems (Linux ARM64, Windows ARM64).
 */
class TouchInput(
    private val virtualDevice: VirtualInputDevice,
    private val mouseInput: MouseInput? = null
) {
    private val activePointers = mutableMapOf<Int, TouchPointerState>()
    private val gestureTracker = mutableMapOf<Int, TouchGestureRecord>()

    companion object {
        const val TAP_MAX_DURATION_MS = 400L
        const val LONG_PRESS_MIN_DURATION_MS = 500L
        const val DRAG_THRESHOLD_DISTANCE = 15.0f
    }

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
        val now = event.timestampMs
        val absX = event.x.toInt()
        val absY = event.y.toInt()

        when (event.action) {
            TouchAction.DOWN -> {
                activePointers[event.pointerId] = TouchPointerState(
                    pointerId = event.pointerId,
                    x = event.x,
                    y = event.y,
                    pressure = event.pressure,
                    isDown = true
                )
                gestureTracker[event.pointerId] = TouchGestureRecord(
                    startX = event.x,
                    startY = event.y,
                    startTimeMs = now,
                    lastX = event.x,
                    lastY = event.y
                )
                // Move virtual mouse to touch location
                mouseInput?.processMove(dx = 0, dy = 0, absX = absX, absY = absY)
            }
            TouchAction.MOVE -> {
                activePointers[event.pointerId] = TouchPointerState(
                    pointerId = event.pointerId,
                    x = event.x,
                    y = event.y,
                    pressure = event.pressure,
                    isDown = true
                )
                val record = gestureTracker[event.pointerId]
                if (record != null) {
                    val dx = (event.x - record.lastX).toInt()
                    val dy = (event.y - record.lastY).toInt()
                    val distFromStart = sqrt(
                        (event.x - record.startX) * (event.x - record.startX) +
                        (event.y - record.startY) * (event.y - record.startY)
                    )

                    if (distFromStart >= DRAG_THRESHOLD_DISTANCE) {
                        if (!record.isDragging) {
                            record.isDragging = true
                            // Begin drag with left mouse button held DOWN
                            mouseInput?.processButton(MouseButton.LEFT, true)
                        }
                        mouseInput?.processMove(
                            dx = dx,
                            dy = dy,
                            absX = absX,
                            absY = absY,
                            buttons = MouseButton.LEFT.mask
                        )
                    } else {
                        mouseInput?.processMove(
                            dx = dx,
                            dy = dy,
                            absX = absX,
                            absY = absY,
                            buttons = 0
                        )
                    }

                    record.lastX = event.x
                    record.lastY = event.y
                }
            }
            TouchAction.UP -> {
                activePointers.remove(event.pointerId)
                val record = gestureTracker.remove(event.pointerId)

                if (record != null) {
                    val distFromStart = sqrt(
                        (event.x - record.startX) * (event.x - record.startX) +
                        (event.y - record.startY) * (event.y - record.startY)
                    )
                    val duration = now - record.startTimeMs

                    if (record.isDragging) {
                        // Release drag button
                        mouseInput?.processButton(MouseButton.LEFT, false)
                    } else if (distFromStart < DRAG_THRESHOLD_DISTANCE) {
                        if (duration >= LONG_PRESS_MIN_DURATION_MS) {
                            // Long press -> Right Click (DOWN then UP)
                            mouseInput?.processButton(MouseButton.RIGHT, true)
                            mouseInput?.processButton(MouseButton.RIGHT, false)
                        } else {
                            // Single tap -> Left Click (DOWN then UP)
                            mouseInput?.processButton(MouseButton.LEFT, true)
                            mouseInput?.processButton(MouseButton.LEFT, false)
                        }
                    }
                }
            }
            TouchAction.CANCEL -> {
                activePointers.remove(event.pointerId)
                val record = gestureTracker.remove(event.pointerId)
                if (record?.isDragging == true) {
                    mouseInput?.processButton(MouseButton.LEFT, false)
                }
            }
        }

        // Direct event dispatch to real virtual HID/digitizer device
        virtualDevice.sendTouchEvent(event)
    }

    /**
     * Direct API to perform a single tap resulting in Left Mouse Click.
     */
    fun performSingleTap(absX: Int, absY: Int) {
        val now = System.currentTimeMillis()
        processTouchEvent(TouchInputEvent(TouchAction.DOWN, absX.toFloat(), absY.toFloat(), 1.0f, 0, now))
        processTouchEvent(TouchInputEvent(TouchAction.UP, absX.toFloat(), absY.toFloat(), 0.0f, 0, now + 50))
    }

    /**
     * Direct API to perform a long press resulting in Right Mouse Click.
     */
    fun performLongPress(absX: Int, absY: Int) {
        val now = System.currentTimeMillis()
        processTouchEvent(TouchInputEvent(TouchAction.DOWN, absX.toFloat(), absY.toFloat(), 1.0f, 0, now))
        processTouchEvent(TouchInputEvent(TouchAction.UP, absX.toFloat(), absY.toFloat(), 0.0f, 0, now + 600))
    }

    /**
     * Direct API to perform a touch drag translating to left-click mouse movement.
     */
    fun performDrag(fromX: Int, fromY: Int, toX: Int, toY: Int, steps: Int = 5) {
        var now = System.currentTimeMillis()
        processTouchEvent(TouchInputEvent(TouchAction.DOWN, fromX.toFloat(), fromY.toFloat(), 1.0f, 0, now))

        val stepCount = steps.coerceAtLeast(1)
        for (i in 1..stepCount) {
            now += 30
            val curX = fromX + ((toX - fromX) * i) / stepCount
            val curY = fromY + ((toY - fromY) * i) / stepCount
            processTouchEvent(TouchInputEvent(TouchAction.MOVE, curX.toFloat(), curY.toFloat(), 1.0f, 0, now))
        }

        now += 30
        processTouchEvent(TouchInputEvent(TouchAction.UP, toX.toFloat(), toY.toFloat(), 0.0f, 0, now))
    }

    /**
     * Direct API to perform a mouse scroll / wheel event.
     */
    fun performScroll(wheelDelta: Int) {
        mouseInput?.processWheel(wheelDelta)
    }

    fun getActivePointers(): Map<Int, TouchPointerState> = activePointers.toMap()

    fun reset() {
        activePointers.clear()
        gestureTracker.clear()
    }
}
