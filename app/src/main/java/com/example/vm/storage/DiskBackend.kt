package com.example.vm.storage

interface DiskBackend {
    fun isPathAuthorized(targetPath: String): Boolean
    fun createDiskImage(diskPath: String, sizeGb: Int, sparse: Boolean): Boolean
    fun readSectors(diskPath: String, lba: Long, count: Int): ByteArray?
    fun writeSectors(diskPath: String, lba: Long, data: ByteArray): Boolean
}

data class DiskStats(
    val sizeBytes: Long,
    val path: String,
    val exists: Boolean,
    val isSparse: Boolean
)
