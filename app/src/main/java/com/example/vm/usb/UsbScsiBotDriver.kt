package com.example.vm.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

data class ScsiInquiryResult(
    val vendor: String,
    val product: String,
    val revision: String,
    val peripheralType: Int
)

data class ScsiCapacityResult(
    val lastLba: Long,
    val blockSize: Int,
    val totalCapacityBytes: Long
)

/**
 * UsbScsiBotDriver implements low-level USB Mass Storage Class (MSC)
 * Bulk-Only Transport (BOT) protocol over Bulk IN and Bulk OUT endpoints.
 */
class UsbScsiBotDriver(
    private val connection: UsbDeviceConnection,
    private val storageInterface: UsbInterface,
    private val bulkInEndpoint: UsbEndpoint,
    private val bulkOutEndpoint: UsbEndpoint
) {
    companion object {
        private const val TAG = "UsbScsiBotDriver"
        private const val CBW_SIGNATURE = 0x43425355 // "USBC" Little Endian
        private const val CSW_SIGNATURE = 0x53425355 // "USBS" Little Endian

        private const val CBW_LENGTH = 31
        private const val CSW_LENGTH = 13
        private const val DEFAULT_TIMEOUT_MS = 2000

        // SCSI Command Opcodes
        const val SCSI_TEST_UNIT_READY = 0x00.toByte()
        const val SCSI_REQUEST_SENSE    = 0x03.toByte()
        const val SCSI_INQUIRY          = 0x12.toByte()
        const val SCSI_READ_CAPACITY_10 = 0x25.toByte()
        const val SCSI_READ_10          = 0x28.toByte()
        const val SCSI_WRITE_10         = 0x2A.toByte()

        fun findBulkEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null

            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        inEp = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        outEp = ep
                    }
                }
            }

            return if (inEp != null && outEp != null) Pair(inEp, outEp) else null
        }
    }

    private val tagCounter = AtomicInteger(1000)

    @Synchronized
    fun sendScsiCommand(
        cdb: ByteArray,
        directionIn: Boolean,
        dataBuffer: ByteArray?,
        dataLength: Int,
        lun: Byte = 0
    ): Boolean {
        val tag = tagCounter.incrementAndGet()

        // 1. Build Command Block Wrapper (CBW - 31 bytes)
        val cbw = ByteBuffer.allocate(CBW_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(CBW_SIGNATURE)
        cbw.putInt(tag)
        cbw.putInt(dataLength)
        cbw.put((if (directionIn) 0x80 else 0x00).toByte()) // Flags
        cbw.put(lun)
        cbw.put(cdb.size.toByte()) // CDB Length

        val cdbBlock = ByteArray(16)
        System.arraycopy(cdb, 0, cdbBlock, 0, minOf(cdb.size, 16))
        cbw.put(cdbBlock)

        // Send CBW via Bulk OUT
        val cbwSent = connection.bulkTransfer(bulkOutEndpoint, cbw.array(), CBW_LENGTH, DEFAULT_TIMEOUT_MS)
        if (cbwSent != CBW_LENGTH) {
            Log.w(TAG, "Failed to send SCSI CBW (wrote $cbwSent of $CBW_LENGTH bytes)")
            return false
        }

        // 2. Transfer Data phase (if applicable)
        if (dataLength > 0 && dataBuffer != null) {
            if (directionIn) {
                var bytesReceived = 0
                val transferred = connection.bulkTransfer(bulkInEndpoint, dataBuffer, dataLength, DEFAULT_TIMEOUT_MS)
                if (transferred > 0) bytesReceived = transferred
                if (bytesReceived < 0) {
                    Log.w(TAG, "Failed data IN transfer for SCSI command 0x${cdb[0].toString(16)}")
                }
            } else {
                val transferred = connection.bulkTransfer(bulkOutEndpoint, dataBuffer, dataLength, DEFAULT_TIMEOUT_MS)
                if (transferred < 0) {
                    Log.w(TAG, "Failed data OUT transfer for SCSI command 0x${cdb[0].toString(16)}")
                }
            }
        }

        // 3. Receive Command Status Wrapper (CSW - 13 bytes)
        val cswBuffer = ByteArray(CSW_LENGTH)
        val cswRead = connection.bulkTransfer(bulkInEndpoint, cswBuffer, CSW_LENGTH, DEFAULT_TIMEOUT_MS)
        if (cswRead != CSW_LENGTH) {
            Log.w(TAG, "Failed to read SCSI CSW (read $cswRead of $CSW_LENGTH bytes)")
            return false
        }

        val csw = ByteBuffer.wrap(cswBuffer).order(ByteOrder.LITTLE_ENDIAN)
        val sig = csw.getInt()
        val cswTag = csw.getInt()
        val residue = csw.getInt()
        val status = csw.get().toInt() and 0xFF

        if (sig != CSW_SIGNATURE || cswTag != tag) {
            Log.w(TAG, "Invalid SCSI CSW signature (0x${sig.toString(16)}) or tag mismatch")
            return false
        }

        return status == 0 // 0 = Command Passed
    }

    /**
     * Executes SCSI INQUIRY (Opcode 0x12) to query Vendor and Product strings.
     */
    fun queryInquiry(): ScsiInquiryResult? {
        val cdb = ByteArray(6).apply {
            this[0] = SCSI_INQUIRY
            this[1] = 0x00
            this[2] = 0x00
            this[3] = 0x00
            this[4] = 36 // Allocation length
            this[5] = 0x00
        }

        val buffer = ByteArray(36)
        val success = sendScsiCommand(cdb, directionIn = true, dataBuffer = buffer, dataLength = 36)
        if (!success) return null

        val pType = buffer[0].toInt() and 0x1F
        val vendor = String(buffer, 8, 8, Charsets.US_ASCII).trim()
        val product = String(buffer, 16, 16, Charsets.US_ASCII).trim()
        val rev = String(buffer, 32, 4, Charsets.US_ASCII).trim()

        return ScsiInquiryResult(
            vendor = vendor,
            product = product,
            revision = rev,
            peripheralType = pType
        )
    }

    /**
     * Executes SCSI READ CAPACITY (10) (Opcode 0x25) to calculate capacity.
     */
    fun queryCapacity(): ScsiCapacityResult? {
        val cdb = ByteArray(10).apply {
            this[0] = SCSI_READ_CAPACITY_10
            this[1] = 0x00
            this[2] = 0x00
            this[3] = 0x00
            this[4] = 0x00
            this[5] = 0x00
            this[6] = 0x00
            this[7] = 0x00
            this[8] = 0x00
            this[9] = 0x00
        }

        val buffer = ByteArray(8)
        val success = sendScsiCommand(cdb, directionIn = true, dataBuffer = buffer, dataLength = 8)
        if (!success) return null

        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
        val lastLba = bb.getInt().toLong() and 0xFFFFFFFFL
        val blockSize = bb.getInt()

        val totalBytes = (lastLba + 1L) * blockSize.toLong()
        return ScsiCapacityResult(
            lastLba = lastLba,
            blockSize = blockSize,
            totalCapacityBytes = totalBytes
        )
    }

    /**
     * Reads sector blocks from the USB Mass Storage device using SCSI READ (10).
     */
    fun readSectors(lba: Long, sectorCount: Int, sectorSize: Int = 512): ByteArray? {
        val totalBytes = sectorCount * sectorSize
        val data = ByteArray(totalBytes)

        val cdb = ByteArray(10).apply {
            this[0] = SCSI_READ_10
            this[1] = 0x00
            // 4-byte Big Endian LBA
            this[2] = ((lba shr 24) and 0xFF).toByte()
            this[3] = ((lba shr 16) and 0xFF).toByte()
            this[4] = ((lba shr 8) and 0xFF).toByte()
            this[5] = (lba and 0xFF).toByte()
            this[6] = 0x00
            // 2-byte Big Endian Sector Count
            this[7] = ((sectorCount shr 8) and 0xFF).toByte()
            this[8] = (sectorCount and 0xFF).toByte()
            this[9] = 0x00
        }

        val success = sendScsiCommand(cdb, directionIn = true, dataBuffer = data, dataLength = totalBytes)
        return if (success) data else null
    }

    /**
     * Writes sector blocks to the USB Mass Storage device using SCSI WRITE (10).
     */
    fun writeSectors(lba: Long, data: ByteArray, sectorCount: Int, sectorSize: Int = 512): Boolean {
        val totalBytes = sectorCount * sectorSize
        if (data.size < totalBytes) return false

        val cdb = ByteArray(10).apply {
            this[0] = SCSI_WRITE_10
            this[1] = 0x00
            // 4-byte Big Endian LBA
            this[2] = ((lba shr 24) and 0xFF).toByte()
            this[3] = ((lba shr 16) and 0xFF).toByte()
            this[4] = ((lba shr 8) and 0xFF).toByte()
            this[5] = (lba and 0xFF).toByte()
            this[6] = 0x00
            // 2-byte Big Endian Sector Count
            this[7] = ((sectorCount shr 8) and 0xFF).toByte()
            this[8] = (sectorCount and 0xFF).toByte()
            this[9] = 0x00
        }

        return sendScsiCommand(cdb, directionIn = false, dataBuffer = data, dataLength = totalBytes)
    }
}
