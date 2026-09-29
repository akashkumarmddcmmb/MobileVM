package com.example.vm.snapshot

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

enum class SnapshotType(val code: Int, val label: String) {
    CONFIGURATION_ONLY(1, "Configuration Snapshot"),
    DISK_POINT_IN_TIME(2, "Disk Snapshot"),
    FULL_VM_STATE(3, "Full VM Snapshot (CPU + RAM + Devices + Disk)")
}

data class CpuSnapshotState(
    val pc: Long = 0L,
    val sp: Long = 0L,
    val pstate: Long = 0L,
    val nzcv: Long = 0L,
    val exceptionLevel: Int = 1,
    val registers: LongArray = LongArray(31)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CpuSnapshotState
        if (pc != other.pc || sp != other.sp || pstate != other.pstate || nzcv != other.nzcv || exceptionLevel != other.exceptionLevel) return false
        return registers.contentEquals(other.registers)
    }

    override fun hashCode(): Int {
        var result = pc.hashCode()
        result = 31 * result + sp.hashCode()
        result = 31 * result + registers.contentHashCode()
        return result
    }
}

data class MemorySnapshotState(
    val ramSizeMb: Int,
    val memoryDumpPath: String = "",
    val checksumSha256: String = ""
)

data class DeviceSnapshotState(
    val uartTxCount: Long = 0L,
    val uartRxCount: Long = 0L,
    val gicPendingMask: Long = 0L,
    val timerTicks: Long = 0L,
    val virtioNetMac: String = "52:54:00:12:34:56",
    val displayWidth: Int = 1024,
    val displayHeight: Int = 768
)

data class FirmwareSnapshotState(
    val nvramPath: String = "",
    val bootOrder: String = "EFI_DISK"
)

data class SnapshotManifest(
    val magic: String = MAGIC_STRING,
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val snapshotId: String = UUID.randomUUID().toString(),
    val vmId: Long,
    val snapshotName: String,
    val snapshotType: SnapshotType,
    val createdAt: Long = System.currentTimeMillis(),
    val vmConfigJson: String,
    val cpuState: CpuSnapshotState? = null,
    val memoryState: MemorySnapshotState? = null,
    val deviceState: DeviceSnapshotState? = null,
    val diskImagePath: String = "",
    val firmwareState: FirmwareSnapshotState? = null,
    val checksumSha256: String = ""
) {
    companion object {
        const val MAGIC_STRING = "MOBLSNAP"
        const val CURRENT_FORMAT_VERSION = 1
    }
}

object VmSnapshotFormat {

    fun computeSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun computeFileSha256(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(65536)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Serializes a snapshot manifest and CPU/device payload into binary format.
     */
    fun serializeManifest(manifest: SnapshotManifest): ByteArray {
        val jsonBytes = manifest.vmConfigJson.toByteArray(Charsets.UTF_8)
        val nameBytes = manifest.snapshotName.toByteArray(Charsets.UTF_8)
        val idBytes = manifest.snapshotId.toByteArray(Charsets.US_ASCII)

        val totalSize = 8 + 4 + 4 + 8 + 8 + 4 + idBytes.size + 4 + nameBytes.size + 4 + jsonBytes.size + (31 * 8 + 32) + 64
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Header (Magic + Version + Type + VM ID + CreatedAt)
        buf.put(SnapshotManifest.MAGIC_STRING.toByteArray(Charsets.US_ASCII))
        buf.putInt(manifest.formatVersion)
        buf.putInt(manifest.snapshotType.code)
        buf.putLong(manifest.vmId)
        buf.putLong(manifest.createdAt)

        // 2. Strings
        buf.putInt(idBytes.size)
        buf.put(idBytes)
        buf.putInt(nameBytes.size)
        buf.put(nameBytes)
        buf.putInt(jsonBytes.size)
        buf.put(jsonBytes)

        // 3. CPU State
        if (manifest.cpuState != null) {
            buf.putLong(manifest.cpuState.pc)
            buf.putLong(manifest.cpuState.sp)
            buf.putLong(manifest.cpuState.pstate)
            buf.putLong(manifest.cpuState.nzcv)
            for (i in 0 until 31) {
                buf.putLong(manifest.cpuState.registers[i])
            }
        } else {
            buf.put(ByteArray(31 * 8 + 32))
        }

        // 4. Device State
        if (manifest.deviceState != null) {
            buf.putLong(manifest.deviceState.uartTxCount)
            buf.putLong(manifest.deviceState.uartRxCount)
            buf.putLong(manifest.deviceState.gicPendingMask)
            buf.putLong(manifest.deviceState.timerTicks)
            buf.putInt(manifest.deviceState.displayWidth)
            buf.putInt(manifest.deviceState.displayHeight)
        } else {
            buf.put(ByteArray(40))
        }

        val payload = ByteArray(buf.position())
        System.arraycopy(buf.array(), 0, payload, 0, payload.size)
        return payload
    }

    /**
     * Parses and validates binary snapshot manifest data.
     */
    fun parseManifest(data: ByteArray): SnapshotManifest? {
        if (data.size < 40) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val magicBytes = ByteArray(8)
        buf.get(magicBytes)
        val magic = String(magicBytes, Charsets.US_ASCII)
        if (magic != SnapshotManifest.MAGIC_STRING) return null

        val version = buf.int
        if (version > SnapshotManifest.CURRENT_FORMAT_VERSION) return null

        val typeCode = buf.int
        val snapshotType = SnapshotType.entries.find { it.code == typeCode } ?: SnapshotType.CONFIGURATION_ONLY
        val vmId = buf.long
        val createdAt = buf.long

        val idLen = buf.int
        if (idLen < 0 || idLen > 128) return null
        val idBytes = ByteArray(idLen)
        buf.get(idBytes)
        val snapshotId = String(idBytes, Charsets.US_ASCII)

        val nameLen = buf.int
        if (nameLen < 0 || nameLen > 512) return null
        val nameBytes = ByteArray(nameLen)
        buf.get(nameBytes)
        val snapshotName = String(nameBytes, Charsets.UTF_8)

        val jsonLen = buf.int
        if (jsonLen < 0 || jsonLen > 65536) return null
        val jsonBytes = ByteArray(jsonLen)
        buf.get(jsonBytes)
        val vmConfigJson = String(jsonBytes, Charsets.UTF_8)

        var cpuState: CpuSnapshotState? = null
        if (buf.remaining() >= 31 * 8 + 32) {
            val pc = buf.long
            val sp = buf.long
            val pstate = buf.long
            val nzcv = buf.long
            val regs = LongArray(31)
            for (i in 0 until 31) {
                regs[i] = buf.long
            }
            cpuState = CpuSnapshotState(pc, sp, pstate, nzcv, 1, regs)
        }

        var deviceState: DeviceSnapshotState? = null
        if (buf.remaining() >= 40) {
            val tx = buf.long
            val rx = buf.long
            val gic = buf.long
            val timer = buf.long
            val width = buf.int
            val height = buf.int
            deviceState = DeviceSnapshotState(tx, rx, gic, timer, "52:54:00:12:34:56", width, height)
        }

        return SnapshotManifest(
            magic = magic,
            formatVersion = version,
            snapshotId = snapshotId,
            vmId = vmId,
            snapshotName = snapshotName,
            snapshotType = snapshotType,
            createdAt = createdAt,
            vmConfigJson = vmConfigJson,
            cpuState = cpuState,
            deviceState = deviceState,
            checksumSha256 = computeSha256(data)
        )
    }
}
