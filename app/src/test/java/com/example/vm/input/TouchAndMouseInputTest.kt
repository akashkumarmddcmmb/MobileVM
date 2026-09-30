package com.example.vm.input

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TouchAndMouseInputTest {

    private lateinit var virtualInputDevice: VirtualInputDevice
    private lateinit var inputBackend: AndroidInputBackend
    private lateinit var touchInput: TouchInput
    private lateinit var mouseInput: MouseInput

    @Before
    fun setUp() {
        virtualInputDevice = VirtualInputDevice(screenWidth = 1024, screenHeight = 768)
        inputBackend = AndroidInputBackend(virtualInputDevice)
        touchInput = inputBackend.touchInput
        mouseInput = inputBackend.mouseInput
    }

    @Test
    fun testSingleTapProducesLeftMouseClick() {
        // Single tap at (500, 300) in 1000x1000 view
        val viewW = 1000f
        val viewH = 1000f

        touchInput.processMotionEvent(
            viewX = 500f,
            viewY = 300f,
            viewWidth = viewW,
            viewHeight = viewH,
            action = TouchAction.DOWN
        )

        // Verify initial cursor position
        val cursor = mouseInput.getCursorPosition()
        assertEquals(512, cursor.first)
        assertEquals(230, cursor.second)

        // Release quickly (Single Tap)
        touchInput.processMotionEvent(
            viewX = 500f,
            viewY = 300f,
            viewWidth = viewW,
            viewHeight = viewH,
            action = TouchAction.UP
        )

        // Button mask is released after full click sequence
        assertEquals(0, mouseInput.getButtonMask())
        assertTrue(virtualInputDevice.eventsDispatched.value >= 2)
    }

    @Test
    fun testDirectSingleTapAndLeftClickAPI() {
        touchInput.performSingleTap(absX = 400, absY = 200)

        val cursor = mouseInput.getCursorPosition()
        assertEquals(400, cursor.first)
        assertEquals(200, cursor.second)
        assertEquals(0, mouseInput.getButtonMask())
    }

    @Test
    fun testLongPressProducesRightMouseClick() {
        val viewW = 800f
        val viewH = 600f
        val startTime = System.currentTimeMillis()

        // Touch DOWN
        touchInput.processTouchEvent(
            TouchInputEvent(
                action = TouchAction.DOWN,
                x = 250f,
                y = 150f,
                timestampMs = startTime
            )
        )

        // Touch UP after 600ms (Long Press)
        touchInput.processTouchEvent(
            TouchInputEvent(
                action = TouchAction.UP,
                x = 250f,
                y = 150f,
                timestampMs = startTime + 600L
            )
        )

        // Right click executed (DOWN and UP sequence)
        assertEquals(0, mouseInput.getButtonMask())
        val cursor = mouseInput.getCursorPosition()
        assertEquals(250, cursor.first)
        assertEquals(150, cursor.second)
    }

    @Test
    fun testDirectLongPressAPI() {
        touchInput.performLongPress(absX = 600, absY = 400)

        val cursor = mouseInput.getCursorPosition()
        assertEquals(600, cursor.first)
        assertEquals(400, cursor.second)
        assertEquals(0, mouseInput.getButtonMask())
    }

    @Test
    fun testTouchDragTranslatesToMouseMovementWithLeftButton() {
        val startTime = System.currentTimeMillis()

        // DOWN at (100, 100)
        touchInput.processTouchEvent(
            TouchInputEvent(
                action = TouchAction.DOWN,
                x = 100f,
                y = 100f,
                timestampMs = startTime
            )
        )

        // MOVE to (200, 200) -> distance > 15px (Drag triggered)
        touchInput.processTouchEvent(
            TouchInputEvent(
                action = TouchAction.MOVE,
                x = 200f,
                y = 200f,
                timestampMs = startTime + 100
            )
        )

        // While dragging, LEFT button is held down
        assertEquals(MouseButton.LEFT.mask, mouseInput.getButtonMask())
        var cursor = mouseInput.getCursorPosition()
        assertEquals(200, cursor.first)
        assertEquals(200, cursor.second)

        // UP releases the drag
        touchInput.processTouchEvent(
            TouchInputEvent(
                action = TouchAction.UP,
                x = 200f,
                y = 200f,
                timestampMs = startTime + 200
            )
        )

        // Drag ended, button released
        assertEquals(0, mouseInput.getButtonMask())
    }

    @Test
    fun testDirectDragAPI() {
        touchInput.performDrag(fromX = 50, fromY = 50, toX = 350, toY = 350, steps = 5)

        val cursor = mouseInput.getCursorPosition()
        assertEquals(350, cursor.first)
        assertEquals(350, cursor.second)
        assertEquals(0, mouseInput.getButtonMask())
    }

    @Test
    fun testVerticalScrollTranslatesToWheelEvent() {
        touchInput.performScroll(wheelDelta = 3)
        touchInput.performScroll(wheelDelta = -2)

        assertTrue(virtualInputDevice.eventsDispatched.value >= 2)
        assertTrue(virtualInputDevice.lastEventSummary.value.contains("wheel="))
    }

    @Test
    fun testCoordinateScalingFromViewToTargetResolution() {
        // Target: 1024x768
        // Android View: 500x1000
        val event = TouchInputEvent.fromViewCoordinates(
            action = TouchAction.MOVE,
            viewX = 250f, // 50%
            viewY = 500f, // 50%
            viewWidth = 500f,
            viewHeight = 1000f,
            targetWidth = 1024,
            targetHeight = 768
        )

        assertEquals(512f, event.x, 0.01f)
        assertEquals(384f, event.y, 0.01f)
    }

    @Test
    fun testOrientationAndScreenResizeCoordinateMapping() {
        // Portrait View: 360 x 640
        val portraitEvent = TouchInputEvent.fromViewCoordinates(
            action = TouchAction.DOWN,
            viewX = 180f,
            viewY = 320f,
            viewWidth = 360f,
            viewHeight = 640f,
            targetWidth = 1024,
            targetHeight = 768
        )
        assertEquals(512f, portraitEvent.x, 0.01f)
        assertEquals(384f, portraitEvent.y, 0.01f)

        // Landscape View: 640 x 360
        val landscapeEvent = TouchInputEvent.fromViewCoordinates(
            action = TouchAction.DOWN,
            viewX = 320f,
            viewY = 180f,
            viewWidth = 640f,
            viewHeight = 360f,
            targetWidth = 1024,
            targetHeight = 768
        )
        assertEquals(512f, landscapeEvent.x, 0.01f)
        assertEquals(384f, landscapeEvent.y, 0.01f)
    }

    @Test
    fun testMouseButtonDownMoveUpSequence() {
        // 1. Button DOWN
        mouseInput.processButton(MouseButton.LEFT, isDown = true)
        assertEquals(MouseButton.LEFT.mask, mouseInput.getButtonMask())

        // 2. Button MOVE
        mouseInput.processMove(dx = 10, dy = 15, absX = 500, absY = 400)
        assertEquals(MouseButton.LEFT.mask, mouseInput.getButtonMask())
        assertEquals(Pair(500, 400), mouseInput.getCursorPosition())

        // 3. Button UP
        mouseInput.processButton(MouseButton.LEFT, isDown = false)
        assertEquals(0, mouseInput.getButtonMask())
    }

    @Test
    fun testSimultaneousTouchAndKeyboardInput() {
        // Perform touch input
        touchInput.performSingleTap(absX = 100, absY = 100)

        // Perform simultaneous keyboard input
        inputBackend.postKeyboardEvent(
            KeyboardInputEvent(
                scanCode = 30, // Key 'A'
                keyCode = 30,
                unicodeChar = 'a',
                type = KeyEventType.DOWN
            )
        )
        inputBackend.postKeyboardEvent(
            KeyboardInputEvent(
                scanCode = 30,
                keyCode = 30,
                unicodeChar = 'a',
                type = KeyEventType.UP
            )
        )

        // Perform another touch drag
        touchInput.performDrag(fromX = 100, fromY = 100, toX = 200, toY = 200)

        assertTrue(virtualInputDevice.eventsDispatched.value >= 5)
    }
}
