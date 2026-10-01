#ifndef NATIVE_MEMORY_H
#define NATIVE_MEMORY_H

#include <cstdint>
#include <cstddef>
#include <string>
#include "native_machine_layout.h"

class NativeMemory {
public:
    static constexpr uint64_t RAM_BASE_ADDRESS = MachineLayout::RAM_BASE; // 0x40000000ULL

    explicit NativeMemory(size_t ramSizeMb);
    ~NativeMemory();

    bool isAllocated() const { return ramBuffer != nullptr; }
    size_t getSize() const { return ramSizeBytes; }
    size_t getSizeMb() const { return ramSizeBytes / (1024 * 1024); }
    uint64_t getBaseAddress() const { return RAM_BASE_ADDRESS; }
    uint64_t getGuestPhysicalBase() const { return RAM_BASE_ADDRESS; }
    uint8_t* getRawBuffer() { return ramBuffer; }
    const uint8_t* getRawBuffer() const { return ramBuffer; }
    uint8_t* getHostVirtualAddress() { return ramBuffer; }
    const uint8_t* getHostVirtualAddress() const { return ramBuffer; }
    std::string getAllocationError() const { return allocationError; }

    bool isValidAddress(uint64_t address, size_t size) const;
    bool isValidGPA(uint64_t address, size_t size) const;
    uint8_t* getHostPtr(uint64_t address);
    const uint8_t* getHostPtr(uint64_t address) const;
    uint64_t toBufferOffset(uint64_t address) const;
    uint64_t getRamBase() const { return RAM_BASE_ADDRESS; }

    uint8_t read8(uint64_t address) const;
    void write8(uint64_t address, uint8_t value);

    uint16_t read16(uint64_t address) const;
    void write16(uint64_t address, uint16_t value);

    uint32_t read32(uint64_t address) const;
    void write32(uint64_t address, uint32_t value);

    uint64_t read64(uint64_t address) const;
    void write64(uint64_t address, uint64_t value);

    bool loadBinary(uint64_t offset, const uint8_t* data, size_t length);
    void reset();

private:
    size_t ramSizeBytes;
    uint8_t* ramBuffer;
    bool isMmapAllocated;
    std::string allocationError;
};

#endif // NATIVE_MEMORY_H
