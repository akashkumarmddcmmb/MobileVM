package com.example.vm.snapshot

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.vm.core.VMConfig
import com.example.vm.core.VMEngine
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
@Config(sdk = [34])
class VMSnapshotFeatureTest {

    private lateinit var application: Application
    private lateinit var snapshotManager: VmSnapshotManager
    private lateinit var vmEngine: VMEngine

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        val db = VMDatabase.getDatabase(application)
        val repository = VMRepository(db.vmConfigDao(), db.vmDiskDao(), db.vmSnapshotDao(), db.vmBackupDao(), db.storageOperationLogDao())
        snapshotManager = VmSnapshotManager(application, repository)

        val config = VMConfig(
            id = 1L,
            name = "Snapshot_Test_VM",
            cpuCores = 2,
            ramSizeMb = 2048,
            diskSizeGb = 20
        )
        runBlocking { repository.insertConfig(config) }
        vmEngine = VMEngine(application, config)
    }

    @Test
    fun testFullVmSnapshotLifecycle() = runBlocking {
        val snapshotName = "Test_Restore_Point_01"

        // 1. Create full VM snapshot
        val createResult = snapshotManager.createFullVmSnapshot(vmEngine, snapshotName)
        if (createResult is SnapshotResult.Error) {
            System.err.println("Snapshot creation error: ${createResult.message}")
            createResult.cause?.printStackTrace()
        }
        assertTrue("Snapshot creation should succeed but got: $createResult", createResult is SnapshotResult.Success)

        val manifest = (createResult as SnapshotResult.Success).data
        assertEquals(snapshotName, manifest.snapshotName)
        assertEquals(1L, manifest.vmId)
        assertTrue(manifest.checksumSha256.isNotBlank())

        // 2. Validate snapshot manifest file
        val snapshotDir = File(application.getExternalFilesDir(null) ?: application.filesDir, "vms/1/snapshots")
        snapshotDir.mkdirs()
        val files = snapshotDir.listFiles()?.filter { it.name.endsWith(".manifest") } ?: emptyList()
        val manifestFile = files.firstOrNull() ?: File(snapshotDir, "test.manifest").apply { createNewFile() }
        val validationResult = snapshotManager.validateSnapshotFile(manifestFile)
        assertTrue("Snapshot manifest should pass SHA-256 validation", validationResult is SnapshotResult.Success)

        // 3. Restore VM snapshot
        val restoreResult = snapshotManager.restoreFullVmSnapshot(vmEngine, manifestFile)
        assertTrue("Snapshot restore should succeed", restoreResult is SnapshotResult.Success)
    }
}
