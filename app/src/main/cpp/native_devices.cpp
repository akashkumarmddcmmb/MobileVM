#include "native_devices.h"
#include "native_memory.h"
#include <cstring>

// --- UART Implementation ---
NativeUART::NativeUART() {}

void NativeUART::writeByte(uint8_t byte) {
    std::lock_guard<std::mutex> lock(uartMutex);
    txBuffer.push_back(byte);
}

bool NativeUART::hasTxData() {
    std::lock_guard<std::mutex> lock(uartMutex);
    return !txBuffer.empty();
}

std::vector<uint8_t> NativeUART::readTxBuffer() {
    std::lock_guard<std::mutex> lock(uartMutex);
    std::vector<uint8_t> out = std::move(txBuffer);
    txBuffer.clear();
    return out;
}

void NativeUART::queueRxByte(uint8_t byte) {
    std::lock_guard<std::mutex> lock(uartMutex);
    rxBuffer.push_back(byte);
}

uint8_t NativeUART::readRxByte() {
    std::lock_guard<std::mutex> lock(uartMutex);
    if (rxBuffer.empty()) return 0;
    uint8_t b = rxBuffer.front();
    rxBuffer.erase(rxBuffer.begin());
    return b;
}

void NativeUART::reset() {
    std::lock_guard<std::mutex> lock(uartMutex);
    txBuffer.clear();
    rxBuffer.clear();
}

// --- Display Implementation ---
NativeDisplay::NativeDisplay(uint32_t w, uint32_t h) : width(w), height(h) {
    framebuffer.resize(width * height, 0xFF000000); // opaque black
}

void NativeDisplay::writePixel(uint32_t index, uint32_t argb) {
    if (index < framebuffer.size()) {
        framebuffer[index] = argb;
    }
}

uint32_t NativeDisplay::readPixel(uint32_t index) {
    if (index < framebuffer.size()) {
        return framebuffer[index];
    }
    return 0;
}

void NativeDisplay::reset() {
    std::fill(framebuffer.begin(), framebuffer.end(), 0xFF000000);
}

// --- Device Manager MMIO Bus ---
NativeDeviceManager::NativeDeviceManager()
    : powerEvent(NativePowerEvent::NONE),
      blkDeviceFeaturesSel(0),
      blkDriverFeatures(0),
      blkDriverFeaturesSel(0),
      blkQueueSel(0),
      blkQueueNum(128),
      blkQueueReady(0),
      blkQueueDescAddr(0),
      blkQueueDriverAddr(0),
      blkQueueDeviceAddr(0),
      blkLastAvailIdx(0),
      blkInterruptStatus(0),
      blkStatus(0),
      blkLba(0),
      blkSectorCount(1),
      blkDmaAddr(0) {}

bool NativeDeviceManager::isMMIOAddress(uint64_t address) const {
    if (address == 0x08000000ULL) return true; // ACPI
    if (address >= 0x09000000ULL && address < 0x09001000ULL) return true; // PL011 UART
    if (address >= 0x0A000000ULL && address < 0x0A000200ULL) return true; // VirtIO Block
    if (address >= 0x0B000000ULL && address < 0x0B000030ULL) return true; // VirtIO Input
    if (address >= 0x10000000ULL && address < 0x10400000ULL) return true; // VirtIO GPU Display Framebuffer
    return false;
}

struct VirtioDesc {
    uint64_t addr;
    uint32_t len;
    uint16_t flags;
    uint16_t next;
} __attribute__((packed));

struct VirtioBlockReqHeader {
    uint32_t type;
    uint32_t reserved;
    uint64_t sector;
} __attribute__((packed));

void NativeDeviceManager::processVirtioBlockQueue(NativeMemory* memory) {
    if (!memory || !blkQueueReady || blkQueueDescAddr == 0 || blkQueueDriverAddr == 0 || blkQueueDeviceAddr == 0 || blkQueueNum == 0) {
        return;
    }

    if (!memory->isValidAddress(blkQueueDriverAddr, 4 + 2 * blkQueueNum)) return;
    if (!memory->isValidAddress(blkQueueDeviceAddr, 4 + 8 * blkQueueNum)) return;

    const uint8_t* memBase = memory->getRawBuffer();
    const uint8_t* availPtr = memBase + blkQueueDriverAddr;
    uint16_t availIdx = *reinterpret_cast<const uint16_t*>(availPtr + 2);

    uint8_t* usedPtr = memory->getRawBuffer() + blkQueueDeviceAddr;
    uint16_t* usedIdxPtr = reinterpret_cast<uint16_t*>(usedPtr + 2);

    while (blkLastAvailIdx != availIdx) {
        uint16_t headIdx = *reinterpret_cast<const uint16_t*>(availPtr + 4 + 2 * (blkLastAvailIdx % blkQueueNum));
        blkLastAvailIdx++;

        uint16_t currIdx = headIdx;
        uint32_t totalWritten = 0;
        uint8_t blkReqStatus = 0; // 0 = VIRTIO_BLK_S_OK, 1 = VIRTIO_BLK_S_IOERR

        VirtioBlockReqHeader reqHeader = {};
        bool hasHeader = false;

        int steps = 0;
        while (steps++ < 128) {
            uint64_t descOffset = blkQueueDescAddr + currIdx * sizeof(VirtioDesc);
            if (!memory->isValidAddress(descOffset, sizeof(VirtioDesc))) {
                blkReqStatus = 1;
                break;
            }

            const VirtioDesc* desc = reinterpret_cast<const VirtioDesc*>(memBase + descOffset);
            if (!hasHeader) {
                if (desc->len >= sizeof(VirtioBlockReqHeader) && memory->isValidAddress(desc->addr, sizeof(VirtioBlockReqHeader))) {
                    std::memcpy(&reqHeader, memBase + desc->addr, sizeof(VirtioBlockReqHeader));
                    hasHeader = true;
                } else {
                    blkReqStatus = 1;
                }
            } else if (desc->flags & 2) { // VRING_DESC_F_WRITE (Device writes to guest -> Read from disk or Status)
                if (desc->len == 1) {
                    if (memory->isValidAddress(desc->addr, 1)) {
                        uint8_t* statusPtr = memory->getRawBuffer() + desc->addr;
                        *statusPtr = blkReqStatus;
                        totalWritten += 1;
                    }
                } else {
                    if (disk.isOpened() && memory->isValidAddress(desc->addr, desc->len)) {
                        std::string err;
                        uint32_t sectorCount = desc->len / 512;
                        if (sectorCount > 0) {
                            uint8_t* dst = memory->getRawBuffer() + desc->addr;
                            if (disk.readSectors(reqHeader.sector, sectorCount, dst, err)) {
                                totalWritten += desc->len;
                            } else {
                                blkReqStatus = 1;
                            }
                        }
                    } else {
                        blkReqStatus = 1;
                    }
                }
            } else { // Write to disk
                if (disk.isOpened() && memory->isValidAddress(desc->addr, desc->len)) {
                    std::string err;
                    uint32_t sectorCount = desc->len / 512;
                    if (sectorCount > 0) {
                        const uint8_t* src = memBase + desc->addr;
                        if (!disk.writeSectors(reqHeader.sector, sectorCount, src, err)) {
                            blkReqStatus = 1;
                        }
                    }
                } else {
                    blkReqStatus = 1;
                }
            }

            if (!(desc->flags & 1)) { // No NEXT descriptor
                break;
            }
            currIdx = desc->next;
        }

        uint16_t curUsedIdx = *usedIdxPtr;
        uint64_t elemOffset = 4 + 8 * (curUsedIdx % blkQueueNum);
        uint32_t* usedElemId = reinterpret_cast<uint32_t*>(usedPtr + elemOffset);
        uint32_t* usedElemLen = reinterpret_cast<uint32_t*>(usedPtr + elemOffset + 4);
        *usedElemId = headIdx;
        *usedElemLen = totalWritten;
        *usedIdxPtr = curUsedIdx + 1;
    }

    blkInterruptStatus |= 1;
}

bool NativeDeviceManager::handleMMIOWrite8(uint64_t address, uint8_t value) {
    std::lock_guard<std::mutex> lock(deviceMutex);
    // PL011 UART TX register at 0x09000000
    if (address == 0x09000000ULL || (address >= 0x09000000ULL && address < 0x09001000ULL)) {
        uart.writeByte(value);
        return true;
    }
    // ACPI Power Controller
    if (address == 0x08000000ULL) {
        if (value == 0x01) powerEvent = NativePowerEvent::PAUSE;
        else if (value == 0x02) powerEvent = NativePowerEvent::SHUTDOWN;
        else if (value == 0x03) powerEvent = NativePowerEvent::TRAP_ERROR;
        return true;
    }
    return false;
}

bool NativeDeviceManager::handleMMIOWrite32(uint64_t address, uint32_t value, NativeMemory* memory) {
    std::lock_guard<std::mutex> lock(deviceMutex);

    // VirtIO GPU Display Framebuffer Base MMIO range: 0x10000000 to 0x10400000
    if (address >= 0x10000000ULL && address < 0x10400000ULL) {
        uint32_t pixelIndex = static_cast<uint32_t>((address - 0x10000000ULL) / 4);
        display.writePixel(pixelIndex, value);
        return true;
    }

    // VirtIO Block Storage Device MMIO range: 0x0A000000 to 0x0A000200
    if (address >= 0x0A000000ULL && address < 0x0A000200ULL) {
        uint64_t reg = address - 0x0A000000ULL;
        switch (reg) {
            case 0x00: { // Command register / Magic write: 1 = READ, 2 = WRITE, 3 = FLUSH
                if (value == 1) { // Read sectors into memory at blkDmaAddr
                    if (memory && disk.isOpened() && memory->isValidAddress(blkDmaAddr, blkSectorCount * 512)) {
                        std::string err;
                        uint8_t* memDst = memory->getRawBuffer() + blkDmaAddr;
                        bool ok = disk.readSectors(blkLba, blkSectorCount, memDst, err);
                        blkStatus = ok ? 0 : 2; // 0 = Success, 2 = Error
                    } else {
                        blkStatus = 2;
                    }
                } else if (value == 2) { // Write sectors from memory at blkDmaAddr
                    if (memory && disk.isOpened() && memory->isValidAddress(blkDmaAddr, blkSectorCount * 512)) {
                        std::string err;
                        const uint8_t* memSrc = memory->getRawBuffer() + blkDmaAddr;
                        bool ok = disk.writeSectors(blkLba, blkSectorCount, memSrc, err);
                        blkStatus = ok ? 0 : 2;
                    } else {
                        blkStatus = 2;
                    }
                } else if (value == 3) { // Flush
                    disk.flush();
                    blkStatus = 0;
                }
                return true;
            }
            case 0x04: // LBA Low 32 bits
                blkLba = (blkLba & 0xFFFFFFFF00000000ULL) | (static_cast<uint64_t>(value) & 0xFFFFFFFFULL);
                return true;
            case 0x08: // LBA High 32 bits
                blkLba = (blkLba & 0x00000000FFFFFFFFULL) | (static_cast<uint64_t>(value) << 32);
                return true;
            case 0x0C: // Sector Count
                blkSectorCount = value;
                return true;
            case 0x10: // DMA Target Address Low 32 bits
                blkDmaAddr = (blkDmaAddr & 0xFFFFFFFF00000000ULL) | (static_cast<uint64_t>(value) & 0xFFFFFFFFULL);
                return true;
            case 0x14: // DeviceFeaturesSel & DMA Target Address High 32 bits
                blkDeviceFeaturesSel = value;
                blkDmaAddr = (blkDmaAddr & 0x00000000FFFFFFFFULL) | (static_cast<uint64_t>(value) << 32);
                return true;
            case 0x20: // DriverFeatures
                blkDriverFeatures = value;
                return true;
            case 0x24: // DriverFeaturesSel
                blkDriverFeaturesSel = value;
                return true;
            case 0x30: // QueueSel
                blkQueueSel = value;
                return true;
            case 0x38: // QueueNum
                blkQueueNum = value;
                return true;
            case 0x44: // QueueReady
                blkQueueReady = value;
                return true;
            case 0x50: // QueueNotify
                processVirtioBlockQueue(memory);
                return true;
            case 0x64: // InterruptACK
                blkInterruptStatus &= ~value;
                return true;
            case 0x70: // VirtIO Status register
                blkStatus = value;
                return true;
            case 0x80: // QueueDescLow
                blkQueueDescAddr = (blkQueueDescAddr & 0xFFFFFFFF00000000ULL) | (static_cast<uint64_t>(value) & 0xFFFFFFFFULL);
                return true;
            case 0x84: // QueueDescHigh
                blkQueueDescAddr = (blkQueueDescAddr & 0x00000000FFFFFFFFULL) | (static_cast<uint64_t>(value) << 32);
                return true;
            case 0x90: // QueueDriverLow
                blkQueueDriverAddr = (blkQueueDriverAddr & 0xFFFFFFFF00000000ULL) | (static_cast<uint64_t>(value) & 0xFFFFFFFFULL);
                return true;
            case 0x94: // QueueDriverHigh
                blkQueueDriverAddr = (blkQueueDriverAddr & 0x00000000FFFFFFFFULL) | (static_cast<uint64_t>(value) << 32);
                return true;
            case 0xA0: // QueueDeviceLow
                blkQueueDeviceAddr = (blkQueueDeviceAddr & 0xFFFFFFFF00000000ULL) | (static_cast<uint64_t>(value) & 0xFFFFFFFFULL);
                return true;
            case 0xA4: // QueueDeviceHigh
                blkQueueDeviceAddr = (blkQueueDeviceAddr & 0x00000000FFFFFFFFULL) | (static_cast<uint64_t>(value) << 32);
                return true;
            default:
                return true;
        }
    }

    // VirtIO Input Device MMIO range: 0x0B000000 to 0x0B000030
    if (address >= 0x0B000000ULL && address < 0x0B000030ULL) {
        inputDevice.handleMMIOWrite(address - 0x0B000000ULL, value);
        return true;
    }

    if (address == 0x08000000ULL) {
        if (value == 0x01) powerEvent = NativePowerEvent::PAUSE;
        else if (value == 0x02) powerEvent = NativePowerEvent::SHUTDOWN;
        else if (value == 0x03) powerEvent = NativePowerEvent::TRAP_ERROR;
        return true;
    }
    return false;
}

uint8_t NativeDeviceManager::handleMMIORead8(uint64_t address) {
    std::lock_guard<std::mutex> lock(deviceMutex);
    if (address == 0x09000000ULL || (address >= 0x09000000ULL && address < 0x09001000ULL)) {
        return uart.readRxByte();
    }
    return 0;
}

uint32_t NativeDeviceManager::handleMMIORead32(uint64_t address) {
    std::lock_guard<std::mutex> lock(deviceMutex);
    if (address >= 0x10000000ULL && address < 0x10400000ULL) {
        uint32_t pixelIndex = static_cast<uint32_t>((address - 0x10000000ULL) / 4);
        return display.readPixel(pixelIndex);
    }
    if (address >= 0x0A000000ULL && address < 0x0A000200ULL) {
        uint64_t reg = address - 0x0A000000ULL;
        switch (reg) {
            case 0x00: return 0x74726976; // 'virt' magic (Standard VirtIO MMIO)
            case 0x04: return 2;          // Version 2 (Modern VirtIO MMIO)
            case 0x08: return 2;          // Device ID: 2 = VirtIO Block
            case 0x0C: return 0x554D4551; // Vendor ID: 'QEMU'
            case 0x10: return (blkDeviceFeaturesSel == 0) ? ((1u << 0) | (1u << 5)) : 1u; // Device Features
            case 0x34: return 128;        // QueueNumMax
            case 0x44: return blkQueueReady;
            case 0x60: return blkInterruptStatus;
            case 0x70: return blkStatus;  // Device Status
            case 0x100: return static_cast<uint32_t>(disk.getSectorCount() & 0xFFFFFFFFULL); // Capacity Low (sectors)
            case 0x104: return static_cast<uint32_t>(disk.getSectorCount() >> 32);           // Capacity High (sectors)
            
            // Legacy / Direct register mappings for test compatibility
            case 0x18: return 0x74726976; // 'virt' magic
            case 0x1C: return static_cast<uint32_t>(disk.getSectorCount() & 0xFFFFFFFFULL);
            case 0x20: return static_cast<uint32_t>(disk.getSectorCount() >> 32);
            case 0x24: return blkStatus;
            default: return 0;
        }
    }
    if (address >= 0x0B000000ULL && address < 0x0B000030ULL) {
        return inputDevice.handleMMIORead(address - 0x0B000000ULL);
    }
    return 0;
}

NativePowerEvent NativeDeviceManager::pollPowerEvent() {
    std::lock_guard<std::mutex> lock(deviceMutex);
    NativePowerEvent ev = powerEvent;
    powerEvent = NativePowerEvent::NONE;
    return ev;
}

void NativeDeviceManager::resetAll() {
    std::lock_guard<std::mutex> lock(deviceMutex);
    uart.reset();
    display.reset();
    inputDevice.clear();
    powerEvent = NativePowerEvent::NONE;
    blkDeviceFeaturesSel = 0;
    blkDriverFeatures = 0;
    blkDriverFeaturesSel = 0;
    blkQueueSel = 0;
    blkQueueNum = 128;
    blkQueueReady = 0;
    blkQueueDescAddr = 0;
    blkQueueDriverAddr = 0;
    blkQueueDeviceAddr = 0;
    blkLastAvailIdx = 0;
    blkInterruptStatus = 0;
    blkStatus = 0;
    blkLba = 0;
    blkSectorCount = 1;
    blkDmaAddr = 0;
}
