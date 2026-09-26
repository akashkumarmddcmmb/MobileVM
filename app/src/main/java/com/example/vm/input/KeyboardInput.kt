package com.example.vm.input

enum class KeyEventType {
    DOWN,
    UP
}

data class KeyboardInputEvent(
    val scanCode: Int,
    val keyCode: Int,
    val unicodeChar: Char = '\u0000',
    val type: KeyEventType = KeyEventType.DOWN,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isDown: Boolean get() = type == KeyEventType.DOWN

    companion object {
        // Standard Linux evdev Keycodes (linux/input-event-codes.h)
        const val KEY_RESERVED = 0
        const val KEY_ESC = 1
        const val KEY_1 = 2
        const val KEY_2 = 3
        const val KEY_3 = 4
        const val KEY_4 = 5
        const val KEY_5 = 6
        const val KEY_6 = 7
        const val KEY_7 = 8
        const val KEY_8 = 9
        const val KEY_9 = 10
        const val KEY_0 = 11
        const val KEY_MINUS = 12
        const val KEY_EQUAL = 13
        const val KEY_BACKSPACE = 14
        const val KEY_TAB = 15
        const val KEY_Q = 16
        const val KEY_W = 17
        const val KEY_E = 18
        const val KEY_R = 19
        const val KEY_T = 20
        const val KEY_Y = 21
        const val KEY_U = 22
        const val KEY_I = 23
        const val KEY_O = 24
        const val KEY_P = 25
        const val KEY_LEFTBRACE = 26
        const val KEY_RIGHTBRACE = 27
        const val KEY_ENTER = 28
        const val KEY_LEFTCTRL = 29
        const val KEY_A = 30
        const val KEY_S = 31
        const val KEY_D = 32
        const val KEY_F = 33
        const val KEY_G = 34
        const val KEY_H = 35
        const val KEY_J = 36
        const val KEY_K = 37
        const val KEY_L = 38
        const val KEY_SEMICOLON = 39
        const val KEY_APOSTROPHE = 40
        const val KEY_GRAVE = 41
        const val KEY_LEFTSHIFT = 42
        const val KEY_BACKSLASH = 43
        const val KEY_Z = 44
        const val KEY_X = 45
        const val KEY_C = 46
        const val KEY_V = 47
        const val KEY_B = 48
        const val KEY_N = 49
        const val KEY_M = 50
        const val KEY_COMMA = 51
        const val KEY_DOT = 52
        const val KEY_SLASH = 53
        const val KEY_RIGHTSHIFT = 54
        const val KEY_KPASTERISK = 55
        const val KEY_LEFTALT = 56
        const val KEY_SPACE = 57
        const val KEY_CAPSLOCK = 58
        const val KEY_F1 = 59
        const val KEY_F2 = 60
        const val KEY_F3 = 61
        const val KEY_F4 = 62
        const val KEY_F5 = 63
        const val KEY_F6 = 64
        const val KEY_F7 = 65
        const val KEY_F8 = 66
        const val KEY_F9 = 67
        const val KEY_F10 = 68
        const val KEY_NUMLOCK = 69
        const val KEY_SCROLLLOCK = 70
        const val KEY_KP7 = 71
        const val KEY_KP8 = 72
        const val KEY_KP9 = 73
        const val KEY_KPMINUS = 74
        const val KEY_KP4 = 75
        const val KEY_KP5 = 76
        const val KEY_KP6 = 77
        const val KEY_KPPLUS = 78
        const val KEY_KP1 = 79
        const val KEY_KP2 = 80
        const val KEY_KP3 = 81
        const val KEY_KP0 = 82
        const val KEY_KPDOT = 83
        const val KEY_F11 = 87
        const val KEY_F12 = 88
        const val KEY_KPENTER = 96
        const val KEY_RIGHTCTRL = 97
        const val KEY_KPSLASH = 98
        const val KEY_SYSRQ = 99
        const val KEY_RIGHTALT = 100
        const val KEY_HOME = 102
        const val KEY_UP = 103
        const val KEY_PAGEUP = 104
        const val KEY_LEFT = 105
        const val KEY_RIGHT = 106
        const val KEY_END = 107
        const val KEY_DOWN = 108
        const val KEY_PAGEDOWN = 109
        const val KEY_INSERT = 110
        const val KEY_DELETE = 111
        const val KEY_PAUSE = 119
        const val KEY_LEFTMETA = 125
        const val KEY_RIGHTMETA = 126

        fun fromChar(char: Char, isDown: Boolean = true): KeyboardInputEvent {
            val scanCode = when (char.lowercaseChar()) {
                'a' -> KEY_A
                'b' -> KEY_B
                'c' -> KEY_C
                'd' -> KEY_D
                'e' -> KEY_E
                'f' -> KEY_F
                'g' -> KEY_G
                'h' -> KEY_H
                'i' -> KEY_I
                'j' -> KEY_J
                'k' -> KEY_K
                'l' -> KEY_L
                'm' -> KEY_M
                'n' -> KEY_N
                'o' -> KEY_O
                'p' -> KEY_P
                'q' -> KEY_Q
                'r' -> KEY_R
                's' -> KEY_S
                't' -> KEY_T
                'u' -> KEY_U
                'v' -> KEY_V
                'w' -> KEY_W
                'x' -> KEY_X
                'y' -> KEY_Y
                'z' -> KEY_Z
                '0' -> KEY_0
                '1' -> KEY_1
                '2' -> KEY_2
                '3' -> KEY_3
                '4' -> KEY_4
                '5' -> KEY_5
                '6' -> KEY_6
                '7' -> KEY_7
                '8' -> KEY_8
                '9' -> KEY_9
                ' ' -> KEY_SPACE
                '\n', '\r' -> KEY_ENTER
                '\t' -> KEY_TAB
                '\b' -> KEY_BACKSPACE
                '-' -> KEY_MINUS
                '=' -> KEY_EQUAL
                '[' -> KEY_LEFTBRACE
                ']' -> KEY_RIGHTBRACE
                ';' -> KEY_SEMICOLON
                '\'' -> KEY_APOSTROPHE
                ',' -> KEY_COMMA
                '.' -> KEY_DOT
                '/' -> KEY_SLASH
                '\\' -> KEY_BACKSLASH
                '`' -> KEY_GRAVE
                else -> KEY_SPACE
            }

            return KeyboardInputEvent(
                scanCode = scanCode,
                keyCode = scanCode,
                unicodeChar = char,
                type = if (isDown) KeyEventType.DOWN else KeyEventType.UP,
                shift = char.isUpperCase()
            )
        }
    }
}

/**
 * KeyboardInput handles hardware and virtual keyboard event translation to Linux evdev scancodes
 * and directly forwards them to the VirtualInputDevice.
 */
class KeyboardInput(
    private val virtualDevice: VirtualInputDevice
) {
    private val pressedKeys = mutableSetOf<Int>()

    fun processChar(char: Char, isDown: Boolean = true) {
        val event = KeyboardInputEvent.fromChar(char, isDown)
        processKeyEvent(event)
    }

    fun processKey(scanCode: Int, isDown: Boolean) {
        val event = KeyboardInputEvent(
            scanCode = scanCode,
            keyCode = scanCode,
            type = if (isDown) KeyEventType.DOWN else KeyEventType.UP
        )
        processKeyEvent(event)
    }

    fun processKeyEvent(event: KeyboardInputEvent) {
        if (event.isDown) {
            pressedKeys.add(event.scanCode)
        } else {
            pressedKeys.remove(event.scanCode)
        }
        // Direct event dispatch to real virtual HID/input device
        virtualDevice.sendKeyEvent(event)
    }

    fun getActiveKeys(): Set<Int> = pressedKeys.toSet()

    fun reset() {
        pressedKeys.clear()
    }
}
