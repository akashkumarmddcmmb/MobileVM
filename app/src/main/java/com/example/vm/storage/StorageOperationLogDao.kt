package com.example.vm.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface StorageOperationLogDao {
    @Query("SELECT * FROM storage_operation_logs ORDER BY timestamp DESC LIMIT 50")
    fun getRecentLogs(): Flow<List<StorageOperationLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: StorageOperationLog): Long
}
