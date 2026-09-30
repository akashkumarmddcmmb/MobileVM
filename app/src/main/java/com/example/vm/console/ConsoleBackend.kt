package com.example.vm.console

import com.example.vm.devices.VirtualDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ConsoleBackend : VirtualDevice {
    val history: StateFlow<List<String>>
    val terminalBuffer: StateFlow<String>
    val isConnected: StateFlow<Boolean>

    fun writeTxBytes(bytes: ByteArray)
    fun writeTxChar(char: Char)
    fun sendInputLine(line: String)
    fun sendRawByte(byte: Byte)
    fun sendRawBytes(bytes: ByteArray)
    fun sendCtrlC()
    fun sendTab()
    fun clear()
    fun getAllText(): String
    fun setSendCallback(callback: (Byte) -> Unit)
    fun notifyVmShutdown()
    fun reconnect()
}

class UartPL011ConsoleBackend : ConsoleBackend {

    private val _history = MutableStateFlow<List<String>>(
        listOf(
            "=== MobileVM Serial Console (ttyAMA0 @ 115200 baud) ===",
            "Connecting to guest virtual UART subsystem at 0x09000000...",
            ""
        )
    )
    override val history: StateFlow<List<String>> = _history.asStateFlow()

    private val _terminalBuffer = MutableStateFlow(
        "=== MobileVM Serial Console (ttyAMA0 @ 115200 baud) ===\nConnecting to guest virtual UART subsystem at 0x09000000...\n"
    )
    override val terminalBuffer: StateFlow<String> = _terminalBuffer.asStateFlow()

    private val _isConnected = MutableStateFlow(true)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var currentLine = StringBuilder()
    private var sendCallback: ((Byte) -> Unit)? = null

    override fun setSendCallback(callback: (Byte) -> Unit) {
        this.sendCallback = callback
    }

    override fun writeTxBytes(bytes: ByteArray) {
        synchronized(this) {
            val text = String(bytes, Charsets.UTF_8)
            var i = 0
            while (i < text.length) {
                val c = text[i]

                // Check for ANSI Escape Sequences (e.g. \033[2J for clear)
                if (c == '\u001B' && i + 1 < text.length && text[i + 1] == '[') {
                    val endIdx = text.indexOf('m', i)
                    val clearIdx = text.indexOf('J', i)
                    val homeIdx = text.indexOf('H', i)

                    if (clearIdx in (i + 2)..(i + 5)) {
                        // Clear screen ANSI sequence
                        _history.value = emptyList()
                        currentLine.clear()
                        i = clearIdx + 1
                        continue
                    } else if (endIdx != -1 && endIdx < i + 10) {
                        // Skip styling ANSI sequence in raw text stream
                        i = endIdx + 1
                        continue
                    } else if (homeIdx != -1 && homeIdx < i + 6) {
                        i = homeIdx + 1
                        continue
                    }
                }

                when (c) {
                    '\n' -> {
                        commitCurrentLine()
                    }
                    '\r' -> {
                        // Check if followed by \n
                        if (i + 1 < text.length && text[i + 1] == '\n') {
                            commitCurrentLine()
                            i++
                        }
                    }
                    '\b' -> {
                        if (currentLine.isNotEmpty()) {
                            currentLine.deleteCharAt(currentLine.length - 1)
                            updateLastLine()
                        }
                    }
                    else -> {
                        if (c.code in 32..126 || c == '\t') {
                            currentLine.append(c)
                            updateLastLine()
                        }
                    }
                }
                i++
            }
        }
    }

    private fun commitCurrentLine() {
        val list = _history.value.toMutableList()
        if (list.isEmpty()) {
            list.add(currentLine.toString())
        } else {
            list[list.size - 1] = currentLine.toString()
        }
        list.add("")
        if (list.size > 500) {
            list.removeAt(0)
        }
        _history.value = list
        _terminalBuffer.value = list.joinToString("\n")
        currentLine.clear()
    }

    private fun updateLastLine() {
        val list = _history.value.toMutableList()
        if (list.isEmpty()) {
            list.add(currentLine.toString())
        } else {
            list[list.size - 1] = currentLine.toString()
        }
        _history.value = list
        _terminalBuffer.value = list.joinToString("\n")
    }

    override fun writeTxChar(char: Char) {
        writeTxBytes(byteArrayOf(char.code.toByte()))
    }

    override fun sendInputLine(line: String) {
        if (!_isConnected.value) return

        sendCallback?.let { cb ->
            for (ch in line) {
                cb(ch.code.toByte())
            }
            cb('\r'.code.toByte())
            cb('\n'.code.toByte())
        }
    }

    override fun sendRawByte(byte: Byte) {
        if (!_isConnected.value) return
        sendCallback?.invoke(byte)
    }

    override fun sendRawBytes(bytes: ByteArray) {
        if (!_isConnected.value) return
        sendCallback?.let { cb ->
            for (b in bytes) {
                cb(b)
            }
        }
    }

    override fun sendCtrlC() {
        sendRawByte(0x03)
    }

    override fun sendTab() {
        sendRawByte(0x09)
    }

    override fun clear() {
        synchronized(this) {
            _history.value = listOf("root@mobilevm:~# ")
            _terminalBuffer.value = "root@mobilevm:~# "
            currentLine.clear()
        }
    }

    override fun getAllText(): String {
        return synchronized(this) {
            _history.value.joinToString("\n")
        }
    }

    override fun notifyVmShutdown() {
        synchronized(this) {
            _isConnected.value = false
            val list = _history.value.toMutableList()
            list.add("\n[Guest OS halted / Serial connection terminated]")
            _history.value = list
            _terminalBuffer.value = list.joinToString("\n")
        }
    }

    override fun reconnect() {
        synchronized(this) {
            _isConnected.value = true
            val list = _history.value.toMutableList()
            list.add("[Reconnected to ttyAMA0 serial console]")
            _history.value = list
            _terminalBuffer.value = list.joinToString("\n")
            sendRawByte('\n'.code.toByte())
        }
    }

    override fun getDeviceName(): String = "PrimeCell PL011 UART Console Controller"

    override fun getDeviceStatus(): String = if (_isConnected.value) "Connected (115200 8N1)" else "Disconnected"

    override fun reset() {
        clear()
        _isConnected.value = true
    }
}
