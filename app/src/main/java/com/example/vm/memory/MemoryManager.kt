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
        const val MIN_HOST_HEADROOM_MB = 1024L // Keep at least 1 GB for Android OS

        val STANDARD_PRESETS = listOf(
            RAMPreset(512, "512 MB", "Lightweight / Embedded Linux (Minimal overhead)"),
            RAMPreset(1024, "1 GB", "Standard Linux Shell / Minimal GUI"),
            RAMPreset(2048, "2 GB", "Ubuntu / Debian Desktop GUI (Recommended)"),
            RAMPreset(4096, "4 GB", "Heavy Workloads / Multi-tasking (High RAM devices)"),
            RAMPreset(8192, "8 GB", "Maximum Allocation (Where device has >= 12GB RAM)")
        )
    }

    fun getHostMemoryStats(): MemoryStats {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        var total = memoryInfo.totalMem
        var avail = memoryInfo.availMem

        if (total <= 0L || avail <= 0L) {
            val procStats = readProcMemInfo()
            if (total <= 0L) total = procStats.first
            if (avail <= 0L) avail = procStats.second
        }

        // Safe fallback if /proc/meminfo is also inaccessible
        if (total <= 0L) total = Runtime.getRuntime().maxMemory().coerceAtLeast(2048L * 1024L * 1024L)
        if (avail <= 0L) avail = (total / 2).coerceAtLeast(1024L * 1024L * 1024L)

        return MemoryStats(
            availableBytes = avail,
            totalBytes = total,
            thresholdBytes = memoryInfo.threshold,
            isLowMemoryState = memoryInfo.lowMemory
        )
    }

    private fun readProcMemInfo(): Pair<Long, Long> {
        var memTotal = 0L
        var memAvailable = 0L
        try {
            val file = java.io.File("/proc/meminfo")
            if (file.exists() && file.canRead()) {
                file.forEachLine { line ->
                    if (line.startsWith("MemTotal:")) {
                        val kb = line.substringAfter(":").trim().substringBefore(" ").toLongOrNull() ?: 0L
                        memTotal = kb * 1024L
                    } else if (line.startsWith("MemAvailable:")) {
                        val kb = line.substringAfter(":").trim().substringBefore(" ").toLongOrNull() ?: 0L
                        memAvailable = kb * 1024L
                    } else if (line.startsWith("MemFree:") && memAvailable == 0L) {
                        val kb = line.substringAfter(":").trim().substringBefore(" ").toLongOrNull() ?: 0L
                        memAvailable = kb * 1024L
                    }
                }
            }
        } catch (_: Exception) {}
        return Pair(memTotal, memAvailable)
    }

    /**
     * Checks whether allocating [ramSizeMb] is safe on the current Android device.
     * Prevents starting a VM if memory would breach system headroom.
     */
    fun isConfigSafe(ramSizeMb: Int): Boolean {
        val safety = getMemorySafetyRecommendation(ramSizeMb)
        return safety !is SafetyResult.Danger
    }

    fun getMemorySafetyRecommendation(ramSizeMb: Int): SafetyResult {
        val stats = getHostMemoryStats()
        val requestedMb = ramSizeMb.toLong()

        if (stats.isLowMemoryState) {
            return SafetyResult.Danger(
                "Android host is in a critical low-memory state (${stats.availableMb} MB free). VM startup is blocked to protect device stability."
            )
        }

        // Rule 1: Must not exceed total physical RAM
        if (requestedMb >= stats.totalMb) {
            return SafetyResult.Danger(
                "Requested ${ramSizeMb} MB exceeds total host RAM (${stats.totalMb} MB). Allocation is impossible."
            )
        }

        // Rule 2: Must leave minimum Android OS headroom
        val remainingAfterAlloc = stats.availableMb - requestedMb
        if (remainingAfterAlloc < MIN_HOST_HEADROOM_MB) {
            return SafetyResult.Danger(
                "Unsafe: Allocating ${ramSizeMb} MB would leave only ${remainingAfterAlloc} MB for Android (minimum ${MIN_HOST_HEADROOM_MB} MB headroom required). Host free RAM is ${stats.availableMb} MB."
            )
        }

        // Rule 3: 8 GB special validation (device must have >= 12GB total RAM)
        if (ramSizeMb >= 8192) {
            if (stats.totalMb < 11500) {
                return SafetyResult.Danger(
                    "8 GB RAM allocation is only safe on devices with 12 GB+ physical RAM. Host total RAM is ${stats.totalMb} MB."
                )
            }
        }

        // Rule 4: Warning if consuming more than 60% of total host RAM
        val percentTotal = (requestedMb.toDouble() / stats.totalMb.toDouble()) * 100.0
        if (percentTotal > 60.0) {
            return SafetyResult.Warning(
                "Allocating ${ramSizeMb} MB (${percentTotal.toInt()}% of host RAM) may cause background apps to be killed by Android LMK."
            )
        }

        // Rule 5: Warning if consuming more than 65% of available RAM
        val percentAvailable = (requestedMb.toDouble() / stats.availableMb.toDouble()) * 100.0
        if (percentAvailable > 65.0) {
            return SafetyResult.Warning(
                "High RAM usage: ${ramSizeMb} MB requested out of ${stats.availableMb} MB free (${remainingAfterAlloc} MB remaining)."
            )
        }

        return SafetyResult.Safe(
            "Safe allocation: ${ramSizeMb} MB guest RAM with ${remainingAfterAlloc} MB host headroom."
        )
    }

    sealed interface SafetyResult {
        data class Safe(val details: String) : SafetyResult
        data class Warning(val message: String) : SafetyResult
        data class Danger(val message: String) : SafetyResult

        val isSafe: Boolean get() = this !is Danger
    }
}
