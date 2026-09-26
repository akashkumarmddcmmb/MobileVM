package com.example.vm.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import com.example.vm.core.VMConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface VMConfigDao {
    @Query("SELECT * FROM vm_configurations ORDER BY createdAt DESC")
    fun getAllConfigurations(): Flow<List<VMConfig>>

    @Query("SELECT * FROM vm_configurations WHERE id = :id LIMIT 1")
    suspend fun getConfigurationById(id: Long): VMConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfiguration(config: VMConfig): Long

    @Update
    suspend fun updateConfiguration(config: VMConfig)

    @Delete
    suspend fun deleteConfiguration(config: VMConfig)

    @Query("DELETE FROM vm_configurations WHERE id = :id")
    suspend fun deleteConfigurationById(id: Long)
}
