package com.example.vm.input

import com.example.vm.devices.VirtualDevice
import com.example.vm.nativebridge.NativeVMBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VirtualInputDevice(
    val screenWidth: Int = 1024,
    val screenHeight: Int = 768
) : VirtualDevice {

    private var nativeHandle: Long = 0L

    private val _eventsDispatched = MutableStateFlow(0L)
    val eventsDispatched: StateFlow<Long> = _eventsDispatched.asStateFlow()

    private val _lastEventSummary = MutableStateFlow("Input device initialized (${screenWidth}x${screenHeight} VirtIO HID)")
    val lastEventSummary: StateFlow<String> = _lastEventSummary.asStateFlow()

    fun bindNativeHandle(handle: Long) {
        this.nativeHandle = handle
    }

    fun sendTouchEvent(event: TouchInputEvent) {
        _eventsDispatched.value++
        _lastEventSummary.value = "Touch ${event.action.name} @ (${event.x.toInt()}, ${event.y.toInt()}) [P=${(event.pressure * 100).toInt()}%] id=${event.pointerId}"

        if (nativeHandle != 0L && NativeVMBinding.isLoaded()) {
            NativeVMBinding.nativeSendTouchEvent(
                handle = nativeHandle,
                action = event.action.code,
                x = event.x,
                y = event.y,
                pressure = event.pressure,
                pointerId = event.pointerId
            )
        }
    }

    fun sendMouseEvent(event: MouseInputEvent) {
        _eventsDispatched.value++
        _lastEventSummary.value = "Mouse [mask=${event.buttonMask} (L:${event.isLeftPressed}, R:${event.isRightPressed}, M:${event.isMiddlePressed})] delta=(${event.deltaX}, ${event.deltaY}) wheel=${event.wheelDelta} abs=(${event.absX}, ${event.absY})"

        if (nativeHandle != 0L && NativeVMBinding.isLoaded()) {
            NativeVMBinding.nativeSendMouseEvent(
                handle = nativeHandle,
                buttonMask = event.buttonMask,
                dx = event.deltaX,
                dy = event.deltaY,
                absX = event.absX,
                absY = event.absY,
                wheelDelta = event.wheelDelta
            )
        }
    }

    fun sendKeyEvent(event: KeyboardInputEvent) {
        _eventsDispatched.value++
        _lastEventSummary.value = "Key ${if (event.isDown) "DOWN" else "UP"} code=${event.scanCode} char='${event.unicodeChar}'"

        if (nativeHandle != 0L && NativeVMBinding.isLoaded()) {
            NativeVMBinding.nativeSendKeyEvent(
                handle = nativeHandle,
                scanCode = event.scanCode,
                isDown = event.isDown
            )
        }
    }

    override fun getDeviceName(): String = "VirtIO Multi-Touch & HID Input Controller"

    override fun getDeviceStatus(): String = "Active (Mapped to IRQ 3, Base MMIO: 0x0B000000, Dispatched: ${_eventsDispatched.value})"

    override fun reset() {
        _eventsDispatched.value = 0L
        _lastEventSummary.value = "Input device reset"
    }
}
