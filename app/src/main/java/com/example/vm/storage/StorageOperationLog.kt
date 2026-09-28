package com.example.vm.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "storage_operation_logs")
data class StorageOperationLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val vmId: Long,
    val operation: String,
    val result: String,
    val details: String,
    val timestamp: Long = System.currentTimeMillis()
)
