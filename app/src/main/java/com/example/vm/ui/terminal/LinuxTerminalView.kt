package com.example.vm.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vm.core.VMEngine
import com.example.vm.core.VMState

/**
 * Authentic Linux Terminal connected directly to the guest's PL011 UART (ttyAMA0).
 * Never fakes shell output or creates a synthetic command interpreter in Java/Kotlin.
 * All keystrokes are forwarded directly to the guest serial console RX register.
 */
@Composable
fun LinuxTerminalView(
    engine: VMEngine?,
    modifier: Modifier = Modifier
) {
    val state = engine?.state?.collectAsStateWithLifecycle()?.value ?: VMState.STOPPED
    val consoleHistory by engine?.serialConsole?.history?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyList()) }
    val isConnected by engine?.serialConsole?.isConnected?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(true) }
    val clipboardManager = LocalClipboardManager.current

    val terminalOutput = remember(consoleHistory) { consoleHistory.joinToString("\n") }
    val isRunning = state == VMState.RUNNING

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var commandInput by remember { mutableStateOf("") }
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }

    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    LaunchedEffect(terminalOutput) {
        verticalScroll.animateScrollTo(verticalScroll.maxValue)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF080B0F))
            .border(1.dp, Color(0xFF1E2833), RoundedCornerShape(12.dp))
            .padding(12.dp)
            .testTag("linux_terminal_view")
    ) {
        // Terminal Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isRunning && isConnected) Color(0xFF00E676) else Color(0xFFFF5252))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "GUEST CONSOLE • PL011 UART (ttyAMA0)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.LightGray
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Copy terminal output
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(terminalOutput))
                    },
                    modifier = Modifier.size(28.dp).testTag("btn_terminal_copy")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Output", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                }

                // Paste clipboard text
                IconButton(
                    onClick = {
                        val clipText = clipboardManager.getText()?.text
                        if (!clipText.isNullOrEmpty() && isRunning) {
                            engine?.serialConsole?.sendRawBytes(clipText.toByteArray(Charsets.UTF_8))
                        }
                    },
                    enabled = isRunning,
                    modifier = Modifier.size(28.dp).testTag("btn_terminal_paste")
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste Input", tint = if (isRunning) Color.LightGray else Color.DarkGray, modifier = Modifier.size(16.dp))
                }

                // Reconnect Console
                IconButton(
                    onClick = {
                        engine?.serialConsole?.reconnect()
                    },
                    modifier = Modifier.size(28.dp).testTag("btn_terminal_reconnect")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reconnect Console", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Terminal Screen (Guest Output Display - Tap to open normal Android keyboard)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 280.dp)
                .background(Color(0xFF040608), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF141A23), RoundedCornerShape(8.dp))
                .clickable {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
                .padding(8.dp)
        ) {
            Text(
                text = if (terminalOutput.isEmpty()) {
                    if (isRunning) "Waiting for guest UART writes (PL011 115200 8N1)..." else "VM stopped. Start VM to receive guest console output."
                } else {
                    terminalOutput
                },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (isRunning) Color(0xFF69F0AE) else Color.Gray,
                modifier = Modifier
                    .verticalScroll(verticalScroll)
                    .horizontalScroll(horizontalScroll)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Remote-Control Keystroke Toolbar (Ctrl, Alt, Esc, Tab, Backspace, Ctrl+C, Ctrl+D, Arrow Keys)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = isCtrlActive,
                onClick = { isCtrlActive = !isCtrlActive },
                label = { Text("Ctrl", fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.Black
                ),
                modifier = Modifier.testTag("key_ctrl")
            )

            FilterChip(
                selected = isAltActive,
                onClick = { isAltActive = !isAltActive },
                label = { Text("Alt", fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.Black
                ),
                modifier = Modifier.testTag("key_alt")
            )

            SpecialKeyButton("Esc") {
                engine?.serialConsole?.sendRawByte(0x1B.toByte())
            }

            SpecialKeyButton("Tab") {
                engine?.serialConsole?.sendTab()
            }

            SpecialKeyButton("Backspace") {
                engine?.serialConsole?.sendRawByte(0x7F.toByte())
            }

            SpecialKeyButton("Ctrl+C") {
                engine?.serialConsole?.sendCtrlC()
            }

            SpecialKeyButton("Ctrl+D") {
                engine?.serialConsole?.sendRawByte(0x04.toByte())
            }

            SpecialKeyButton("Enter") {
                engine?.serialConsole?.sendRawBytes(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte()))
            }

            SpecialKeyButton("↑") {
                engine?.serialConsole?.sendRawBytes("\u001B[A".toByteArray())
            }

            SpecialKeyButton("↓") {
                engine?.serialConsole?.sendRawBytes("\u001B[B".toByteArray())
            }

            SpecialKeyButton("←") {
                engine?.serialConsole?.sendRawBytes("\u001B[D".toByteArray())
            }

            SpecialKeyButton("→") {
                engine?.serialConsole?.sendRawBytes("\u001B[C".toByteArray())
            }

            SpecialKeyButton("Clear") {
                engine?.serialConsole?.clear()
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Direct Console Keystroke Input Field
        val sendCurrentCommand = {
            if (isRunning) {
                if (commandInput.isNotEmpty()) {
                    if (isCtrlActive) {
                        for (c in commandInput) {
                            val charToSend = if (c in 'a'..'z') (c.code - 'a'.code + 1).toByte()
                            else if (c in 'A'..'Z') (c.code - 'A'.code + 1).toByte()
                            else c.code.toByte()
                            engine?.serialConsole?.sendRawByte(charToSend)
                        }
                    } else {
                        engine?.serialConsole?.sendRawBytes(commandInput.toByteArray(Charsets.UTF_8))
                    }
                    engine?.serialConsole?.sendRawBytes(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte()))
                    commandInput = ""
                    isCtrlActive = false
                    isAltActive = false
                } else {
                    engine?.serialConsole?.sendRawBytes(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte()))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = commandInput,
                onValueChange = { newValue ->
                    commandInput = newValue
                },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                placeholder = {
                    Text(
                        if (isRunning) "Type command here (Android Keyboard)..." else "VM must be RUNNING to send input",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color.DarkGray
                    )
                },
                singleLine = true,
                enabled = isRunning,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .testTag("terminal_stdin_input"),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii
                ),
                keyboardActions = KeyboardActions(
                    onSend = { sendCurrentCommand() },
                    onDone = { sendCurrentCommand() },
                    onGo = { sendCurrentCommand() },
                    onNext = { sendCurrentCommand() }
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF141A23),
                    unfocusedContainerColor = Color(0xFF0F141C),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = Color(0xFF232D38)
                )
            )

            Button(
                onClick = { sendCurrentCommand() },
                enabled = isRunning,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("btn_send_terminal")
            ) {
                Text("Enter", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun SpecialKeyButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier.height(32.dp).testTag("special_key_$label")
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}
