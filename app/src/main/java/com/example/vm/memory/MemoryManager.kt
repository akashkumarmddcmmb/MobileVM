package com.example.vm.memory

import android.app.ActivityManager
import android.content.Context

class MemoryManager(private val context: Context) {

    data class MemoryStats(
        val availableBytes: Long,
        val totalBytes: Long,
        val thresholdBytes: Long,
        val isLowMemoryState: Boolean
    ) {
        val availableMb: Long get() = availableBytes / (1024 * 1024)
        val totalMb: Long get() = totalBytes / (1024 * 1024)
        val thresholdMb: Long get() = thresholdBytes / (1024 * 1024)
    }

    data class RAMPreset(
        val sizeMb: Int,
        val displayName: String,
        val description: String
    )

    companion object {
        const val MIN_HOST_HEADROOM_MB = 768L

        val STANDARD_PRESETS = listOf(
            RAMPreset(512, "512 MB", "Lightweight / Embedded Linux (Minimal overhead)"),
            RAMPreset(1024, "1 GB", "Standard Linux Shell / Minimal GUI"),
            RAMPreset(1536, "1.5 GB", "Intermediate Linux Workloads"),
            RAMPreset(2048, "2 GB", "Ubuntu / Debian / Windows ARM64 (Recommended)"),
            RAMPreset(3072, "3 GB", "Performance Linux / Windows Workloads"),
            RAMPreset(4096, "4 GB", "Heavy Workloads (Requires >= 6GB free RAM)")
        )
    }

    fun getHostMemoryStats(): MemoryStats {
        val info = HostMemoryPlanner.getHostMemoryInfo(context)
        return MemoryStats(
            availableBytes = info.availableMemMb * 1024 * 1024,
            totalBytes = info.totalMemMb * 1024 * 1024,
            thresholdBytes = info.thresholdMb * 1024 * 1024,
            isLowMemoryState = info.lowMemory
        )
    }

    fun isConfigSafe(ramSizeMb: Int, isKvmMode: Boolean = true, isWindowsGuest: Boolean = false): Boolean {
        val plan = HostMemoryPlanner.calculateMemoryPlan(context, ramSizeMb, isKvmMode, isWindowsGuest)
        return plan.isSafe
    }

    fun getMemorySafetyRecommendation(
        ramSizeMb: Int,
        isKvmMode: Boolean = true,
        isWindowsGuest: Boolean = false
    ): SafetyResult {
        val plan = HostMemoryPlanner.calculateMemoryPlan(context, ramSizeMb, isKvmMode, isWindowsGuest)

        if (plan.isLowMemoryPressure) {
            return SafetyResult.Danger(plan.reason)
        }

        if (!plan.isSafe) {
            return SafetyResult.Danger(plan.reason)
        }

        val percentAvailable = (ramSizeMb.toDouble() / plan.availableHostMemMb.toDouble()) * 100.0
        if (percentAvailable > 70.0) {
            return SafetyResult.Warning(
                "High RAM usage: $ramSizeMb MB requested out of ${plan.availableHostMemMb} MB free host RAM."
            )
        }

        return SafetyResult.Safe(
            "Safe allocation: $ramSizeMb MB guest RAM with ${plan.availableHostMemMb - ramSizeMb} MB host headroom."
        )
    }

    sealed interface SafetyResult {
        data class Safe(val details: String) : SafetyResult
        data class Warning(val message: String) : SafetyResult
        data class Danger(val message: String) : SafetyResult

        val isSafe: Boolean get() = this !is Danger
    }
}
