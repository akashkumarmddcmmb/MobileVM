package com.example.vm.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VmSnapshotDao {
    @Query("SELECT * FROM vm_snapshots ORDER BY timestamp DESC")
    fun getAllSnapshots(): Flow<List<VmSnapshot>>

    @Query("SELECT * FROM vm_snapshots WHERE vmId = :vmId ORDER BY timestamp DESC")
    fun getSnapshotsForVm(vmId: Long): Flow<List<VmSnapshot>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(snapshot: VmSnapshot): Long

    @Update
    suspend fun updateSnapshot(snapshot: VmSnapshot)

    @Delete
    suspend fun deleteSnapshot(snapshot: VmSnapshot)
}
