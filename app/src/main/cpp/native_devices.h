#ifndef NATIVE_DEVICES_H
#define NATIVE_DEVICES_H

#include <cstdint>
#include <vector>
#include <string>
#include <mutex>
#include "native_disk.h"
#include "native_input.h"

enum class NativePowerEvent {
    NONE = 0,
    PAUSE = 1,
    SHUTDOWN = 2,
    TRAP_ERROR = 3
};

class NativeUART {
public:
    NativeUART();
    void writeByte(uint8_t byte);
    bool hasTxData();
    std::vector<uint8_t> readTxBuffer();
    void queueRxByte(uint8_t byte);
    uint8_t readRxByte();
    void reset();

private:
    std::mutex uartMutex;
    std::vector<uint8_t> txBuffer;
    std::vector<uint8_t> rxBuffer;
};

class NativeDisplay {
public:
    NativeDisplay(uint32_t width = 1024, uint32_t height = 768);
    void writePixel(uint32_t index, uint32_t argb);
    uint32_t readPixel(uint32_t index);
    const uint32_t* getFramebuffer() const { return framebuffer.data(); }
    uint32_t getWidth() const { return width; }
    uint32_t getHeight() const { return height; }
    size_t getPixelCount() const { return framebuffer.size(); }
    void reset();

private:
    uint32_t width;
    uint32_t height;
    std::vector<uint32_t> framebuffer;
};

class NativeMemory; // Forward declaration

class NativeDeviceManager {
public:
    NativeDeviceManager();

    bool handleMMIOWrite8(uint64_t address, uint8_t value);
    bool handleMMIOWrite32(uint64_t address, uint32_t value, NativeMemory* memory = nullptr);
    uint8_t handleMMIORead8(uint64_t address);
    uint32_t handleMMIORead32(uint64_t address);

    bool isMMIOAddress(uint64_t address) const;

    NativeUART& getUART() { return uart; }
    const NativeUART& getUART() const { return uart; }
    NativeDisplay& getDisplay() { return display; }
    const NativeDisplay& getDisplay() const { return display; }
    NativeDisk& getDisk() { return disk; }
    const NativeDisk& getDisk() const { return disk; }
    NativeInputDevice& getInput() { return inputDevice; }
    const NativeInputDevice& getInput() const { return inputDevice; }

    NativePowerEvent pollPowerEvent();
    void resetAll();

private:
    void processVirtioBlockQueue(NativeMemory* memory);

    NativeUART uart;
    NativeDisplay display;
    NativeDisk disk;
    NativeInputDevice inputDevice;
    NativePowerEvent powerEvent;
    std::mutex deviceMutex;

    // VirtIO-Block MMIO Modern Registers & Split-Virtqueue state
    uint32_t blkDeviceFeaturesSel;
    uint32_t blkDriverFeatures;
    uint32_t blkDriverFeaturesSel;
    uint32_t blkQueueSel;
    uint32_t blkQueueNum;
    uint32_t blkQueueReady;
    uint64_t blkQueueDescAddr;
    uint64_t blkQueueDriverAddr;
    uint64_t blkQueueDeviceAddr;
    uint16_t blkLastAvailIdx;
    uint32_t blkInterruptStatus;
    uint32_t blkStatus;

    // Direct DMA MMIO Registers for test compatibility
    uint64_t blkLba;
    uint32_t blkSectorCount;
    uint64_t blkDmaAddr;
};

#endif // NATIVE_DEVICES_H
