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
      blkLba(0),
      blkSectorCount(1),
      blkDmaAddr(0),
      blkStatus(0) {}

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

    // VirtIO Block Storage Device MMIO range: 0x0A000000 to 0x0A000040
    if (address >= 0x0A000000ULL && address < 0x0A000040ULL) {
        uint64_t reg = address - 0x0A000000ULL;
        switch (reg) {
            case 0x00: { // Command register: 1 = READ, 2 = WRITE, 3 = FLUSH
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
            case 0x14: // DMA Target Address High 32 bits
                blkDmaAddr = (blkDmaAddr & 0x00000000FFFFFFFFULL) | (static_cast<uint64_t>(value) << 32);
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
    if (address == 0x09000000ULL) {
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
    if (address >= 0x0A000000ULL && address < 0x0A000040ULL) {
        uint64_t reg = address - 0x0A000000ULL;
        switch (reg) {
            case 0x00: return blkStatus;
            case 0x04: return static_cast<uint32_t>(blkLba & 0xFFFFFFFFULL);
            case 0x08: return static_cast<uint32_t>(blkLba >> 32);
            case 0x0C: return blkSectorCount;
            case 0x10: return static_cast<uint32_t>(blkDmaAddr & 0xFFFFFFFFULL);
            case 0x14: return static_cast<uint32_t>(blkDmaAddr >> 32);
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
    blkLba = 0;
    blkSectorCount = 1;
    blkDmaAddr = 0;
    blkStatus = 0;
}
