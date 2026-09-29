package com.example.vm.monitor

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VMWatchdog: Monitors VM CPU execution and I/O progress to detect deadlocks,
 * thread freezes, and infinite loops without interfering with legitimate long operations.
 */
class VMWatchdog(
    private val timeoutMs: Long = 30000L, // 30 seconds watchdog threshold
    private val checkIntervalMs: Long = 5000L,
    private val onStallDetected: (stallDurationMs: Long) -> Unit
) {
    companion object {
        private const val TAG = "VMWatchdog"
    }

    private var watchdogJob: Job? = null
    private var lastProgressTimestamp: Long = System.currentTimeMillis()
    private var isEnabled = false

    private val _isStalled = MutableStateFlow(false)
    val isStalled: StateFlow<Boolean> = _isStalled.asStateFlow()

    /**
     * Kicks the watchdog timer when CPU advances instructions or I/O completes.
     */
    fun kick() {
        lastProgressTimestamp = System.currentTimeMillis()
        if (_isStalled.value) {
            _isStalled.value = false
            Log.i(TAG, "VM Watchdog recovered: progress resumed.")
        }
    }

    /**
     * Starts monitoring VM health in a background coroutine.
     */
    fun start(scope: CoroutineScope) {
        if (isEnabled) return
        isEnabled = true
        lastProgressTimestamp = System.currentTimeMillis()
        _isStalled.value = false

        watchdogJob = scope.launch(Dispatchers.Default) {
            while (isActive && isEnabled) {
                delay(checkIntervalMs)
                val elapsed = System.currentTimeMillis() - lastProgressTimestamp
                if (elapsed > timeoutMs) {
                    _isStalled.value = true
                    Log.w(TAG, "VM Watchdog alert: No progress for ${elapsed}ms (timeout: ${timeoutMs}ms)")
                    onStallDetected(elapsed)
                }
            }
        }
    }

    /**
     * Stops the watchdog when VM is paused or stopped.
     */
    fun stop() {
        isEnabled = false
        watchdogJob?.cancel()
        watchdogJob = null
        _isStalled.value = false
    }

    fun isRunning(): Boolean = isEnabled
}
