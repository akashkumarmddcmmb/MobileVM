package com.example.vm.console

import com.example.vm.devices.VirtualDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SerialConsole : VirtualDevice {
    private val _history = MutableStateFlow<List<String>>(listOf("=== MobileVM Serial Console Initialized ===", "Type 'help' for a list of available host-guest commands."))
    val history: StateFlow<List<String>> = _history

    private val listeners = mutableListOf<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun write(text: String) {
        val current = _history.value.toMutableList()
        // Split by lines
        text.split("\n").forEach { line ->
            current.add(line)
        }
        // Limit log scrollback
        if (current.size > 200) {
            current.removeAt(0)
        }
        _history.value = current
        listeners.forEach { it(text) }
    }

    fun clear() {
        _history.value = emptyList()
    }

    fun handleInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        listeners.forEach { it(trimmed) }
    }

    override fun getDeviceName(): String = "Virtual Serial Terminal (16550A UART)"

    override fun getDeviceStatus(): String = "Active (baud rate: 115200)"

    override fun reset() {
        clear()
        write("=== MobileVM Serial Console Reset ===")
    }
}
