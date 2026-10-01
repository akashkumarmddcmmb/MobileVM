package com.example.vm.snapshot

import androidx.test.core.app.ApplicationProvider
import com.example.vm.core.VMConfig
import com.example.vm.core.VMState
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
class VMStateSnapshotTest {

    private lateinit var database: VMDatabase
    private lateinit var repository: VMRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = androidx.room.Room.inMemoryDatabaseBuilder(context, VMDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = VMRepository(
            database.vmConfigDao(),
            database.vmDiskDao(),
            database.vmSnapshotDao(),
            database.vmBackupDao(),
            database.storageOperationLogDao()
        )
    }

    @Test
    fun testSnapshotManifestSerializationAndParsing() {
        val regs = LongArray(31) { (it + 1) * 1000L }
        val cpuState = CpuSnapshotState(
            pc = 0x40080000L,
            sp = 0x47FFFFF0L,
            pstate = 0x00000005L,
            nzcv = 0x60000000L,
            exceptionLevel = 1,
            registers = regs
        )

        val devState = DeviceSnapshotState(
            uartTxCount = 1024L,
            uartRxCount = 256L,
            gicPendingMask = 0x04L,
            timerTicks = 50000L,
            virtioNetMac = "52:54:00:12:34:56",
            displayWidth = 1280,
            displayHeight = 720
        )

        val originalManifest = SnapshotManifest(
            vmId = 42L,
            snapshotName = "Checkpoint_Alpha",
            snapshotType = SnapshotType.FULL_VM_STATE,
            vmConfigJson = "{\"name\":\"TestVM\",\"ram\":2048}",
            cpuState = cpuState,
            deviceState = devState,
            diskImagePath = "/vms/42/rootfs.img"
        )

        val serializedBytes = VmSnapshotFormat.serializeManifest(originalManifest)
        assertNotNull(serializedBytes)
        assertTrue(serializedBytes.size > 200)

        val parsed = VmSnapshotFormat.parseManifest(serializedBytes)
        assertNotNull(parsed)
        assertEquals("MOBLSNAP", parsed!!.magic)
        assertEquals(SnapshotManifest.CURRENT_FORMAT_VERSION, parsed.formatVersion)
        assertEquals(42L, parsed.vmId)
        assertEquals("Checkpoint_Alpha", parsed.snapshotName)
        assertEquals(SnapshotType.FULL_VM_STATE, parsed.snapshotType)
        assertEquals("{\"name\":\"TestVM\",\"ram\":2048}", parsed.vmConfigJson)

        // Verify CPU state preservation
        assertNotNull(parsed.cpuState)
        assertEquals(0x40080000L, parsed.cpuState!!.pc)
        assertEquals(0x47FFFFF0L, parsed.cpuState!!.sp)
        assertEquals(1000L, parsed.cpuState!!.registers[0])
        assertEquals(31000L, parsed.cpuState!!.registers[30])

        // Verify Device state preservation
        assertNotNull(parsed.deviceState)
        assertEquals(1024L, parsed.deviceState!!.uartTxCount)
        assertEquals(1280, parsed.deviceState!!.displayWidth)
        assertEquals(720, parsed.deviceState!!.displayHeight)
    }

    @Test
    fun testSnapshotChecksumAndTamperingDetection() {
        val manifest = SnapshotManifest(
            vmId = 99L,
            snapshotName = "Integrity_Test",
            snapshotType = SnapshotType.CONFIGURATION_ONLY,
            vmConfigJson = "{\"test\":true}"
        )

        val bytes = VmSnapshotFormat.serializeManifest(manifest)
        val originalHash = VmSnapshotFormat.computeSha256(bytes)
        assertTrue(originalHash.length == 64)

        // Tamper with one byte in the serialized payload
        val tamperedBytes = bytes.clone()
        tamperedBytes[20] = (tamperedBytes[20].toInt() xor 0xFF).toByte()
        val tamperedHash = VmSnapshotFormat.computeSha256(tamperedBytes)

        assertNotEquals(originalHash, tamperedHash)
    }

    @Test
    fun testCrashRecoveryLifecycle() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val recoveryManager = CrashRecoveryManager(context)
        recoveryManager.clearHeartbeat()

        val config = VMConfig(id = 101L, name = "CrashTestVM")

        // 1. Initial state: clean
        val reportInit = recoveryManager.checkCrashRecovery()
        assertFalse(reportInit.hasUncleanShutdown)

        // 2. VM starts running -> heartbeat active
        recoveryManager.recordVmStateChange(config, VMState.RUNNING)
        val reportRunning = recoveryManager.checkCrashRecovery()
        assertTrue(reportRunning.hasUncleanShutdown)
        assertEquals(101L, reportRunning.vmId)
        assertEquals("CrashTestVM", reportRunning.vmName)
        assertEquals(VMState.RUNNING, reportRunning.lastKnownState)

        // 3. Normal graceful shutdown -> heartbeat cleared
        recoveryManager.recordVmStateChange(config, VMState.STOPPED)
        val reportClean = recoveryManager.checkCrashRecovery()
        assertFalse(reportClean.hasUncleanShutdown)
    }

    @Test
    fun testVMStateTransitionsForSavingAndRestoring() {
        // State transitions for saving
        assertTrue(VMState.RUNNING.canSaveSnapshot())
        assertTrue(VMState.PAUSED.canSaveSnapshot())
        assertFalse(VMState.STOPPED.canSaveSnapshot())

        // State transitions for restoring
        assertTrue(VMState.STOPPED.canRestoreSnapshot())
        assertTrue(VMState.PAUSED.canRestoreSnapshot())
        assertFalse(VMState.RUNNING.canRestoreSnapshot())

        // Terminal states
        assertTrue(VMState.STOPPED.isTerminal())
        assertTrue(VMState.ERROR.isTerminal())
        assertFalse(VMState.SAVING.isTerminal())
        assertFalse(VMState.RESTORING.isTerminal())
        assertFalse(VMState.CRASH_DETECTED.isTerminal())
        assertTrue(VMState.CRASH_DETECTED.canStop())
        assertTrue(VMState.CRASH_DETECTED.canStart())
    }

    @Test
    fun testVmCloneManagerCreatesIndependentClone() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val cloneManager = VmCloneManager(context, repository)

            val srcDiskFile = File(context.filesDir, "test_parent_disk.img")
            srcDiskFile.writeText("PARENT_DISK_DATA")

            val parentConfig = VMConfig(
                name = "Parent_VM",
                ramSizeMb = 2048,
                cpuCores = 2,
                diskImagePath = srcDiskFile.absolutePath
            )
            val parentId = repository.insertConfig(parentConfig)
            val parentWithId = parentConfig.copy(id = parentId)

            val clonedConfig = cloneManager.cloneVm(parentWithId, "Cloned_Child_VM")
            assertNotNull(clonedConfig)
            assertNotEquals(parentId, clonedConfig!!.id)
            assertEquals("Cloned_Child_VM", clonedConfig.name)
            assertNotEquals(parentWithId.diskImagePath, clonedConfig.diskImagePath)
            assertTrue(File(clonedConfig.diskImagePath).exists())
            assertEquals("PARENT_DISK_DATA", File(clonedConfig.diskImagePath).readText())

            // Verify independent disk modification doesn't affect parent
            File(clonedConfig.diskImagePath).writeText("CHILD_DISK_MODIFIED")
            assertEquals("PARENT_DISK_DATA", srcDiskFile.readText())
            assertEquals("CHILD_DISK_MODIFIED", File(clonedConfig.diskImagePath).readText())

            srcDiskFile.delete()
            File(clonedConfig.diskImagePath).delete()
        }
    }
}
