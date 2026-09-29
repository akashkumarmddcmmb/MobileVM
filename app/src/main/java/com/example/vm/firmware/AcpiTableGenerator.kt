package com.example.vm.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ACPI Table Generator for ARM64 Guest Platforms (ACPI 6.2).
 *
 * Generates binary compliant ACPI tables in guest memory:
 * - RSDP (Root System Description Pointer)
 * - XSDT (Extended System Description Table)
 * - MADT (Multiple APIC Description Table / GIC)
 * - FADT (Fixed ACPI Description Table)
 * - GTDT (Generic Timer Description Table)
 * - DSDT (Differentiated System Description Table)
 */
object AcpiTableGenerator {

    data class AcpiPayload(
        val rsdpAddress: Long,
        val xsdtAddress: Long,
        val madtAddress: Long,
        val fadtAddress: Long,
        val gtdtAddress: Long,
        val dsdtAddress: Long,
        val tableBytes: ByteArray
    )

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Byte {
        var sum = 0
        for (i in offset until (offset + length)) {
            sum += (data[i].toInt() and 0xFF)
        }
        return ((-sum) and 0xFF).toByte()
    }

    /**
     * Builds ACPI tables for an ARM64 virtual machine with given vCPU count and RAM base/size.
     */
    fun generateArm64AcpiTables(
        baseAddress: Long = 0x47000000L,
        numCores: Int = 2,
        ramBase: Long = 0x40000000L,
        ramSizeMb: Int = 2048,
        gicDistBase: Long = 0x08000000L,
        gicCpuBase: Long = 0x08010000L
    ): AcpiPayload {
        val totalBuffer = ByteArray(65536) // 64 KB ACPI table space
        val buf = ByteBuffer.wrap(totalBuffer).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Fixed Offsets inside ACPI table buffer
        val rsdpOffset = 0
        val xsdtOffset = 64
        val madtOffset = 256
        val fadtOffset = 512
        val gtdtOffset = 1024
        val dsdtOffset = 1536

        // --- RSDP (36 bytes) ---
        buf.position(rsdpOffset)
        buf.put("RSD PTR ".toByteArray(Charsets.US_ASCII)) // Signature
        buf.put(0.toByte()) // Checksum (computed below)
        buf.put("MOBILE".toByteArray(Charsets.US_ASCII)) // OEMID (6 bytes)
        buf.put(2.toByte()) // Revision 2.0 (ACPI 2.0+)
        buf.putInt(0) // RsdtAddress (unused in 64-bit ACPI)
        buf.putInt(36) // Length
        buf.putLong(baseAddress + xsdtOffset) // XsdtAddress (64-bit)
        buf.put(0.toByte()) // Extended Checksum
        buf.put(ByteArray(3)) // Reserved

        // Compute RSDP checksums
        totalBuffer[rsdpOffset + 8] = calculateChecksum(totalBuffer, rsdpOffset, 20)
        totalBuffer[rsdpOffset + 32] = calculateChecksum(totalBuffer, rsdpOffset, 36)

        // --- XSDT Header ---
        buf.position(xsdtOffset)
        buf.put("XSDT".toByteArray(Charsets.US_ASCII))
        val xsdtLength = 36 + (8 * 3) // Header + 3 pointers (FADT, MADT, GTDT)
        buf.putInt(xsdtLength)
        buf.put(1.toByte()) // Revision
        buf.put(0.toByte()) // Checksum
        buf.put("MOBILE".toByteArray(Charsets.US_ASCII))
        buf.put("VMXSDT  ".toByteArray(Charsets.US_ASCII))
        buf.putInt(1) // OEM Revision
        buf.put("MOBL".toByteArray(Charsets.US_ASCII)) // Creator ID
        buf.putInt(1) // Creator Revision
        buf.putLong(baseAddress + fadtOffset)
        buf.putLong(baseAddress + madtOffset)
        buf.putLong(baseAddress + gtdtOffset)
        totalBuffer[xsdtOffset + 9] = calculateChecksum(totalBuffer, xsdtOffset, xsdtLength)

        // --- MADT (ARM GIC Distributor & CPU interfaces) ---
        buf.position(madtOffset)
        buf.put("APIC".toByteArray(Charsets.US_ASCII))
        val madtLength = 44 + 24 + (numCores * 80) // Header (44) + GICD (24) + GICC (numCores * 80)
        buf.putInt(madtLength)
        buf.put(3.toByte()) // Revision 3
        buf.put(0.toByte()) // Checksum
        buf.put("MOBILE".toByteArray(Charsets.US_ASCII))
        buf.put("VMMADT  ".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        buf.put("MOBL".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        buf.putInt(0) // Local APIC Address
        buf.putInt(1) // Flags (PC-AT compatible = 0/1)

        // GIC Distributor Entry (Type 0x0C, Length 24)
        buf.put(0x0C.toByte()) // Type
        buf.put(24.toByte()) // Length
        buf.putShort(0) // Reserved
        buf.putInt(0) // GIC ID
        buf.putLong(gicDistBase) // GIC Distributor Base Address
        buf.putInt(0) // System Vector Base
        buf.put(2.toByte()) // GIC Version 2
        buf.put(ByteArray(3)) // Reserved

        // GICC Entries (Type 0x0B, Length 80) for each vCPU core
        for (i in 0 until numCores) {
            buf.put(0x0B.toByte()) // Type: GIC CPU Interface
            buf.put(80.toByte()) // Length = 80 bytes
            buf.putShort(0) // Reserved
            buf.putInt(i) // CPU Interface Number
            buf.putInt(i) // ACPI Processor UID
            buf.putInt(1) // Flags (Enabled = 1)
            buf.putInt(0) // Parking Protocol Version
            buf.putInt(23) // Performance Interrupt GSIV
            buf.putLong(0) // Parked Address
            buf.putLong(gicCpuBase) // GIC CPU Base
            buf.putLong(0) // GICV
            buf.putLong(0) // GICH
            buf.putInt(25) // VGIC Maintenance Interrupt
            buf.putLong(0) // GICR Base
            buf.putLong(i.toLong()) // MPIDR
            buf.put(0.toByte()) // Processor Power Efficiency Class
            buf.put(ByteArray(3)) // Reserved
        }
        totalBuffer[madtOffset + 9] = calculateChecksum(totalBuffer, madtOffset, madtLength)

        // --- FADT (Fixed ACPI Description Table) ---
        buf.position(fadtOffset)
        buf.put("FACP".toByteArray(Charsets.US_ASCII))
        val fadtLength = 268
        buf.putInt(fadtLength)
        buf.put(6.toByte()) // ACPI 6.0
        buf.put(0.toByte()) // Checksum
        buf.put("MOBILE".toByteArray(Charsets.US_ASCII))
        buf.put("VMFADT  ".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        buf.put("MOBL".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        buf.putInt(0) // Firmware Ctrl
        buf.putInt(0) // Dsdt32
        buf.put(0.toByte()) // Reserved
        buf.put(0.toByte()) // Preferred PM Profile
        buf.putShort(0) // SCI Interrupt
        buf.putInt(0) // SMI Command Port
        buf.position(fadtOffset + 112)
        buf.putInt(1 shl 20) // Flags: HW_REDUCED_ACPI
        buf.position(fadtOffset + 140)
        buf.putLong(baseAddress + dsdtOffset) // X_DSDT Pointer
        buf.position(fadtOffset + fadtLength)
        totalBuffer[fadtOffset + 9] = calculateChecksum(totalBuffer, fadtOffset, fadtLength)

        // --- GTDT (Generic Timer Description Table) ---
        buf.position(gtdtOffset)
        buf.put("GTDT".toByteArray(Charsets.US_ASCII))
        val gtdtLength = 104
        buf.putInt(gtdtLength)
        buf.put(2.toByte()) // Revision
        buf.put(0.toByte()) // Checksum
        buf.put("MOBILE".toByteArray(Charsets.US_ASCII))
        buf.put("VMGTDT  ".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        buf.put("MOBL".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        buf.putLong(0) // Counter Control Base
        buf.putInt(0) // Reserved
        buf.putInt(30) // Secure EL1 Timer GSIV
        buf.putInt(0) // Secure EL1 Timer Flags
        buf.putInt(27) // Non-Secure EL1 Timer GSIV
        buf.putInt(0) // Non-Secure EL1 Timer Flags
        buf.putInt(26) // Virtual Timer GSIV
        buf.putInt(0) // Virtual Timer Flags
        buf.putInt(28) // Non-Secure EL2 Timer GSIV
        buf.putInt(0) // Non-Secure EL2 Timer Flags
        totalBuffer[gtdtOffset + 9] = calculateChecksum(totalBuffer, gtdtOffset, gtdtLength)

        // --- DSDT Minimal Skeleton ---
        buf.position(dsdtOffset)
        buf.put("DSDT".toByteArray(Charsets.US_ASCII))
        val dsdtLength = 36
        buf.putInt(dsdtLength)
        buf.put(2.toByte())
        buf.put(0.toByte())
        buf.put("MOBILE".toByteArray(Charsets.US_ASCII))
        buf.put("VMDSDT  ".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        buf.put("MOBL".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        totalBuffer[dsdtOffset + 9] = calculateChecksum(totalBuffer, dsdtOffset, dsdtLength)

        return AcpiPayload(
            rsdpAddress = baseAddress + rsdpOffset,
            xsdtAddress = baseAddress + xsdtOffset,
            madtAddress = baseAddress + madtOffset,
            fadtAddress = baseAddress + fadtOffset,
            gtdtAddress = baseAddress + gtdtOffset,
            dsdtAddress = baseAddress + dsdtOffset,
            tableBytes = totalBuffer
        )
    }
}
