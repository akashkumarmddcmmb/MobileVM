#include "native_devices.h"
#include "native_memory.h"
#include "native_machine_layout.h"
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

bool NativeUART::hasRxData() {
    std::lock_guard<std::mutex> lock(uartMutex);
    return !rxBuffer.empty();
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
      blkDmaAddr(0),
      cdDeviceFeaturesSel(0),
      cdDriverFeatures(0),
      cdDriverFeaturesSel(0),
      cdQueueSel(0),
      cdQueueNum(128),
      cdQueueReady(0),
      cdQueueDescAddr(0),
      cdQueueDriverAddr(0),
      cdQueueDeviceAddr(0),
      cdLastAvailIdx(0),
      cdInterruptStatus(0),
      cdStatus(0),
      cdLba(0),
      cdSectorCount(1),
      cdDmaAddr(0) {}

bool NativeDeviceManager::isMMIOAddress(uint64_t address) const {
    return MachineLayout::isMMIO(address);
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
    const uint8_t* availPtr = memBase + memory->toBufferOffset(blkQueueDriverAddr);
    uint16_t availIdx = *reinterpret_cast<const uint16_t*>(availPtr + 2);

    uint8_t* usedPtr = memory->getRawBuffer() + memory->toBufferOffset(blkQueueDeviceAddr);
    uint16_t* usedIdxPtr = reinterpret_cast<uint16_t*>(usedPtr + 2);

    bool processedAny = false;

    while (blkLastAvailIdx != availIdx) {
        uint16_t headIdx = *reinterpret_cast<const uint16_t*>(availPtr + 4 + 2 * (blkLastAvailIdx % blkQueueNum));
        blkLastAvailIdx++;
        processedAny = true;

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

            const VirtioDesc* desc = reinterpret_cast<const VirtioDesc*>(memBase + memory->toBufferOffset(descOffset));
            if (!hasHeader) {
                if (desc->len >= sizeof(VirtioBlockReqHeader) && memory->isValidAddress(desc->addr, sizeof(VirtioBlockReqHeader))) {
                    std::memcpy(&reqHeader, memBase + memory->toBufferOffset(desc->addr), sizeof(VirtioBlockReqHeader));
                    hasHeader = true;
                } else {
                    blkReqStatus = 1;
                }
            } else if (desc->flags & 2) { // VRING_DESC_F_WRITE (Device writes to guest -> Read from disk or Status)
                if (desc->len == 1) {
                    if (memory->isValidAddress(desc->addr, 1)) {
                        uint8_t* statusPtr = memory->getRawBuffer() + memory->toBufferOffset(desc->addr);
                        *statusPtr = blkReqStatus;
                        totalWritten += 1;
                    }
                } else {
                    if (disk.isOpened() && memory->isValidAddress(desc->addr, desc->len)) {
                        std::string err;
                        uint32_t sectorCount = desc->len / 512;
                        if (sectorCount > 0) {
                            uint8_t* memDst = memory->getRawBuffer() + memory->toBufferOffset(desc->addr);
                            if (disk.readSectors(reqHeader.sector, sectorCount, memDst, err)) {
                                totalWritten += desc->len;
                            } else {
                                blkReqStatus = 1;
                            }
                        }
                    } else {
                        blkReqStatus = 1;
                    }
                }
            } else { // Host reads from guest memory -> Write to disk
                if (disk.isOpened() && memory->isValidAddress(desc->addr, desc->len)) {
                    std::string err;
                    uint32_t sectorCount = desc->len / 512;
                    if (sectorCount > 0) {
                        const uint8_t* memSrc = memBase + memory->toBufferOffset(desc->addr);
                        if (!disk.writeSectors(reqHeader.sector, sectorCount, memSrc, err)) {
                            blkReqStatus = 1;
                        }
                    }
                } else {
                    blkReqStatus = 1;
                }
            }

            if ((desc->flags & 1) == 0) { // VRING_DESC_F_NEXT not set -> End of chain
                break;
            }
            currIdx = desc->next;
        }

        // Put result on used ring
        uint16_t curUsedIdx = *usedIdxPtr;
        uint64_t usedElemOffset = blkQueueDeviceAddr + 4 + (curUsedIdx % blkQueueNum) * 8;
        if (memory->isValidAddress(usedElemOffset, 8)) {
            uint32_t* usedElemId = reinterpret_cast<uint32_t*>(memory->getRawBuffer() + memory->toBufferOffset(usedElemOffset));
            uint32_t* usedElemLen = reinterpret_cast<uint32_t*>(memory->getRawBuffer() + memory->toBufferOffset(usedElemOffset) + 4);
            *usedElemId = headIdx;
            *usedElemLen = totalWritten;
            *usedIdxPtr = curUsedIdx + 1;
        }
    }

    if (processedAny) {
        blkInterruptStatus |= 1; // Used Buffer Notification
        gic.setInterruptPending(NativeGIC::IRQ_VIRTIO_BLK, true);
    }
}

void NativeDeviceManager::processVirtioCdromQueue(NativeMemory* memory) {
    if (!memory || !cdQueueReady || cdQueueDescAddr == 0 || cdQueueDriverAddr == 0 || cdQueueDeviceAddr == 0 || cdQueueNum == 0) {
        return;
    }

    if (!memory->isValidAddress(cdQueueDriverAddr, 4 + 2 * cdQueueNum)) return;
    if (!memory->isValidAddress(cdQueueDeviceAddr, 4 + 8 * cdQueueNum)) return;

    const uint8_t* memBase = memory->getRawBuffer();
    const uint8_t* availPtr = memBase + memory->toBufferOffset(cdQueueDriverAddr);
    uint16_t availIdx = *reinterpret_cast<const uint16_t*>(availPtr + 2);

    uint8_t* usedPtr = memory->getRawBuffer() + memory->toBufferOffset(cdQueueDeviceAddr);
    uint16_t* usedIdxPtr = reinterpret_cast<uint16_t*>(usedPtr + 2);

    bool processedAny = false;

    while (cdLastAvailIdx != availIdx) {
        uint16_t headIdx = *reinterpret_cast<const uint16_t*>(availPtr + 4 + 2 * (cdLastAvailIdx % cdQueueNum));
        cdLastAvailIdx++;
        processedAny = true;

        uint16_t currIdx = headIdx;
        uint32_t totalWritten = 0;
        uint8_t cdReqStatus = 0; // 0 = VIRTIO_BLK_S_OK, 1 = VIRTIO_BLK_S_IOERR

        VirtioBlockReqHeader reqHeader = {};
        bool hasHeader = false;

        int steps = 0;
        while (steps++ < 128) {
            uint64_t descAddr = cdQueueDescAddr + currIdx * sizeof(VirtioDesc);
            if (!memory->isValidAddress(descAddr, sizeof(VirtioDesc))) {
                cdReqStatus = 1;
                break;
            }

            const VirtioDesc* desc = reinterpret_cast<const VirtioDesc*>(memory->getRawBuffer() + memory->toBufferOffset(descAddr));
            if (!hasHeader) {
                if (desc->len >= sizeof(VirtioBlockReqHeader) && memory->isValidAddress(desc->addr, sizeof(VirtioBlockReqHeader))) {
                    std::memcpy(&reqHeader, memory->getRawBuffer() + memory->toBufferOffset(desc->addr), sizeof(VirtioBlockReqHeader));
                    hasHeader = true;
                } else {
                    cdReqStatus = 1;
                }
            } else if (desc->flags & 2) { // VRING_DESC_F_WRITE (Device writes to guest -> Read from CDROM or Status)
                if (desc->len == 1) {
                    if (memory->isValidAddress(desc->addr, 1)) {
                        uint8_t* statusPtr = memory->getRawBuffer() + memory->toBufferOffset(desc->addr);
                        *statusPtr = cdReqStatus;
                        totalWritten += 1;
                    }
                } else {
                    if (cdrom.isOpened() && memory->isValidAddress(desc->addr, desc->len)) {
                        std::string err;
                        uint32_t secSize = cdrom.getSectorSize();
                        if (secSize == 0) secSize = 2048;
                        uint32_t sectorCount = desc->len / secSize;
                        if (sectorCount == 0 && desc->len > 0) sectorCount = 1;
                        uint8_t* memDst = memory->getRawBuffer() + memory->toBufferOffset(desc->addr);
                        if (cdrom.readSectors(reqHeader.sector, sectorCount, memDst, err)) {
                            totalWritten += desc->len;
                        } else {
                            cdReqStatus = 1;
                        }
                    } else {
                        cdReqStatus = 1;
                    }
                }
            } else { // Host reads from guest memory -> Write to CDROM (Rejected: CDROM is Read-Only!)
                cdReqStatus = 1;
            }

            if ((desc->flags & 1) == 0) { // VRING_DESC_F_NEXT not set -> End of chain
                break;
            }
            currIdx = desc->next;
        }

        // Put result on used ring
        uint16_t curUsedIdx = *usedIdxPtr;
        uint64_t usedElemAddr = cdQueueDeviceAddr + 4 + (curUsedIdx % cdQueueNum) * 8;
        if (memory->isValidAddress(usedElemAddr, 8)) {
            uint32_t* usedElemId = reinterpret_cast<uint32_t*>(memory->getRawBuffer() + memory->toBufferOffset(usedElemAddr));
            uint32_t* usedElemLen = reinterpret_cast<uint32_t*>(memory->getRawBuffer() + memory->toBufferOffset(usedElemAddr) + 4);
            *usedElemId = headIdx;
            *usedElemLen = totalWritten;
            *usedIdxPtr = curUsedIdx + 1;
        }
    }

    if (processedAny) {
        cdInterruptStatus |= 1;
        gic.setInterruptPending(NativeGIC::IRQ_VIRTIO_CDROM, true);
    }
}

bool NativeDeviceManager::handleMMIOWrite8(uint64_t address, uint8_t value) {
    std::lock_guard<std::mutex> lock(deviceMutex);

    if (gic.isGICAddress(address)) {
        gic.writeMMIO8(address, value);
        return true;
    }

    if (address == 0x09000000ULL || (address >= 0x09000000ULL && address < 0x09001000ULL)) {
        uart.writeByte(value);
        return true;
    }

    if (address == 0x08000000ULL) {
        if (value == 0x01) powerEvent = NativePowerEvent::PAUSE;
        else if (value == 0x02) powerEvent = NativePowerEvent::SHUTDOWN;
        else if (value == 0x03) powerEvent = NativePowerEvent::TRAP_ERROR;
        return true;
    }

    if (address >= 0x10000000ULL && address < 0x10400000ULL) {
        uint64_t pixelIndex = (address - 0x10000000ULL) / 4;
        display.writePixel(static_cast<uint32_t>(pixelIndex), static_cast<uint32_t>(value));
        return true;
    }

    return false;
}

bool NativeDeviceManager::handleMMIOWrite32(uint64_t address, uint32_t value, NativeMemory* memory) {
    std::lock_guard<std::mutex> lock(deviceMutex);

    if (gic.isGICAddress(address)) {
        gic.writeMMIO32(address, value);
        return true;
    }

    // PL011 UART MMIO range: 0x09000000 to 0x09001000
    if (address >= 0x09000000ULL && address < 0x09001000ULL) {
        uint64_t reg = address - 0x09000000ULL;
        if (reg == 0x00) { // UARTDR
            uart.writeByte(static_cast<uint8_t>(value & 0xFF));
        }
        return true;
    }

    // VirtIO GPU Display Framebuffer MMIO range: 0x10000000 to 0x10400000
    if (address >= 0x10000000ULL && address < 0x10400000ULL) {
        uint32_t pixelIndex = static_cast<uint32_t>((address - 0x10000000ULL) / 4);
        display.writePixel(pixelIndex, value);
        return true;
    }

    // Virtual Sound MMIO range: 0x10007000 to 0x10007020
    if (address >= 0x10007000ULL && address < 0x10007020ULL) {
        uint8_t pcmSample[4];
        pcmSample[0] = static_cast<uint8_t>(value & 0xFF);
        pcmSample[1] = static_cast<uint8_t>((value >> 8) & 0xFF);
        pcmSample[2] = static_cast<uint8_t>((value >> 16) & 0xFF);
        pcmSample[3] = static_cast<uint8_t>((value >> 24) & 0xFF);
        audioDevice.pushPcmBytes(pcmSample, 4);
        return true;
    }

    // VirtIO Block MMIO range: 0x0A000000 to 0x0A000200
    if (address >= 0x0A000000ULL && address < 0x0A000200ULL) {
        uint64_t reg = address - 0x0A000000ULL;
        switch (reg) {
            case 0x00: { // Direct Command / Legacy mode
                if (value == 1) { // Read sectors to memory at blkDmaAddr
                    if (memory && disk.isOpened() && memory->isValidAddress(blkDmaAddr, blkSectorCount * 512)) {
                        std::string err;
                        uint8_t* memDst = memory->getRawBuffer() + memory->toBufferOffset(blkDmaAddr);
                        bool ok = disk.readSectors(blkLba, blkSectorCount, memDst, err);
                        blkStatus = ok ? 0 : 2; // 0 = Success, 2 = Error
                    } else {
                        blkStatus = 2;
                    }
                } else if (value == 2) { // Write sectors from memory at blkDmaAddr
                    if (memory && disk.isOpened() && memory->isValidAddress(blkDmaAddr, blkSectorCount * 512)) {
                        std::string err;
                        const uint8_t* memSrc = memory->getRawBuffer() + memory->toBufferOffset(blkDmaAddr);
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
                if (blkInterruptStatus == 0) {
                    gic.setInterruptPending(NativeGIC::IRQ_VIRTIO_BLK, false);
                }
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

    // VirtIO CD-ROM MMIO range: 0x0A000200 to 0x0A000400
    if (address >= 0x0A000200ULL && address < 0x0A000400ULL) {
        uint64_t reg = address - 0x0A000200ULL;
        switch (reg) {
            case 0x00: { // Direct Command / Legacy mode
                if (value == 1) { // Read sectors to memory at cdDmaAddr
                    uint32_t secSize = cdrom.getSectorSize();
                    if (secSize == 0) secSize = 2048;
                    if (memory && cdrom.isOpened() && memory->isValidAddress(cdDmaAddr, cdSectorCount * secSize)) {
                        std::string err;
                        uint8_t* memDst = memory->getRawBuffer() + memory->toBufferOffset(cdDmaAddr);
                        bool ok = cdrom.readSectors(cdLba, cdSectorCount, memDst, err);
                        cdStatus = ok ? 0 : 2; // 0 = Success, 2 = Error
                    } else {
                        cdStatus = 2;
                    }
                } else if (value == 2) { // Write sectors (Rejected: Read-Only media!)
                    cdStatus = 2;
                } else if (value == 3) { // Flush
                    cdStatus = 0;
                }
                return true;
            }
            case 0x04: // LBA Low 32 bits
                cdLba = (cdLba & 0xFFFFFFFF00000000ULL) | (static_cast<uint64_t>(value) & 0xFFFFFFFFULL);
                return true;
            case 0x08: // LBA High 32 bits
                cdLba = (cdLba & 0x00000000FFFFFFFFULL) | (static_cast<uint64_t>(value) << 32);
                return true;
            case 0x0C: // Sector Count
                cdSectorCount = value;
                return true;
            case 0x10: // DMA Target Address Low 32 bits
                cdDmaAddr = (cdDmaAddr & 0xFFFFFFFF00000000ULL) | (static_cast<uint64_t>(value) & 0xFFFFFFFFULL);
                return true;
            case 0x14: // DeviceFeaturesSel & DMA Target Address High 32 bits
                cdDeviceFeaturesSel = value;
                cdDmaAddr = (cdDmaAddr & 0x00000000FFFFFFFFULL) | (static_cast<uint64_t>(value) << 32);
                return true;
            case 0x20: // DriverFeatures
                cdDriverFeatures = value;
                return true;
            case 0x24: // DriverFeaturesSel
                cdDriverFeaturesSel = value;
                return true;
            case 0x30: // QueueSel
                cdQueueSel = value;
                return true;
            case 0x38: // QueueNum
                cdQueueNum = value;
                return true;
            case 0x44: // QueueReady
                cdQueueReady = value;
                return true;
            case 0x50: // QueueNotify
                processVirtioCdromQueue(memory);
                return true;
            case 0x64: // InterruptACK
                cdInterruptStatus &= ~value;
                if (cdInterruptStatus == 0) {
                    gic.setInterruptPending(NativeGIC::IRQ_VIRTIO_CDROM, false);
                }
                return true;
            case 0x70: // VirtIO Status register
                cdStatus = value;
                return true;
            case 0x80: // QueueDescLow
                cdQueueDescAddr = (cdQueueDescAddr & 0xFFFFFFFF00000000ULL) | (static_cast<uint64_t>(value) & 0xFFFFFFFFULL);
                return true;
            case 0x84: // QueueDescHigh
                cdQueueDescAddr = (cdQueueDescAddr & 0x00000000FFFFFFFFULL) | (static_cast<uint64_t>(value) << 32);
                return true;
            case 0x90: // QueueDriverLow
                cdQueueDriverAddr = (cdQueueDriverAddr & 0xFFFFFFFF00000000ULL) | (static_cast<uint64_t>(value) & 0xFFFFFFFFULL);
                return true;
            case 0x94: // QueueDriverHigh
                cdQueueDriverAddr = (cdQueueDriverAddr & 0x00000000FFFFFFFFULL) | (static_cast<uint64_t>(value) << 32);
                return true;
            case 0xA0: // QueueDeviceLow
                cdQueueDeviceAddr = (cdQueueDeviceAddr & 0xFFFFFFFF00000000ULL) | (static_cast<uint64_t>(value) & 0xFFFFFFFFULL);
                return true;
            case 0xA4: // QueueDeviceHigh
                cdQueueDeviceAddr = (cdQueueDeviceAddr & 0x00000000FFFFFFFFULL) | (static_cast<uint64_t>(value) << 32);
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

    // TPM 2.0 CRB MMIO range: 0x0FED0000 to 0x0FED1000
    if (address >= 0x0FED0000ULL && address < 0x0FED1000ULL) {
        // Acknowledge TPM control / command registers without error
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
    if (gic.isGICAddress(address)) {
        return gic.readMMIO8(address);
    }
    if (address == 0x09000000ULL || (address >= 0x09000000ULL && address < 0x09001000ULL)) {
        return uart.readRxByte();
    }
    return 0;
}

uint32_t NativeDeviceManager::handleMMIORead32(uint64_t address) {
    std::lock_guard<std::mutex> lock(deviceMutex);
    if (gic.isGICAddress(address)) {
        return gic.readMMIO32(address);
    }
    // PL011 UART MMIO range: 0x09000000 to 0x09001000
    if (address >= 0x09000000ULL && address < 0x09001000ULL) {
        uint64_t reg = address - 0x09000000ULL;
        switch (reg) {
            case 0x00: { // UARTDR (Data register)
                uint8_t val = uart.readRxByte();
                if (!uart.hasRxData()) {
                    gic.setInterruptPending(NativeGIC::IRQ_UART, false);
                }
                return static_cast<uint32_t>(val);
            }
            case 0x18: { // UARTFR (Flag register)
                // Bit 7: TXFE (TX FIFO empty) = 1 when txBuffer empty
                // Bit 4: RXFE (RX FIFO empty) = 1 when rxBuffer empty
                uint32_t flags = 0;
                if (!uart.hasTxData()) flags |= (1u << 7);
                if (!uart.hasRxData()) flags |= (1u << 4);
                return flags;
            }
            case 0x24: return 1;    // UARTIBRD
            case 0x28: return 0;    // UARTFBRD
            case 0x2C: return 0x60; // UARTLCR_H (8-bit word length)
            case 0x30: return 0x301;// UARTCR (UARTEN | TXE | RXE)
            case 0x38: return 0;    // UARTIMSC
            case 0x3C: return 0;    // UARTRIS
            case 0x40: return 0;    // UARTMIS
            // ARM PrimeCell identification registers
            case 0xFE0: return 0x11;
            case 0xFE4: return 0x10;
            case 0xFE8: return 0x14;
            case 0xFEC: return 0x00;
            case 0xFF0: return 0x0D;
            case 0xFF4: return 0xF0;
            case 0xFF8: return 0x05;
            case 0xFFC: return 0xB1;
            default: return 0;
        }
    }
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
    if (address >= 0x0A000200ULL && address < 0x0A000400ULL) {
        uint64_t reg = address - 0x0A000200ULL;
        switch (reg) {
            case 0x00: return 0x74726976; // 'virt' magic
            case 0x04: return 2;          // Version 2
            case 0x08: return 2;          // Device ID: 2 = VirtIO Block / CDROM
            case 0x0C: return 0x554D4551; // Vendor ID: 'QEMU'
            case 0x10: return (cdDeviceFeaturesSel == 0) ? ((1u << 0) | (1u << 5)) : 1u; // Features: VIRTIO_BLK_F_RO bit 5 set
            case 0x34: return 128;        // QueueNumMax
            case 0x44: return cdQueueReady;
            case 0x60: return cdInterruptStatus;
            case 0x70: return cdStatus;  // Device Status
            case 0x100: return static_cast<uint32_t>(cdrom.getSectorCount() & 0xFFFFFFFFULL); // Capacity Low (sectors)
            case 0x104: return static_cast<uint32_t>(cdrom.getSectorCount() >> 32);           // Capacity High (sectors)
            
            // Legacy / Direct register mappings for test compatibility
            case 0x18: return 0x74726976; // 'virt' magic
            case 0x1C: return static_cast<uint32_t>(cdrom.getSectorCount() & 0xFFFFFFFFULL);
            case 0x20: return static_cast<uint32_t>(cdrom.getSectorCount() >> 32);
            case 0x24: return cdStatus;
            default: return 0;
        }
    }
    if (address >= 0x0B000000ULL && address < 0x0B000030ULL) {
        return inputDevice.handleMMIORead(address - 0x0B000000ULL);
    }
    // TPM 2.0 CRB MMIO range: 0x0FED0000 to 0x0FED1000
    if (address >= 0x0FED0000ULL && address < 0x0FED1000ULL) {
        uint64_t reg = address - 0x0FED0000ULL;
        switch (reg) {
            case 0x0000: return 0x00010000; // TPM Interface ID (CRB active)
            case 0x000C: return 0x01;       // LOC_STS (Locality 0 granted)
            case 0x0010: return 0;          // CRB_CTRL_REQ
            case 0x0014: return 0;          // CRB_CTRL_STS (Idle)
            case 0x001C: return 0;          // CRB_CTRL_START (Completed)
            case 0x0028: return 1024;       // CRB_CTRL_CMD_SIZE
            case 0x002C: return 0x0FED0080; // CRB_CTRL_CMD_LADDR
            case 0x0034: return 1024;       // CRB_CTRL_RSP_SIZE
            case 0x0038: return 0x0FED0080; // CRB_CTRL_RSP_ADDR
            default: return 0;
        }
    }
    return 0;
}

// --- Audio Implementation ---
NativeAudioDevice::NativeAudioDevice() {}

void NativeAudioDevice::pushPcmBytes(const uint8_t* data, size_t size) {
    if (!data || size == 0) return;
    std::lock_guard<std::mutex> lock(audioMutex);
    pcmBuffer.insert(pcmBuffer.end(), data, data + size);
}

std::vector<uint8_t> NativeAudioDevice::fetchPcmBuffer() {
    std::lock_guard<std::mutex> lock(audioMutex);
    std::vector<uint8_t> out = std::move(pcmBuffer);
    pcmBuffer.clear();
    return out;
}

bool NativeAudioDevice::hasPcmData() {
    std::lock_guard<std::mutex> lock(audioMutex);
    return !pcmBuffer.empty();
}

void NativeAudioDevice::reset() {
    std::lock_guard<std::mutex> lock(audioMutex);
    pcmBuffer.clear();
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
    gic.reset();
    audioDevice.reset();
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
    cdDeviceFeaturesSel = 0;
    cdDriverFeatures = 0;
    cdDriverFeaturesSel = 0;
    cdQueueSel = 0;
    cdQueueNum = 128;
    cdQueueReady = 0;
    cdQueueDescAddr = 0;
    cdQueueDriverAddr = 0;
    cdQueueDeviceAddr = 0;
    cdLastAvailIdx = 0;
    cdInterruptStatus = 0;
    cdStatus = 0;
    cdLba = 0;
    cdSectorCount = 1;
    cdDmaAddr = 0;
}
