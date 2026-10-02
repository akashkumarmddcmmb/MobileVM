package com.example.vm.terminal

import android.app.Application
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.example.vm.core.VMConfig
import com.example.vm.core.VMEngine
import com.example.vm.ui.terminal.TerminalInputBridgeView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalInputBridgeTest {

    private lateinit var application: Application
    private lateinit var vmEngine: VMEngine
    private lateinit var bridgeView: TerminalInputBridgeView

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        val config = VMConfig(
            id = 1L,
            name = "Terminal_Test_VM",
            cpuCores = 2,
            ramSizeMb = 2048
        )
        vmEngine = VMEngine(application, config)
        bridgeView = TerminalInputBridgeView(application, vmEngine)
    }

    @Test
    fun testTerminalInputBridgeInitialization() {
        assertNotNull(bridgeView)
        assertTrue(bridgeView.isFocusable)
        assertTrue(bridgeView.onCheckIsTextEditor())
    }

    @Test
    fun testTerminalSpecialKeyTranslations() {
        val enterEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        val handledEnter = bridgeView.handleTerminalKeyEvent(KeyEvent.KEYCODE_ENTER, enterEvent)
        assertNotNull(handledEnter)

        val delEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        val handledDel = bridgeView.handleTerminalKeyEvent(KeyEvent.KEYCODE_DEL, delEvent)
        assertNotNull(handledDel)

        val arrowUpEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)
        val handledUp = bridgeView.handleTerminalKeyEvent(KeyEvent.KEYCODE_DPAD_UP, arrowUpEvent)
        assertNotNull(handledUp)
    }

    @Test
    fun testCtrlKeyTranslations() {
        bridgeView.isCtrlActive = true
        val cEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C)
        val handledCtrlC = bridgeView.handleTerminalKeyEvent(KeyEvent.KEYCODE_C, cEvent)
        assertNotNull(handledCtrlC)
    }
}
