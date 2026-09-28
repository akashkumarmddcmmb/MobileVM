package com.example.vm.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vm_backups")
data class VmBackup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val vmId: Long,
    val backupName: String,
    val archivePath: String,
    val sizeBytes: Long,
    val vmConfigJson: String,
    val timestamp: Long = System.currentTimeMillis()
)
