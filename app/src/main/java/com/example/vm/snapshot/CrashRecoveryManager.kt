package com.example.vm.snapshot

import android.content.Context
import com.example.vm.core.VMConfig
import com.example.vm.core.VMState
import org.json.JSONObject
import java.io.File

data class CrashRecoveryReport(
    val hasUncleanShutdown: Boolean = false,
    val vmId: Long = 0L,
    val vmName: String = "",
    val lastKnownState: VMState = VMState.STOPPED,
    val crashTimestamp: Long = 0L
)

/**
 * CrashRecoveryManager monitors active VM execution lifecycles, detects unclean
 * process terminations or system crashes, and provides recovery checkpoints.
 */
class CrashRecoveryManager(private val context: Context) {

    private val heartbeatFile: File
        get() = File(context.filesDir, "active_vm_heartbeat.json")

    fun recordVmStateChange(config: VMConfig, state: VMState) {
        try {
            if (state == VMState.RUNNING || state == VMState.STARTING || state == VMState.PAUSED) {
                val json = JSONObject().apply {
                    put("vmId", config.id)
                    put("vmName", config.name)
                    put("state", state.name)
                    put("timestamp", System.currentTimeMillis())
                }
                heartbeatFile.writeText(json.toString())
            } else if (state.isTerminal() || state == VMState.STOPPED) {
                clearHeartbeat()
            }
        } catch (e: Exception) {
            // Heartbeat failure handled safely without interrupting VM
        }
    }

    fun checkCrashRecovery(): CrashRecoveryReport {
        if (!heartbeatFile.exists() || heartbeatFile.length() == 0L) {
            return CrashRecoveryReport(hasUncleanShutdown = false)
        }

        return try {
            val content = heartbeatFile.readText()
            val json = JSONObject(content)
            val vmId = json.optLong("vmId", 0L)
            val vmName = json.optString("vmName", "Unknown VM")
            val stateStr = json.optString("state", VMState.RUNNING.name)
            val timestamp = json.optLong("timestamp", 0L)

            val state = try {
                VMState.valueOf(stateStr)
            } catch (e: Exception) {
                VMState.RUNNING
            }

            CrashRecoveryReport(
                hasUncleanShutdown = true,
                vmId = vmId,
                vmName = vmName,
                lastKnownState = state,
                crashTimestamp = timestamp
            )
        } catch (e: Exception) {
            CrashRecoveryReport(hasUncleanShutdown = false)
        }
    }

    fun clearHeartbeat() {
        if (heartbeatFile.exists()) {
            heartbeatFile.delete()
        }
    }
}
