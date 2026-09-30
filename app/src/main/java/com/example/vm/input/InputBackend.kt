package com.example.vm.input

interface InputBackend {
    val touchInput: TouchInput
    val keyboardInput: KeyboardInput
    val mouseInput: MouseInput
    val virtualInputDevice: VirtualInputDevice

    fun postTouchEvent(event: TouchInputEvent)
    fun postMotionEvent(
        viewX: Float,
        viewY: Float,
        viewWidth: Float,
        viewHeight: Float,
        action: TouchAction,
        pressure: Float = 1.0f,
        pointerId: Int = 0
    )
    fun postKeyboardEvent(event: KeyboardInputEvent)
    fun postMouseEvent(event: MouseInputEvent)
    fun reset()
}

class AndroidInputBackend(
    override val virtualInputDevice: VirtualInputDevice = VirtualInputDevice(1024, 768)
) : InputBackend {

    override val mouseInput: MouseInput = MouseInput(virtualInputDevice)
    override val touchInput: TouchInput = TouchInput(virtualInputDevice, mouseInput)
    override val keyboardInput: KeyboardInput = KeyboardInput(virtualInputDevice)

    override fun postTouchEvent(event: TouchInputEvent) {
        touchInput.processTouchEvent(event)
    }

    override fun postMotionEvent(
        viewX: Float,
        viewY: Float,
        viewWidth: Float,
        viewHeight: Float,
        action: TouchAction,
        pressure: Float,
        pointerId: Int
    ) {
        touchInput.processMotionEvent(
            viewX = viewX,
            viewY = viewY,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            action = action,
            pressure = pressure,
            pointerId = pointerId
        )
    }

    override fun postKeyboardEvent(event: KeyboardInputEvent) {
        keyboardInput.processKeyEvent(event)
    }

    override fun postMouseEvent(event: MouseInputEvent) {
        mouseInput.processEvent(event)
    }

    override fun reset() {
        touchInput.reset()
        keyboardInput.reset()
        mouseInput.reset()
        virtualInputDevice.reset()
    }
}
