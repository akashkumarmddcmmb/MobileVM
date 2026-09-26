#include "native_disk.h"
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <cstring>
#include <cerrno>
#include <algorithm>

NativeDisk::NativeDisk()
    : fd(-1),
      totalSizeBytes(0),
      sectorSize(512),
      readOnlyMode(false),
      sectorsReadTotal(0),
      sectorsWrittenTotal(0) {}

NativeDisk::~NativeDisk() {
    closeDisk();
}

bool NativeDisk::isPathSafe(const std::string& path, const std::string& allowedPrefix) {
    if (path.empty()) return false;

    // Check for directory traversal attempts
    if (path.find("..") != std::string::npos) {
        return false;
    }

    // Protect sensitive Android and Linux system locations from guest writes
    const std::vector<std::string> blockedPrefixes = {
        "/system", "/proc", "/sys", "/dev", "/etc", "/vendor",
        "/data/system", "/data/misc", "/data/app"
    };

    for (const auto& prefix : blockedPrefixes) {
        if (path.compare(0, prefix.length(), prefix) == 0) {
            return false;
        }
    }

    // If an allowed prefix directory is specified, enforce that path starts with it
    if (!allowedPrefix.empty()) {
        if (path.compare(0, allowedPrefix.length(), allowedPrefix) != 0) {
            return false;
        }
    }

    return true;
}

bool NativeDisk::createRawDisk(const std::string& path, uint64_t sizeBytes, bool sparse, std::string& outError) {
    if (!isPathSafe(path, "")) {
        outError = "Blocked: Target disk path violates host filesystem security policy.";
        return false;
    }

    if (sizeBytes < 1024 * 1024) { // Minimum 1 MB
        outError = "Disk image size must be at least 1 MB.";
        return false;
    }

    int createFd = open(path.c_str(), O_CREAT | O_RDWR | O_TRUNC | O_CLOEXEC, 0644);
    if (createFd < 0) {
        int err = errno;
        outError = "Cannot create disk image: " + std::string(strerror(err));
        return false;
    }

    if (ftruncate(createFd, static_cast<off_t>(sizeBytes)) != 0) {
        int err = errno;
        outError = "Cannot allocate disk capacity: " + std::string(strerror(err));
        close(createFd);
        unlink(path.c_str());
        return false;
    }

    // Write real Master Boot Record (MBR) partition table at LBA 0 (Sector 0)
    std::vector<uint8_t> mbr(512, 0);

    // Bootloader signature code dummy stub
    mbr[0] = 0xFA; // CLI
    mbr[1] = 0x31; // XOR EAX, EAX
    mbr[2] = 0xC0;

    // Partition 1 Entry at offset 446 (0x1BE)
    size_t partOffset = 446;
    mbr[partOffset + 0] = 0x80; // Bootable flag (active)
    mbr[partOffset + 1] = 0x20; // Starting CHS
    mbr[partOffset + 2] = 0x21;
    mbr[partOffset + 3] = 0x00;
    mbr[partOffset + 4] = 0x83; // Partition Type: 0x83 = Linux native filesystem
    mbr[partOffset + 5] = 0xDF; // Ending CHS
    mbr[partOffset + 6] = 0x13;
    mbr[partOffset + 7] = 0x0C;

    // Starting LBA = 2048 (1 MB offset)
    uint32_t startLba = 2048;
    std::memcpy(&mbr[partOffset + 8], &startLba, sizeof(uint32_t));

    // Sector Count = (Total Size - 1MB) / 512
    uint32_t partitionSectors = static_cast<uint32_t>((sizeBytes / 512) - startLba);
    std::memcpy(&mbr[partOffset + 12], &partitionSectors, sizeof(uint32_t));

    // Valid MBR Boot Signature at byte 510-511
    mbr[510] = 0x55;
    mbr[511] = 0xAA;

    if (pwrite(createFd, mbr.data(), mbr.size(), 0) != static_cast<ssize_t>(mbr.size())) {
        int err = errno;
        outError = "Cannot initialize MBR partition table: " + std::string(strerror(err));
        close(createFd);
        return false;
    }

    fdatasync(createFd);
    close(createFd);
    return true;
}

bool NativeDisk::openRawDisk(const std::string& path, bool readOnly, const std::string& allowedPrefixDir, std::string& outError) {
    std::lock_guard<std::mutex> lock(diskMutex);
    closeDisk();

    if (!isPathSafe(path, allowedPrefixDir)) {
        outError = "Disk image access rejected: host file protection policy violated.";
        return false;
    }

    int flags = readOnly ? (O_RDONLY | O_CLOEXEC) : (O_RDWR | O_CLOEXEC);
    fd = open(path.c_str(), flags);
    if (fd < 0) {
        int err = errno;
        outError = "Failed to open virtual disk image: " + std::string(strerror(err));
        return false;
    }

    struct stat st;
    if (fstat(fd, &st) != 0) {
        int err = errno;
        outError = "Failed to stat disk image: " + std::string(strerror(err));
        close(fd);
        fd = -1;
        return false;
    }

    if (!S_ISREG(st.st_mode)) {
        outError = "Specified path is not a regular file.";
        close(fd);
        fd = -1;
        return false;
    }

    totalSizeBytes = static_cast<uint64_t>(st.st_size);
    sectorSize = 512;
    readOnlyMode = readOnly;
    diskFilePath = path;
    sectorsReadTotal = 0;
    sectorsWrittenTotal = 0;

    return true;
}

void NativeDisk::closeDisk() {
    if (fd >= 0) {
        if (!readOnlyMode) {
            fdatasync(fd);
        }
        close(fd);
        fd = -1;
    }
    totalSizeBytes = 0;
    diskFilePath.clear();
}

bool NativeDisk::readSectors(uint64_t lba, uint32_t count, uint8_t* outBuffer, std::string& outError) {
    std::lock_guard<std::mutex> lock(diskMutex);
    if (fd < 0) {
        outError = "Disk device is offline / not opened.";
        return false;
    }

    uint64_t byteOffset = lba * sectorSize;
    size_t byteCount = count * sectorSize;

    if (byteOffset + byteCount > totalSizeBytes) {
        outError = "Disk read out of bounds (LBA " + std::to_string(lba) + ", count " + std::to_string(count) + ").";
        return false;
    }

    ssize_t bytesRead = pread(fd, outBuffer, byteCount, static_cast<off_t>(byteOffset));
    if (bytesRead < 0) {
        int err = errno;
        outError = "Disk I/O read failure: " + std::string(strerror(err));
        return false;
    }

    // If read reached EOF before full sector requested, zero the remaining tail
    if (static_cast<size_t>(bytesRead) < byteCount) {
        std::memset(outBuffer + bytesRead, 0, byteCount - bytesRead);
    }

    sectorsReadTotal += count;
    return true;
}

bool NativeDisk::writeSectors(uint64_t lba, uint32_t count, const uint8_t* inBuffer, std::string& outError) {
    std::lock_guard<std::mutex> lock(diskMutex);
    if (fd < 0) {
        outError = "Disk device is offline / not opened.";
        return false;
    }

    if (readOnlyMode) {
        outError = "Write failed: virtual disk is attached in Read-Only mode.";
        return false;
    }

    uint64_t byteOffset = lba * sectorSize;
    size_t byteCount = count * sectorSize;

    if (byteOffset + byteCount > totalSizeBytes) {
        outError = "Disk write out of bounds (LBA " + std::to_string(lba) + ", count " + std::to_string(count) + ").";
        return false;
    }

    ssize_t bytesWritten = pwrite(fd, inBuffer, byteCount, static_cast<off_t>(byteOffset));
    if (bytesWritten < 0 || static_cast<size_t>(bytesWritten) != byteCount) {
        int err = errno;
        outError = "Disk I/O write failure: " + std::string(strerror(err));
        return false;
    }

    sectorsWrittenTotal += count;
    return true;
}

bool NativeDisk::flush() {
    std::lock_guard<std::mutex> lock(diskMutex);
    if (fd >= 0 && !readOnlyMode) {
        return (fdatasync(fd) == 0);
    }
    return true;
}
