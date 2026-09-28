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
}
