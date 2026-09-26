package com.example.vm.usb

import com.example.vm.input.MouseButton
import com.example.vm.input.MouseInputEvent

data class ParsedMouseReport(
    val event: MouseInputEvent,
    val isLeftPressed: Boolean,
    val isRightPressed: Boolean,
    val isMiddlePressed: Boolean,
    val deltaX: Int,
    val deltaY: Int,
    val wheelDelta: Int,
    val absX: Int,
    val absY: Int,
    val buttonMask: Int
)

/**
 * UsbMouseParser decodes raw USB HID Mouse Reports (3 to 8 bytes) into
 * relative movement deltas, button states (Left, Right, Middle), scroll wheel ticks,
 * and virtual coordinate space positions.
 */
class UsbMouseParser(
    val screenWidth: Int = 1024,
    val screenHeight: Int = 768
) {
    private var currentAbsX = screenWidth / 2
    private var currentAbsY = screenHeight / 2
    private var prevButtonMask = 0

    companion object {
        const val MASK_BUTTON_LEFT   = 0x01
        const val MASK_BUTTON_RIGHT  = 0x02
        const val MASK_BUTTON_MIDDLE = 0x04
        const val MASK_BUTTON_4      = 0x08
        const val MASK_BUTTON_5      = 0x10
    }

    /**
     * Parses a raw USB HID mouse report.
     * Standard Boot Mouse Protocol:
     * Byte 0: Button bits (0: Left, 1: Right, 2: Middle)
     * Byte 1: Delta X (signed 8-bit, -128..127)
     * Byte 2: Delta Y (signed 8-bit, -128..127)
     * Byte 3: Wheel Delta (signed 8-bit, -128..127, optional for 4-byte/Intellimouse reports)
     */
    fun parseReport(buffer: ByteArray, length: Int): ParsedMouseReport? {
        if (length < 3) return null

        val buttonByte = buffer[0].toInt() and 0xFF
        val rawDx = buffer[1].toInt() // sign-extended 8-bit
        val rawDy = buffer[2].toInt() // sign-extended 8-bit

        val rawWheel = if (length >= 4) {
            buffer[3].toInt() // sign-extended 8-bit
        } else {
            0
        }

        // Standardize button mask: bit 0: Left, bit 1: Right, bit 2: Middle
        var buttonMask = 0
        if ((buttonByte and MASK_BUTTON_LEFT) != 0) buttonMask = buttonMask or MouseButton.LEFT.mask
        if ((buttonByte and MASK_BUTTON_RIGHT) != 0) buttonMask = buttonMask or MouseButton.RIGHT.mask
        if ((buttonByte and MASK_BUTTON_MIDDLE) != 0) buttonMask = buttonMask or MouseButton.MIDDLE.mask

        // Update absolute coordinate projection within guest screen bounds
        currentAbsX = (currentAbsX + rawDx).coerceIn(0, screenWidth)
        currentAbsY = (currentAbsY + rawDy).coerceIn(0, screenHeight)

        val isLeft = (buttonMask and MouseButton.LEFT.mask) != 0
        val isRight = (buttonMask and MouseButton.RIGHT.mask) != 0
        val isMiddle = (buttonMask and MouseButton.MIDDLE.mask) != 0

        val event = MouseInputEvent(
            buttonMask = buttonMask,
            deltaX = rawDx,
            deltaY = rawDy,
            absX = currentAbsX,
            absY = currentAbsY,
            wheelDelta = rawWheel
        )

        prevButtonMask = buttonMask

        return ParsedMouseReport(
            event = event,
            isLeftPressed = isLeft,
            isRightPressed = isRight,
            isMiddlePressed = isMiddle,
            deltaX = rawDx,
            deltaY = rawDy,
            wheelDelta = rawWheel,
            absX = currentAbsX,
            absY = currentAbsY,
            buttonMask = buttonMask
        )
    }

    fun setCursorPosition(x: Int, y: Int) {
        currentAbsX = x.coerceIn(0, screenWidth)
        currentAbsY = y.coerceIn(0, screenHeight)
    }

    fun reset() {
        currentAbsX = screenWidth / 2
        currentAbsY = screenHeight / 2
        prevButtonMask = 0
    }
}
