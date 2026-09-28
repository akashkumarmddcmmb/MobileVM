package com.example.vm.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VmDiskDao {
    @Query("SELECT * FROM vm_disks ORDER BY createdAtTimestamp DESC")
    fun getAllDisks(): Flow<List<VmDisk>>

    @Query("SELECT * FROM vm_disks WHERE vmId = :vmId ORDER BY createdAtTimestamp ASC")
    fun getDisksForVm(vmId: Long): Flow<List<VmDisk>>

    @Query("SELECT * FROM vm_disks WHERE id = :id")
    suspend fun getDiskById(id: Long): VmDisk?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDisk(disk: VmDisk): Long

    @Update
    suspend fun updateDisk(disk: VmDisk)

    @Delete
    suspend fun deleteDisk(disk: VmDisk)

    @Query("DELETE FROM vm_disks WHERE vmId = :vmId")
    suspend fun deleteDisksForVm(vmId: Long)
}
