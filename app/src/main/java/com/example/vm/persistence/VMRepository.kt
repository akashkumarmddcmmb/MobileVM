package com.example.vm.persistence

import com.example.vm.core.VMConfig
import com.example.vm.storage.StorageOperationLog
import com.example.vm.storage.StorageOperationLogDao
import com.example.vm.storage.VmBackup
import com.example.vm.storage.VmBackupDao
import com.example.vm.storage.VmDisk
import com.example.vm.storage.VmDiskDao
import com.example.vm.storage.VmSnapshot
import com.example.vm.storage.VmSnapshotDao
import kotlinx.coroutines.flow.Flow

class VMRepository(
    private val vmConfigDao: VMConfigDao,
    private val vmDiskDao: VmDiskDao,
    private val vmSnapshotDao: VmSnapshotDao,
    private val vmBackupDao: VmBackupDao,
    private val storageLogDao: StorageOperationLogDao
) {
    val allConfigs: Flow<List<VMConfig>> = vmConfigDao.getAllConfigurations()
    val allDisks: Flow<List<VmDisk>> = vmDiskDao.getAllDisks()
    val allSnapshots: Flow<List<VmSnapshot>> = vmSnapshotDao.getAllSnapshots()
    val allBackups: Flow<List<VmBackup>> = vmBackupDao.getAllBackups()
    val recentStorageLogs: Flow<List<StorageOperationLog>> = storageLogDao.getRecentLogs()

    suspend fun getConfigById(id: Long): VMConfig? = try { vmConfigDao.getConfigurationById(id) } catch (e: Exception) { null }
    suspend fun insertConfig(config: VMConfig): Long = try { vmConfigDao.insertConfiguration(config) } catch (e: Exception) { -1L }
    suspend fun updateConfig(config: VMConfig) { try { vmConfigDao.updateConfiguration(config) } catch (_: Exception) {} }
    suspend fun deleteConfig(config: VMConfig) { try { vmConfigDao.deleteConfiguration(config) } catch (_: Exception) {} }
    suspend fun deleteConfigById(id: Long) { try { vmConfigDao.deleteConfigurationById(id) } catch (_: Exception) {} }

    // Disk operations
    fun getDisksForVm(vmId: Long): Flow<List<VmDisk>> = vmDiskDao.getDisksForVm(vmId)
    suspend fun getDiskById(id: Long): VmDisk? = try { vmDiskDao.getDiskById(id) } catch (e: Exception) { null }
    suspend fun insertDisk(disk: VmDisk): Long = try { vmDiskDao.insertDisk(disk) } catch (e: Exception) { -1L }
    suspend fun updateDisk(disk: VmDisk) { try { vmDiskDao.updateDisk(disk) } catch (_: Exception) {} }
    suspend fun deleteDisk(disk: VmDisk) { try { vmDiskDao.deleteDisk(disk) } catch (_: Exception) {} }
    suspend fun deleteDisksForVm(vmId: Long) { try { vmDiskDao.deleteDisksForVm(vmId) } catch (_: Exception) {} }

    // Snapshot operations
    fun getSnapshotsForVm(vmId: Long): Flow<List<VmSnapshot>> = vmSnapshotDao.getSnapshotsForVm(vmId)
    suspend fun insertSnapshot(snapshot: VmSnapshot): Long = try { vmSnapshotDao.insertSnapshot(snapshot) } catch (e: Exception) { -1L }
    suspend fun updateSnapshot(snapshot: VmSnapshot) { try { vmSnapshotDao.updateSnapshot(snapshot) } catch (_: Exception) {} }
    suspend fun deleteSnapshot(snapshot: VmSnapshot) { try { vmSnapshotDao.deleteSnapshot(snapshot) } catch (_: Exception) {} }

    // Backup operations
    fun getBackupsForVm(vmId: Long): Flow<List<VmBackup>> = vmBackupDao.getBackupsForVm(vmId)
    suspend fun insertBackup(backup: VmBackup): Long = try { vmBackupDao.insertBackup(backup) } catch (e: Exception) { -1L }
    suspend fun updateBackup(backup: VmBackup) { try { vmBackupDao.updateBackup(backup) } catch (_: Exception) {} }
    suspend fun deleteBackup(backup: VmBackup) { try { vmBackupDao.deleteBackup(backup) } catch (_: Exception) {} }

    // Logging operations
    suspend fun logStorageOperation(operation: String, vmId: Long, result: String, details: String) {
        try {
            storageLogDao.insertLog(
                StorageOperationLog(
                    vmId = vmId,
                    operation = operation,
                    result = result,
                    details = details
                )
            )
        } catch (e: Exception) {
            android.util.Log.w("VMRepository", "Could not log storage operation: ${e.message}")
        }
    }
}
