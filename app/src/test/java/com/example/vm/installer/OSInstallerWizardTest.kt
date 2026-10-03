package com.example.vm.installer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.vm.core.VMConfig
import com.example.vm.persistence.VMDatabase
import com.example.vm.persistence.VMRepository
import com.example.vm.ui.VMViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
class OSInstallerWizardTest {

    private lateinit var application: Application
    private lateinit var viewModel: VMViewModel
    private lateinit var repository: VMRepository

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        val db = VMDatabase.getDatabase(application)
        repository = VMRepository(
            db.vmConfigDao(),
            db.vmDiskDao(),
            db.vmSnapshotDao(),
            db.vmBackupDao(),
            db.storageOperationLogDao()
        )
        viewModel = VMViewModel(application)
    }

    @Test
    fun testOSProvisioningAndBootFlow() = runBlocking {
        val vmName = "Ubuntu_24_04_Installer_Test"
        val vmDir = File(application.filesDir, "vms/test_provision")
        vmDir.mkdirs()

        val diskFile = File(vmDir, "rootfs.img").apply { writeBytes(ByteArray(1024)) }
        val isoFile = File(vmDir, "install_media.iso").apply { writeBytes(ByteArray(2048)) }

        val config = VMConfig(
            id = 0L,
            name = vmName,
            guestOsType = "Ubuntu Linux (ARM64)",
            ramSizeMb = 2048,
            cpuCores = 2,
            diskSizeGb = 20,
            diskImagePath = diskFile.absolutePath,
            isoPath = isoFile.absolutePath
        )

        val insertedId = try {
            repository.insertConfig(config)
        } catch (e: Exception) {
            e.printStackTrace()
            -1L
        }
        if (insertedId <= 0) {
            System.err.println("insertConfig returned $insertedId")
        }
        assertTrue("Configuration insertion should return valid row ID", insertedId > 0)

        val savedConfig = repository.getConfigById(insertedId)
        assertNotNull("Saved VM configuration should exist in database", savedConfig)
        assertEquals(vmName, savedConfig?.name)
        assertEquals(isoFile.absolutePath, savedConfig?.isoPath)

        viewModel.stopVM()
        viewModel.startVM(savedConfig!!)
        delay(500)
        val activeEngine = viewModel.activeVM.value ?: viewModel.activeVM.first { it != null }
        assertNotNull("VM Engine should be active after startVM call", activeEngine)
        assertEquals(vmName, activeEngine?.config?.name)
    }
}
