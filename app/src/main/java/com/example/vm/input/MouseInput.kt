package com.example.vm.input

enum class MouseButton(val mask: Int) {
    NONE(0),
    LEFT(1),
    RIGHT(2),
    MIDDLE(4)
}

data class MouseInputEvent(
    val buttonMask: Int = 0,
    val deltaX: Int = 0,
    val deltaY: Int = 0,
    val absX: Int = -1,
    val absY: Int = -1,
    val wheelDelta: Int = 0,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isLeftPressed: Boolean get() = (buttonMask and MouseButton.LEFT.mask) != 0
    val isRightPressed: Boolean get() = (buttonMask and MouseButton.RIGHT.mask) != 0
    val isMiddlePressed: Boolean get() = (buttonMask and MouseButton.MIDDLE.mask) != 0
}

/**
 * MouseInput processes relative mouse motion, wheel deltas, absolute cursor points,
 * and mouse buttons and dispatches them to VirtualInputDevice.
 */
class MouseInput(
    private val virtualDevice: VirtualInputDevice
) {
    private var lastAbsX = 0
    private var lastAbsY = 0
    private var currentButtonMask = 0

    fun processMove(dx: Int, dy: Int, absX: Int = -1, absY: Int = -1, buttons: Int = currentButtonMask, wheelDelta: Int = 0) {
        currentButtonMask = buttons
        if (absX >= 0) lastAbsX = absX
        if (absY >= 0) lastAbsY = absY
        val event = MouseInputEvent(
            buttonMask = buttons,
            deltaX = dx,
            deltaY = dy,
            absX = absX,
            absY = absY,
            wheelDelta = wheelDelta
        )
        // Direct event dispatch to real virtual HID/input device
        virtualDevice.sendMouseEvent(event)
    }

    fun processWheel(wheelDelta: Int) {
        val event = MouseInputEvent(
            buttonMask = currentButtonMask,
            deltaX = 0,
            deltaY = 0,
            absX = lastAbsX,
            absY = lastAbsY,
            wheelDelta = wheelDelta
        )
        virtualDevice.sendMouseEvent(event)
    }

    fun processEvent(event: MouseInputEvent) {
        currentButtonMask = event.buttonMask
        if (event.absX >= 0) lastAbsX = event.absX
        if (event.absY >= 0) lastAbsY = event.absY
        virtualDevice.sendMouseEvent(event)
    }

    fun processButton(button: MouseButton, isDown: Boolean) {
        currentButtonMask = if (isDown) {
            currentButtonMask or button.mask
        } else {
            currentButtonMask and button.mask.inv()
        }
        val event = MouseInputEvent(
            buttonMask = currentButtonMask,
            absX = lastAbsX,
            absY = lastAbsY
        )
        virtualDevice.sendMouseEvent(event)
    }

    fun getCursorPosition(): Pair<Int, Int> = Pair(lastAbsX, lastAbsY)
    fun getButtonMask(): Int = currentButtonMask

    fun reset() {
        lastAbsX = 0
        lastAbsY = 0
        currentButtonMask = 0
    }
}
