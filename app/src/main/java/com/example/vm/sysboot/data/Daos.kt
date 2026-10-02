package com.example.vm.sysboot.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BootEntryDao {
    @Query("SELECT * FROM boot_entries ORDER BY displayOrder ASC")
    fun getAllBootEntries(): Flow<List<BootEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBootEntry(entry: BootEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBootEntries(entries: List<BootEntryEntity>)

    @Update
    suspend fun updateBootEntry(entry: BootEntryEntity)

    @Delete
    suspend fun deleteBootEntry(entry: BootEntryEntity)

    @Query("UPDATE boot_entries SET isDefault = 0")
    suspend fun clearDefaultFlags()

    @Query("UPDATE boot_entries SET isDefault = 1 WHERE id = :entryId")
    suspend fun setDefaultBootEntry(entryId: Int)
}

@Dao
interface DiagnosticDao {
    @Query("SELECT * FROM diagnostic_results ORDER BY timestamp DESC")
    fun getAllDiagnostics(): Flow<List<DiagnosticResultEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDiagnostic(result: DiagnosticResultEntity): Long

    @Query("DELETE FROM diagnostic_results")
    suspend fun clearAllDiagnostics()
}

@Dao
interface SecurityDao {
    @Query("SELECT * FROM security_audits ORDER BY timestamp DESC")
    fun getAllSecurityAudits(): Flow<List<SecurityAuditEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudit(audit: SecurityAuditEntity): Long
}

@Dao
interface SourceModuleDao {
    @Query("SELECT * FROM c_source_modules ORDER BY filename ASC")
    fun getAllModules(): Flow<List<CSourceModuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModule(module: CSourceModuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModules(modules: List<CSourceModuleEntity>)

    @Update
    suspend fun updateModule(module: CSourceModuleEntity)
}

@Dao
interface FailureSimulationDao {
    @Query("SELECT * FROM failure_simulations ORDER BY timestamp DESC")
    fun getAllSimulations(): Flow<List<BootFailureSimulationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSimulation(sim: BootFailureSimulationEntity): Long
}
