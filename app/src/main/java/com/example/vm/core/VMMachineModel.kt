package com.example.vm.core

import com.example.vm.cpu.GuestArchitecture

/**
 * Authoritative Machine Configuration Model for ARM64 Guest Platforms.
 *
 * Implements one unified specification defining the virtual hardware layout
 * shared identically across Kotlin components, Native C++ drivers, DTB generation,
 * and ACPI table generation.
 */
object VMMachineModel {
    const val MACHINE_TYPE = "linux,dummy-virt"
    val ARCHITECTURE = GuestArchitecture.ARM64

    // Firmware Flash Memory (64MB ROM + 64MB NVRAM Vars)
    const val FLASH0_BASE = 0x00000000L
    const val FLASH0_SIZE = 0x04000000L // 64 MB
    const val FLASH1_BASE = 0x04000000L
    const val FLASH1_SIZE = 0x04000000L // 64 MB

    // GIC Interrupt Controller (GICv2)
    const val GIC_DIST_BASE = 0x08000000L
    const val GIC_DIST_SIZE = 0x00010000L // 64 KB
    const val GIC_CPU_BASE = 0x08010000L
    const val GIC_CPU_SIZE = 0x00010000L // 64 KB

    // Power Control Port
    const val ACPI_POWER_PORT = 0x08000000L

    // PL011 UART
    const val UART_BASE = 0x09000000L
    const val UART_SIZE = 0x00001000L // 4 KB
    const val IRQ_UART = 33

    // VirtIO Block (Disk)
    const val VIRTIO_BLK_BASE = 0x0A000000L
    const val VIRTIO_BLK_SIZE = 0x00000200L // 512 bytes
    const val IRQ_VIRTIO_BLK = 48

    // VirtIO CD-ROM (ISO)
    const val VIRTIO_CDROM_BASE = 0x0A000200L
    const val VIRTIO_CDROM_SIZE = 0x00000200L // 512 bytes
    const val IRQ_VIRTIO_CDROM = 49

    // VirtIO Network
    const val VIRTIO_NET_BASE = 0x0A000400L
    const val VIRTIO_NET_SIZE = 0x00000200L // 512 bytes
    const val IRQ_VIRTIO_NET = 50

    // VirtIO Input
    const val VIRTIO_INPUT_BASE = 0x0B000000L
    const val VIRTIO_INPUT_SIZE = 0x00000200L // 512 bytes
    const val IRQ_VIRTIO_INPUT = 51

    // TPM 2.0 CRB Interface
    const val TPM_CRB_BASE = 0x0FED0000L
    const val TPM_CRB_SIZE = 0x00001000L // 4 KB
    const val IRQ_TPM = 52

    // VirtIO GPU Display Framebuffer
    const val DISPLAY_FB_BASE = 0x10000000L
    const val DISPLAY_FB_SIZE = 0x00400000L // 4 MB (1024x768x4)
    const val IRQ_DISPLAY = 53

    // Guest Physical RAM
    const val RAM_BASE = 0x40000000L // 1 GB Boundary

    // Memory Allocator Offsets inside Guest Physical RAM
    const val KERNEL_LOAD_OFFSET = 0x00080000L // +512 KB
    const val INITRD_LOAD_OFFSET = 0x04000000L // +64 MB
    const val ACPI_LOAD_OFFSET   = 0x07000000L // +112 MB
    const val DTB_LOAD_OFFSET    = 0x07F00000L // +127 MB

    // ARM Generic Timer PPIs
    const val TIMER_SEC_PHYS_PPI    = 29
    const val TIMER_NONSEC_PHYS_PPI = 30
    const val TIMER_VIRT_PPI        = 27
    const val TIMER_HYP_PPI         = 26

    /**
     * Checks if a guest physical address falls in any MMIO device window.
     */
    fun isMmioAddress(address: Long): Boolean {
        if (address in GIC_DIST_BASE until (GIC_CPU_BASE + GIC_CPU_SIZE)) return true
        if (address == ACPI_POWER_PORT) return true
        if (address in UART_BASE until (UART_BASE + UART_SIZE)) return true
        if (address in VIRTIO_BLK_BASE until (VIRTIO_BLK_BASE + VIRTIO_BLK_SIZE)) return true
        if (address in VIRTIO_CDROM_BASE until (VIRTIO_CDROM_BASE + VIRTIO_CDROM_SIZE)) return true
        if (address in VIRTIO_NET_BASE until (VIRTIO_NET_BASE + VIRTIO_NET_SIZE)) return true
        if (address in VIRTIO_INPUT_BASE until (VIRTIO_INPUT_BASE + VIRTIO_INPUT_SIZE)) return true
        if (address in TPM_CRB_BASE until (TPM_CRB_BASE + TPM_CRB_SIZE)) return true
        if (address in DISPLAY_FB_BASE until (DISPLAY_FB_BASE + DISPLAY_FB_SIZE)) return true
        return false
    }

    /**
     * Validates that requested RAM size does not overlap any device MMIO range.
     */
    fun validateMemoryLayout(ramSizeMb: Int): Boolean {
        val ramSizeBytes = ramSizeMb.toLong() * 1024L * 1024L
        val ramEnd = RAM_BASE + ramSizeBytes
        // Verify overflow
        if (ramEnd < RAM_BASE) return false
        // Verify RAM begins strictly above all MMIO peripheral devices
        if (RAM_BASE < DISPLAY_FB_BASE + DISPLAY_FB_SIZE) return false
        return true
    }
}
