package com.example.vm.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vm.core.VMConfig
import com.example.vm.persistence.VMDatabase
import com.example.vm.persistence.VMRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VMStorageTest {

    private lateinit var context: Context
    private lateinit var database: VMDatabase
    private lateinit var repository: VMRepository
    private lateinit var diskBackend: AndroidStorageDiskBackend
    private lateinit var storageManager: VMStorageManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = VMDatabase.getDatabase(context)
        repository = VMRepository(
            database.vmConfigDao(),
            database.vmDiskDao(),
            database.vmSnapshotDao(),
            database.vmBackupDao(),
            database.storageOperationLogDao()
        )
        diskBackend = AndroidStorageDiskBackend(context)
        storageManager = VMStorageManager(context, repository, diskBackend)
    }

    @Test
    fun testVmDisksDirectoryLayout() {
        val vmDir = storageManager.getVmDisksDirectory(101L)
        assertTrue(vmDir.exists())
        assertTrue(vmDir.isDirectory)
        assertTrue(vmDir.absolutePath.contains("vms/vm_101/disks"))
    }

    @Test
    fun testSparseDiskCreationAndMBRHeader() = runBlocking {
        val disk = storageManager.createDiskForVm(
            vmId = 101L,
            diskName = "test_rootfs",
            sizeGb = 2,
            role = DiskRole.ROOTFS,
            isBootable = true,
            isSparse = true
        )

        assertNotNull(disk)
        assertEquals(2, disk!!.sizeGb)
        assertTrue(disk.isBootable)

        val diskFile = File(disk.diskPath)
        assertTrue(diskFile.exists())
        assertEquals(2L * 1024L * 1024L * 1024L, diskFile.length())

        val mbr = diskBackend.inspectMBR(disk.diskPath)
        assertNotNull(mbr)
        assertTrue(mbr!!.isValidSignature)
        assertEquals(1, mbr.partitions.size)
        assertEquals("0x83", mbr.partitions[0].typeHex)
        assertTrue(mbr.partitions[0].bootable)
    }

    @Test
    fun testDiskResizeExpand() = runBlocking {
        val disk = storageManager.createDiskForVm(
            vmId = 102L,
            diskName = "expandable_disk",
            sizeGb = 4,
            isSparse = true
        )
        assertNotNull(disk)

        val resized = storageManager.resizeDisk(disk!!, 8)
        assertTrue(resized)

        val diskFile = File(disk.diskPath)
        assertEquals(8L * 1024L * 1024L * 1024L, diskFile.length())
    }

    @Test
    fun testDiskResizeRefuseShrink() = runBlocking {
        val disk = storageManager.createDiskForVm(
            vmId = 103L,
            diskName = "shrink_test_disk",
            sizeGb = 8,
            isSparse = true
        )
        assertNotNull(disk)

        val shrinkResult = storageManager.resizeDisk(disk!!, 4)
        assertFalse(shrinkResult)

        val diskFile = File(disk.diskPath)
        assertEquals(8L * 1024L * 1024L * 1024L, diskFile.length())
    }

    @Test
    fun testBackupAndRestoreFlow() = runBlocking {
        val config = VMConfig(
            id = 555L,
            name = "Test_Backup_VM",
            ramSizeMb = 2048,
            cpuCores = 2,
            guestOsType = "UBUNTU_22_04",
            diskSizeGb = 8
        )

        val backup = storageManager.createBackup(config, "UnitTest_Backup")
        assertNotNull(backup)
        assertTrue(File(backup!!.archivePath).exists())
        assertTrue(backup.sizeBytes > 0)

        val restoredConfig = storageManager.restoreBackup(backup)
        assertNotNull(restoredConfig)
        assertTrue(restoredConfig!!.name.contains("Restored"))
        assertEquals("UBUNTU_22_04", restoredConfig.guestOsType)
    }

    @Test
    fun testGptHeaderAndPartitionDetection() {
        runBlocking {
            val diskFile = File(storageManager.getVmDisksDirectory(999L), "gpt_test.img")
            diskFile.parentFile?.mkdirs()

            // Create a 10MB test disk file
            java.io.RandomAccessFile(diskFile, "rw").use { raf ->
                raf.setLength(10L * 1024L * 1024L)

                // Write Protective MBR at LBA 0
                val mbr = ByteArray(512)
                mbr[446 + 4] = 0xEE.toByte() // 0xEE = GPT Protective MBR
                mbr[510] = 0x55.toByte()
                mbr[511] = 0xAA.toByte()
                raf.seek(0)
                raf.write(mbr)

                // Write GPT Header at LBA 1 (Sector 1)
                val gptHeader = ByteArray(512)
                System.arraycopy("EFI PART".toByteArray(Charsets.US_ASCII), 0, gptHeader, 0, 8)
                val buf = java.nio.ByteBuffer.wrap(gptHeader).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.putInt(8, 0x00010000) // Revision 1.0
                buf.putInt(12, 92) // Header size
                buf.putLong(24, 1L) // My LBA = 1
                buf.putLong(40, 34L) // First usable LBA
                buf.putLong(48, 20000L) // Last usable LBA
                buf.putLong(72, 2L) // Partition entries LBA = 2
                buf.putInt(80, 128) // Num partition entries
                buf.putInt(84, 128) // Entry size
                raf.seek(512)
                raf.write(gptHeader)

                // Write Partition Entry at LBA 2 (EFI System Partition GUID)
                val partEntry = ByteArray(512)
                val espGuidBytes = byteArrayOf(
                    0x28, 0x73, 0x2A, 0xC1.toByte(), 0x1F, 0xF8.toByte(), 0xD2.toByte(), 0x11,
                    0xBA.toByte(), 0x4B, 0x00, 0xA0.toByte(), 0xC9.toByte(), 0x3E, 0xC9.toByte(), 0x3B
                )
                System.arraycopy(espGuidBytes, 0, partEntry, 0, 16)
                val pBuf = java.nio.ByteBuffer.wrap(partEntry).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                pBuf.putLong(32, 2048L) // Start LBA = 2048
                pBuf.putLong(40, 10000L) // End LBA = 10000
                raf.seek(1024)
                raf.write(partEntry)
            }

            val scheme = diskBackend.detectPartitionScheme(diskFile.absolutePath)
            assertEquals(AndroidStorageDiskBackend.PartitionTableType.GPT, scheme.scheme)
            assertTrue(scheme.isEfiBootable)
            assertNotNull(scheme.gptInfo)
            assertTrue(scheme.gptInfo!!.isValidSignature)
            assertTrue(scheme.gptInfo!!.partitions.isNotEmpty())
            assertTrue(scheme.gptInfo!!.partitions[0].isEfiSystemPartition)

            diskFile.delete()
        }
    }

    @Test
    fun testSectorReadWriteAndBounds() {
        val diskFile = File(storageManager.getVmDisksDirectory(888L), "rw_test.img")
        diskFile.parentFile?.mkdirs()

        java.io.RandomAccessFile(diskFile, "rw").use { raf ->
            raf.setLength(1024L * 1024L) // 1 MB = 2048 sectors
        }

        val testData = ByteArray(512) { it.toByte() }
        val writeOk = diskBackend.writeSectors(diskFile.absolutePath, 10, testData)
        assertTrue(writeOk)

        val readData = diskBackend.readSectors(diskFile.absolutePath, 10, 1)
        assertNotNull(readData)
        assertArrayEquals(testData, readData)

        // Read out of bounds should safely return null
        val outOfBounds = diskBackend.readSectors(diskFile.absolutePath, 5000, 1)
        assertNull(outOfBounds)

        diskFile.delete()
    }
}
