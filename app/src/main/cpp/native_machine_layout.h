#ifndef NATIVE_MACHINE_LAYOUT_H
#define NATIVE_MACHINE_LAYOUT_H

#include <cstdint>
#include <cstddef>

/**
 * Authoritative Machine Configuration and Physical Memory Map for ARM64 Guest Platform.
 *
 * Referenced uniformly across:
 * - CPU / VMM Execution Core
 * - KVM Hypervisor Memory Slots
 * - Device Tree Blob (DTB) Generation
 * - ACPI 6.2 Table Generation
 * - PL011 UART Console
 * - GICv2 Interrupt Controller
 * - VirtIO Block & CD-ROM Storage MMIO
 * - VirtIO Network & Input MMIO
 * - Virtual TPM 2.0 CRB Interface
 * - Guest Physical RAM and Firmware Placement
 */
namespace MachineLayout {

// --- Firmware Flash Memory (64MB ROM + 64MB NVRAM Vars) ---
constexpr uint64_t FLASH0_BASE = 0x00000000ULL;
constexpr uint64_t FLASH0_SIZE = 0x04000000ULL; // 64 MB EDK2 / UEFI Code
constexpr uint64_t FLASH1_BASE = 0x04000000ULL;
constexpr uint64_t FLASH1_SIZE = 0x04000000ULL; // 64 MB NVRAM Variables

// --- GIC Interrupt Controller (GICv2) ---
constexpr uint64_t GIC_DIST_BASE = 0x08000000ULL;
constexpr uint64_t GIC_DIST_SIZE = 0x00010000ULL; // 64 KB
constexpr uint64_t GIC_CPU_BASE  = 0x08010000ULL;
constexpr uint64_t GIC_CPU_SIZE  = 0x00010000ULL; // 64 KB

// --- Power Control ---
constexpr uint64_t ACPI_POWER_PORT = 0x08000000ULL;

// --- PL011 UART ---
constexpr uint64_t UART_BASE = 0x09000000ULL;
constexpr uint64_t UART_SIZE = 0x00001000ULL; // 4 KB
constexpr uint32_t IRQ_UART  = 33;            // SPI 1 (32 + 1)

// --- VirtIO Block MMIO ---
constexpr uint64_t VIRTIO_BLK_BASE   = 0x0A000000ULL;
constexpr uint64_t VIRTIO_BLK_SIZE   = 0x00000200ULL; // 512 bytes
constexpr uint32_t IRQ_VIRTIO_BLK    = 48;            // SPI 16 (32 + 16)

// --- VirtIO CD-ROM MMIO ---
constexpr uint64_t VIRTIO_CDROM_BASE = 0x0A000200ULL;
constexpr uint64_t VIRTIO_CDROM_SIZE = 0x00000200ULL; // 512 bytes
constexpr uint32_t IRQ_VIRTIO_CDROM  = 49;            // SPI 17 (32 + 17)

// --- VirtIO Network MMIO ---
constexpr uint64_t VIRTIO_NET_BASE   = 0x0A000400ULL;
constexpr uint64_t VIRTIO_NET_SIZE   = 0x00000200ULL; // 512 bytes
constexpr uint32_t IRQ_VIRTIO_NET    = 50;            // SPI 18 (32 + 18)

// --- VirtIO Input MMIO ---
constexpr uint64_t VIRTIO_INPUT_BASE = 0x0B000000ULL;
constexpr uint64_t VIRTIO_INPUT_SIZE = 0x00000200ULL; // 512 bytes
constexpr uint32_t IRQ_VIRTIO_INPUT  = 51;            // SPI 19 (32 + 19)

// --- TPM 2.0 CRB Interface ---
constexpr uint64_t TPM_CRB_BASE = 0x0FED0000ULL;
constexpr uint64_t TPM_CRB_SIZE = 0x00001000ULL; // 4 KB
constexpr uint32_t IRQ_TPM      = 52;            // SPI 20 (32 + 20)

// --- VirtIO GPU Display Framebuffer ---
constexpr uint64_t DISPLAY_FB_BASE = 0x10000000ULL;
constexpr uint64_t DISPLAY_FB_SIZE = 0x00400000ULL; // 4 MB (1024x768x4)
constexpr uint32_t IRQ_DISPLAY     = 53;            // SPI 21 (32 + 21)

// --- Guest Physical RAM ---
constexpr uint64_t RAM_BASE = 0x40000000ULL; // 1 GB boundary per ARM virt standard

// --- Memory Allocator Offsets Inside Guest Physical RAM ---
constexpr uint64_t KERNEL_LOAD_OFFSET = 0x00080000ULL; // +512 KB offset
constexpr uint64_t INITRD_LOAD_OFFSET = 0x04000000ULL; // +64 MB offset
constexpr uint64_t ACPI_LOAD_OFFSET   = 0x07000000ULL; // +112 MB offset
constexpr uint64_t DTB_LOAD_OFFSET    = 0x07F00000ULL; // +127 MB offset

// --- ARM Generic Timer PPIs ---
constexpr uint32_t TIMER_SEC_PHYS_PPI    = 29;
constexpr uint32_t TIMER_NONSEC_PHYS_PPI = 30;
constexpr uint32_t TIMER_VIRT_PPI        = 27;
constexpr uint32_t TIMER_HYP_PPI         = 26;

inline bool isMMIO(uint64_t addr) {
    if (addr >= GIC_DIST_BASE && addr < GIC_CPU_BASE + GIC_CPU_SIZE) return true;
    if (addr == ACPI_POWER_PORT) return true;
    if (addr >= UART_BASE && addr < UART_BASE + UART_SIZE) return true;
    if (addr >= VIRTIO_BLK_BASE && addr < VIRTIO_BLK_BASE + VIRTIO_BLK_SIZE) return true;
    if (addr >= VIRTIO_CDROM_BASE && addr < VIRTIO_CDROM_BASE + VIRTIO_CDROM_SIZE) return true;
    if (addr >= VIRTIO_NET_BASE && addr < VIRTIO_NET_BASE + VIRTIO_NET_SIZE) return true;
    if (addr >= VIRTIO_INPUT_BASE && addr < VIRTIO_INPUT_BASE + VIRTIO_INPUT_SIZE) return true;
    if (addr >= TPM_CRB_BASE && addr < TPM_CRB_BASE + TPM_CRB_SIZE) return true;
    if (addr >= DISPLAY_FB_BASE && addr < DISPLAY_FB_BASE + DISPLAY_FB_SIZE) return true;
    return false;
}

} // namespace MachineLayout

#endif // NATIVE_MACHINE_LAYOUT_H
