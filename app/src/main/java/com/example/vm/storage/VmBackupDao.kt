package com.example.vm.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VmBackupDao {
    @Query("SELECT * FROM vm_backups ORDER BY timestamp DESC")
    fun getAllBackups(): Flow<List<VmBackup>>

    @Query("SELECT * FROM vm_backups WHERE vmId = :vmId ORDER BY timestamp DESC")
    fun getBackupsForVm(vmId: Long): Flow<List<VmBackup>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackup(backup: VmBackup): Long

    @Update
    suspend fun updateBackup(backup: VmBackup)

    @Delete
    suspend fun deleteBackup(backup: VmBackup)
}
