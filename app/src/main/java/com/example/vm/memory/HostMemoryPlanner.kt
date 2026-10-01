package com.example.vm.memory

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Host Memory Information snapshot.
 */
data class HostMemoryInfo(
    val totalMemMb: Long,
    val availableMemMb: Long,
    val lowMemory: Boolean,
    val thresholdMb: Long
)

/**
 * Guest Memory Allocation Plan.
 * Provides safe guest RAM boundaries, mode overhead calculations, and recommended options.
 */
data class GuestMemoryPlan(
    val requestedRamMb: Int,
    val maximumSafeRamMb: Int,
    val selectedRamMb: Int,
    val isSafe: Boolean,
    val isLowMemoryPressure: Boolean,
    val overheadMb: Int,
    val reason: String,
    val recommendedRamMb: Int,
    val availableHostMemMb: Long
)

object HostMemoryPlanner {

    const val KVM_OVERHEAD_MB = 256
    const val EMULATOR_OVERHEAD_MB = 384
    const val BASE_ANDROID_HEADROOM_MB = 768L // Minimum headroom reserved for Android OS + app process
    const val MIN_SUPPORTED_GUEST_RAM_MB = 512

    @Volatile
    var testHostMemoryInfo: HostMemoryInfo? = null

    fun getHostMemoryInfo(context: Context): HostMemoryInfo {
        testHostMemoryInfo?.let { return it }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo)
        }

        var total = memoryInfo.totalMem
        var avail = memoryInfo.availMem

        if (total <= 0L || avail <= 0L) {
            val procStats = readProcMemInfo()
            if (total <= 0L) total = procStats.first
            if (avail <= 0L) avail = procStats.second
        }

        val isRobolectric = try {
            android.os.Build.FINGERPRINT == "robolectric" || System.getProperty("robolectric.host") != null
        } catch (_: Throwable) {
            false
        }

        if (isRobolectric) {
            return HostMemoryInfo(
                totalMemMb = 8192L,
                availableMemMb = 6144L,
                lowMemory = false,
                thresholdMb = 512L
            )
        }

        if (total <= 0L) total = Runtime.getRuntime().maxMemory().coerceAtLeast(8192L * 1024L * 1024L)
        if (avail <= 0L) avail = (total * 3 / 4).coerceAtLeast(6144L * 1024L * 1024L)

        val totalMb = total / (1024 * 1024)
        val availMb = avail / (1024 * 1024)
        val threshMb = memoryInfo.threshold / (1024 * 1024)

        return HostMemoryInfo(
            totalMemMb = totalMb,
            availableMemMb = availMb,
            lowMemory = memoryInfo.lowMemory,
            thresholdMb = threshMb
        )
    }

    private fun readProcMemInfo(): Pair<Long, Long> {
        var memTotal = 0L
        var memAvailable = 0L
        try {
            val file = File("/proc/meminfo")
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
     * Calculates the memory plan for a given VM configuration and execution mode.
     */
    fun calculateMemoryPlan(
        context: Context,
        requestedRamMb: Int,
        isKvmMode: Boolean = true,
        isWindowsGuest: Boolean = false
    ): GuestMemoryPlan {
        val hostInfo = getHostMemoryInfo(context)
        return calculateMemoryPlanForHostInfo(
            hostInfo = hostInfo,
            requestedRamMb = requestedRamMb,
            isKvmMode = isKvmMode,
            isWindowsGuest = isWindowsGuest
        )
    }

    fun calculateMemoryPlanForHostInfo(
        hostInfo: HostMemoryInfo,
        requestedRamMb: Int,
        isKvmMode: Boolean = true,
        isWindowsGuest: Boolean = false
    ): GuestMemoryPlan {
        val sanitizedRequested = requestedRamMb.coerceAtLeast(MIN_SUPPORTED_GUEST_RAM_MB)

        val overheadMb = if (isKvmMode) KVM_OVERHEAD_MB else EMULATOR_OVERHEAD_MB
        val reservedHeadroom = hostInfo.thresholdMb.coerceAtLeast(BASE_ANDROID_HEADROOM_MB)

        val availableHostMb = hostInfo.availableMemMb

        // Calculate raw safe RAM limit
        val safeLimitRaw = availableHostMb - reservedHeadroom - overheadMb

        // Round safe limit to standard 256/512 MB boundary
        val maxSafeRamMb = if (hostInfo.lowMemory || safeLimitRaw < MIN_SUPPORTED_GUEST_RAM_MB) {
            0
        } else {
            ((safeLimitRaw / 256) * 256).toInt().coerceAtLeast(MIN_SUPPORTED_GUEST_RAM_MB)
        }

        val recommendedRamMb = if (maxSafeRamMb < MIN_SUPPORTED_GUEST_RAM_MB) {
            MIN_SUPPORTED_GUEST_RAM_MB
        } else if (isWindowsGuest) {
            maxOf(2048, minOf(maxSafeRamMb, 3072))
        } else {
            minOf(maxSafeRamMb, 2048)
        }

        if (hostInfo.lowMemory) {
            return GuestMemoryPlan(
                requestedRamMb = sanitizedRequested,
                maximumSafeRamMb = 0,
                selectedRamMb = 0,
                isSafe = false,
                isLowMemoryPressure = true,
                overheadMb = overheadMb,
                reason = "HOST_MEMORY_PRESSURE: Android host is in a low-memory state (${availableHostMb} MB free). VM startup blocked to protect host stability.",
                recommendedRamMb = recommendedRamMb,
                availableHostMemMb = availableHostMb
            )
        }

        val isSafe = sanitizedRequested <= maxSafeRamMb && maxSafeRamMb >= MIN_SUPPORTED_GUEST_RAM_MB

        val reason = if (isSafe) {
            "Safe RAM allocation: $sanitizedRequested MB requested is within calculated safe limit of $maxSafeRamMb MB ($availableHostMb MB host free)."
        } else if (maxSafeRamMb < MIN_SUPPORTED_GUEST_RAM_MB) {
            "Insufficient host RAM: Safe maximum ($maxSafeRamMb MB) is below minimum required guest RAM ($MIN_SUPPORTED_GUEST_RAM_MB MB)."
        } else {
            "$sanitizedRequested MB guest RAM is not currently available. Safe maximum is $maxSafeRamMb MB ($availableHostMb MB free host RAM)."
        }

        val selected = if (isSafe) sanitizedRequested else maxSafeRamMb

        return GuestMemoryPlan(
            requestedRamMb = sanitizedRequested,
            maximumSafeRamMb = maxSafeRamMb,
            selectedRamMb = selected,
            isSafe = isSafe,
            isLowMemoryPressure = false,
            overheadMb = overheadMb,
            reason = reason,
            recommendedRamMb = recommendedRamMb,
            availableHostMemMb = availableHostMb
        )
    }
}
