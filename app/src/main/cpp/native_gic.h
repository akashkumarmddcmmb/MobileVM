#ifndef NATIVE_GIC_H
#define NATIVE_GIC_H

#include <cstdint>
#include <array>
#include <mutex>

/**
 * Virtual ARM Generic Interrupt Controller v2 (GICv2)
 *
 * Implements GIC Distributor (GICD) at 0x08000000 and GIC CPU Interface (GICC) at 0x08010000.
 * Complies with ARM Generic Interrupt Controller Architecture Specification (ARM IHI 0048B).
 */
class NativeGIC {
public:
    static constexpr uint64_t GICD_BASE = 0x08000000ULL;
    static constexpr uint64_t GICD_SIZE = 0x00001000ULL;
    static constexpr uint64_t GICC_BASE = 0x08010000ULL;
    static constexpr uint64_t GICC_SIZE = 0x00001000ULL;

    static constexpr uint32_t IRQ_VIRTUAL_TIMER = 27;  // PPI 27 (Virtual Timer)
    static constexpr uint32_t IRQ_PHYSICAL_TIMER = 30; // PPI 30 (Physical Timer)
    static constexpr uint32_t IRQ_UART = 33;           // SPI 1 (PL011 UART)
    static constexpr uint32_t IRQ_VIRTIO_BLK = 48;     // SPI 16 (VirtIO Block)
    static constexpr uint32_t IRQ_VIRTIO_CDROM = 49;   // SPI 17 (VirtIO CD-ROM)
    static constexpr uint32_t IRQ_VIRTIO_NET = 50;     // SPI 18 (VirtIO Net)

    NativeGIC();

    void reset();

    // Interrupt line controls
    void setInterruptPending(uint32_t irqId, bool pending);
    bool isInterruptPending(uint32_t irqId) const;
    bool isInterruptEnabled(uint32_t irqId) const;
    bool hasPendingIRQ() const;

    // CPU Interface operations
    uint32_t acknowledgeInterrupt();
    void endOfInterrupt(uint32_t irqId);

    // MMIO bus handlers
    bool isGICAddress(uint64_t address) const;
    uint32_t readMMIO32(uint64_t address);
    void writeMMIO32(uint64_t address, uint32_t value);
    uint8_t readMMIO8(uint64_t address);
    void writeMMIO8(uint64_t address, uint8_t value);

private:
    mutable std::mutex gicMutex;

    // Distributor Registers
    uint32_t distCtlr;                     // GICD_CTLR (0x000)
    uint32_t distTyper;                    // GICD_TYPER (0x004)
    uint32_t distIidr;                     // GICD_IIDR (0x008)
    std::array<uint32_t, 2> isEnabler;    // GICD_ISENABLER 0..63
    std::array<uint32_t, 2> isPendr;      // GICD_ISPENDR 0..63
    std::array<uint32_t, 2> isActivr;     // GICD_ISACTIVER 0..63
    std::array<uint8_t, 64> ipriorityr;   // GICD_IPRIORITYR 0..63
    std::array<uint8_t, 64> itargetsr;    // GICD_ITARGETSR 0..63
    std::array<uint32_t, 4> icfgr;        // GICD_ICFGR 0..63

    // CPU Interface Registers
    uint32_t cpuCtlr;                      // GICC_CTLR (0x000)
    uint32_t cpuPmr;                       // GICC_PMR (0x004) - Priority Mask
    uint32_t cpuBpr;                       // GICC_BPR (0x008) - Binary Point
    uint32_t cpuIar;                       // GICC_IAR (0x00C)
    uint32_t cpuEoir;                      // GICC_EOIR (0x010)
    uint32_t cpuHppir;                     // GICC_HPPIR (0x018)
};

#endif // NATIVE_GIC_H
