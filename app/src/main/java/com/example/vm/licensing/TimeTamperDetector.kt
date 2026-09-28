package com.example.vm.licensing

import android.content.Context
import android.os.SystemClock

/**
 * Mobile BM Time Tamper & Clock Rollback Detection Engine.
 *
 * Protects against offline license abuse by users rolling back the device clock
 * to artificially extend trial or offline grace periods.
 *
 * Employs a dual-verification strategy:
 *  1. Persistent Monotonic Checkpointing: Every validation updates a stored high-water mark.
 *     If System.currentTimeMillis() is ever earlier than the stored checkpoint, rollback is flagged.
 *  2. Elapsed Realtime Differential: Compares elapsed realtime progression against wall-clock changes.
 */
class TimeTamperDetector(private val context: Context) {

    private val prefs = context.getSharedPreferences("mbm_time_guard", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_KNOWN_WALL_TIME = "last_known_wall_time"
        private const val KEY_LAST_KNOWN_ELAPSED = "last_known_elapsed"
        private const val ALLOWED_BACKWARD_DRIFT_MS = 60_000L // 1 minute allowed for NTP sync drift
    }

    /**
     * Checks if the system clock has been tampered with or rolled back.
     * Returns true if tampering is detected.
     */
    @Synchronized
    fun isClockManipulated(): Boolean {
        val currentWallTime = System.currentTimeMillis()
        val currentElapsed = SystemClock.elapsedRealtime()

        val lastWallTime = prefs.getLong(KEY_LAST_KNOWN_WALL_TIME, 0L)
        val lastElapsed = prefs.getLong(KEY_LAST_KNOWN_ELAPSED, 0L)

        if (lastWallTime == 0L) {
            // First run, record initial checkpoint
            recordCheckpoint()
            return false
        }

        // Test 1: Absolute wall clock rollback detection
        if (currentWallTime + ALLOWED_BACKWARD_DRIFT_MS < lastWallTime) {
            return true
        }

        // Test 2: If elapsed realtime advanced, wall clock should have advanced accordingly
        if (currentElapsed > lastElapsed && lastElapsed > 0L) {
            val elapsedDelta = currentElapsed - lastElapsed
            val wallDelta = currentWallTime - lastWallTime

            // If elapsed time grew by > 5 minutes, but wall clock went backwards or stayed frozen
            if (elapsedDelta > 300_000L && wallDelta < -ALLOWED_BACKWARD_DRIFT_MS) {
                return true
            }
        }

        // Clock is valid, update checkpoint
        recordCheckpoint()
        return false
    }

    /**
     * Records an authenticated time checkpoint (e.g. after successful server validation).
     */
    @Synchronized
    fun recordCheckpoint(authenticatedWallTime: Long = System.currentTimeMillis()) {
        val currentElapsed = SystemClock.elapsedRealtime()
        val highestWallTime = maxOf(authenticatedWallTime, prefs.getLong(KEY_LAST_KNOWN_WALL_TIME, 0L))

        prefs.edit()
            .putLong(KEY_LAST_KNOWN_WALL_TIME, highestWallTime)
            .putLong(KEY_LAST_KNOWN_ELAPSED, currentElapsed)
            .apply()
    }

    @Synchronized
    fun getLastRecordedTimestamp(): Long {
        return prefs.getLong(KEY_LAST_KNOWN_WALL_TIME, 0L)
    }

    @Synchronized
    fun resetForTesting() {
        prefs.edit().clear().apply()
    }
}
