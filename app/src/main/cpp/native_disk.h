#ifndef NATIVE_DISK_H
#define NATIVE_DISK_H

#include <cstdint>
#include <string>
#include <mutex>
#include <vector>

class NativeDisk {
public:
    NativeDisk();
    ~NativeDisk();

    static bool createRawDisk(const std::string& path, uint64_t sizeBytes, bool sparse, std::string& outError);
    bool openRawDisk(const std::string& path, bool readOnly, const std::string& allowedPrefixDir, std::string& outError);
    bool openCdrom(const std::string& path, const std::string& allowedPrefixDir, std::string& outError);
    void closeDisk();

    bool isOpened() const { return fd >= 0; }
    uint64_t getSizeBytes() const { return totalSizeBytes; }
    uint64_t getSectorCount() const { return (sectorSize > 0) ? (totalSizeBytes / sectorSize) : 0; }
    uint32_t getSectorSize() const { return sectorSize; }
    void setSectorSize(uint32_t size) { if (size > 0) sectorSize = size; }
    const std::string& getDiskPath() const { return diskFilePath; }
    bool isReadOnly() const { return readOnlyMode; }

    bool readSectors(uint64_t lba, uint32_t count, uint8_t* outBuffer, std::string& outError);
    bool writeSectors(uint64_t lba, uint32_t count, const uint8_t* inBuffer, std::string& outError);
    bool flush();

    // Stats
    uint64_t getTotalSectorsRead() const { return sectorsReadTotal; }
    uint64_t getTotalSectorsWritten() const { return sectorsWrittenTotal; }

private:
    int fd;
    uint64_t totalSizeBytes;
    uint32_t sectorSize;
    bool readOnlyMode;
    std::string diskFilePath;

    uint64_t sectorsReadTotal;
    uint64_t sectorsWrittenTotal;
    mutable std::mutex diskMutex;

    static bool isPathSafe(const std::string& path, const std::string& allowedPrefix);
};

#endif // NATIVE_DISK_H
