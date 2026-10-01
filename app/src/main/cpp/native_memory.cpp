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

uint64_t NativeMemory::toBufferOffset(uint64_t address) const {
    if (address >= RAM_BASE_ADDRESS && address < RAM_BASE_ADDRESS + ramSizeBytes) {
        return address - RAM_BASE_ADDRESS;
    }
    return address;
}

bool NativeMemory::isValidGPA(uint64_t address, size_t size) const {
    if (!ramBuffer || size == 0 || size > ramSizeBytes) return false;
    if (address < RAM_BASE_ADDRESS) return false;
    uint64_t ramEnd = RAM_BASE_ADDRESS + ramSizeBytes;
    if (address >= ramEnd) return false;
    // Overflow check
    if (address + size < address) return false;
    if (address + size > ramEnd) return false;
    return true;
}

uint8_t* NativeMemory::getHostPtr(uint64_t address) {
    if (!isValidAddress(address, 1)) return nullptr;
    return ramBuffer + toBufferOffset(address);
}

const uint8_t* NativeMemory::getHostPtr(uint64_t address) const {
    if (!isValidAddress(address, 1)) return nullptr;
    return ramBuffer + toBufferOffset(address);
}

bool NativeMemory::isValidAddress(uint64_t address, size_t size) const {
    if (!ramBuffer || size == 0 || size > ramSizeBytes) return false;
    if (address >= RAM_BASE_ADDRESS) {
        return isValidGPA(address, size);
    }
    // Offset-from-zero fallback (used by local unit tests or internal staging)
    if (address >= ramSizeBytes) return false;
    if (address + size < address) return false;
    if (address + size > ramSizeBytes) return false;
    return true;
}

uint8_t NativeMemory::read8(uint64_t address) const {
    if (!isValidAddress(address, 1)) {
        return 0;
    }
    return ramBuffer[toBufferOffset(address)];
}

void NativeMemory::write8(uint64_t address, uint8_t value) {
    if (isValidAddress(address, 1)) {
        ramBuffer[toBufferOffset(address)] = value;
    }
}

uint16_t NativeMemory::read16(uint64_t address) const {
    if (!isValidAddress(address, 2)) {
        return 0;
    }
    uint16_t val;
    std::memcpy(&val, &ramBuffer[toBufferOffset(address)], sizeof(uint16_t));
    return val;
}

void NativeMemory::write16(uint64_t address, uint16_t value) {
    if (isValidAddress(address, 2)) {
        std::memcpy(&ramBuffer[toBufferOffset(address)], &value, sizeof(uint16_t));
    }
}

uint32_t NativeMemory::read32(uint64_t address) const {
    if (!isValidAddress(address, 4)) {
        return 0;
    }
    uint32_t val;
    std::memcpy(&val, &ramBuffer[toBufferOffset(address)], sizeof(uint32_t));
    return val;
}

void NativeMemory::write32(uint64_t address, uint32_t value) {
    if (isValidAddress(address, 4)) {
        std::memcpy(&ramBuffer[toBufferOffset(address)], &value, sizeof(uint32_t));
    }
}

uint64_t NativeMemory::read64(uint64_t address) const {
    if (!isValidAddress(address, 8)) {
        return 0;
    }
    uint64_t val;
    std::memcpy(&val, &ramBuffer[toBufferOffset(address)], sizeof(uint64_t));
    return val;
}

void NativeMemory::write64(uint64_t address, uint64_t value) {
    if (isValidAddress(address, 8)) {
        std::memcpy(&ramBuffer[toBufferOffset(address)], &value, sizeof(uint64_t));
    }
}

bool NativeMemory::loadBinary(uint64_t offset, const uint8_t* data, size_t length) {
    if (!ramBuffer || !isValidAddress(offset, length)) {
        return false;
    }
    std::memcpy(&ramBuffer[toBufferOffset(offset)], data, length);
    return true;
}

void NativeMemory::reset() {
    if (ramBuffer) {
        std::memset(ramBuffer, 0, ramSizeBytes);
    }
}
