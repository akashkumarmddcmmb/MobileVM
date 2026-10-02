package com.example.vm.ui.terminal

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.vm.core.VMEngine
import com.example.vm.core.VMState

/**
 * Real PTY / Termux-style Terminal Input View Surface.
 * Extends View and overrides onCreateInputConnection to establish a direct, real-time IME input bridge
 * between Android soft keyboards / hardware keyboards and the guest serial console (ttyAMA0).
 * Immediately streams committed UTF-8 text, DEL/BS bytes, and ANSI escape sequences.
 */
class TerminalInputBridgeView(
    context: Context,
    private var engine: VMEngine? = null,
    var isCtrlActive: Boolean = false,
    var isAltActive: Boolean = false
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        requestFocus()
    }

    fun updateEngine(newEngine: VMEngine?) {
        this.engine = newEngine
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    sendTerminalText(text.toString())
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    repeat(beforeLength) {
                        sendTerminalBytes(byteArrayOf(0x7F.toByte())) // DEL / BS
                    }
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                    return handleTerminalKeyEvent(event.keyCode, event)
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && handleTerminalKeyEvent(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun handleTerminalKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        val currentEngine = engine ?: return false
        if (currentEngine.state.value != VMState.RUNNING) return false

        val ctrl = isCtrlActive || event.isCtrlPressed
        val alt = isAltActive || event.isAltPressed

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                sendTerminalBytes(byteArrayOf('\r'.code.toByte()))
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                sendTerminalBytes(byteArrayOf(0x7F.toByte()))
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                sendTerminalBytes(byteArrayOf('\t'.code.toByte()))
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                sendTerminalBytes(byteArrayOf(0x1B.toByte()))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                sendTerminalBytes("\u001B[A".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                sendTerminalBytes("\u001B[B".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                sendTerminalBytes("\u001B[D".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                sendTerminalBytes("\u001B[C".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                sendTerminalBytes("\u001B[H".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                sendTerminalBytes("\u001B[F".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                sendTerminalBytes("\u001B[5~".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                sendTerminalBytes("\u001B[6~".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                sendTerminalBytes("\u001B[3~".toByteArray())
                return true
            }
            else -> {
                val unicodeChar = event.keyCharacterMap.get(keyCode, event.metaState)
                if (unicodeChar > 0) {
                    val char = unicodeChar.toChar()
                    if (ctrl) {
                        val ctrlByte = when (char) {
                            in 'a'..'z' -> (char.code - 'a'.code + 1).toByte()
                            in 'A'..'Z' -> (char.code - 'A'.code + 1).toByte()
                            '[' -> 0x1B.toByte()
                            '\\' -> 0x1C.toByte()
                            ']' -> 0x1D.toByte()
                            '^' -> 0x1E.toByte()
                            '_' -> 0x1F.toByte()
                            else -> char.code.toByte()
                        }
                        sendTerminalBytes(byteArrayOf(ctrlByte))
                        return true
                    } else if (alt) {
                        sendTerminalBytes(byteArrayOf(0x1B.toByte(), char.code.toByte()))
                        return true
                    } else {
                        sendTerminalText(char.toString())
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun sendTerminalText(text: String) {
        val currentEngine = engine ?: return
        if (currentEngine.state.value != VMState.RUNNING) return

        if (isCtrlActive) {
            for (c in text) {
                val ctrlByte = when (c) {
                    in 'a'..'z' -> (c.code - 'a'.code + 1).toByte()
                    in 'A'..'Z' -> (c.code - 'A'.code + 1).toByte()
                    else -> c.code.toByte()
                }
                currentEngine.serialConsole.sendRawByte(ctrlByte)
            }
        } else {
            currentEngine.serialConsole.sendRawBytes(text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun sendTerminalBytes(bytes: ByteArray) {
        val currentEngine = engine ?: return
        if (currentEngine.state.value == VMState.RUNNING) {
            currentEngine.serialConsole.sendRawBytes(bytes)
        }
    }

    fun openKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
}

/**
 * Jetpack Compose wrapper component for TerminalInputBridgeView.
 */
@Composable
fun RealtimeTerminalInputSurface(
    engine: VMEngine?,
    isCtrlActive: Boolean,
    isAltActive: Boolean,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            TerminalInputBridgeView(ctx, engine, isCtrlActive, isAltActive).apply {
                openKeyboard()
            }
        },
        update = { view ->
            view.updateEngine(engine)
            view.isCtrlActive = isCtrlActive
            view.isAltActive = isAltActive
        },
        modifier = modifier.fillMaxWidth()
    )
}
