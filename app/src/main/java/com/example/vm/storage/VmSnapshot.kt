package com.example.vm.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vm_snapshots")
data class VmSnapshot(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val diskId: Long,
    val vmId: Long,
    val snapshotName: String,
    val snapshotPath: String,
    val sizeBytes: Long,
    val timestamp: Long = System.currentTimeMillis()
)
