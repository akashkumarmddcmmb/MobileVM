#include "native_memory.h"
#include <sys/mman.h>
#include <cstdlib>
#include <cstring>
#include <cerrno>

NativeMemory::NativeMemory(size_t ramSizeMb)
    : ramSizeBytes(ramSizeMb * 1024ULL * 1024ULL),
      ramBuffer(nullptr),
      isMmapAllocated(false) {

    if (ramSizeMb == 0) {
        allocationError = "Cannot allocate 0 MB guest memory";
        return;
    }

    // Allocate page-aligned guest virtual memory via mmap
    void* ptr = mmap(
        nullptr,
        ramSizeBytes,
        PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1,
        0
    );

    if (ptr != MAP_FAILED) {
        ramBuffer = static_cast<uint8_t*>(ptr);
        isMmapAllocated = true;
        std::memset(ramBuffer, 0, ramSizeBytes);
    } else {
        // Fallback to posix_memalign
        void* memAlignPtr = nullptr;
        if (posix_memalign(&memAlignPtr, 4096, ramSizeBytes) == 0 && memAlignPtr != nullptr) {
            ramBuffer = static_cast<uint8_t*>(memAlignPtr);
            isMmapAllocated = false;
            std::memset(ramBuffer, 0, ramSizeBytes);
        } else {
            int err = errno;
            allocationError = "mmap guest RAM allocation of " + std::to_string(ramSizeMb) + 
                              " MB failed: " + std::string(strerror(err));
            ramBuffer = nullptr;
        }
    }
}

NativeMemory::~NativeMemory() {
    if (ramBuffer) {
        if (isMmapAllocated) {
            munmap(ramBuffer, ramSizeBytes);
        } else {
            free(ramBuffer);
        }
        ramBuffer = nullptr;
    }
}

bool NativeMemory::isValidAddress(uint64_t address, size_t size) const {
    if (!ramBuffer) return false;
    return (address + size <= ramSizeBytes);
}

uint8_t NativeMemory::read8(uint64_t address) const {
    if (!isValidAddress(address, 1)) {
        return 0;
    }
    return ramBuffer[address];
}

void NativeMemory::write8(uint64_t address, uint8_t value) {
    if (isValidAddress(address, 1)) {
        ramBuffer[address] = value;
    }
}

uint32_t NativeMemory::read32(uint64_t address) const {
    if (!isValidAddress(address, 4)) {
        return 0;
    }
    uint32_t val;
    std::memcpy(&val, &ramBuffer[address], sizeof(uint32_t));
    return val;
}

void NativeMemory::write32(uint64_t address, uint32_t value) {
    if (isValidAddress(address, 4)) {
        std::memcpy(&ramBuffer[address], &value, sizeof(uint32_t));
    }
}

uint64_t NativeMemory::read64(uint64_t address) const {
    if (!isValidAddress(address, 8)) {
        return 0;
    }
    uint64_t val;
    std::memcpy(&val, &ramBuffer[address], sizeof(uint64_t));
    return val;
}

void NativeMemory::write64(uint64_t address, uint64_t value) {
    if (isValidAddress(address, 8)) {
        std::memcpy(&ramBuffer[address], &value, sizeof(uint64_t));
    }
}

bool NativeMemory::loadBinary(uint64_t offset, const uint8_t* data, size_t length) {
    if (!ramBuffer || offset + length > ramSizeBytes) {
        return false;
    }
    std::memcpy(&ramBuffer[offset], data, length);
    return true;
}

void NativeMemory::reset() {
    if (ramBuffer) {
        std::memset(ramBuffer, 0, ramSizeBytes);
    }
}
