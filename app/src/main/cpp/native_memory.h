#ifndef NATIVE_MEMORY_H
#define NATIVE_MEMORY_H

#include <cstdint>
#include <cstddef>
#include <string>

class NativeMemory {
public:
    explicit NativeMemory(size_t ramSizeMb);
    ~NativeMemory();

    bool isAllocated() const { return ramBuffer != nullptr; }
    size_t getSize() const { return ramSizeBytes; }
    size_t getSizeMb() const { return ramSizeBytes / (1024 * 1024); }
    uint8_t* getRawBuffer() { return ramBuffer; }
    const uint8_t* getRawBuffer() const { return ramBuffer; }
    std::string getAllocationError() const { return allocationError; }

    bool isValidAddress(uint64_t address, size_t size) const;

    uint8_t read8(uint64_t address) const;
    void write8(uint64_t address, uint8_t value);

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
