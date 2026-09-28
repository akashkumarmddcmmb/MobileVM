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

    suspend fun getConfigById(id: Long): VMConfig? = vmConfigDao.getConfigurationById(id)
    suspend fun insertConfig(config: VMConfig): Long = vmConfigDao.insertConfiguration(config)
    suspend fun updateConfig(config: VMConfig) = vmConfigDao.updateConfiguration(config)
    suspend fun deleteConfig(config: VMConfig) = vmConfigDao.deleteConfiguration(config)
    suspend fun deleteConfigById(id: Long) = vmConfigDao.deleteConfigurationById(id)

    // Disk operations
    fun getDisksForVm(vmId: Long): Flow<List<VmDisk>> = vmDiskDao.getDisksForVm(vmId)
    suspend fun getDiskById(id: Long): VmDisk? = vmDiskDao.getDiskById(id)
    suspend fun insertDisk(disk: VmDisk): Long = vmDiskDao.insertDisk(disk)
    suspend fun updateDisk(disk: VmDisk) = vmDiskDao.updateDisk(disk)
    suspend fun deleteDisk(disk: VmDisk) = vmDiskDao.deleteDisk(disk)
    suspend fun deleteDisksForVm(vmId: Long) = vmDiskDao.deleteDisksForVm(vmId)

    // Snapshot operations
    fun getSnapshotsForVm(vmId: Long): Flow<List<VmSnapshot>> = vmSnapshotDao.getSnapshotsForVm(vmId)
    suspend fun insertSnapshot(snapshot: VmSnapshot): Long = vmSnapshotDao.insertSnapshot(snapshot)
    suspend fun updateSnapshot(snapshot: VmSnapshot) = vmSnapshotDao.updateSnapshot(snapshot)
    suspend fun deleteSnapshot(snapshot: VmSnapshot) = vmSnapshotDao.deleteSnapshot(snapshot)

    // Backup operations
    fun getBackupsForVm(vmId: Long): Flow<List<VmBackup>> = vmBackupDao.getBackupsForVm(vmId)
    suspend fun insertBackup(backup: VmBackup): Long = vmBackupDao.insertBackup(backup)
    suspend fun updateBackup(backup: VmBackup) = vmBackupDao.updateBackup(backup)
    suspend fun deleteBackup(backup: VmBackup) = vmBackupDao.deleteBackup(backup)

    // Logging operations
    suspend fun logStorageOperation(operation: String, vmId: Long, result: String, details: String) {
        storageLogDao.insertLog(
            StorageOperationLog(
                vmId = vmId,
                operation = operation,
                result = result,
                details = details
            )
        )
    }
}
