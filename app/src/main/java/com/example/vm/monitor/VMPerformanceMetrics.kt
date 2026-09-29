package com.example.vm.monitor

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThermalState(val displayName: String, val isWarning: Boolean) {
    NOMINAL("Nominal (Cool)", false),
    FAIR("Fair (Normal)", false),
    MODERATE("Moderate (Warm)", false),
    SEVERE("Severe (Throttling)", true),
    CRITICAL("Critical (Overheating)", true),
    EMERGENCY("Emergency (Shutdown Risk)", true),
    SHUTDOWN("Shutdown (Hardware Protection)", true)
}

data class VMPerformanceMetrics(
    val cpuUsagePercent: Double = 0.0,
    val guestRamUsedMb: Long = 0,
    val guestRamTotalMb: Long = 0,
    val hostFreeRamMb: Long = 0,
    val fps: Double = 60.0,
    val frameTimeMs: Double = 16.6,
    val diskReadBytesPerSec: Long = 0,
    val diskWriteBytesPerSec: Long = 0,
    val netRxBytesPerSec: Long = 0,
    val netTxBytesPerSec: Long = 0,
    val activeThreadsCount: Int = 0,
    val thermalState: ThermalState = ThermalState.NOMINAL,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * VMThermalMonitor: Tracks Android device thermal state to protect hardware and guest stability.
 */
class VMThermalMonitor(private val context: Context) {
    companion object {
        private const val TAG = "VMThermalMonitor"
    }

    private val _thermalState = MutableStateFlow(ThermalState.NOMINAL)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    fun updateThermalState(state: ThermalState) {
        _thermalState.value = state
        if (state.isWarning) {
            Log.w(TAG, "Device thermal warning: ${state.displayName}")
        }
    }

    fun getDeviceThermalStatus(): ThermalState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val status = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                val mapped = when (status) {
                    PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
                    PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.FAIR
                    PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
                    PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
                    PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL
                    PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.EMERGENCY
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.SHUTDOWN
                    else -> ThermalState.NOMINAL
                }
                _thermalState.value = mapped
                return mapped
            } catch (e: Exception) {
                Log.e(TAG, "Error checking thermal status: ${e.message}")
            }
        }
        return _thermalState.value
    }
}
