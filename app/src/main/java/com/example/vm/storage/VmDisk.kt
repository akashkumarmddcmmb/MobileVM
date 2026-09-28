package com.example.vm.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DiskRole {
    ROOTFS,
    DATA,
    SWAP,
    USB_PASSTHROUGH
}

@Entity(tableName = "vm_disks")
data class VmDisk(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val vmId: Long,
    val name: String,
    val diskPath: String,
    val sizeGb: Int,
    val actualAllocatedBytes: Long = 0,
    val isBootable: Boolean = false,
    val role: DiskRole = DiskRole.ROOTFS,
    val isSparse: Boolean = true,
    val createdAtTimestamp: Long = System.currentTimeMillis(),
    val lastModifiedTimestamp: Long = System.currentTimeMillis()
)
