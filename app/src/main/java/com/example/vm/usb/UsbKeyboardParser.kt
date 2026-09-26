package com.example.vm.usb

import com.example.vm.input.KeyEventType
import com.example.vm.input.KeyboardInputEvent
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_0
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_1
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_2
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_3
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_4
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_5
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_6
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_7
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_8
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_9
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_A
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_APOSTROPHE
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_B
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_BACKSLASH
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_BACKSPACE
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_C
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_CAPSLOCK
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_COMMA
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_D
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_DELETE
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_DOT
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_DOWN
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_E
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_END
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_ENTER
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_EQUAL
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_ESC
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_F
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_F1
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_F10
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_F11
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_F12
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_F2
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_F3
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_F4
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_F5
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_F6
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_F7
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_F8
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_F9
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_G
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_GRAVE
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_H
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_HOME
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_I
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_INSERT
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_J
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_K
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_KPASTERISK
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_KPDOT
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_KPENTER
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_KPMINUS
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_KPPLUS
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_KPSLASH
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_L
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_LEFT
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_LEFTALT
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_LEFTBRACE
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_LEFTCTRL
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_LEFTMETA
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_LEFTSHIFT
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_M
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_MINUS
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_N
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_NUMLOCK
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_O
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_P
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_PAGEDOWN
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_PAGEUP
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_PAUSE
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_Q
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_R
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_RESERVED
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_RIGHT
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_RIGHTALT
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_RIGHTBRACE
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_RIGHTCTRL
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_RIGHTMETA
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_RIGHTSHIFT
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_S
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_SCROLLLOCK
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_SEMICOLON
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_SLASH
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_SPACE
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_SYSRQ
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_T
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_TAB
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_U
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_UP
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_V
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_W
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_X
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_Y
import com.example.vm.input.KeyboardInputEvent.Companion.KEY_Z

data class ParsedKeyboardReport(
    val events: List<KeyboardInputEvent>,
    val terminalOutputBytes: ByteArray,
    val activePressedUsageIds: Set<Int>,
    val modifierMask: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ParsedKeyboardReport
        return events == other.events &&
                terminalOutputBytes.contentEquals(other.terminalOutputBytes) &&
                activePressedUsageIds == other.activePressedUsageIds &&
                modifierMask == other.modifierMask
    }

    override fun hashCode(): Int {
        var result = events.hashCode()
        result = 31 * result + terminalOutputBytes.contentHashCode()
        result = 31 * result + activePressedUsageIds.hashCode()
        result = 31 * result + modifierMask
        return result
    }
}

/**
 * UsbKeyboardParser decodes raw USB HID Boot Keyboard Reports (8 bytes) into
 * standard Linux evdev key events and terminal escape sequences.
 */
class UsbKeyboardParser {

    private var prevModifierMask = 0
    private val prevPressedUsageIds = mutableSetOf<Int>()

    // Modifier bitmasks
    companion object {
        const val MOD_LCTRL  = 0x01
        const val MOD_LSHIFT = 0x02
        const val MOD_LALT   = 0x04
        const val MOD_LMETA  = 0x08
        const val MOD_RCTRL  = 0x10
        const val MOD_RSHIFT = 0x20
        const val MOD_RALT   = 0x40
        const val MOD_RMETA  = 0x80

        val MODIFIER_SCANCODES = mapOf(
            MOD_LCTRL  to KEY_LEFTCTRL,
            MOD_LSHIFT to KEY_LEFTSHIFT,
            MOD_LALT   to KEY_LEFTALT,
            MOD_LMETA  to KEY_LEFTMETA,
            MOD_RCTRL  to KEY_RIGHTCTRL,
            MOD_RSHIFT to KEY_RIGHTSHIFT,
            MOD_RALT   to KEY_RIGHTALT,
            MOD_RMETA  to KEY_RIGHTMETA
        )

        /**
         * Mapping from USB HID Usage ID (Page 0x07) to Linux evdev scancode.
         */
        val HID_USAGE_TO_EVDEV: Map<Int, Int> = mapOf(
            0x04 to KEY_A,
            0x05 to KEY_B,
            0x06 to KEY_C,
            0x07 to KEY_D,
            0x08 to KEY_E,
            0x09 to KEY_F,
            0x0A to KEY_G,
            0x0B to KEY_H,
            0x0C to KEY_I,
            0x0D to KEY_J,
            0x0E to KEY_K,
            0x0F to KEY_L,
            0x10 to KEY_M,
            0x11 to KEY_N,
            0x12 to KEY_O,
            0x13 to KEY_P,
            0x14 to KEY_Q,
            0x15 to KEY_R,
            0x16 to KEY_S,
            0x17 to KEY_T,
            0x18 to KEY_U,
            0x19 to KEY_V,
            0x1A to KEY_W,
            0x1B to KEY_X,
            0x1C to KEY_Y,
            0x1D to KEY_Z,
            0x1E to KEY_1,
            0x1F to KEY_2,
            0x20 to KEY_3,
            0x21 to KEY_4,
            0x22 to KEY_5,
            0x23 to KEY_6,
            0x24 to KEY_7,
            0x25 to KEY_8,
            0x26 to KEY_9,
            0x27 to KEY_0,
            0x28 to KEY_ENTER,
            0x29 to KEY_ESC,
            0x2A to KEY_BACKSPACE,
            0x2B to KEY_TAB,
            0x2C to KEY_SPACE,
            0x2D to KEY_MINUS,
            0x2E to KEY_EQUAL,
            0x2F to KEY_LEFTBRACE,
            0x30 to KEY_RIGHTBRACE,
            0x31 to KEY_BACKSLASH,
            0x32 to KEY_BACKSLASH,
            0x33 to KEY_SEMICOLON,
            0x34 to KEY_APOSTROPHE,
            0x35 to KEY_GRAVE,
            0x36 to KEY_COMMA,
            0x37 to KEY_DOT,
            0x38 to KEY_SLASH,
            0x39 to KEY_CAPSLOCK,
            0x3A to KEY_F1,
            0x3B to KEY_F2,
            0x3C to KEY_F3,
            0x3D to KEY_F4,
            0x3E to KEY_F5,
            0x3F to KEY_F6,
            0x40 to KEY_F7,
            0x41 to KEY_F8,
            0x42 to KEY_F9,
            0x43 to KEY_F10,
            0x44 to KEY_F11,
            0x45 to KEY_F12,
            0x46 to KEY_SYSRQ,
            0x47 to KEY_SCROLLLOCK,
            0x48 to KEY_PAUSE,
            0x49 to KEY_INSERT,
            0x4A to KEY_HOME,
            0x4B to KEY_PAGEUP,
            0x4C to KEY_DELETE,
            0x4D to KEY_END,
            0x4E to KEY_PAGEDOWN,
            0x4F to KEY_RIGHT,
            0x50 to KEY_LEFT,
            0x51 to KEY_DOWN,
            0x52 to KEY_UP,
            0x53 to KEY_NUMLOCK,
            0x54 to KEY_KPSLASH,
            0x55 to KEY_KPASTERISK,
            0x56 to KEY_KPMINUS,
            0x57 to KEY_KPPLUS,
            0x58 to KEY_KPENTER,
            0x63 to KEY_KPDOT
        )

        /**
         * Resolves printable ASCII character or control sequence for terminal serial line.
         */
        fun usageIdToChar(usageId: Int, isShift: Boolean, isCtrl: Boolean, isAlt: Boolean): Pair<Char, ByteArray?> {
            if (isCtrl) {
                // Control characters
                return when (usageId) {
                    0x04 -> Pair('a', byteArrayOf(0x01)) // Ctrl+A
                    0x05 -> Pair('b', byteArrayOf(0x02)) // Ctrl+B
                    0x06 -> Pair('c', byteArrayOf(0x03)) // Ctrl+C (SIGINT)
                    0x07 -> Pair('d', byteArrayOf(0x04)) // Ctrl+D (EOF)
                    0x08 -> Pair('e', byteArrayOf(0x05)) // Ctrl+E
                    0x09 -> Pair('f', byteArrayOf(0x06)) // Ctrl+F
                    0x0A -> Pair('g', byteArrayOf(0x07)) // Ctrl+G
                    0x0B -> Pair('h', byteArrayOf(0x08)) // Ctrl+H (Backspace)
                    0x0C -> Pair('i', byteArrayOf(0x09)) // Ctrl+I (Tab)
                    0x0D -> Pair('j', byteArrayOf(0x0A)) // Ctrl+J (LF)
                    0x0E -> Pair('k', byteArrayOf(0x0B)) // Ctrl+K
                    0x0F -> Pair('l', byteArrayOf(0x0C)) // Ctrl+L (Clear screen)
                    0x10 -> Pair('m', byteArrayOf(0x0D)) // Ctrl+M (CR)
                    0x11 -> Pair('n', byteArrayOf(0x0E)) // Ctrl+N
                    0x12 -> Pair('o', byteArrayOf(0x0F)) // Ctrl+O
                    0x13 -> Pair('p', byteArrayOf(0x10)) // Ctrl+P
                    0x14 -> Pair('q', byteArrayOf(0x11)) // Ctrl+Q
                    0x15 -> Pair('r', byteArrayOf(0x12)) // Ctrl+R
                    0x16 -> Pair('s', byteArrayOf(0x13)) // Ctrl+S
                    0x17 -> Pair('t', byteArrayOf(0x14)) // Ctrl+T
                    0x18 -> Pair('u', byteArrayOf(0x15)) // Ctrl+U (Erase line)
                    0x19 -> Pair('v', byteArrayOf(0x16)) // Ctrl+V
                    0x1A -> Pair('w', byteArrayOf(0x17)) // Ctrl+W (Erase word)
                    0x1B -> Pair('x', byteArrayOf(0x18)) // Ctrl+X
                    0x1C -> Pair('y', byteArrayOf(0x19)) // Ctrl+Y
                    0x1D -> Pair('z', byteArrayOf(0x1A)) // Ctrl+Z (SIGTSTP)
                    0x2F -> Pair('[', byteArrayOf(0x1B)) // Ctrl+[ (ESC)
                    0x31 -> Pair('\\', byteArrayOf(0x1C)) // Ctrl+\ (SIGQUIT)
                    0x30 -> Pair(']', byteArrayOf(0x1D)) // Ctrl+]
                    else -> Pair('\u0000', null)
                }
            }

            // Normal & Shifted characters
            return when (usageId) {
                0x04 -> Pair(if (isShift) 'A' else 'a', byteArrayOf((if (isShift) 'A' else 'a').code.toByte()))
                0x05 -> Pair(if (isShift) 'B' else 'b', byteArrayOf((if (isShift) 'B' else 'b').code.toByte()))
                0x06 -> Pair(if (isShift) 'C' else 'c', byteArrayOf((if (isShift) 'C' else 'c').code.toByte()))
                0x07 -> Pair(if (isShift) 'D' else 'd', byteArrayOf((if (isShift) 'D' else 'd').code.toByte()))
                0x08 -> Pair(if (isShift) 'E' else 'e', byteArrayOf((if (isShift) 'E' else 'e').code.toByte()))
                0x09 -> Pair(if (isShift) 'F' else 'f', byteArrayOf((if (isShift) 'F' else 'f').code.toByte()))
                0x0A -> Pair(if (isShift) 'G' else 'g', byteArrayOf((if (isShift) 'G' else 'g').code.toByte()))
                0x0B -> Pair(if (isShift) 'H' else 'h', byteArrayOf((if (isShift) 'H' else 'h').code.toByte()))
                0x0C -> Pair(if (isShift) 'I' else 'i', byteArrayOf((if (isShift) 'I' else 'i').code.toByte()))
                0x0D -> Pair(if (isShift) 'J' else 'j', byteArrayOf((if (isShift) 'J' else 'j').code.toByte()))
                0x0E -> Pair(if (isShift) 'K' else 'k', byteArrayOf((if (isShift) 'K' else 'k').code.toByte()))
                0x0F -> Pair(if (isShift) 'L' else 'l', byteArrayOf((if (isShift) 'L' else 'l').code.toByte()))
                0x10 -> Pair(if (isShift) 'M' else 'm', byteArrayOf((if (isShift) 'M' else 'm').code.toByte()))
                0x11 -> Pair(if (isShift) 'N' else 'n', byteArrayOf((if (isShift) 'N' else 'n').code.toByte()))
                0x12 -> Pair(if (isShift) 'O' else 'o', byteArrayOf((if (isShift) 'O' else 'o').code.toByte()))
                0x13 -> Pair(if (isShift) 'P' else 'p', byteArrayOf((if (isShift) 'P' else 'p').code.toByte()))
                0x14 -> Pair(if (isShift) 'Q' else 'q', byteArrayOf((if (isShift) 'Q' else 'q').code.toByte()))
                0x15 -> Pair(if (isShift) 'R' else 'r', byteArrayOf((if (isShift) 'R' else 'r').code.toByte()))
                0x16 -> Pair(if (isShift) 'S' else 's', byteArrayOf((if (isShift) 'S' else 's').code.toByte()))
                0x17 -> Pair(if (isShift) 'T' else 't', byteArrayOf((if (isShift) 'T' else 't').code.toByte()))
                0x18 -> Pair(if (isShift) 'U' else 'u', byteArrayOf((if (isShift) 'U' else 'u').code.toByte()))
                0x19 -> Pair(if (isShift) 'V' else 'v', byteArrayOf((if (isShift) 'V' else 'v').code.toByte()))
                0x1A -> Pair(if (isShift) 'W' else 'w', byteArrayOf((if (isShift) 'W' else 'w').code.toByte()))
                0x1B -> Pair(if (isShift) 'X' else 'x', byteArrayOf((if (isShift) 'X' else 'x').code.toByte()))
                0x1C -> Pair(if (isShift) 'Y' else 'y', byteArrayOf((if (isShift) 'Y' else 'y').code.toByte()))
                0x1D -> Pair(if (isShift) 'Z' else 'z', byteArrayOf((if (isShift) 'Z' else 'z').code.toByte()))
                0x1E -> Pair(if (isShift) '!' else '1', byteArrayOf((if (isShift) '!' else '1').code.toByte()))
                0x1F -> Pair(if (isShift) '@' else '2', byteArrayOf((if (isShift) '@' else '2').code.toByte()))
                0x20 -> Pair(if (isShift) '#' else '3', byteArrayOf((if (isShift) '#' else '3').code.toByte()))
                0x21 -> Pair(if (isShift) '$' else '4', byteArrayOf((if (isShift) '$' else '4').code.toByte()))
                0x22 -> Pair(if (isShift) '%' else '5', byteArrayOf((if (isShift) '%' else '5').code.toByte()))
                0x23 -> Pair(if (isShift) '^' else '6', byteArrayOf((if (isShift) '^' else '6').code.toByte()))
                0x24 -> Pair(if (isShift) '&' else '7', byteArrayOf((if (isShift) '&' else '7').code.toByte()))
                0x25 -> Pair(if (isShift) '*' else '8', byteArrayOf((if (isShift) '*' else '8').code.toByte()))
                0x26 -> Pair(if (isShift) '(' else '9', byteArrayOf((if (isShift) '(' else '9').code.toByte()))
                0x27 -> Pair(if (isShift) ')' else '0', byteArrayOf((if (isShift) ')' else '0').code.toByte()))
                0x28 -> Pair('\n', byteArrayOf(0x0D)) // Enter -> CR
                0x29 -> Pair('\u001b', byteArrayOf(0x1B)) // Escape
                0x2A -> Pair('\b', byteArrayOf(0x08)) // Backspace -> 0x08 or 0x7F
                0x2B -> Pair('\t', byteArrayOf(0x09)) // Tab
                0x2C -> Pair(' ', byteArrayOf(0x20)) // Space
                0x2D -> Pair(if (isShift) '_' else '-', byteArrayOf((if (isShift) '_' else '-').code.toByte()))
                0x2E -> Pair(if (isShift) '+' else '=', byteArrayOf((if (isShift) '+' else '=').code.toByte()))
                0x2F -> Pair(if (isShift) '{' else '[', byteArrayOf((if (isShift) '{' else '[').code.toByte()))
                0x30 -> Pair(if (isShift) '}' else ']', byteArrayOf((if (isShift) '}' else ']').code.toByte()))
                0x31 -> Pair(if (isShift) '|' else '\\', byteArrayOf((if (isShift) '|' else '\\').code.toByte()))
                0x33 -> Pair(if (isShift) ':' else ';', byteArrayOf((if (isShift) ':' else ';').code.toByte()))
                0x34 -> Pair(if (isShift) '"' else '\'', byteArrayOf((if (isShift) '"' else '\'').code.toByte()))
                0x35 -> Pair(if (isShift) '~' else '`', byteArrayOf((if (isShift) '~' else '`').code.toByte()))
                0x36 -> Pair(if (isShift) '<' else ',', byteArrayOf((if (isShift) '<' else ',').code.toByte()))
                0x37 -> Pair(if (isShift) '>' else '.', byteArrayOf((if (isShift) '>' else '.').code.toByte()))
                0x38 -> Pair(if (isShift) '?' else '/', byteArrayOf((if (isShift) '?' else '/').code.toByte()))
                0x49 -> Pair('\u0000', byteArrayOf(0x1B, '['.code.toByte(), '2'.code.toByte(), '~'.code.toByte())) // Insert: \e[2~
                0x4A -> Pair('\u0000', byteArrayOf(0x1B, '['.code.toByte(), 'H'.code.toByte())) // Home: \e[H
                0x4B -> Pair('\u0000', byteArrayOf(0x1B, '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte())) // PageUp: \e[5~
                0x4C -> Pair('\u0000', byteArrayOf(0x1B, '['.code.toByte(), '3'.code.toByte(), '~'.code.toByte())) // Delete: \e[3~
                0x4D -> Pair('\u0000', byteArrayOf(0x1B, '['.code.toByte(), 'F'.code.toByte())) // End: \e[F
                0x4E -> Pair('\u0000', byteArrayOf(0x1B, '['.code.toByte(), '6'.code.toByte(), '~'.code.toByte())) // PageDown: \e[6~
                0x4F -> Pair('\u0000', byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte())) // Right Arrow: \e[C
                0x50 -> Pair('\u0000', byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte())) // Left Arrow: \e[D
                0x51 -> Pair('\u0000', byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())) // Down Arrow: \e[B
                0x52 -> Pair('\u0000', byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())) // Up Arrow: \e[A
                0x58 -> Pair('\n', byteArrayOf(0x0D)) // KP Enter
                0x63 -> Pair('.', byteArrayOf('.'.code.toByte())) // KP Dot
                else -> Pair('\u0000', null)
            }
        }
    }

    /**
     * Parses a raw 8-byte USB HID report into discrete key transition events
     * and console terminal bytes.
     */
    fun parseReport(buffer: ByteArray, length: Int): ParsedKeyboardReport {
        if (length < 8) {
            return ParsedKeyboardReport(emptyList(), byteArrayOf(), prevPressedUsageIds.toSet(), prevModifierMask)
        }

        val modifierMask = buffer[0].toInt() and 0xFF
        val currentPressed = mutableSetOf<Int>()

        for (i in 2 until minOf(8, length)) {
            val usageId = buffer[i].toInt() and 0xFF
            // 0x00 is empty, 0x01 is ErrorRollOver
            if (usageId > 0x03) {
                currentPressed.add(usageId)
            }
        }

        val events = mutableListOf<KeyboardInputEvent>()
        val terminalBytes = mutableListOf<Byte>()

        val isCtrl = (modifierMask and (MOD_LCTRL or MOD_RCTRL)) != 0
        val isShift = (modifierMask and (MOD_LSHIFT or MOD_RSHIFT)) != 0
        val isAlt = (modifierMask and (MOD_LALT or MOD_RALT)) != 0
        val isMeta = (modifierMask and (MOD_LMETA or MOD_RMETA)) != 0

        // 1. Check modifier transitions
        for ((mask, scanCode) in MODIFIER_SCANCODES) {
            val wasPressed = (prevModifierMask and mask) != 0
            val isNowPressed = (modifierMask and mask) != 0

            if (!wasPressed && isNowPressed) {
                events.add(
                    KeyboardInputEvent(
                        scanCode = scanCode,
                        keyCode = scanCode,
                        type = KeyEventType.DOWN,
                        ctrl = isCtrl,
                        alt = isAlt,
                        shift = isShift,
                        meta = isMeta
                    )
                )
            } else if (wasPressed && !isNowPressed) {
                events.add(
                    KeyboardInputEvent(
                        scanCode = scanCode,
                        keyCode = scanCode,
                        type = KeyEventType.UP,
                        ctrl = isCtrl,
                        alt = isAlt,
                        shift = isShift,
                        meta = isMeta
                    )
                )
            }
        }

        // 2. Check released keys (keys in prev but not in current)
        for (usageId in prevPressedUsageIds) {
            if (!currentPressed.contains(usageId)) {
                val scanCode = HID_USAGE_TO_EVDEV[usageId] ?: KEY_RESERVED
                val (charVal, _) = usageIdToChar(usageId, isShift, isCtrl, isAlt)
                events.add(
                    KeyboardInputEvent(
                        scanCode = scanCode,
                        keyCode = scanCode,
                        unicodeChar = charVal,
                        type = KeyEventType.UP,
                        ctrl = isCtrl,
                        alt = isAlt,
                        shift = isShift,
                        meta = isMeta
                    )
                )
            }
        }

        // 3. Check newly pressed keys (keys in current but not in prev)
        for (usageId in currentPressed) {
            if (!prevPressedUsageIds.contains(usageId)) {
                val scanCode = HID_USAGE_TO_EVDEV[usageId] ?: KEY_RESERVED
                val (charVal, bytes) = usageIdToChar(usageId, isShift, isCtrl, isAlt)
                events.add(
                    KeyboardInputEvent(
                        scanCode = scanCode,
                        keyCode = scanCode,
                        unicodeChar = charVal,
                        type = KeyEventType.DOWN,
                        ctrl = isCtrl,
                        alt = isAlt,
                        shift = isShift,
                        meta = isMeta
                    )
                )
                if (bytes != null) {
                    bytes.forEach { terminalBytes.add(it) }
                }
            }
        }

        prevModifierMask = modifierMask
        prevPressedUsageIds.clear()
        prevPressedUsageIds.addAll(currentPressed)

        return ParsedKeyboardReport(
            events = events,
            terminalOutputBytes = terminalBytes.toByteArray(),
            activePressedUsageIds = currentPressed,
            modifierMask = modifierMask
        )
    }

    fun reset() {
        prevModifierMask = 0
        prevPressedUsageIds.clear()
    }
}
